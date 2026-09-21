package com.upscaler.ai.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.upscaler.ai.engine.CloudEngine
import com.upscaler.ai.engine.ColorMode
import com.upscaler.ai.engine.ComputeMode
import com.upscaler.ai.engine.ContentAnalyzer
import com.upscaler.ai.engine.DeviceProfiler
import com.upscaler.ai.engine.ModelStore
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
    val model: UpscaleModel = UpscaleModel.NATURAL,
    val preset: QualityPreset = QualityPreset.BALANCED,
    val target: TargetResolution = TargetResolution.P1080,
    val sharpen: Float = 0.3f,
    val antiFlicker: Boolean = true,
    val hevc: Boolean = true,
    val gpu: Boolean = true,
    val color: ColorMode = ColorMode.OFF,
    val autoModel: Boolean = true,
    val compute: ComputeMode = ComputeMode.DEVICE,
    val natural: Float = 1.0f,
    /** One-button mode: ignore manual choices, always produce the same video at the maximum possible resolution. */
    val maxMode: Boolean = true,
) {
    fun toJson() = JSONObject().apply {
        put("model", model.name); put("preset", preset.name); put("target", target.name)
        put("sharpen", sharpen.toDouble()); put("antiFlicker", antiFlicker); put("hevc", hevc); put("gpu", gpu)
        put("color", color.name); put("autoModel", autoModel); put("compute", compute.name); put("natural", natural.toDouble())
        put("maxMode", maxMode)
    }
    companion object {
        fun fromJson(o: JSONObject) = Settings(
            UpscaleModel.fromName(o.optString("model")), QualityPreset.fromName(o.optString("preset")),
            TargetResolution.fromName(o.optString("target")), o.optDouble("sharpen", 0.3).toFloat(),
            o.optBoolean("antiFlicker", true), o.optBoolean("hevc", true), o.optBoolean("gpu", true),
            ColorMode.fromName(o.optString("color")), o.optBoolean("autoModel", true), ComputeMode.fromName(o.optString("compute")), o.optDouble("natural", 1.0).toFloat(),
            o.optBoolean("maxMode", true))
    }
}

data class ModelDownloadState(val model: UpscaleModel? = null, val progress: Float = 0f, val error: String? = null)

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
    private val _analysis = MutableStateFlow<ContentAnalyzer.Analysis?>(null)
    val analysis: StateFlow<ContentAnalyzer.Analysis?> = _analysis
    private val _download = MutableStateFlow(ModelDownloadState())
    val download: StateFlow<ModelDownloadState> = _download
    private val _cloud = MutableStateFlow<CloudEngine.Health?>(null)
    val cloud: StateFlow<CloudEngine.Health?> = _cloud
    private val _modelsAvailable = MutableStateFlow(UpscaleModel.entries.associateWith { ModelStore.isAvailable(app, it) })
    val modelsAvailable: StateFlow<Map<UpscaleModel, Boolean>> = _modelsAvailable

    val state: StateFlow<UpscaleState> = UpscaleService.state
    val queue: StateFlow<List<QueuedJob>> = UpscaleService.queue
    val profile: DeviceProfiler.Profile by lazy { DeviceProfiler.profile(ctx) }
    private var previewJob: Job? = null

    val primary: SourceItem? get() = _sources.value.firstOrNull()

    /**
     * MAX mode → the settings actually used for a job: same video, highest resolution the pipeline/encoder
     * can produce, faithful colours (natural = 1), no colour grading, AI on every frame, 2-pass when 16x fits 4K,
     * best available model (Ultra+ if downloaded, else content-recommended), cloud GPU helping when online.
     */
    fun effective(info: VideoInfo?): Settings {
        val s = _settings.value
        if (!s.maxMode) return s
        val short = info?.let { minOf(it.displayWidth, it.displayHeight) } ?: 144
        val long = info?.let { maxOf(it.displayWidth, it.displayHeight) } ?: 256
        val twoPassFits = long * 16 <= 4096
        val preset = if (twoPassFits) QualityPreset.ULTRA else QualityPreset.HIGH
        // biggest target we can reach: ×16 (2-pass) or ×4, capped at 4K
        val reach = short * (if (twoPassFits) 16 else 4)
        val target = TargetResolution.entries.filter { it != TargetResolution.AUTO && it.height <= minOf(reach, 2160) }
            .maxByOrNull { it.height } ?: TargetResolution.AUTO
        val ultraReady = _modelsAvailable.value[UpscaleModel.ULTRA_PLUS] == true
        val model = when {
            ultraReady && !twoPassFits -> UpscaleModel.ULTRA_PLUS          // heavy net only when single pass
            else -> _analysis.value?.recommended ?: UpscaleModel.NATURAL
        }
        val cloudUp = _cloud.value?.ok == true
        val compute = if (cloudUp && !twoPassFits) ComputeMode.HYBRID else ComputeMode.DEVICE
        return s.copy(preset = preset, target = target, model = model, natural = 1.0f, color = ColorMode.OFF,
            sharpen = 0.15f, antiFlicker = true, compute = compute, autoModel = true)
    }

    init { refreshCloud() }
    fun refreshCloud() { viewModelScope.launch(Dispatchers.IO) { _cloud.value = runCatching { CloudEngine().health() }.getOrNull() ?: CloudEngine.Health(false, null, null, 0) } }

    fun addSources(uris: List<Uri>) {
        if (uris.isEmpty()) return
        UpscaleService.clearFinished()
        _preview.value = PreviewState()
        _trim.value = 0L to 0L
        if (_sources.value.isEmpty()) _analysis.value = null
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
                    if (_sources.value.firstOrNull()?.uri == item.uri) {
                        autoTarget(i)
                        val a = ContentAnalyzer.analyze(ctx, item.uri, i.durationMs)
                        _analysis.value = a
                        if (_settings.value.autoModel) {
                            val curr = _settings.value
                            // don't override an explicitly downloaded Ultra+ choice
                            if (curr.model != UpscaleModel.ULTRA_PLUS) update { it.copy(model = a.recommended) }
                            if (a.recommendColorFix && curr.color == ColorMode.OFF) update { it.copy(color = ColorMode.AUTO) }
                        }
                    }
                } catch (_: Throwable) {
                    _sources.value = _sources.value.filter { it.uri != item.uri }
                }
            }
        }
    }

    fun removeSource(uri: Uri) { _sources.value = _sources.value.filter { it.uri != uri } }
    fun clearSources() { _sources.value = emptyList(); _preview.value = PreviewState(); _trim.value = 0L to 0L; _analysis.value = null }

    fun downloadModel(m: UpscaleModel) {
        if (!m.isDownloadable || _download.value.model != null) return
        _download.value = ModelDownloadState(m, 0f)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ModelStore.download(ctx, m) { p -> _download.value = ModelDownloadState(m, p) }
                _modelsAvailable.value = UpscaleModel.entries.associateWith { ModelStore.isAvailable(ctx, it) }
                _download.value = ModelDownloadState()
                update { it.copy(model = m) }
            } catch (t: Throwable) {
                _download.value = ModelDownloadState(null, 0f, t.message ?: "download failed")
            }
        }
    }

    fun deleteModel(m: UpscaleModel) {
        ModelStore.delete(ctx, m)
        _modelsAvailable.value = UpscaleModel.entries.associateWith { ModelStore.isAvailable(ctx, it) }
        if (_settings.value.model == m) update { it.copy(model = UpscaleModel.NATURAL) }
    }

    fun pause() = UpscaleService.pause(ctx)
    fun resume() = UpscaleService.resume(ctx)

    private fun autoTarget(i: VideoInfo) {
        val s = _settings.value
        val t = when {
            i.displayHeight <= 360 -> TargetResolution.P1080
            i.displayHeight <= 540 -> TargetResolution.P2160
            else -> TargetResolution.AUTO
        }
        update { s.copy(target = t) }
    }

    fun pickModel(m: UpscaleModel) = update { it.copy(model = m, autoModel = false) }

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
        val s = effective(info)
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
        var total = 0.0
        items.forEachIndexed { idx, i ->
            val s = effective(i)
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
        val cloudUp = _cloud.value?.ok == true
        return when {
            s.compute == ComputeMode.HYBRID && cloudUp -> total * 0.6 + 8
            s.compute == ComputeMode.CLOUD && cloudUp -> (durMs / 1000.0) * (if (s.model == UpscaleModel.ULTRA_PLUS) 3.0 else 1.2) + 40 // upload + queue + GPU
            else -> total * 1.1 + 5
        }
    }

    val isMeasured: Boolean get() { val s = effective(primary?.info); return PreviewEngine.cachedMsPerTile(s.model, s.gpu) != null }

    /** Enqueue all selected sources. */
    fun startAll() {
        _sources.value.forEachIndexed { idx, src ->
            val s = effective(src.info)
            val (a, b) = if (idx == 0) _trim.value else 0L to 0L
            UpscaleService.enqueue(ctx, UpscaleJob(src.uri, s.model, s.preset, s.target, s.sharpen, s.antiFlicker, s.hevc, s.gpu, a, b, s.color, s.compute, s.natural),
                src.info?.displayName ?: "video")
        }
    }

    /** Quick 10-second test of the first source with current settings. */
    fun startQuickTest() {
        val src = primary ?: return
        val s = effective(src.info)
        val start = _trim.value.first
        val info = src.info
        val end = minOf(start + 10_000, info?.durationMs ?: (start + 10_000))
        UpscaleService.enqueue(ctx, UpscaleJob(src.uri, s.model, s.preset, s.target, s.sharpen, s.antiFlicker, s.hevc, s.gpu, start, end, s.color, s.compute, s.natural),
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
