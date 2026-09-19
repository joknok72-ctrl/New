# ONNX Runtime uses JNI — keep everything
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# Keep our engine classes (used via reflection-free JNI callbacks)
-keep class com.upscaler.ai.engine.** { *; }
