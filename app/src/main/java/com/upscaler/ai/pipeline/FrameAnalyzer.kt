package com.upscaler.ai.pipeline

import kotlin.math.abs

/**
 * Cheap per-frame analysis used to make the pipeline *smart*:
 *
 *  • Duplicate / near-static detection: low-res web video (144p) very often has
 *    repeated frames (e.g. 15 fps content padded to 30 fps, slideshows, talking heads).
 *    Re-running the AI on an identical frame is wasted work → we reuse the previous output.
 *
 *  • Scene-cut detection: used to reset temporal smoothing so we never blend across cuts.
 *
 * Works on a 32x32 luma thumbnail so its cost is ~0 compared to the network.
 */
class FrameAnalyzer {
    companion object {
        const val T = 32
        /** Mean abs luma diff (0..255) below which frames are considered duplicates. */
        const val DUP_THRESHOLD = 1.2f
        /** Above this → scene cut. */
        const val CUT_THRESHOLD = 40f
    }

    private val prev = FloatArray(T * T)
    private val cur = FloatArray(T * T)
    private var hasPrev = false

    data class Result(val meanDiff: Float, val isDuplicate: Boolean, val isSceneCut: Boolean)

    fun analyze(argb: IntArray, w: Int, h: Int): Result {
        // box-downsample luma to T×T
        val cw = w / T.toFloat()
        val ch = h / T.toFloat()
        for (ty in 0 until T) {
            val y0 = (ty * ch).toInt()
            val y1 = ((ty + 1) * ch).toInt().coerceAtMost(h).coerceAtLeast(y0 + 1)
            for (tx in 0 until T) {
                val x0 = (tx * cw).toInt()
                val x1 = ((tx + 1) * cw).toInt().coerceAtMost(w).coerceAtLeast(x0 + 1)
                var sum = 0L
                var n = 0
                var y = y0
                while (y < y1) {
                    val row = y * w
                    var x = x0
                    while (x < x1) {
                        val p = argb[row + x]
                        // luma ≈ (2R + 5G + B) / 8
                        sum += ((((p shr 16) and 0xFF) shl 1) + (((p shr 8) and 0xFF) * 5) + (p and 0xFF)) shr 3
                        n++
                        x++
                    }
                    y++
                }
                cur[ty * T + tx] = sum.toFloat() / n
            }
        }
        var diff = 0f
        if (hasPrev) {
            var acc = 0f
            for (i in 0 until T * T) acc += abs(cur[i] - prev[i])
            diff = acc / (T * T)
        } else diff = 255f
        System.arraycopy(cur, 0, prev, 0, T * T)
        hasPrev = true
        return Result(diff, hasPrev && diff < DUP_THRESHOLD, diff > CUT_THRESHOLD)
    }

    fun reset() { hasPrev = false }
}
