package com.upscaler.ai.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.upscaler.ai.R
import com.upscaler.ai.UpscalerApp
import com.upscaler.ai.engine.QualityPreset
import com.upscaler.ai.engine.TargetResolution
import com.upscaler.ai.engine.UpscaleModel
import com.upscaler.ai.pipeline.UpscaleJob
import com.upscaler.ai.pipeline.UpscalePipeline
import com.upscaler.ai.pipeline.UpscaleState
import com.upscaler.ai.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service so the upscale keeps running with the screen off / app in background.
 * Holds a partial wake lock (CPU/GPU stay on). State is exposed via a process-wide StateFlow
 * that the UI observes — simple and robust, no binder needed.
 */
class UpscaleService : Service() {

    companion object {
        private const val NOTIF_ID = 1001
        private const val ACTION_START = "com.upscaler.ai.START"
        private const val ACTION_CANCEL = "com.upscaler.ai.CANCEL"

        private val _state = MutableStateFlow<UpscaleState>(UpscaleState.Idle)
        val state: StateFlow<UpscaleState> = _state
        val isRunning get() = _state.value is UpscaleState.Running || _state.value is UpscaleState.Preparing

        fun start(ctx: Context, job: UpscaleJob) {
            val i = Intent(ctx, UpscaleService::class.java).apply {
                action = ACTION_START
                putExtra("uri", job.inputUri.toString())
                putExtra("model", job.model.name)
                putExtra("preset", job.preset.name)
                putExtra("target", job.target.name)
                putExtra("sharpen", job.sharpen)
                putExtra("antiFlicker", job.antiFlicker)
                putExtra("hevc", job.preferHevc)
                putExtra("gpu", job.useGpu)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun cancel(ctx: Context) {
            ctx.startService(Intent(ctx, UpscaleService::class.java).apply { action = ACTION_CANCEL })
        }

        fun resetState() { if (!isRunning) _state.value = UpscaleState.Idle }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobHandle: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pipeline: UpscalePipeline? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> { jobHandle?.cancel(); stopSelfSafely(); return START_NOT_STICKY }
            ACTION_START -> {
                if (jobHandle?.isActive == true) return START_NOT_STICKY
                val job = UpscaleJob(
                    inputUri = Uri.parse(intent.getStringExtra("uri")!!),
                    model = UpscaleModel.fromName(intent.getStringExtra("model")),
                    preset = QualityPreset.fromName(intent.getStringExtra("preset")),
                    target = TargetResolution.fromName(intent.getStringExtra("target")),
                    sharpen = intent.getFloatExtra("sharpen", 0.3f),
                    antiFlicker = intent.getBooleanExtra("antiFlicker", true),
                    preferHevc = intent.getBooleanExtra("hevc", true),
                    useGpu = intent.getBooleanExtra("gpu", true),
                )
                startInForeground(buildNotification(getString(R.string.notif_title), 0, true))
                acquireWakeLock()
                val p = UpscalePipeline(this)
                pipeline = p
                jobHandle = scope.launch {
                    val collector = launch {
                        p.state.collect { st ->
                            _state.value = st
                            updateNotification(st)
                        }
                    }
                    try { p.run(job) } finally { collector.cancel() }
                    _state.value = p.state.value
                    updateNotification(p.state.value)
                    stopSelfSafely()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else startForeground(NOTIF_ID, n)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoUpscalerAI:upscale").also {
            it.setReferenceCounted(false)
            it.acquire(3 * 60 * 60 * 1000L) // hard cap 3h
        }
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean, done: Boolean = false): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, UpscaleService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, UpscalerApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setSilent(true)
        if (!done) {
            b.setProgress(100, progress, indeterminate)
            b.addAction(0, getString(R.string.notif_cancel), cancel)
        }
        return b.build()
    }

    private fun updateNotification(st: UpscaleState) {
        val n = when (st) {
            is UpscaleState.Preparing -> buildNotification(st.message, 0, true)
            is UpscaleState.Running -> {
                val eta = formatEta(st.etaSeconds)
                buildNotification("${(st.progress * 100).toInt()}%  •  ${st.outputRes}  •  ⏳ $eta", (st.progress * 100).toInt(), false)
            }
            is UpscaleState.Done -> buildNotification(getString(R.string.notif_done) + " • ${st.outputRes}", 100, false, done = true)
            is UpscaleState.Failed -> buildNotification(getString(R.string.notif_failed) + ": ${st.error}", 0, false, done = true)
            else -> return
        }
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }

    private fun formatEta(s: Long): String = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

    private fun stopSelfSafely() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wakeLock = null
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        jobHandle?.cancel()
        scope.cancel()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        super.onDestroy()
    }
}
