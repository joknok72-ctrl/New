package com.upscaler.ai.engine

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Looks at a few frames and decides what kind of content this is, so the app can pick the
 * right AI model automatically. Pure heuristics on cheap statistics — runs in ~50 ms.
 *
 *  • Anime/cartoon: few distinct colours, large flat regions, very sharp edges (high edge
 *    contrast but low edge density), low gradient noise.
 *  • Heavily compressed: strong 8x8 block-boundary energy (macroblock edges) relative to
 *    interior gradient → recommend the denoise model.
 *  • Otherwise: general.
 */
object ContentAnalyzer {

    data class Analysis(
        val recommended: UpscaleModel,
        val animeScore: Float,     // 0..1
        val blockiness: Float,     // 0..1
        val darkness: Float,       // 0..1 (1 = very dark)
        val saturation: Float,     // 0..1 mean saturation
        val recommendColorFix: Boolean,
        val reasonAr: String,
        val reasonEn: String,
    )

    fun analyze(ctx: Context, uri: Uri, durationMs: Long): Analysis {
        val mmr = MediaMetadataRetriever()
        val frames = ArrayList<Bitmap>(3)
        try {
            mmr.setDataSource(ctx, uri)
            val ts = listOf(0.15, 0.5, 0.85).map { (durationMs * it).toLong() * 1000 }
            for (t in ts) mmr.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frames.add(it) }
        } catch (_: Throwable) {} finally { mmr.release() }
        if (frames.isEmpty()) return Analysis(UpscaleModel.NATURAL, 0f, 0f, 0f, 0.5f, false, "افتراضي", "default")

        var anime = 0f; var block = 0f; var dark = 0f; var sat = 0f
        for (f in frames) {
            val r = analyzeFrame(f); anime += r[0]; block += r[1]; dark += r[2]; sat += r[3]
            f.recycle()
        }
        val n = frames.size
        anime /= n; block /= n; dark /= n; sat /= n

        val colorFix = dark > 0.55f || sat < 0.18f
        val (model, ar, en) = when {
            anime > 0.6f -> Triple(UpscaleModel.ANIME, "اكتشفنا رسوم متحركة → موديل الأنمي (أسرع وأدق للخطوط)", "Animation detected → anime model (faster, crisper lines)")
            block > 0.55f -> Triple(UpscaleModel.GENERAL_DENOISE, "ضغط شديد وبلوكات → موديل إزالة التشويش", "Heavy compression artifacts → denoise model")
            else -> Triple(UpscaleModel.NATURAL, "فيديو واقعي → الموديل الطبيعي (بدون مظهر بلاستيكي)", "Real footage → natural model (no plastic look)")
        }
        return Analysis(model, anime, block, dark, sat, colorFix, ar, en)
    }

    /** returns [animeScore, blockiness, darkness, saturation] */
    private fun analyzeFrame(src: Bitmap): FloatArray {
        // work on a downscaled copy for speed; keep ≥ 256 px so 8x8 blocks remain measurable when source is small
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val luma = FloatArray(w * h)
        var satSum = 0f; var lumSum = 0f
        val hist = IntArray(512) // 8-8-8 → 3 bits per channel colour histogram (512 bins)
        for (i in px.indices) {
            val p = px[i]
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            val l = (r * 299 + g * 587 + b * 114) / 1000f
            luma[i] = l; lumSum += l
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            satSum += if (mx == 0) 0f else (mx - mn).toFloat() / mx
            hist[((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)]++
        }
        val n = px.size.toFloat()
        val darkness = 1f - (lumSum / n / 255f).coerceIn(0f, 1f)
        val saturation = satSum / n

        // colour diversity: how many bins hold 99% of pixels
        val sorted = hist.sortedDescending()
        var acc = 0; var bins = 0
        for (c in sorted) { acc += c; bins++; if (acc >= n * 0.99f) break }
        val diversity = bins / 512f // anime: low

        // gradients
        var edgeStrong = 0; var edgeAny = 0; var gradSum = 0f
        var blockE = 0f; var innerE = 0f; var blockN = 0; var innerN = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = abs(luma[i + 1] - luma[i - 1])
                val gy = abs(luma[i + w] - luma[i - w])
                val g = gx + gy
                gradSum += g
                if (g > 6f) edgeAny++
                if (g > 60f) edgeStrong++
                // horizontal block boundary energy (every 8th column) vs interior
                if (x % 8 == 0) { blockE += gx; blockN++ } else { innerE += gx; innerN++ }
                if (y % 8 == 0) { blockE += gy; blockN++ } else { innerE += gy; innerN++ }
            }
        }
        val area = ((w - 2) * (h - 2)).toFloat()
        val edgeDensity = edgeAny / area              // anime: low (flat regions)
        val strongRatio = if (edgeAny > 0) edgeStrong.toFloat() / edgeAny else 0f // anime: high (hard lines)
        val meanGrad = gradSum / area                 // noise proxy
        val blockRatio = if (innerN > 0 && blockN > 0) (blockE / blockN) / ((innerE / innerN) + 0.5f) else 1f

        var anime = 0f
        anime += (1f - diversity * 4f).coerceIn(0f, 1f) * 0.4f          // few colours
        anime += (1f - edgeDensity * 6f).coerceIn(0f, 1f) * 0.3f         // flat regions
        anime += (strongRatio * 3f).coerceIn(0f, 1f) * 0.3f              // hard lines
        val blockiness = ((blockRatio - 1.05f) * 2.5f).coerceIn(0f, 1f)  // >1 means block edges stand out
        // very noisy real footage also benefits from denoise
        val noisy = ((meanGrad - 14f) / 20f).coerceIn(0f, 1f)
        return floatArrayOf(anime, maxOf(blockiness, noisy * 0.7f), darkness, saturation)
    }

    @Suppress("unused")
    private fun rms(a: FloatArray): Float { var s = 0f; for (v in a) s += v * v; return sqrt(s / a.size) }
}
