package com.upscaler.ai.pipeline

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.upscaler.ai.engine.DeviceProfiler
import com.upscaler.ai.engine.QualityPreset
import com.upscaler.ai.engine.SuperResolutionEngine
import com.upscaler.ai.engine.TargetResolution
import com.upscaler.ai.video.DecodedFrame
import com.upscaler.ai.video.FrameDecoder
import com.upscaler.ai.video.GlFrameRenderer
import com.upscaler.ai.video.VideoEncoder
import com.upscaler.ai.video.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The full video → AI → video pipeline.
 *
 * Three stages run concurrently on separate threads and talk through bounded channels:
 *
 *   [Decoder (HW)]  →  [AI upscale (NNAPI/GPU or CPU)]  →  [GL resize+sharpen → HW encoder + muxer]
 *
 * So while the GPU/NPU chews on frame N, the decoder is already producing N+1 and the encoder
 * is compressing N-1. On a typical phone this hides ~30–40% of wall-clock time.
 *
 * ULTRA preset = two chained AI passes (x16) for 144p → 4K, with the intermediate result
 * fed straight back into the engine (no encode in between).
 */
class UpscalePipeline(private val ctx: Context) {

    companion object {
        private const val TAG = "UpscalePipeline"
        private const val QUEUE = 3
    }

    private val _state = MutableStateFlow<UpscaleState>(UpscaleState.Idle)
    val state: StateFlow<UpscaleState> = _state

    suspend fun run(job: UpscaleJob): Unit = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtime()
        var engine: SuperResolutionEngine? = null
        var decoder: FrameDecoder? = null
        var encoder: VideoEncoder? = null
        var renderer: GlFrameRenderer? = null
        var tmpOut: File? = null
        try {
            _state.value = UpscaleState.Preparing("Reading video…")
            val info = VideoInfo.probe(ctx, job.inputUri)
            require(info.width > 0 && info.height > 0) { "Could not read video dimensions" }
            Log.i(TAG, "Input: $info")

            val profile = DeviceProfiler.profile(ctx)

            // ---- Trim range ---------------------------------------------------------------
            val startUs = job.startMs.coerceAtLeast(0) * 1000
            val endUs = if (job.endMs > 0 && job.endMs * 1000 > startUs) job.endMs * 1000 else Long.MAX_VALUE
            val effectiveDurMs = (minOf(endUs, info.durationMs * 1000) - startUs) / 1000
            val useAi = job.preset != QualityPreset.FAST
            val srcW = info.width
            val srcH = info.height
            // ULTRA = 2 chained passes (x16) but only when the result stays within 4K
            val passes = if (job.preset == QualityPreset.ULTRA && srcW * 16 <= 4096 && srcH * 16 <= 4096) 2 else 1
            val aiScale = if (useAi) (if (passes == 2) 16 else SuperResolutionEngine.SCALE) else 1

            // ---- Output resolution ------------------------------------------------------
            val aiW = srcW * aiScale
            val aiH = srcH * aiScale
            val nominalW = if (useAi) aiW else srcW * 4
            val nominalH = if (useAi) aiH else srcH * 4
            var (outW, outH) = computeOutput(info, nominalW, nominalH, job.target)
            // Encoder needs even dims, and we cap at 4K (encoder limits)
            outW = (outW / 2) * 2; outH = (outH / 2) * 2
            if (maxOf(outW, outH) > 4096) {
                val s = 4096f / maxOf(outW, outH)
                outW = ((outW * s).roundToInt() / 2) * 2; outH = ((outH * s).roundToInt() / 2) * 2
            }
            // Encode in coded orientation; rotation is carried as MP4 metadata (like the source).
            val encW = outW
            val encH = outH
            Log.i(TAG, "AI ${aiW}x$aiH → encode ${encW}x$encH (rot ${info.rotation})")

            // ---- Engines -----------------------------------------------------------------
            if (useAi) {
                _state.value = UpscaleState.Preparing("Loading AI model (${job.model.displayNameEn})…")
                engine = SuperResolutionEngine(ctx, job.model, profile, preferNnapi = job.useGpu)
            }
            val provider = engine?.providerName ?: "hw-scaler"

            // ---- Output file -------------------------------------------------------------
            val outName = buildOutName(info, encW, encH)
            tmpOut = File(ctx.cacheDir, "out_${System.currentTimeMillis()}.mp4")
            encoder = VideoEncoder(ctx, tmpOut.absolutePath, encW, encH, info.fps, job.inputUri,
                copyAudio = info.hasAudio, preferHevc = job.preferHevc, rotationHint = info.rotation,
                audioStartUs = startUs, audioEndUs = endUs)

            // ---- Renderer (owns EGL on its own thread) ------------------------------------
            val encSurface = encoder.inputSurface
            decoder = FrameDecoder(ctx, job.inputUri, startUs, endUs)
            val dec: FrameDecoder = decoder
            val enc: VideoEncoder = encoder
            val eng: SuperResolutionEngine? = engine

            val analyzer = FrameAnalyzer()
            val stabilizer = if (job.antiFlicker && useAi) TemporalStabilizer(aiW, aiH) else null
            val totalFrames = (effectiveDurMs / 1000f * info.fps).toLong().coerceAtLeast(1)
            val smartSkip = job.preset == QualityPreset.BALANCED

            // Frame pools (reuse memory)
            val decodedPool = Channel<DecodedFrame>(QUEUE + 1)
            repeat(QUEUE + 1) { decodedPool.trySend(DecodedFrame(srcW, srcH)) }
            val decodedQ = Channel<DecodedFrame?>(QUEUE)
            val upscaledPool = Channel<IntArray>(QUEUE + 1)
            repeat(QUEUE + 1) { upscaledPool.trySend(IntArray(aiW * aiH)) }
            val upscaledQ = Channel<Triple<IntArray, Long, Boolean>?>(QUEUE) // (pixels, ptsUs, isLast)

            var processed = 0L
            var skipped = 0L
            var lastReport = 0L

            coroutineScope {
                // Stage 1: decode
                launch(Dispatchers.IO) {
                    try {
                        while (isActive) {
                            val f = decodedPool.receive()
                            if (!dec.nextFrame(f)) { decodedPool.trySend(f); break }
                            decodedQ.send(f)
                        }
                    } finally { decodedQ.send(null) }
                }

                // Stage 2: AI
                launch(Dispatchers.Default) {
                    val thermal = ThermalGuard(ctx)
                    var prevLow: IntArray? = null
                    val prevLowBuf = IntArray(srcW * srcH)
                    var lastOut: IntArray? = null
                    val mid = if (passes == 2) IntArray(srcW * 4 * srcH * 4) else null
                    try {
                        while (isActive) {
                            val f = decodedQ.receive() ?: break
                            val out = upscaledPool.receive()
                            thermal.cooldownIfNeeded()
                            val a = analyzer.analyze(f.pixels, srcW, srcH)
                            if (a.isSceneCut) stabilizer?.reset()

                            if (smartSkip && a.isDuplicate && lastOut != null) {
                                System.arraycopy(lastOut, 0, out, 0, out.size)
                                skipped++
                            } else if (eng != null) {
                                if (passes == 2 && mid != null) {
                                    eng.upscale(f.pixels, srcW, srcH, mid)
                                    eng.upscale(mid, srcW * 4, srcH * 4, out)
                                } else {
                                    eng.upscale(f.pixels, srcW, srcH, out)
                                }
                                if (stabilizer != null && passes == 1) {
                                    stabilizer.apply(out, prevLow, f.pixels, srcW, srcH)
                                }
                            } else {
                                // FAST: no AI, pass through; GPU does the scaling
                                System.arraycopy(f.pixels, 0, out, 0, out.size)
                            }
                            // remember low-res prev for motion mask
                            System.arraycopy(f.pixels, 0, prevLowBuf, 0, prevLowBuf.size)
                            prevLow = prevLowBuf
                            lastOut = out
                            val pts = f.ptsUs
                            decodedPool.send(f)
                            upscaledQ.send(Triple(out, pts, false))
                        }
                    } finally { upscaledQ.send(null) }
                }

                // Stage 3: render + encode (single thread owns EGL context)
                launch(Dispatchers.IO) {
                    val r = GlFrameRenderer(encSurface, encW, encH).also {
                        it.sharpenAmount = if (useAi) job.sharpen * 0.6f else (0.35f + job.sharpen * 0.6f)
                    }
                    renderer = r
                    while (isActive) {
                        val item = upscaledQ.receive() ?: break
                        val (pix, ptsUs, _) = item
                        r.draw(pix, aiW, aiH, 0, ptsUs * 1000)
                        enc.drain(false)
                        upscaledPool.send(pix)
                        processed++
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReport > 400) {
                            lastReport = now
                            val el = (now - t0) / 1000f
                            val fps = processed / el.coerceAtLeast(0.001f)
                            val remaining = (totalFrames - processed).coerceAtLeast(0)
                            _state.value = UpscaleState.Running(
                                frame = processed, totalFrames = totalFrames,
                                progress = (processed.toFloat() / totalFrames).coerceIn(0f, 0.999f),
                                fps = fps, etaSeconds = (remaining / fps.coerceAtLeast(0.01f)).toLong(),
                                skipped = skipped, provider = provider,
                                inputRes = "${info.displayWidth}×${info.displayHeight}",
                                outputRes = "${outW}×$outH", elapsedSeconds = el.toLong(),
                            )
                        }
                    }
                    r.close(); renderer = null
                    enc.finish()
                }
            }
            currentCoroutineContext().ensureActive()

            enc.close(); encoder = null
            dec.close(); decoder = null
            eng?.close(); engine = null
            stabilizer?.close()

            _state.value = UpscaleState.Preparing("Saving to gallery…")
            val outFile: File = tmpOut
            val uri = saveToGallery(outFile, outName)
            val size = outFile.length()
            outFile.delete()
            val el = (SystemClock.elapsedRealtime() - t0) / 1000
            Log.i(TAG, "Done in ${el}s, frames=$processed skipped=$skipped")
            _state.value = UpscaleState.Done(job.inputUri, uri, outName, el, processed, skipped, "${outW}×$outH", size)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            _state.value = UpscaleState.Cancelled
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "pipeline failed", t)
            _state.value = UpscaleState.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            try { renderer?.close() } catch (_: Throwable) {}
            try { encoder?.close() } catch (_: Throwable) {}
            try { decoder?.close() } catch (_: Throwable) {}
            try { engine?.close() } catch (_: Throwable) {}
            tmpOut?.let { if (it.exists()) it.delete() }
        }
    }

    private fun computeOutput(info: VideoInfo, aiW: Int, aiH: Int, target: TargetResolution): Pair<Int, Int> {
        if (target == TargetResolution.AUTO) return aiW to aiH
        // target.height refers to the *display* short side for landscape, i.e. displayHeight.
        val landscape = info.displayWidth >= info.displayHeight
        val aspect = info.displayWidth.toFloat() / info.displayHeight
        return if (landscape) {
            val h = target.height
            val w = (h * aspect).roundToInt()
            // return in coded orientation
            if (info.rotation == 90 || info.rotation == 270) h to w else w to h
        } else {
            val w = target.height // short side
            val h = (w / aspect).roundToInt()
            if (info.rotation == 90 || info.rotation == 270) h to w else w to h
        }
    }

    private fun buildOutName(info: VideoInfo, w: Int, h: Int): String {
        val base = info.displayName.substringBeforeLast('.').take(40).ifBlank { "video" }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val short = minOf(w, h)
        return "${base}_${short}p_AI_$ts.mp4"
    }

    private fun saveToGallery(src: File, name: String): Uri {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoUpscalerAI")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= 29)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { out -> FileInputStream(src).use { it.copyTo(out, 1 shl 20) } }
        if (Build.VERSION.SDK_INT >= 29) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }
}
