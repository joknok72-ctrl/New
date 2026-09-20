package com.upscaler.ai.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Resolves model bytes: bundled asset or downloaded file. Handles on-demand download with
 * progress and resume-safe temp files.
 */
object ModelStore {
    private const val TAG = "ModelStore"

    private fun dir(ctx: Context) = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: UpscaleModel) = File(dir(ctx), m.assetName)

    fun isAvailable(ctx: Context, m: UpscaleModel): Boolean =
        !m.isDownloadable || file(ctx, m).let { it.exists() && it.length() > 1_000_000 }

    fun loadBytes(ctx: Context, m: UpscaleModel): ByteArray =
        if (m.isDownloadable) file(ctx, m).readBytes()
        else ctx.assets.open("models/${m.assetName}").use { it.readBytes() }

    fun delete(ctx: Context, m: UpscaleModel) { if (m.isDownloadable) file(ctx, m).delete() }

    /** Downloads with progress callback (0..1). Throws on failure. */
    suspend fun download(ctx: Context, m: UpscaleModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val url = m.downloadUrl ?: return@withContext
        val dst = file(ctx, m)
        val tmp = File(dst.path + ".part")
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        // Manual redirect follow (GitHub → objects.githubusercontent.com switches host)
        var hops = 0
        while (conn.responseCode in 300..399 && hops < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            hops++
        }
        check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: (m.downloadSizeMb * 1_000_000L)
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(256 * 1024)
                var read = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); read += n
                    val pct = (read * 100 / total).toInt()
                    if (pct != lastPct) { lastPct = pct; onProgress((read.toFloat() / total).coerceIn(0f, 0.999f)) }
                }
            }
        }
        check(tmp.length() > 1_000_000) { "download too small" }
        if (dst.exists()) dst.delete()
        check(tmp.renameTo(dst)) { "rename failed" }
        onProgress(1f)
        Log.i(TAG, "downloaded ${m.assetName} (${dst.length() / 1e6} MB)")
    }
}
