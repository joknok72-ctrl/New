package com.upscaler.ai.pipeline

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import com.upscaler.ai.engine.CloudEngine
import com.upscaler.ai.engine.ComputeMode
import com.upscaler.ai.engine.DeviceProfiler
import com.upscaler.ai.engine.ModelStore
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Pause gate — when true the AI stage idles (decoder/encoder queues simply fill up and block). */
    val paused = MutableStateFlow(false)
    private var pausedTotalMs = 0L
    private suspend fun awaitResume() {
        if (!paused.value) return
        val t = SystemClock.elapsedRealtime()
        paused.first { !it }
        pausedTotalMs += SystemClock.elapsedRealtime() - t
    }

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

            if (job.compute == ComputeMode.CLOUD && job.preset != QualityPreset.FAST) {
                runCloudOnly(job, info, t0)
                return@withContext
            }

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
                if (!ModelStore.isAvailable(ctx, job.model)) {
                    ModelStore.download(ctx, job.model) { p ->
                        _state.value = UpscaleState.Preparing("Downloading ${job.model.displayNameEn} ${(p * 100).toInt()}%")
                    }
                }
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
            val stabilizer = if (job.antiFlicker && useAi) TemporalStabilizer(aiW, aiH).also { it.strength = 0.5f } else null
            val natural = if (useAi && job.natural > 0.01f) NaturalBlend(aiW, aiH, srcW, srcH) else null
            val totalFrames = (effectiveDurMs / 1000f * info.fps).toLong().coerceAtLeast(1)
            val smartSkip = job.preset == QualityPreset.BALANCED

            val cloud: CloudEngine? = if (job.compute == ComputeMode.HYBRID && useAi && passes == 1) CloudEngine() else null
            val cloudFrames = java.util.concurrent.atomic.AtomicLong(0)
            val cloudOk = cloud?.let { c -> runCatching { c.health().ok }.getOrDefault(false) } ?: false
            if (cloud != null) Log.i(TAG, "hybrid: cloud ${if (cloudOk) "available" else "unavailable → device only"}")

            // Frame pools (reuse memory)
            val decodedPool = Channel<DecodedFrame>(QUEUE + 1)
            repeat(QUEUE + 1) { decodedPool.trySend(DecodedFrame(srcW, srcH)) }
            val decodedQ = Channel<DecodedFrame?>(QUEUE + 4) // room for EOF pills
            val nWorkers = 1 + (if (cloud != null && cloudOk) 3 else 0)
            val poolSize = QUEUE + 1 + (nWorkers - 1) * 2
            val upscaledPool = Channel<IntArray>(poolSize)
            repeat(poolSize) { upscaledPool.trySend(IntArray(aiW * aiH)) }
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

                val thermal = ThermalGuard(ctx)
                // Stage 2: AI — device worker + (optional) cloud workers pulling from the same queue.
                // Order is restored with a reorder buffer keyed by frame index.
                val reorder = HashMap<Long, Triple<IntArray, Long, Boolean>>()
                val reorderLock = Mutex()
                var nextToEmit = 0L
                suspend fun emit(idx: Long, item: Triple<IntArray, Long, Boolean>) {
                    reorderLock.withLock {
                        reorder[idx] = item
                        while (true) {
                            val n = reorder.remove(nextToEmit) ?: break
                            upscaledQ.send(n); nextToEmit++
                        }
                    }
                }
                val producersDone = java.util.concurrent.atomic.AtomicInteger(0)
                suspend fun workerFinished() { if (producersDone.incrementAndGet() == nWorkers) upscaledQ.send(null) }
                val engLock = Mutex() // SuperResolutionEngine is single-threaded

                // device worker (also handles duplicate skipping + temporal stabilizer)
                launch(Dispatchers.Default) {
                    var prevLow: IntArray? = null
                    val prevLowBuf = IntArray(srcW * srcH)
                    var lastOut: IntArray? = null
                    val mid = if (passes == 2) IntArray(srcW * 4 * srcH * 4) else null
                    try {
                        while (isActive) {
                            val f = decodedQ.receive()
                            if (f == null) { decodedQ.send(null); break } // propagate EOF to other workers
                            val out = upscaledPool.receive()
                            thermal.cooldownIfNeeded()
                            awaitResume()
                            val a = analyzer.analyze(f.pixels, srcW, srcH)
                            if (a.isSceneCut) stabilizer?.reset()

                            if (smartSkip && a.isDuplicate && lastOut != null) {
                                System.arraycopy(lastOut, 0, out, 0, out.size)
                                skipped++
                            } else if (eng != null) {
                                engLock.withLock {
                                    if (passes == 2 && mid != null) {
                                        eng.upscale(f.pixels, srcW, srcH, mid)
                                        eng.upscale(mid, srcW * 4, srcH * 4, out)
                                    } else {
                                        eng.upscale(f.pixels, srcW, srcH, out)
                                    }
                                }
                                if (passes == 1) natural?.apply(out, f.pixels, job.natural)
                                if (stabilizer != null && passes == 1 && cloud == null) {
                                    stabilizer.apply(out, prevLow, f.pixels, srcW, srcH)
                                }
                            } else {
                                System.arraycopy(f.pixels, 0, out, 0, out.size)
                            }
                            System.arraycopy(f.pixels, 0, prevLowBuf, 0, prevLowBuf.size)
                            prevLow = prevLowBuf
                            lastOut = out
                            val pts = f.ptsUs; val idx = f.index
                            decodedPool.send(f)
                            emit(idx, Triple(out, pts, false))
                        }
                    } finally { workerFinished() }
                }

                // cloud workers (hybrid only). Each takes a frame, sends it to the free GPU, and
                // falls back to the device engine if the cloud errors (device-side copy of the engine
                // is NOT thread-safe → we route fallbacks through a mutex).
                if (cloud != null && cloudOk && eng != null) {
                    repeat(nWorkers - 1) {
                        launch(Dispatchers.IO) {
                            try {
                                while (isActive) {
                                    // back-pressure: don't grab frames while cloud is slow & congested
                                    if (cloud.failures >= 3) break
                                    val f = decodedQ.receive()
                                    if (f == null) { decodedQ.send(null); break }
                                    val out = upscaledPool.receive()
                                    awaitResume()
                                    val pts = f.ptsUs; val idx = f.index
                                    val src = f.pixels.copyOf()
                                    decodedPool.send(f)
                                    try {
                                        cloud.frame(src, srcW, srcH, job.model, out)
                                        cloudFrames.incrementAndGet()
                                    } catch (e: Exception) {
                                        Log.w(TAG, "cloud frame failed, device fallback: ${e.message}")
                                        engLock.withLock { eng.upscale(src, srcW, srcH, out) }
                                    }
                                    natural?.apply(out, src, job.natural)
                                    emit(idx, Triple(out, pts, false))
                                }
                            } finally { workerFinished() }
                        }
                    }
                }

                // Stage 3: render + encode (single thread owns EGL context)
                launch(Dispatchers.IO) {
                    val r = GlFrameRenderer(encSurface, encW, encH).also {
                        it.sharpenAmount = if (useAi) job.sharpen * 0.6f else (0.35f + job.sharpen * 0.6f)
                        it.colorMode = job.colorMode.ordinal
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
                            val el = (now - t0 - pausedTotalMs - thermal.pausedMs) / 1000f
                            val fps = processed / el.coerceAtLeast(0.001f)
                            val remaining = (totalFrames - processed).coerceAtLeast(0)
                            _state.value = UpscaleState.Running(
                                frame = processed, totalFrames = totalFrames,
                                progress = (processed.toFloat() / totalFrames).coerceIn(0f, 0.999f),
                                fps = fps, etaSeconds = (remaining / fps.coerceAtLeast(0.01f)).toLong(),
                                skipped = skipped, provider = if (cloud != null && cloudOk) "$provider+cloud" else provider, paused = paused.value, cloudFrames = cloudFrames.get(),
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
            natural?.close()

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

    /** CLOUD mode: (trim →) upload the clip to the free GPU via the Worker, download, save. */
    private suspend fun runCloudOnly(job: UpscaleJob, info: VideoInfo, t0: Long) {
        val cloud = CloudEngine()
        _state.value = UpscaleState.Preparing("Checking cloud GPU…")
        val h = cloud.health()
        if (!h.ok) { _state.value = UpscaleState.Failed("Cloud GPU unavailable — switch to Hybrid or Device"); return }
        _state.value = UpscaleState.Preparing("Preparing upload (${h.gpu ?: "GPU"})…")
        val tmpIn = File(ctx.cacheDir, "cloud_in_${System.currentTimeMillis()}.mp4")
        var out: File? = null
        try {
            val startUs = job.startMs.coerceAtLeast(0) * 1000
            val endUs = if (job.endMs > 0 && job.endMs * 1000 > startUs) job.endMs * 1000 else Long.MAX_VALUE
            remuxRange(job.inputUri, tmpIn, startUs, endUs)
            if (tmpIn.length() > 60_000_000L) { _state.value = UpscaleState.Failed("Clip too large for cloud (max 60 MB). Trim it or use Hybrid."); return }
            // choose 2x or 4x based on target
            val need = if (job.target == TargetResolution.AUTO) 4f else job.target.height.toFloat() / minOf(info.displayWidth, info.displayHeight)
            val scale = if (need <= 2.2f) 2 else 4
            out = cloud.video(tmpIn, scale, job.model, job.natural) { ev ->
                when (ev) {
                    is CloudEngine.VideoEvent.Progress -> _state.value = UpscaleState.Preparing("Cloud GPU: ${ev.stage}${ev.detail?.let { " • $it" } ?: ""}")
                    is CloudEngine.VideoEvent.Done -> _state.value = UpscaleState.Preparing("Downloading result…")
                    is CloudEngine.VideoEvent.Error -> Log.w(TAG, "cloud: ${ev.message}")
                }
            }
            val f = out ?: run { _state.value = UpscaleState.Failed("Cloud processing failed — try Hybrid mode"); return }
            _state.value = UpscaleState.Preparing("Saving to gallery…")
            val outW = info.displayWidth * scale; val outH = info.displayHeight * scale
            val name = buildOutName(info, outW, outH).replace("_AI_", "_CLOUD_")
            val uri = saveToGallery(f, name)
            val el = (SystemClock.elapsedRealtime() - t0) / 1000
            _state.value = UpscaleState.Done(job.inputUri, uri, name, el, info.frameCountEstimate, 0, "${outW}×$outH", f.length())
        } finally {
            tmpIn.delete(); out?.delete()
        }
    }

    /** Copies all tracks of [uri] between startUs..endUs into [dst] without re-encoding. */
    private fun remuxRange(uri: Uri, dst: File, startUs: Long, endUs: Long) {
        val ex = android.media.MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        val muxer = android.media.MediaMuxer(dst.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val map = HashMap<Int, Int>()
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val m = f.getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("video/") || m.startsWith("audio/")) { map[i] = muxer.addTrack(f); ex.selectTrack(i) }
        }
        if (startUs > 0) ex.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        muxer.start()
        val buf = java.nio.ByteBuffer.allocate(2 shl 20)
        val bi = android.media.MediaCodec.BufferInfo()
        val base = if (startUs > 0) ex.sampleTime.coerceAtLeast(0) else 0L
        while (true) {
            val n = ex.readSampleData(buf, 0); if (n < 0) break
            val t = ex.sampleTime
            if (t > endUs) break
            val trk = map[ex.sampleTrackIndex]
            if (trk != null) {
                bi.offset = 0; bi.size = n; bi.presentationTimeUs = (t - base).coerceAtLeast(0)
                bi.flags = if (ex.sampleFlags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(trk, buf, bi)
            }
            ex.advance()
        }
        try { muxer.stop() } catch (_: Throwable) {}
        muxer.release(); ex.release()
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
