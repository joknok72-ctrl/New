package com.upscaler.ai.ui

import java.util.Locale

/** Tiny bilingual string table (Arabic / English), picked from device locale. */
object S {
    val ar: Boolean get() = Locale.getDefault().language == "ar"
    private fun t(a: String, e: String) = if (ar) a else e

    val title get() = t("محسّن الفيديو AI", "Video Upscaler AI")
    val subtitle get() = t("من 144p إلى 4K بالذكاء الاصطناعي — على جهازك، بدون إنترنت", "144p → 4K with on-device AI. No internet needed")
    val pickVideo get() = t("اختر فيديو", "Pick a video")
    val changeVideo get() = t("تغيير الفيديو", "Change video")
    val start get() = t("ابدأ التحسين", "Start upscaling")
    val cancel get() = t("إلغاء", "Cancel")
    val model get() = t("موديل الذكاء الاصطناعي", "AI model")
    val preset get() = t("الجودة والسرعة", "Quality & speed")
    val target get() = t("الدقة المطلوبة", "Target resolution")
    val sharpen get() = t("حدّة إضافية", "Extra sharpness")
    val antiFlicker get() = t("منع الوميض (ثبات زمني)", "Anti-flicker (temporal stability)")
    val hevc get() = t("ترميز HEVC (حجم أصغر)", "HEVC encoding (smaller file)")
    val gpu get() = t("تسريع GPU/NPU", "GPU/NPU acceleration")
    val advanced get() = t("إعدادات متقدمة", "Advanced")
    val estimate get() = t("الوقت المتوقع", "Estimated time")
    val inputInfo get() = t("الفيديو الأصلي", "Source video")
    val output get() = t("النتيجة", "Output")
    val preparing get() = t("جارٍ التحضير…", "Preparing…")
    val processing get() = t("جارٍ المعالجة", "Processing")
    val frames get() = t("فريم", "frames")
    val skipped get() = t("فريمات مكررة تم توفيرها", "duplicate frames saved")
    val speed get() = t("السرعة", "Speed")
    val eta get() = t("الوقت المتبقي", "Remaining")
    val elapsed get() = t("الوقت المنقضي", "Elapsed")
    val engine get() = t("المحرك", "Engine")
    val done get() = t("تم بنجاح", "Done")
    val savedTo get() = t("تم الحفظ في: Movies/VideoUpscalerAI", "Saved to Movies/VideoUpscalerAI")
    val open get() = t("فتح", "Open")
    val share get() = t("مشاركة", "Share")
    val newVideo get() = t("فيديو جديد", "New video")
    val failed get() = t("حدث خطأ", "Failed")
    val retry get() = t("حاول مرة أخرى", "Try again")
    val cancelled get() = t("تم الإلغاء", "Cancelled")
    val screenOffOk get() = t("يمكنك قفل الشاشة — المعالجة تستمر في الخلفية", "You can lock the screen — processing continues in background")
    val keepPlugged get() = t("نصيحة: وصّل الشاحن لأفضل أداء", "Tip: plug in the charger for best performance")
    val tooLong get() = t("تحذير: الوقت المتوقع طويل. اختر إعداد أسرع أو دقة أقل.", "Warning: long estimated time. Pick a faster preset or lower resolution.")
    val permissionNeeded get() = t("نحتاج إذن الإشعارات لعرض التقدم", "Notification permission is needed to show progress")
    val duration get() = t("المدة", "Duration")
    val minutesShort get() = t("د", "m")
    val secondsShort get() = t("ث", "s")
    val hoursShort get() = t("س", "h")
    val howItWorks get() = t("كيف يعمل؟", "How it works")
    val howItWorksBody get() = t(
        "1) يفك الفيديو بالهاردوير\n2) شبكة Real-ESRGAN تكبّر كل فريم ×4 على GPU/NPU\n3) يتخطى الفريمات المكررة ويثبّت النتيجة زمنياً\n4) يعيد الترميز HEVC مع نسخ الصوت الأصلي بدون فقدان",
        "1) Hardware decode\n2) Real-ESRGAN upscales each frame ×4 on GPU/NPU\n3) Skips duplicate frames & stabilizes temporally\n4) HEVC re-encode with lossless audio copy"
    )
}
