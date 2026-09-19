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
import androidx.compose.material.icons.filled.AutoAwesome
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
import com.upscaler.ai.engine.UpscaleModel
import com.upscaler.ai.pipeline.UpscaleState

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
        if (i?.action == Intent.ACTION_SEND) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                            else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            uri?.let { vm.setInput(it) }
        }
    }
}

// ───────────────────────────────────────────────────────────────────────────────

@Composable
fun MainScreen(vm: MainViewModel) {
    val input by vm.input.collectAsState()
    val info by vm.info.collectAsState()
    val thumb by vm.thumb.collectAsState()
    val settings by vm.settings.collectAsState()
    val state by vm.state.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.setInput(it) }
    }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Header()

        when (val st = state) {
            is UpscaleState.Preparing, is UpscaleState.Running -> ProgressCard(st, onCancel = vm::cancel)
            is UpscaleState.Done -> DoneCard(st, onNew = vm::reset, onOpen = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(st.outputUri, "video/mp4"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
            }, onShare = {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, st.outputUri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, null))
            })
            is UpscaleState.Failed -> FailedCard(st.error, onRetry = vm::start, onNew = vm::reset)
            is UpscaleState.Cancelled -> FailedCard(S.cancelled, onRetry = vm::start, onNew = vm::reset)
            UpscaleState.Idle -> {
                SourceCard(thumb = thumb, info = info, hasInput = input != null) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                }
                if (input != null) {
                    SettingsCard(settings, vm)
                    EstimateCard(vm.estimateSeconds(), settings, info?.durationMs)
                    Button(
                        onClick = vm::start, enabled = info != null,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Bg)
                    ) {
                        Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(10.dp))
                        Text(S.start, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(S.keepPlugged, color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                } else HowItWorks()
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Header() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Accent, Accent2, Pink))),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Default.AutoAwesome, null, tint = Color.White) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(S.title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Text(S.subtitle, fontSize = 12.sp, color = TextSecondary)
            }
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
private fun SourceCard(thumb: android.graphics.Bitmap?, info: com.upscaler.ai.video.VideoInfo?, hasInput: Boolean, onPick: () -> Unit) {
    SectionCard {
        if (!hasInput) {
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
            Box(Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                if (info == null) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
                info?.let {
                    Text("${it.displayWidth}×${it.displayHeight}", color = Color.White, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color.Black.copy(.55f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                    Text(fmtDur(it.durationMs) + "  •  ${"%.0f".format(it.fps)} fps", color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).background(Color.Black.copy(.55f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            info?.let { Text(it.displayName, color = TextSecondary, fontSize = 12.sp, maxLines = 1) }
            OutlinedButton(onClick = onPick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(S.changeVideo) }
        }
    }
}

@Composable
private fun SettingsCard(s: Settings, vm: MainViewModel) {
    var adv by remember { mutableStateOf(false) }
    SectionCard {
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
            Label(S.model)
            UpscaleModel.entries.forEach { m ->
                ChoiceRow(
                    selected = s.model == m,
                    title = if (S.ar) m.displayNameAr else m.displayNameEn,
                    subtitle = if (S.ar) m.descriptionAr else m.descriptionEn,
                ) { vm.update { it.copy(model = m) } }
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
private fun EstimateCard(sec: Long?, s: Settings, durMs: Long?) {
    if (sec == null) return
    val warn = sec > 30 * 60
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (warn) Amber.copy(.12f) else Surface2)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Timer, null, tint = if (warn) Amber else Accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${S.estimate}: ~${fmtSec(sec)}", color = TextPrimary, fontWeight = FontWeight.Bold)
                if (warn) Text(S.tooLong, color = Amber, fontSize = 12.sp)
                else durMs?.let { Text("${S.duration}: ${fmtDur(it)}", color = TextSecondary, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ProgressCard(st: UpscaleState, onCancel: () -> Unit) {
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
                }
                Text(S.screenOffOk, color = TextSecondary, fontSize = 12.sp)
            }
            else -> {}
        }
        OutlinedButton(onClick = onCancel, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(S.cancel) }
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
