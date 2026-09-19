package com.upscaler.ai.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.upscaler.ai.engine.DeviceProfiler
import com.upscaler.ai.engine.QualityPreset
import com.upscaler.ai.engine.TargetResolution
import com.upscaler.ai.engine.UpscaleModel
import com.upscaler.ai.pipeline.UpscaleJob
import com.upscaler.ai.pipeline.UpscaleState
import com.upscaler.ai.service.UpscaleService
import com.upscaler.ai.video.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Settings(
    val model: UpscaleModel = UpscaleModel.GENERAL,
    val preset: QualityPreset = QualityPreset.BALANCED,
    val target: TargetResolution = TargetResolution.P1080,
    val sharpen: Float = 0.3f,
    val antiFlicker: Boolean = true,
    val hevc: Boolean = true,
    val gpu: Boolean = true,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    private val _input = MutableStateFlow<Uri?>(null)
    val input: StateFlow<Uri?> = _input
    private val _info = MutableStateFlow<VideoInfo?>(null)
    val info: StateFlow<VideoInfo?> = _info
    private val _thumb = MutableStateFlow<Bitmap?>(null)
    val thumb: StateFlow<Bitmap?> = _thumb
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    val state: StateFlow<UpscaleState> = UpscaleService.state
    val profile: DeviceProfiler.Profile by lazy { DeviceProfiler.profile(ctx) }

    fun setInput(uri: Uri) {
        _input.value = uri
        _info.value = null
        _thumb.value = null
        UpscaleService.resetState()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val i = VideoInfo.probe(ctx, uri)
                _info.value = i
                // auto-pick smart defaults based on source
                val s = _settings.value
                val auto = when {
                    i.displayHeight <= 180 -> s.copy(target = TargetResolution.P1080, preset = QualityPreset.BALANCED)
                    i.displayHeight <= 360 -> s.copy(target = TargetResolution.P1080)
                    i.displayHeight <= 540 -> s.copy(target = TargetResolution.P2160)
                    else -> s.copy(target = TargetResolution.AUTO)
                }
                _settings.value = auto
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(ctx, uri)
                _thumb.value = mmr.getFrameAtTime(minOf(i.durationMs, 2000L) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                mmr.release()
            } catch (_: Throwable) {}
        }
    }

    fun update(block: (Settings) -> Settings) { _settings.value = block(_settings.value) }

    /**
     * Rough ETA before starting. Calibrated on mid-range 2023 phones (Snapdragon 7-series class):
     * 64-feat net ≈ 45 ms per 128² input tile on NNAPI fp16, ~4x slower on CPU.
     */
    fun estimateSeconds(): Long? {
        val i = _info.value ?: return null
        val s = _settings.value
        if (s.preset == QualityPreset.FAST) return (i.durationMs / 1000 * 0.15).toLong().coerceAtLeast(2)
        val tile = profile.tileSize
        val step = tile - 16
        val tilesPerFrame = ((i.width + step - 1) / step) * ((i.height + step - 1) / step)
        var msPerTile = 45.0 * (tile * tile) / (128.0 * 128.0) * s.model.relativeCost
        if (!s.gpu) msPerTile *= 4
        if (!profile.isHighEnd) msPerTile *= 1.6
        var frames = i.frameCountEstimate.toDouble()
        if (s.preset == QualityPreset.BALANCED) frames *= 0.75 // typical duplicate ratio on web video
        var total = frames * tilesPerFrame * msPerTile / 1000.0
        if (s.preset == QualityPreset.ULTRA) {
            val step2 = step
            val t2 = ((i.width * 4 + step2 - 1) / step2) * ((i.height * 4 + step2 - 1) / step2)
            total += frames * t2 * msPerTile / 1000.0
        }
        // pipeline overlap hides part of decode/encode
        return (total * 1.1 + 5).toLong()
    }

    fun start() {
        val uri = _input.value ?: return
        val s = _settings.value
        UpscaleService.start(ctx, UpscaleJob(uri, s.model, s.preset, s.target, s.sharpen, s.antiFlicker, s.hevc, s.gpu))
    }

    fun cancel() = UpscaleService.cancel(ctx)

    fun reset() {
        UpscaleService.resetState()
        _input.value = null; _info.value = null; _thumb.value = null
    }
}
