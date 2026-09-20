package com.upscaler.ai.engine

/**
 * Available AI models. Small ones are bundled in assets/models (ONNX files);
 * big ones are downloaded on demand from the GitHub Release into filesDir/models.
 */
enum class UpscaleModel(
    val assetName: String,
    val displayNameAr: String,
    val displayNameEn: String,
    val descriptionAr: String,
    val descriptionEn: String,
    /** Relative compute cost vs GENERAL (64-feature compact net). */
    val relativeCost: Float,
    /** null = bundled in APK; otherwise download URL. */
    val downloadUrl: String? = null,
    val downloadSizeMb: Int = 0,
) {
    NATURAL(
        assetName = "realesr-general-natural-x4v3.onnx",
        displayNameAr = "طبيعي (موصى به للفيديو)",
        displayNameEn = "Natural (recommended for video)",
        descriptionAr = "مزيج 50/50 من العام + إزالة التشويش: أنعم وأقرب للطبيعي، بدون مظهر بلاستيكي",
        descriptionEn = "50/50 blend of General + Denoise: softer, film-like, no plastic look",
        relativeCost = 1.0f,
    ),
    GENERAL(
        assetName = "realesr-general-x4v3.onnx",
        displayNameAr = "عام حاد (أقصى تفاصيل)",
        displayNameEn = "General sharp (max detail)",
        descriptionAr = "أحدّ ما يمكن — ممتاز للنصوص والمباني، قد يبدو صناعياً على الوجوه",
        descriptionEn = "Sharpest — great for text/buildings, can look artificial on faces",
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
    ),
    ULTRA_PLUS(
        assetName = "realesrgan-x4plus.onnx",
        displayNameAr = "Ultra+ (وجوه وتفاصيل — أبطأ ×8)",
        displayNameEn = "Ultra+ (faces & fine detail — 8x slower)",
        descriptionAr = "الشبكة الكاملة RRDBNet 16M. أفضل جودة ممكنة للوجوه والملابس والنصوص. يُحمَّل مرة واحدة (67 MB)",
        descriptionEn = "Full RRDBNet 16M-param net. Best possible quality for faces, fabric, text. One-time 67 MB download",
        relativeCost = 8.0f,
        downloadUrl = "https://github.com/joknok72-ctrl/New/releases/latest/download/realesrgan-x4plus.onnx",
        downloadSizeMb = 67,
    );

    val isDownloadable get() = downloadUrl != null

    companion object {
        fun fromName(n: String?): UpscaleModel = entries.firstOrNull { it.name == n } ?: NATURAL
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

/** Automatic colour/contrast enhancement for washed-out / dark sources (runs on GPU). */
enum class ColorMode(val labelAr: String, val labelEn: String) {
    OFF("بدون", "Off"),
    AUTO("تلقائي (تباين + تشبع)", "Auto (contrast + saturation)"),
    VIVID("حيوي", "Vivid");

    companion object {
        fun fromName(n: String?): ColorMode = entries.firstOrNull { it.name == n } ?: OFF
    }
}

/** Where the AI runs. */
enum class ComputeMode(val labelAr: String, val labelEn: String, val descAr: String, val descEn: String) {
    DEVICE("الجهاز", "Device", "على GPU الموبايل — بدون إنترنت", "Phone GPU/NPU — works offline"),
    HYBRID("هايبرد (أسرع)", "Hybrid (fastest)", "الموبايل + GPU سحابي مجاني يشتغلوا مع بعض", "Phone + free cloud GPU working in parallel"),
    CLOUD("سحابي بالكامل", "Cloud only", "يرفع الفيديو لـ GPU مجاني (A10G/T4) ويرجّع النتيجة — أفضل للفيديوهات القصيرة", "Uploads the clip to a free GPU (A10G/T4) — best for short clips");

    companion object {
        fun fromName(n: String?): ComputeMode = entries.firstOrNull { it.name == n } ?: DEVICE
    }
}
