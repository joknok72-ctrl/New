package com.upscaler.ai.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.upscaler.ai.engine.DeviceProfiler
import com.upscaler.ai.engine.PreviewEngine
import com.upscaler.ai.engine.QualityPreset
import com.upscaler.ai.engine.TargetResolution
import com.upscaler.ai.engine.UpscaleModel
import com.upscaler.ai.pipeline.UpscaleJob
import com.upscaler.ai.pipeline.UpscaleState
import com.upscaler.ai.service.QueuedJob
import com.upscaler.ai.service.UpscaleService
import com.upscaler.ai.util.Prefs
import com.upscaler.ai.video.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

data class Settings(
    val model: UpscaleModel = UpscaleModel.GENERAL,
    val preset: QualityPreset = QualityPreset.BALANCED,
    val target: TargetResolution = TargetResolution.P1080,
    val sharpen: Float = 0.3f,
    val antiFlicker: Boolean = true,
    val hevc: Boolean = true,
    val gpu: Boolean = true,
) {
    fun toJson() = JSONObject().apply {
        put("model", model.name); put("preset", preset.name); put("target", target.name)
        put("sharpen", sharpen.toDouble()); put("antiFlicker", antiFlicker); put("hevc", hevc); put("gpu", gpu)
    }
    companion object {
        fun fromJson(o: JSONObject) = Settings(
            UpscaleModel.fromName(o.optString("model")), QualityPreset.fromName(o.optString("preset")),
            TargetResolution.fromName(o.optString("target")), o.optDouble("sharpen", 0.3).toFloat(),
            o.optBoolean("antiFlicker", true), o.optBoolean("hevc", true), o.optBoolean("gpu", true))
    }
}

/** One selected source video with its metadata. */
data class SourceItem(val uri: Uri, val info: VideoInfo?, val thumb: Bitmap?)

data class PreviewState(
    val loading: Boolean = false,
    val before: Bitmap? = null,
    val after: Bitmap? = null,
    val provider: String? = null,
    val msPerTile: Double? = null,
    val error: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()

    private val _sources = MutableStateFlow<List<SourceItem>>(emptyList())
    val sources: StateFlow<List<SourceItem>> = _sources
    private val _settings = MutableStateFlow(Prefs.loadSettings(app)?.let { Settings.fromJson(it) } ?: Settings())
    val settings: StateFlow<Settings> = _settings
    private val _preview = MutableStateFlow(PreviewState())
    val preview: StateFlow<PreviewState> = _preview
    private val _history = MutableStateFlow(Prefs.loadHistory(app))
    val history: StateFlow<List<Prefs.HistoryItem>> = _history
    /** Trim range (ms) applied to the FIRST source only (single-video mode). */
    private val _trim = MutableStateFlow(0L to 0L)
    val trim: StateFlow<Pair<Long, Long>> = _trim
    private val _showHistory = MutableStateFlow(false)
    val showHistory: StateFlow<Boolean> = _showHistory

    val state: StateFlow<UpscaleState> = UpscaleService.state
    val queue: StateFlow<List<QueuedJob>> = UpscaleService.queue
    val profile: DeviceProfiler.Profile by lazy { DeviceProfiler.profile(ctx) }
    private var previewJob: Job? = null

    val primary: SourceItem? get() = _sources.value.firstOrNull()

    fun addSources(uris: List<Uri>) {
        if (uris.isEmpty()) return
        UpscaleService.clearFinished()
        _preview.value = PreviewState()
        _trim.value = 0L to 0L
        val existing = _sources.value.map { it.uri }.toSet()
        val fresh = uris.filter { it !in existing }.map { SourceItem(it, null, null) }
        _sources.value = _sources.value + fresh
        fresh.forEach { item ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val i = VideoInfo.probe(ctx, item.uri)
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(ctx, item.uri)
                    val th = mmr.getFrameAtTime(minOf(i.durationMs, 2000L) * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    mmr.release()
                    _sources.value = _sources.value.map { if (it.uri == item.uri) it.copy(info = i, thumb = th) else it }
                    if (_sources.value.firstOrNull()?.uri == item.uri) autoTarget(i)
                } catch (_: Throwable) {
                    _sources.value = _sources.value.filter { it.uri != item.uri }
                }
            }
        }
    }

    fun removeSource(uri: Uri) { _sources.value = _sources.value.filter { it.uri != uri } }
    fun clearSources() { _sources.value = emptyList(); _preview.value = PreviewState(); _trim.value = 0L to 0L }

    private fun autoTarget(i: VideoInfo) {
        val s = _settings.value
        val t = when {
            i.displayHeight <= 360 -> TargetResolution.P1080
            i.displayHeight <= 540 -> TargetResolution.P2160
            else -> TargetResolution.AUTO
        }
        update { s.copy(target = t) }
    }

    fun update(block: (Settings) -> Settings) {
        val new = block(_settings.value)
        val modelChanged = new.model != _settings.value.model || new.gpu != _settings.value.gpu
        _settings.value = new
        Prefs.saveSettings(ctx, new.toJson())
        if (modelChanged) _preview.value = PreviewState()
    }

    fun setTrim(startMs: Long, endMs: Long) { _trim.value = startMs to endMs }
    fun toggleHistory() { _showHistory.value = !_showHistory.value; if (_showHistory.value) _history.value = Prefs.loadHistory(ctx) }
    fun refreshHistory() { _history.value = Prefs.loadHistory(ctx) }
    fun clearHistory() { Prefs.clearHistory(ctx); _history.value = emptyList() }

    /** Upscale one frame of the first source for before/after and to benchmark the device. */
    fun runPreview() {
        val src = primary ?: return
        val info = src.info ?: return
        val s = _settings.value
        previewJob?.cancel()
        _preview.value = PreviewState(loading = true)
        previewJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val at = (_trim.value.first + minOf(info.durationMs, 3000L)).coerceAtMost(info.durationMs - 1)
                val r = PreviewEngine.run(ctx, src.uri, at, s.model, s.gpu)
                _preview.value = PreviewState(before = r.before, after = r.after, provider = r.provider, msPerTile = r.msPerTile)
            } catch (t: Throwable) {
                _preview.value = PreviewState(error = t.message ?: "preview failed")
            }
        }
    }

    /** ETA in seconds for the whole selection. Uses measured benchmark when available. */
    fun estimateSeconds(): Long? {
        val items = _sources.value.mapNotNull { it.info }
        if (items.isEmpty()) return null
        val s = _settings.value
        var total = 0.0
        items.forEachIndexed { idx, i ->
            var durMs = i.durationMs
            if (idx == 0 && _trim.value.second > _trim.value.first) durMs = _trim.value.second - _trim.value.first
            total += estimateOne(i, durMs, s)
        }
        return total.toLong()
    }

    private fun estimateOne(i: VideoInfo, durMs: Long, s: Settings): Double {
        if (s.preset == QualityPreset.FAST) return (durMs / 1000.0 * 0.15).coerceAtLeast(2.0)
        val tile = profile.tileSize
        val step = tile - 16
        val tilesPerFrame = ((i.width + step - 1) / step) * ((i.height + step - 1) / step)
        val measured = PreviewEngine.cachedMsPerTile(s.model, s.gpu)?.first
        var msPerTile = measured ?: run {
            var m = 45.0 * (tile * tile) / (128.0 * 128.0) * s.model.relativeCost
            if (!s.gpu) m *= 4
            if (!profile.isHighEnd) m *= 1.6
            m
        }
        var frames = durMs / 1000.0 * i.fps
        if (s.preset == QualityPreset.BALANCED) frames *= 0.75
        var total = frames * tilesPerFrame * msPerTile / 1000.0
        if (s.preset == QualityPreset.ULTRA) {
            val t2 = ((i.width * 4 + step - 1) / step) * ((i.height * 4 + step - 1) / step)
            total += frames * t2 * msPerTile / 1000.0
        }
        return total * 1.1 + 5
    }

    val isMeasured: Boolean get() = PreviewEngine.cachedMsPerTile(_settings.value.model, _settings.value.gpu) != null

    /** Enqueue all selected sources. */
    fun startAll() {
        val s = _settings.value
        _sources.value.forEachIndexed { idx, src ->
            val (a, b) = if (idx == 0) _trim.value else 0L to 0L
            UpscaleService.enqueue(ctx, UpscaleJob(src.uri, s.model, s.preset, s.target, s.sharpen, s.antiFlicker, s.hevc, s.gpu, a, b),
                src.info?.displayName ?: "video")
        }
    }

    /** Quick 10-second test of the first source with current settings. */
    fun startQuickTest() {
        val src = primary ?: return
        val s = _settings.value
        val start = _trim.value.first
        val info = src.info
        val end = minOf(start + 10_000, info?.durationMs ?: (start + 10_000))
        UpscaleService.enqueue(ctx, UpscaleJob(src.uri, s.model, s.preset, s.target, s.sharpen, s.antiFlicker, s.hevc, s.gpu, start, end),
            "TEST 10s • " + (info?.displayName ?: "video"))
    }

    fun cancelCurrent() = UpscaleService.cancelCurrent(ctx)
    fun cancelAll() = UpscaleService.cancelAll(ctx)
    fun removeQueued(id: Long) = UpscaleService.remove(ctx, id)

    fun reset() {
        UpscaleService.clearFinished()
        clearSources()
        refreshHistory()
    }
}
