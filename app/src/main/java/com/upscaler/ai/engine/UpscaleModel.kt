package com.upscaler.ai.engine

/**
 * Available AI models bundled in assets/models/*.onnx
 * All are Real-ESRGAN "SRVGGNetCompact" x4 networks (BSD-3 license, xinntao).
 */
enum class UpscaleModel(
    val assetName: String,
    val displayNameAr: String,
    val displayNameEn: String,
    val descriptionAr: String,
    val descriptionEn: String,
    /** Relative compute cost. anime model has 16 features → ~4x faster than general (64 features). */
    val relativeCost: Float,
) {
    GENERAL(
        assetName = "realesr-general-x4v3.onnx",
        displayNameAr = "عام (أفضل جودة)",
        displayNameEn = "General (best quality)",
        descriptionAr = "مناسب لفيديوهات حقيقية: أفلام، يوتيوب 144p، تسجيلات قديمة",
        descriptionEn = "Real footage: movies, 144p YouTube rips, old recordings",
        relativeCost = 1.0f,
    ),
    GENERAL_DENOISE(
        assetName = "realesr-general-wdn-x4v3.onnx",
        displayNameAr = "عام + إزالة تشويش قوية",
        displayNameEn = "General + strong denoise",
        descriptionAr = "لفيديوهات مضغوطة جداً ومليانة بلوكات وتشويش (WhatsApp, Telegram)",
        descriptionEn = "Heavily compressed / blocky / noisy sources (WhatsApp, Telegram)",
        relativeCost = 1.0f,
    ),
    ANIME(
        assetName = "realesr-animevideov3.onnx",
        displayNameAr = "أنمي / كارتون (أسرع ×4)",
        displayNameEn = "Anime / cartoon (4x faster)",
        descriptionAr = "مخصص للرسوم المتحركة، سريع جداً وخطوط حادة",
        descriptionEn = "Animation only. Very fast, crisp lines",
        relativeCost = 0.25f,
    );

    companion object {
        fun fromName(n: String?): UpscaleModel = entries.firstOrNull { it.name == n } ?: GENERAL
    }
}

/** Output resolution targets. The net always does x4; we then hardware-scale to exactly this. */
enum class TargetResolution(val height: Int, val label: String) {
    AUTO(0, "Auto (×4)"),
    P480(480, "480p"),
    P720(720, "720p HD"),
    P1080(1080, "1080p Full HD"),
    P1440(1440, "1440p 2K"),
    P2160(2160, "2160p 4K");

    companion object {
        fun fromName(n: String?): TargetResolution = entries.firstOrNull { it.name == n } ?: AUTO
    }
}

/** Speed / quality trade-off preset chosen by the user. */
enum class QualityPreset(val labelAr: String, val labelEn: String) {
    /** Two passes of AI (x16 theoretical) for extremely tiny inputs like 144p → 4K. */
    ULTRA("فائق (مرحلتين AI)", "Ultra (2-pass AI)"),
    /** Single AI pass, every frame. */
    HIGH("عالي (AI لكل فريم)", "High (AI every frame)"),
    /** Single AI pass, smart skipping of near-duplicate frames. */
    BALANCED("متوازن (ذكي)", "Balanced (smart skip)"),
    /** No AI, hardware Lanczos + sharpen. Real-time. */
    FAST("سريع (بدون AI)", "Fast (no AI)");

    companion object {
        fun fromName(n: String?): QualityPreset = entries.firstOrNull { it.name == n } ?: BALANCED
    }
}
