package com.upscaler.ai.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * Talks to the Cloudflare Worker proxy which fans out to free GPU backends
 * (HuggingFace ZeroGPU A10G, or a user-run Kaggle T4 notebook).
 *
 * Two modes:
 *  • frame(): upscale a single frame (used by HYBRID: cloud + device work in parallel)
 *  • video(): upload a whole clip and let the GPU do everything (fastest when the GPU is free;
 *             the Worker streams progress back as SSE and caches the result in R2)
 */
class CloudEngine(private val baseUrl: String = DEFAULT_URL) {

    companion object {
        const val DEFAULT_URL = "https://upscaler-cloud.cracknew37.workers.dev"
        private const val TAG = "CloudEngine"

        fun modelParam(m: UpscaleModel) = when (m) {
            UpscaleModel.NATURAL -> "natural"
            UpscaleModel.GENERAL -> "general"
            UpscaleModel.GENERAL_DENOISE -> "wdn"
            UpscaleModel.ANIME -> "anime"
            UpscaleModel.ULTRA_PLUS -> "x4plus"
        }
    }

    data class Health(val ok: Boolean, val gpu: String?, val primary: String?, val latencyMs: Long)

    /** In-flight requests counter (used by the hybrid scheduler to avoid over-queuing). */
    val inFlight = AtomicInteger(0)
    /** Rolling average of cloud latency per frame (ms). */
    @Volatile var avgFrameMs: Double = 3000.0
        private set
    @Volatile var failures = 0
        private set

    suspend fun health(): Health = withContext(Dispatchers.IO) {
        val t = System.currentTimeMillis()
        try {
            val c = (URL("$baseUrl/health").openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15000 }
            val txt = c.inputStream.bufferedReader().readText()
            val j = JSONObject(txt)
            Health(j.optBoolean("ok"), j.optString("gpu", null), j.optString("primary", null), System.currentTimeMillis() - t)
        } catch (e: Exception) {
            Log.w(TAG, "health failed: ${e.message}")
            Health(false, null, null, System.currentTimeMillis() - t)
        }
    }

    /**
     * Upscale one ARGB frame ×4 on the cloud GPU. Returns pixels of size (w*4)*(h*4) or throws.
     * Uses PNG (lossless) upload so the network sees exactly what the phone decoded.
     */
    suspend fun frame(src: IntArray, w: Int, h: Int, model: UpscaleModel, dst: IntArray): Unit = withContext(Dispatchers.IO) {
        inFlight.incrementAndGet()
        val t0 = System.currentTimeMillis()
        try {
            val bmp = Bitmap.createBitmap(src, w, h, Bitmap.Config.ARGB_8888)
            val bos = ByteArrayOutputStream(w * h * 2)
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
            bmp.recycle()
            val png = bos.toByteArray()

            val c = (URL("$baseUrl/frame?scale=4&model=${modelParam(model)}").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 15_000; readTimeout = 180_000
                setRequestProperty("Content-Type", "image/png")
                setFixedLengthStreamingMode(png.size)
            }
            c.outputStream.use { it.write(png) }
            if (c.responseCode != 200) throw IllegalStateException("cloud ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()?.take(200)}")
            val bytes = c.inputStream.readBytes()
            val out = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IllegalStateException("bad image from cloud")
            try {
                if (out.width != w * 4 || out.height != h * 4) {
                    val scaled = Bitmap.createScaledBitmap(out, w * 4, h * 4, true)
                    scaled.getPixels(dst, 0, w * 4, 0, 0, w * 4, h * 4); scaled.recycle()
                } else out.getPixels(dst, 0, w * 4, 0, 0, w * 4, h * 4)
            } finally { out.recycle() }
            val dt = (System.currentTimeMillis() - t0).toDouble()
            avgFrameMs = avgFrameMs * 0.7 + dt * 0.3
            failures = 0
        } catch (e: Exception) {
            failures++
            throw e
        } finally { inFlight.decrementAndGet() }
    }

    sealed class VideoEvent {
        data class Progress(val stage: String, val detail: String?) : VideoEvent()
        data class Done(val resultUrl: String) : VideoEvent()
        data class Error(val message: String) : VideoEvent()
    }

    /**
     * Upload an MP4 and get the upscaled MP4 back. Streams SSE progress events.
     * @param scale 2 or 4
     */
    suspend fun video(file: File, scale: Int, model: UpscaleModel, natural: Float, onEvent: (VideoEvent) -> Unit): File? = withContext(Dispatchers.IO) {
        val c = (URL("$baseUrl/video?scale=$scale&model=${modelParam(model)}&natural=${"%.2f".format(java.util.Locale.US, natural)}").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 15_000; readTimeout = 0 // SSE — no read timeout
            setRequestProperty("Content-Type", "video/mp4")
            setFixedLengthStreamingMode(file.length())
        }
        FileInputStream(file).use { it.copyTo(c.outputStream, 256 * 1024) }
        c.outputStream.close()
        if (c.responseCode != 200) { onEvent(VideoEvent.Error("cloud ${c.responseCode}")); return@withContext null }
        var resultUrl: String? = null
        c.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data: ")) continue
                val j = JSONObject(line.substring(6))
                when (j.optString("stage")) {
                    "done" -> { resultUrl = j.getString("url"); onEvent(VideoEvent.Done(resultUrl!!)) }
                    "error" -> { onEvent(VideoEvent.Error(j.optString("error"))); return@withContext null }
                    else -> onEvent(VideoEvent.Progress(j.optString("stage"), j.optString("log", null) ?: j.optString("eta", null)?.let { "ETA ${it}s" }))
                }
            }
        }
        val url = resultUrl ?: return@withContext null
        // download result
        val out = File(file.parentFile, "cloud_${System.currentTimeMillis()}.mp4")
        val d = (URL(baseUrl + url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 300_000 }
        if (d.responseCode != 200) { onEvent(VideoEvent.Error("download ${d.responseCode}")); return@withContext null }
        d.inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        out
    }
}
