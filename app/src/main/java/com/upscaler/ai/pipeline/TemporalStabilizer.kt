package com.upscaler.ai.pipeline

import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Anti-flicker for AI-upscaled video.
 *
 * Single-image SR networks (Real-ESRGAN) hallucinate slightly different textures on each
 * frame, which shows up as shimmering. We blend the current output with the previous output
 * *only where the input didn't change much* (motion-adaptive). Static regions become rock
 * solid, moving regions stay sharp (no ghosting).
 *
 * Runs on the x4 output; cost is a single pass of int math — cheap next to the network.
 */
class TemporalStabilizer(private val width: Int, private val height: Int) {
    private var prevOut: IntArray? = null
    private val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
    private val pool = Executors.newFixedThreadPool(threads)
    /** 0 = off, 1 = max. 0.35 is a good default. */
    var strength = 0.35f

    fun reset() { prevOut = null }

    /**
     * @param out   current AI output (modified in place)
     * @param lowResPrev previous *input* frame, used to compute motion mask
     * @param lowResCur  current input frame
     */
    fun apply(out: IntArray, lowResPrev: IntArray?, lowResCur: IntArray, lw: Int, lh: Int) {
        val prev = prevOut
        if (prev == null || lowResPrev == null || strength <= 0.001f) {
            prevOut = (prevOut ?: IntArray(out.size)).also { System.arraycopy(out, 0, it, 0, out.size) }
            return
        }
        val scale = width / lw // 4
        val aMax = (strength * 256).toInt()
        val rowsPer = (height + threads - 1) / threads
        val futures = ArrayList<Future<*>>(threads)
        for (t in 0 until threads) {
            val y0 = t * rowsPer
            val y1 = minOf(height, y0 + rowsPer)
            if (y0 >= y1) break
            futures += pool.submit { blendRows(out, prev, lowResPrev, lowResCur, lw, lh, scale, aMax, y0, y1) }
        }
        futures.forEach { it.get() }
        System.arraycopy(out, 0, prev, 0, out.size)
    }

    private fun blendRows(out: IntArray, prev: IntArray, lowResPrev: IntArray, lowResCur: IntArray, lw: Int, lh: Int, scale: Int, aMax: Int, y0: Int, y1: Int) {
        // per-output-pixel: motion = |cur - prev| at the low-res source pixel
        for (y in y0 until y1) {
            val ly = (y / scale).coerceAtMost(lh - 1) * lw
            val row = y * width
            for (x in 0 until width) {
                val li = ly + (x / scale).coerceAtMost(lw - 1)
                val a = lowResCur[li]
                val b = lowResPrev[li]
                val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
                val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
                val db = (a and 0xFF) - (b and 0xFF)
                val motion = (if (dr < 0) -dr else dr) + (if (dg < 0) -dg else dg) + (if (db < 0) -db else db)
                // motion 0 → full blend (aMax), motion >= 24 → no blend
                val alpha = if (motion >= 24) 0 else aMax * (24 - motion) / 24
                if (alpha == 0) continue
                val c = out[row + x]
                val p = prev[row + x]
                val inv = 256 - alpha
                val r = (((c shr 16) and 0xFF) * inv + ((p shr 16) and 0xFF) * alpha) shr 8
                val g = (((c shr 8) and 0xFF) * inv + ((p shr 8) and 0xFF) * alpha) shr 8
                val bl = ((c and 0xFF) * inv + (p and 0xFF) * alpha) shr 8
                out[row + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
            }
        }
    }

    fun close() { pool.shutdown() }
}
