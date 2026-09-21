package com.upscaler.ai.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.IconButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Download
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.upscaler.ai.engine.ColorMode
import com.upscaler.ai.engine.ComputeMode
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import com.upscaler.ai.engine.ContentAnalyzer
import com.upscaler.ai.engine.UpscaleModel
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.upscaler.ai.engine.QualityPreset
import com.upscaler.ai.engine.TargetResolution
import com.upscaler.ai.pipeline.UpscaleState
import com.upscaler.ai.service.QueuedJob
import com.upscaler.ai.util.Prefs

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent { UpscalerTheme { Surface(Modifier.fillMaxSize(), color = Bg) { MainScreen(vm) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(i: Intent?) {
        when (i?.action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
                uri?.let { vm.addSources(listOf(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                                       else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                uris?.let { vm.addSources(it) }
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────────

@Composable
fun MainScreen(vm: MainViewModel) {
    val sources by vm.sources.collectAsState()
    val settings by vm.settings.collectAsState()
    val state by vm.state.collectAsState()
    val queue by vm.queue.collectAsState()
    val preview by vm.preview.collectAsState()
    val trim by vm.trim.collectAsState()
    val history by vm.history.collectAsState()
    val showHistory by vm.showHistory.collectAsState()
    val analysis by vm.analysis.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) vm.addSources(uris)
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    val open: (Uri) -> Unit = { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(u, "video/mp4"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }) } }
    val share: (Uri) -> Unit = { u -> ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, u); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, null)) }

    val active = queue.any { it.status == QueuedJob.Status.WAITING || it.status == QueuedJob.Status.RUNNING }
    val finished = queue.isNotEmpty() && !active

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Header(onHistory = vm::toggleHistory, historyOpen = showHistory)

        when {
            showHistory -> HistoryCard(history, onOpen = open, onShare = share, onClear = vm::clearHistory, onBack = vm::toggleHistory)

            active -> {
                ProgressCard(state, onCancel = vm::cancelCurrent, onPause = vm::pause, onResume = vm::resume)
                QueueCard(queue, onRemove = vm::removeQueued, onCancelAll = vm::cancelAll, onOpen = open)
            }

            finished -> {
                val done = queue.filter { it.status == QueuedJob.Status.DONE }
                val single = queue.size == 1
                val st = queue.first().result
                if (single && st is UpscaleState.Done) DoneCard(st, onNew = vm::reset, onOpen = { open(st.outputUri) }, onShare = { share(st.outputUri) })
                else if (single && st is UpscaleState.Failed) FailedCard(st.error, onRetry = vm::startAll, onNew = vm::reset)
                else if (single) FailedCard(S.cancelled, onRetry = vm::startAll, onNew = vm::reset)
                else {
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(36.dp)); Spacer(Modifier.width(12.dp))
                            Text("${S.allDone} (${done.size}/${queue.size})", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        }
                        Button(onClick = vm::reset, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(S.newVideo) }
                    }
                    QueueCard(queue, onRemove = {}, onCancelAll = null, onOpen = open)
                }
            }

            else -> {
                SourcesCard(sources, onPick = pick, onRemove = vm::removeSource)
                val first = sources.firstOrNull()
                if (first?.info != null) {
                    MaxModeCard(settings, vm.plan(first.info), onToggle = { v -> vm.update { it.copy(maxMode = v) } })
                    EstimateCard(vm.estimateSeconds(), vm.isMeasured, sources.size)
                    var expert by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clickable { expert = !expert }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tune, null, tint = TextSecondary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text(S.expert, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Icon(if (expert) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextSecondary)
                    }
                    if (expert) {
                        analysis?.let { AnalysisBanner(it) }
                        PreviewCard(preview, onRun = vm::runPreview)
                        if (sources.size == 1) TrimCard(first.info.durationMs, trim, onChange = vm::setTrim)
                        if (!settings.maxMode) SettingsCard(settings, vm)
                        else Text(S.maxModeLocked, color = TextSecondary, fontSize = 11.sp)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!settings.maxMode) OutlinedButton(onClick = vm::startQuickTest, Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(18.dp)) {
                            Icon(Icons.Default.Science, null); Spacer(Modifier.width(6.dp)); Text(S.quickTest, fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center)
                        }
                        Button(
                            onClick = vm::startAll, modifier = Modifier.weight(1.4f).height(if (settings.maxMode) 66.dp else 58.dp), shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)
                        ) {
                            Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp))
                            Text(if (sources.size > 1) "${S.startAll} (${sources.size})" else S.start, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(S.keepPlugged + "  •  " + S.thermalNote, color = TextSecondary, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else if (sources.isEmpty()) HowItWorks()
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Header(onHistory: () -> Unit, historyOpen: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Accent, Accent2, Pink))),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.AutoAwesome, null, tint = Color.White) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(S.title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Text(S.subtitle, fontSize = 11.sp, color = TextSecondary)
            }
            IconButton(onClick = onHistory) { Icon(Icons.Default.History, S.history, tint = if (historyOpen) Accent else TextSecondary) }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
}

@Composable
private fun SourcesCard(sources: List<SourceItem>, onPick: () -> Unit, onRemove: (Uri) -> Unit) {
    SectionCard {
        if (sources.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, Accent.copy(alpha = .4f), RoundedCornerShape(16.dp))
                    .background(Surface2).clickable(onClick = onPick),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.VideoLibrary, null, tint = Accent, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(S.pickVideo, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
            }
        } else {
            val first = sources.first()
            Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                first.thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                if (first.info == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
                first.info?.let {
                    Text("${it.displayWidth}×${it.displayHeight}", color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color.Black.copy(.55f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                    Text(fmtDur(it.durationMs) + "  •  ${"%.0f".format(it.fps)} fps", color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Color.Black.copy(.55f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            sources.forEach { src ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VideoLibrary, null, tint = TextSecondary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                    Text(src.info?.displayName ?: "…", color = TextSecondary, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    src.info?.let { Text("${it.displayWidth}×${it.displayHeight}", color = TextSecondary, fontSize = 11.sp) }
                    IconButton(onClick = { onRemove(src.uri) }, Modifier.size(28.dp)) { Icon(Icons.Default.Close, S.remove, tint = TextSecondary, modifier = Modifier.size(16.dp)) }
                }
            }
            OutlinedButton(onClick = onPick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(if (sources.size > 1) "${S.addVideos} (${sources.size} ${S.videosSelected})" else S.addVideos)
            }
        }
    }
}

@Composable
private fun PreviewCard(p: PreviewState, onRun: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Visibility, null, tint = Accent2); Spacer(Modifier.width(8.dp))
            Text(S.previewTitle, fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
            p.provider?.let { Text(it.uppercase(), color = Green, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
        if (p.before != null && p.after != null) CompareView(p.before, p.after)
        p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (p.loading) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Accent, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                Text(S.measuring, color = TextSecondary, fontSize = 13.sp)
            }
        } else {
            OutlinedButton(onClick = onRun, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Speed, null); Spacer(Modifier.width(6.dp)); Text(S.previewBtn, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TrimCard(durationMs: Long, trim: Pair<Long, Long>, onChange: (Long, Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val startMs = trim.first
    val endMs = if (trim.second <= 0) durationMs else trim.second
    SectionCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ContentCut, null, tint = Pink); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(S.trim, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(if (trim.second > 0 || trim.first > 0) "${fmtDur(startMs)} → ${fmtDur(endMs)}" else S.trimHint, color = TextSecondary, fontSize = 12.sp)
            }
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = TextSecondary)
        }
        if (open) {
            RangeSlider(
                value = startMs.toFloat()..endMs.toFloat(),
                onValueChange = { r -> onChange(r.start.toLong(), if (r.endInclusive.toLong() >= durationMs - 200) 0L else r.endInclusive.toLong()) },
                valueRange = 0f..durationMs.toFloat(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat(S.from, fmtDur(startMs)); Stat(S.duration, fmtDur(endMs - startMs)); Stat(S.to, fmtDur(endMs))
            }
        }
    }
}

@Composable
private fun QueueCard(queue: List<QueuedJob>, onRemove: (Long) -> Unit, onCancelAll: (() -> Unit)?, onOpen: (Uri) -> Unit) {
    if (queue.size <= 1 && onCancelAll != null) return
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${S.queueTitle} (${queue.size})", fontWeight = FontWeight.Bold, color = TextPrimary, modifier = Modifier.weight(1f))
            onCancelAll?.let { OutlinedButton(onClick = it, shape = RoundedCornerShape(10.dp)) { Text(S.cancelAll, fontSize = 12.sp) } }
        }
        queue.forEach { q ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icon, tint) = when (q.status) {
                    QueuedJob.Status.WAITING -> Icons.Default.HourglassEmpty to TextSecondary
                    QueuedJob.Status.RUNNING -> Icons.Default.Bolt to Accent
                    QueuedJob.Status.DONE -> Icons.Default.CheckCircle to Green
                    QueuedJob.Status.FAILED -> Icons.Default.Error to MaterialTheme.colorScheme.error
                    QueuedJob.Status.CANCELLED -> Icons.Default.Close to Amber
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(q.displayName, color = TextPrimary, fontSize = 13.sp, maxLines = 1)
                    val sub = when (val r = q.result) {
                        is UpscaleState.Done -> "${r.outputRes} • ${fmtSec(r.elapsedSeconds)} • ${"%.1f".format(r.sizeBytes / 1e6)} MB"
                        is UpscaleState.Failed -> r.error
                        else -> when (q.status) { QueuedJob.Status.WAITING -> S.waiting; QueuedJob.Status.RUNNING -> S.running; else -> S.cancelled }
                    }
                    Text(sub, color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                }
                when {
                    q.status == QueuedJob.Status.WAITING -> IconButton(onClick = { onRemove(q.id) }, Modifier.size(28.dp)) { Icon(Icons.Default.Close, S.remove, tint = TextSecondary, modifier = Modifier.size(16.dp)) }
                    q.result is UpscaleState.Done -> IconButton(onClick = { onOpen((q.result as UpscaleState.Done).outputUri) }, Modifier.size(28.dp)) { Icon(Icons.Default.PlayArrow, S.open, tint = Accent) }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(items: List<Prefs.HistoryItem>, onOpen: (Uri) -> Unit, onShare: (Uri) -> Unit, onClear: () -> Unit, onBack: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.History, null, tint = Accent); Spacer(Modifier.width(8.dp))
            Text(S.history, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 18.sp, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Default.Delete, S.clearHistory, tint = TextSecondary) }
        }
        if (items.isEmpty()) Text(S.historyEmpty, color = TextSecondary)
        items.forEach { h ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Surface2).clickable { onOpen(h.outputUri) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.name, color = TextPrimary, fontSize = 13.sp, maxLines = 1)
                    Text("${h.outputRes} • ${fmtSec(h.elapsedSec)} • ${"%.1f".format(h.sizeBytes / 1e6)} MB • ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(h.timestamp))}", color = TextSecondary, fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = { onShare(h.outputUri) }, Modifier.size(32.dp)) { Icon(Icons.Default.Share, S.share, tint = Accent, modifier = Modifier.size(18.dp)) }
            }
        }
        OutlinedButton(onClick = onBack, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(S.back) }
    }
}

@Composable
private fun SettingsCard(s: Settings, vm: MainViewModel) {
    var adv by remember { mutableStateOf(false) }
    val cloud by vm.cloud.collectAsState()
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(S.compute); Spacer(Modifier.weight(1f))
            val up = cloud?.ok == true
            Icon(if (up) Icons.Default.Cloud else Icons.Default.CloudOff, null, tint = if (up) Green else TextSecondary, modifier = Modifier.size(16.dp).clickable { vm.refreshCloud() })
            Spacer(Modifier.width(4.dp))
            Text(if (up) (cloud?.gpu ?: S.cloudOnline) else S.cloudOffline, color = if (up) Green else TextSecondary, fontSize = 11.sp, maxLines = 1)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ComputeMode.entries.forEach { m ->
                Chip(if (S.ar) m.labelAr else m.labelEn, s.compute == m, Modifier.weight(1f)) { vm.update { it.copy(compute = m) } }
            }
        }
        Text((if (S.ar) s.compute.descAr else s.compute.descEn) + if (s.compute != ComputeMode.DEVICE) "\n" + S.cloudNote else "", color = TextSecondary, fontSize = 11.sp)

        Label(S.preset)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityPreset.entries.forEach { p ->
                ChoiceRow(
                    selected = s.preset == p,
                    title = if (S.ar) p.labelAr else p.labelEn,
                    icon = when (p) { QualityPreset.ULTRA -> Icons.Default.AutoAwesome; QualityPreset.HIGH -> Icons.Default.Memory; QualityPreset.BALANCED -> Icons.Default.Speed; QualityPreset.FAST -> Icons.Default.Bolt }
                ) { vm.update { it.copy(preset = p) } }
            }
        }

        Label(S.target)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TargetResolution.entries.forEach { t ->
                Chip(t.label.substringBefore(' '), s.target == t, Modifier.weight(1f)) { vm.update { it.copy(target = t) } }
            }
        }

        if (s.preset != QualityPreset.FAST) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(S.model); Spacer(Modifier.weight(1f))
                Text(S.autoModelOn, color = TextSecondary, fontSize = 11.sp); Spacer(Modifier.width(6.dp))
                Switch(s.autoModel, { v -> vm.update { it.copy(autoModel = v) } }, Modifier.height(24.dp))
            }
            val avail by vm.modelsAvailable.collectAsState()
            val dl by vm.download.collectAsState()
            UpscaleModel.entries.forEach { m ->
                val ok = avail[m] == true
                ModelRow(
                    selected = s.model == m, model = m, available = ok,
                    downloading = dl.model == m, progress = dl.progress,
                    onSelect = { if (ok) vm.pickModel(m) else vm.downloadModel(m) },
                    onDelete = { vm.deleteModel(m) },
                )
            }
            dl.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }

        Text("${S.natural}: ${(s.natural * 100).toInt()}%", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Slider(s.natural, { v -> vm.update { it.copy(natural = v) } }, valueRange = 0f..1f)
        Text(S.naturalHint, color = TextSecondary, fontSize = 11.sp)

        Label(S.colorMode)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorMode.entries.forEach { c ->
                Chip(if (S.ar) c.labelAr else c.labelEn, s.color == c, Modifier.weight(1f)) { vm.update { it.copy(color = c) } }
            }
        }

        Row(Modifier.fillMaxWidth().clickable { adv = !adv }, verticalAlignment = Alignment.CenterVertically) {
            Text(S.advanced, color = Accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (adv) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Accent)
        }
        if (adv) {
            Text("${S.sharpen}: ${(s.sharpen * 100).toInt()}%", color = TextSecondary, fontSize = 13.sp)
            Slider(s.sharpen, { v -> vm.update { it.copy(sharpen = v) } }, valueRange = 0f..1f)
            ToggleRow(S.antiFlicker, s.antiFlicker) { v -> vm.update { it.copy(antiFlicker = v) } }
            ToggleRow(S.hevc, s.hevc) { v -> vm.update { it.copy(hevc = v) } }
            ToggleRow(S.gpu, s.gpu) { v -> vm.update { it.copy(gpu = v) } }
        }
    }
}

@Composable
private fun MaxModeCard(s: Settings, plan: String, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (s.maxMode) Accent.copy(.14f) else Surface2)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = Accent, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                Text(S.maxMode, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(s.maxMode, onToggle, Modifier.height(24.dp))
            }
            if (s.maxMode) {
                Text(S.maxModeHint, color = TextSecondary, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HighQuality, null, tint = Green, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("${S.willProduce}: $plan", color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun EstimateCard(sec: Long?, measured: Boolean, count: Int) {
    if (sec == null) return
    val warn = sec > 30 * 60
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (warn) Amber.copy(.12f) else Surface2)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Timer, null, tint = if (warn) Amber else Accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${S.estimate}: ~${fmtSec(sec)}" + (if (count > 1) "  ($count)" else ""), color = TextPrimary, fontWeight = FontWeight.Bold)
                if (warn) Text(S.tooLong, color = Amber, fontSize = 12.sp)
                else Text(if (measured) "✓ " + S.measured else S.estimatedRough, color = if (measured) Green else TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ProgressCard(st: UpscaleState, onCancel: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    SectionCard {
        when (st) {
            is UpscaleState.Preparing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.5.dp)
                    Spacer(Modifier.width(12.dp)); Text(st.message, color = TextPrimary)
                }
                LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = Accent, trackColor = Surface2)
            }
            is UpscaleState.Running -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${(st.progress * 100).toInt()}%", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Accent)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("${st.inputRes}  →  ${st.outputRes}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("${st.frame} / ${st.totalFrames} ${S.frames}", color = TextSecondary, fontSize = 12.sp)
                    }
                }
                LinearProgressIndicator(progress = { st.progress }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)), color = Accent, trackColor = Surface2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(S.eta, fmtSec(st.etaSeconds)); Stat(S.elapsed, fmtSec(st.elapsedSeconds)); Stat(S.speed, "%.1f fps".format(st.fps))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat(S.engine, st.provider.uppercase()); Stat(S.skipped, "${st.skipped}")
                    if (st.cloudFrames > 0) Stat(S.cloudFrames, "${st.cloudFrames}")
                }
                if (st.paused) Text("⏸ " + S.pausedLabel, color = Amber, fontWeight = FontWeight.Bold)
                else Text(S.screenOffOk, color = TextSecondary, fontSize = 12.sp)
            }
            else -> {}
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (st is UpscaleState.Running) {
                OutlinedButton(onClick = if (st.paused) onResume else onPause, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                    Icon(if (st.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null); Spacer(Modifier.width(6.dp))
                    Text(if (st.paused) S.resumeBtn else S.pause)
                }
            }
            OutlinedButton(onClick = onCancel, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(S.cancel) }
        }
    }
}

@Composable
private fun AnalysisBanner(a: ContentAnalyzer.Analysis) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Accent2.copy(.12f))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AutoAwesome, null, tint = Accent2); Spacer(Modifier.width(10.dp))
            Column {
                Text(S.autoDetect, color = Accent2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(if (S.ar) a.reasonAr else a.reasonEn, color = TextPrimary, fontSize = 13.sp)
                if (a.recommendColorFix) Text(S.colorSuggested, color = Amber, fontSize = 12.sp)
            }
        }
    }
}

/** Before/after with a draggable divider. "after" is downscaled to match "before" size on screen. */
@Composable
private fun CompareView(before: android.graphics.Bitmap, after: android.graphics.Bitmap) {
    var frac by remember { mutableStateOf(0.5f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val beforeImg = remember(before) { before.asImageBitmap() }
    val afterImg = remember(after) { after.asImageBitmap() }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Color.Black)
            .onSizeChanged { size = it }
            .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> if (size.width > 0) frac = (change.position.x / size.width).coerceIn(0.02f, 0.98f) } }
    ) {
        Image(afterImg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Image(beforeImg, null, Modifier.fillMaxSize().drawWithContent {
            clipRect(right = this.size.width * frac) { this@drawWithContent.drawContent() }
        }, contentScale = ContentScale.Fit, filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
        // divider
        Box(Modifier.fillMaxSize().drawWithContent {
            drawContent()
            val x = this.size.width * frac
            drawRect(Color.White, Offset(x - 1.5f, 0f), Size(3f, this.size.height))
        })
        Text(S.before, color = Color.White, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(Color.Black.copy(.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        Text(S.after, color = Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Black.copy(.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        Text(S.compareHint, color = Color.White.copy(.7f), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp))
    }
}

@Composable
private fun DoneCard(st: UpscaleState.Done, onNew: () -> Unit, onOpen: () -> Unit, onShare: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(S.done, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(S.savedTo, color = TextSecondary, fontSize = 12.sp)
            }
        }
        Text(st.outputPath, color = TextSecondary, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat(S.output, st.outputRes); Stat(S.elapsed, fmtSec(st.elapsedSeconds)); Stat("MB", "%.1f".format(st.sizeBytes / 1e6))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpen, Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(S.open)
            }
            OutlinedButton(onClick = onShare, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text(S.share)
            }
        }
        OutlinedButton(onClick = onNew, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(S.newVideo) }
    }
}

@Composable
private fun FailedCard(msg: String, onRetry: () -> Unit, onNew: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column { Text(S.failed, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary); Text(msg, color = TextSecondary, fontSize = 13.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRetry, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(S.retry) }
            OutlinedButton(onClick = onNew, Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text(S.newVideo) }
        }
    }
}

@Composable
private fun HowItWorks() {
    SectionCard {
        Text(S.howItWorks, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 16.sp)
        Text(S.howItWorksBody, color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

// ── small building blocks ─────────────────────────────────────────────────────

@Composable private fun Label(t: String) = Text(t, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)

@Composable
private fun Stat(label: String, value: String) {
    Column { Text(value, color = TextPrimary, fontWeight = FontWeight.Bold); Text(label, color = TextSecondary, fontSize = 11.sp) }
}

@Composable
private fun ChoiceRow(selected: Boolean, title: String, subtitle: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent.copy(.14f) else Surface2)
            .border(1.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let { Icon(it, null, tint = if (selected) Accent else TextSecondary); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            subtitle?.let { Text(it, color = TextSecondary, fontSize = 12.sp) }
        }
        if (selected) Icon(Icons.Default.CheckCircle, null, tint = Accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ModelRow(selected: Boolean, model: UpscaleModel, available: Boolean, downloading: Boolean, progress: Float, onSelect: () -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent.copy(.14f) else Surface2)
            .border(1.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(14.dp))
            .clickable(enabled = !downloading, onClick = onSelect).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (S.ar) model.displayNameAr else model.displayNameEn, color = TextPrimary, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                Text(if (S.ar) model.descriptionAr else model.descriptionEn, color = TextSecondary, fontSize = 12.sp)
            }
            when {
                downloading -> Text("${(progress * 100).toInt()}%", color = Accent, fontWeight = FontWeight.Bold)
                !available -> Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Download, null, tint = Accent2, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("${S.downloadModel} ${model.downloadSizeMb} MB", color = Accent2, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                selected -> Icon(Icons.Default.CheckCircle, null, tint = Accent, modifier = Modifier.size(20.dp))
            }
            if (available && model.isDownloadable && !downloading) IconButton(onClick = onDelete, Modifier.size(28.dp)) { Icon(Icons.Default.Delete, S.deleteModel, tint = TextSecondary, modifier = Modifier.size(16.dp)) }
        }
        if (downloading) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)), color = Accent, trackColor = Surface1)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) Accent else Surface2).clickable(onClick = onClick).padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (selected) Bg else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked, onChange)
    }
}

private fun fmtDur(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }
private fun fmtSec(s: Long): String = when {
    s >= 3600 -> "${s / 3600}${S.hoursShort} ${(s % 3600) / 60}${S.minutesShort}"
    s >= 60 -> "${s / 60}${S.minutesShort} ${s % 60}${S.secondsShort}"
    else -> "$s${S.secondsShort}"
}
