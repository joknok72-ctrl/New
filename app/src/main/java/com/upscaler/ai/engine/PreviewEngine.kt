package com.upscaler.ai.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log

/**
 * Upscales a single frame for an instant before/after preview and, as a side effect,
 * *benchmarks the real device* so the ETA shown to the user is measured, not guessed.
 */
object PreviewEngine {
    private const val TAG = "PreviewEngine"

    data class Result(
        val before: Bitmap,
        val after: Bitmap,
        /** measured ms per input tile on this device with this model / provider */
        val msPerTile: Double,
        val provider: String,
        val tile: Int,
    )

    /** Cached benchmark per (model, gpu) so we don't reload the model every time the user toggles. */
    private val bench = HashMap<String, Pair<Double, String>>()

    fun cachedMsPerTile(model: UpscaleModel, gpu: Boolean): Pair<Double, String>? = bench["${model.name}/$gpu"]

    fun run(ctx: Context, uri: Uri, atMs: Long, model: UpscaleModel, gpu: Boolean, crop: Int = 96): Result {
        val mmr = MediaMetadataRetriever()
        val frame: Bitmap = try {
            mmr.setDataSource(ctx, uri)
            mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("no frame")
        } finally { mmr.release() }

        // Take a centered crop so the preview shows real detail (a face, text…) at 1:1 → 4:1
        val cw = minOf(crop, frame.width)
        val ch = minOf(crop, frame.height)
        val cx = (frame.width - cw) / 2
        val cy = (frame.height - ch) / 2
        val before = Bitmap.createBitmap(frame, cx, cy, cw, ch)
        val src = IntArray(cw * ch)
        before.getPixels(src, 0, cw, 0, 0, cw, ch)

        val profile = DeviceProfiler.profile(ctx)
        val engine = SuperResolutionEngine(ctx, model, profile, preferNnapi = gpu)
        try {
            val dst = IntArray(cw * 4 * ch * 4)
            // warm-up (first NNAPI run includes compilation)
            engine.upscale(src, cw, ch, dst)
            // timed runs
            val t0 = SystemClock.elapsedRealtime()
            val runs = 2
            repeat(runs) { engine.upscale(src, cw, ch, dst) }
            val ms = (SystemClock.elapsedRealtime() - t0).toDouble() / runs
            val tiles = engine.tilesPerFrame(cw, ch)
            val msPerTile = ms / tiles
            bench["${model.name}/$gpu"] = msPerTile to engine.providerName
            Log.i(TAG, "bench ${model.name} gpu=$gpu → ${"%.1f".format(msPerTile)} ms/tile via ${engine.providerName}")

            val after = Bitmap.createBitmap(cw * 4, ch * 4, Bitmap.Config.ARGB_8888)
            after.setPixels(dst, 0, cw * 4, 0, 0, cw * 4, ch * 4)
            return Result(before, after, msPerTile, engine.providerName, engine.tile)
        } finally {
            engine.close()
            if (frame !== before) frame.recycle()
        }
    }
}
