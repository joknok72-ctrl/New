package com.upscaler.ai.pipeline

import android.net.Uri
import com.upscaler.ai.engine.ColorMode
import com.upscaler.ai.engine.ComputeMode
import com.upscaler.ai.engine.QualityPreset
import com.upscaler.ai.engine.TargetResolution
import com.upscaler.ai.engine.UpscaleModel

data class UpscaleJob(
    val inputUri: Uri,
    val model: UpscaleModel,
    val preset: QualityPreset,
    val target: TargetResolution,
    val sharpen: Float,           // 0..1 extra sharpening on GPU
    val antiFlicker: Boolean,
    val preferHevc: Boolean,
    val useGpu: Boolean,          // NNAPI on/off
    /** Optional trim range in ms. endMs <= 0 means "to the end". */
    val startMs: Long = 0,
    val endMs: Long = 0,
    val colorMode: ColorMode = ColorMode.OFF,
    val compute: ComputeMode = ComputeMode.DEVICE,
)

sealed class UpscaleState {
    data object Idle : UpscaleState()
    data class Preparing(val message: String) : UpscaleState()
    data class Running(
        val frame: Long,
        val totalFrames: Long,
        val progress: Float,       // 0..1
        val fps: Float,            // processing speed
        val etaSeconds: Long,
        val skipped: Long,         // duplicate frames reused
        val provider: String,      // nnapi / xnnpack / cpu
        val inputRes: String,
        val outputRes: String,
        val elapsedSeconds: Long,
        val paused: Boolean = false,
        val cloudFrames: Long = 0,
    ) : UpscaleState()
    data class Done(
        val inputUri: Uri,
        val outputUri: Uri,
        val outputPath: String,
        val elapsedSeconds: Long,
        val frames: Long,
        val skipped: Long,
        val outputRes: String,
        val sizeBytes: Long,
    ) : UpscaleState()
    data class Failed(val error: String) : UpscaleState()
    data object Cancelled : UpscaleState()
}
