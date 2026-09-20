package com.upscaler.ai.pipeline

import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Anti-"plastic" pass. Single-image SR nets over-smooth flat regions (skin, sky, walls) and
 * invent crisp texture everywhere, which reads as "robotic". We:
 *   1. build a detail mask from the *source* (where the low-res frame really has edges),
 *   2. in flat regions pull the AI result back toward a bilinear upscale of the source
 *      (the "honest" version) by up to 70 % × strength,
 *   3. add back a whisper of the source's own fine grain.
 * Cheap: a few int ops per output pixel, multithreaded.
 */
class NaturalBlend(private val outW: Int, private val outH: Int, private val srcW: Int, private val srcH: Int) {
    private val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    private val pool = Executors.newFixedThreadPool(threads)
    private val edge = ByteArray(srcW * srcH) // 0..255 detail mask at source res
    private val scale = outW / srcW

    /** strength 0..1 (0 = untouched AI output). */
    fun apply(out: IntArray, src: IntArray, strength: Float) {
        if (strength <= 0.01f) return
        buildEdgeMask(src)
        val kFlat = (strength * 0.7f * 256).toInt()      // max pull toward source in flat regions
        val kGrain = (strength * 0.35f * 256).toInt()    // grain add-back
        val rowsPer = (outH + threads - 1) / threads
        val fs = ArrayList<Future<*>>(threads)
        for (t in 0 until threads) {
            val y0 = t * rowsPer; val y1 = minOf(outH, y0 + rowsPer)
            if (y0 >= y1) break
            fs += pool.submit { blendRows(out, src, kFlat, kGrain, y0, y1) }
        }
        fs.forEach { it.get() }
    }

    private fun buildEdgeMask(src: IntArray) {
        // Sobel-ish on luma, then normalise so ~40 gradient == full detail; light 3x3 box blur
        val lum = IntArray(srcW * srcH)
        for (i in src.indices) { val p = src[i]; lum[i] = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8 }
        val raw = IntArray(srcW * srcH)
        for (y in 1 until srcH - 1) for (x in 1 until srcW - 1) {
            val i = y * srcW + x
            val gx = lum[i + 1] - lum[i - 1]; val gy = lum[i + srcW] - lum[i - srcW]
            raw[i] = (kotlin.math.abs(gx) + kotlin.math.abs(gy)) * 3 // ≈ /40 → *255 scaled
        }
        for (y in 1 until srcH - 1) for (x in 1 until srcW - 1) {
            val i = y * srcW + x
            var s = 0
            for (dy in -1..1) for (dx in -1..1) s += raw[i + dy * srcW + dx]
            edge[i] = (s / 9).coerceAtMost(255).toByte()
        }
    }

    private fun blendRows(out: IntArray, src: IntArray, kFlat: Int, kGrain: Int, y0: Int, y1: Int) {
        for (y in y0 until y1) {
            // bilinear source coords
            val syf = ((y * 256) / scale - 128).coerceAtLeast(0); val sy = (syf shr 8).coerceAtMost(srcH - 2); val wy = syf and 255
            val row = y * outW
            for (x in 0 until outW) {
                val sxf = ((x * 256) / scale - 128).coerceAtLeast(0); val sx = (sxf shr 8).coerceAtMost(srcW - 2); val wx = sxf and 255
                val i00 = sy * srcW + sx; val i01 = i00 + 1; val i10 = i00 + srcW; val i11 = i10 + 1
                val p00 = src[i00]; val p01 = src[i01]; val p10 = src[i10]; val p11 = src[i11]
                // bilinear per channel
                val w00 = (256 - wx) * (256 - wy); val w01 = wx * (256 - wy); val w10 = (256 - wx) * wy; val w11 = wx * wy
                val br = (((p00 shr 16) and 0xFF) * w00 + ((p01 shr 16) and 0xFF) * w01 + ((p10 shr 16) and 0xFF) * w10 + ((p11 shr 16) and 0xFF) * w11) shr 16
                val bg = (((p00 shr 8) and 0xFF) * w00 + ((p01 shr 8) and 0xFF) * w01 + ((p10 shr 8) and 0xFF) * w10 + ((p11 shr 8) and 0xFF) * w11) shr 16
                val bb = ((p00 and 0xFF) * w00 + (p01 and 0xFF) * w01 + (p10 and 0xFF) * w10 + (p11 and 0xFF) * w11) shr 16
                // detail 0..255 (edge mask), flatness = 255 - detail
                val det = edge[(sy + (wy shr 7)).coerceAtMost(srcH - 1) * srcW + (sx + (wx shr 7)).coerceAtMost(srcW - 1)].toInt() and 0xFF
                val pull = (kFlat * (255 - det)) shr 8          // 0..kFlat
                // grain: source high-pass ≈ nearest − bilinear
                val pn = src[(sy + (wy shr 7)).coerceAtMost(srcH - 1) * srcW + (sx + (wx shr 7)).coerceAtMost(srcW - 1)]
                val gr = (((pn shr 16) and 0xFF) - br) * kGrain shr 8
                val gg = (((pn shr 8) and 0xFF) - bg) * kGrain shr 8
                val gb = ((pn and 0xFF) - bb) * kGrain shr 8
                val c = out[row + x]
                val r = ((((c shr 16) and 0xFF) * (256 - pull) + br * pull) shr 8) + gr
                val g = ((((c shr 8) and 0xFF) * (256 - pull) + bg * pull) shr 8) + gg
                val b = (((c and 0xFF) * (256 - pull) + bb * pull) shr 8) + gb
                out[row + x] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
    }

    fun close() { pool.shutdown() }
}
