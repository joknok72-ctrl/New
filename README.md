# Video Upscaler AI — محسّن الفيديو بالذكاء الاصطناعي

<p align="center">
<b>144p → 1080p / 4K on your Android phone. Offline. Free. Open source.</b><br>
تحويل الفيديوهات منخفضة الدقة إلى دقة عالية بالذكاء الاصطناعي — على جهازك بدون إنترنت
</p>

## 📱 تحميل التطبيق (Download APK)

1. افتح صفحة **[Releases](../../releases/latest)** واضغط على ملف `VideoUpscalerAI-*.apk`
2. سطّبه على موبايلك (Android 8.0 أو أحدث — يُفضّل موبايل من 2021+ بـ 6GB RAM أو أكثر)
3. افتح التطبيق → اختر فيديو → اضغط **ابدأ التحسين**

> كل `push` على `main` يبني APK جديد أوتوماتيكياً عبر GitHub Actions وينشره في Releases.

---

## ✨ Features

| | |
|---|---|
| 🧠 **Real-ESRGAN AI** | 3 bundled models (general / denoise / anime), x4 per pass, x16 in ULTRA mode |
| ⚡ **GPU / NPU acceleration** | ONNX Runtime + NNAPI (fp16). Auto-fallback to XNNPACK CPU |
| 🔁 **Smart duplicate-frame skipping** | Web video at 144p often repeats frames — we reuse the previous AI output (typ. 20–40 % time saved) |
| 🎞️ **Temporal anti-flicker** | Motion-adaptive blending kills the "shimmer" typical of single-image SR |
| 🧩 **Fixed-shape tiling with overlap** | NNAPI compiles the graph once; 8 px overlap → zero seams |
| 🔀 **3-stage parallel pipeline** | HW decode ‖ AI ‖ GL post-process + HW encode — run concurrently |
| 🎚️ **GPU sharpening & exact resize** | Adaptive unsharp mask in GLSL, output exactly 720p/1080p/1440p/4K |
| 🔊 **Lossless audio copy** | Original audio track is muxed bit-for-bit |
| 📦 **HEVC output** | Smaller files; falls back to H.264 automatically |
| 🔋 **Foreground service + wake lock** | Lock the screen, processing continues; progress in notification |
| 🌍 **Arabic + English UI** | Follows system language, RTL supported |
| 📤 **Share-to-app** | Share any video from Gallery / WhatsApp straight into the upscaler |

## 🏗️ Tech Stack

- **Kotlin 2.2 + Jetpack Compose (Material 3)** — modern declarative UI
- **ONNX Runtime Android 1.30** — inference (NNAPI / XNNPACK execution providers)
- **Real-ESRGAN `SRVGGNetCompact`** (BSD-3, xinntao) — exported to ONNX at CI time by `tools/export_models.py`
- **MediaCodec / MediaMuxer / MediaExtractor** — hardware decode & encode
- **OpenGL ES 3.0** — resize, rotation, sharpening on GPU
- **Coroutines + Channels** — producer/consumer pipeline
- **GitHub Actions** — builds, signs and publishes the APK

## 🧭 Quality presets

| Preset | What it does | 1 min of 144p → 1080p (mid-range 2023 phone) |
|---|---|---|
| ⚡ Fast | No AI. HW scale + GPU sharpen | ~10 s |
| ⚖️ Balanced *(default)* | AI x4, skips duplicate frames, anti-flicker | ~3–6 min |
| 🎯 High | AI x4 on **every** frame | ~5–8 min |
| 💎 Ultra | Two chained AI passes (x16) — for 144p → 4K | ~15–30 min |

The app shows an **estimated time before you start** and warns if it will exceed 30 minutes.

## 📂 Project layout

```
app/src/main/java/com/upscaler/ai/
├── engine/     SuperResolutionEngine (ORT+NNAPI tiling), DeviceProfiler, model enums
├── video/      FrameDecoder (HW→ARGB), VideoEncoder (+audio copy), GlFrameRenderer, VideoInfo
├── pipeline/   UpscalePipeline (3-stage), FrameAnalyzer (dup/scene-cut), TemporalStabilizer
├── service/    UpscaleService (foreground, notification, wake lock)
└── ui/         MainActivity (Compose), MainViewModel, Strings (AR/EN), Theme
tools/export_models.py   PyTorch → ONNX export (runs in CI, models cached)
.github/workflows/build.yml
```

## 🔐 Release signing (optional)

By default CI signs with the debug key (installable, but updates need uninstall). For a stable signature add these repository **Secrets**:
`KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## 🛠️ Build locally

```bash
pip install torch onnx onnxruntime && python tools/export_models.py
./gradlew assembleRelease
```

## 🗺️ Roadmap / ideas

- [ ] Batch queue (multiple videos)
- [ ] Optional cloud GPU mode for 10× faster processing (paid API)
- [ ] Face-enhancement pass (GFPGAN) for talking-head videos
- [ ] Frame interpolation (RIFE) 15 fps → 60 fps
- [ ] Side-by-side before/after preview scrubber

## 📜 License

App code: MIT. Real-ESRGAN weights: BSD-3-Clause (© Xintao Wang et al.).
