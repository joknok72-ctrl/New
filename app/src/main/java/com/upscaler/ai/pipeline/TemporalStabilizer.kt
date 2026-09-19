package com.upscaler.ai.pipeline

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
        // per-output-pixel: motion = |cur - prev| at the low-res source pixel
        for (y in 0 until height) {
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
        System.arraycopy(out, 0, prev, 0, out.size)
    }
}
