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
import com.upscaler.ai.engine.ColorMode
import com.upscaler.ai.engine.ComputeMode
import com.upscaler.ai.engine.QualityPreset
import com.upscaler.ai.engine.TargetResolution
import com.upscaler.ai.engine.UpscaleModel
import com.upscaler.ai.pipeline.UpscaleJob
import com.upscaler.ai.pipeline.UpscalePipeline
import com.upscaler.ai.pipeline.UpscaleState
import com.upscaler.ai.ui.MainActivity
import com.upscaler.ai.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** A job waiting in / running from the batch queue. */
data class QueuedJob(val id: Long, val job: UpscaleJob, val displayName: String) {
    var status: Status = Status.WAITING
    var result: UpscaleState? = null
    enum class Status { WAITING, RUNNING, DONE, FAILED, CANCELLED }
}

/**
 * Foreground service that processes a FIFO queue of upscale jobs one after another.
 * Holds a partial wake lock (CPU/GPU stay on). State is exposed via process-wide StateFlows.
 */
class UpscaleService : Service() {

    companion object {
        private const val NOTIF_ID = 1001
        private const val ACTION_ENQUEUE = "com.upscaler.ai.ENQUEUE"
        private const val ACTION_CANCEL_CURRENT = "com.upscaler.ai.CANCEL_CURRENT"
        private const val ACTION_CANCEL_ALL = "com.upscaler.ai.CANCEL_ALL"
        private const val ACTION_REMOVE = "com.upscaler.ai.REMOVE"
        private const val ACTION_PAUSE = "com.upscaler.ai.PAUSE"
        private const val ACTION_RESUME = "com.upscaler.ai.RESUME"
        @Volatile private var currentPipeline: UpscalePipeline? = null
        val isPaused get() = currentPipeline?.paused?.value == true

        private val _state = MutableStateFlow<UpscaleState>(UpscaleState.Idle)
        /** State of the *currently running* job. */
        val state: StateFlow<UpscaleState> = _state

        private val _queue = MutableStateFlow<List<QueuedJob>>(emptyList())
        val queue: StateFlow<List<QueuedJob>> = _queue
        private val items = CopyOnWriteArrayList<QueuedJob>()
        private var nextId = 1L

        val isRunning get() = _state.value is UpscaleState.Running || _state.value is UpscaleState.Preparing

        fun enqueue(ctx: Context, job: UpscaleJob, displayName: String) {
            val i = Intent(ctx, UpscaleService::class.java).apply {
                action = ACTION_ENQUEUE
                putExtra("uri", job.inputUri.toString())
                putExtra("name", displayName)
                putExtra("model", job.model.name)
                putExtra("preset", job.preset.name)
                putExtra("target", job.target.name)
                putExtra("sharpen", job.sharpen)
                putExtra("antiFlicker", job.antiFlicker)
                putExtra("hevc", job.preferHevc)
                putExtra("gpu", job.useGpu)
                putExtra("startMs", job.startMs)
                putExtra("endMs", job.endMs)
                putExtra("color", job.colorMode.name)
                putExtra("compute", job.compute.name)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun pause(ctx: Context) = ctx.startService(Intent(ctx, UpscaleService::class.java).apply { action = ACTION_PAUSE })
        fun resume(ctx: Context) = ctx.startService(Intent(ctx, UpscaleService::class.java).apply { action = ACTION_RESUME })
        fun cancelCurrent(ctx: Context) = ctx.startService(Intent(ctx, UpscaleService::class.java).apply { action = ACTION_CANCEL_CURRENT })
        fun cancelAll(ctx: Context) = ctx.startService(Intent(ctx, UpscaleService::class.java).apply { action = ACTION_CANCEL_ALL })
        fun remove(ctx: Context, id: Long) = ctx.startService(Intent(ctx, UpscaleService::class.java).apply { action = ACTION_REMOVE; putExtra("id", id) })

        fun clearFinished() {
            items.removeAll { it.status != QueuedJob.Status.WAITING && it.status != QueuedJob.Status.RUNNING }
            _queue.value = items.toList()
            if (!isRunning) _state.value = UpscaleState.Idle
        }

        fun resetState() { if (!isRunning) _state.value = UpscaleState.Idle }
        private fun publish() { _queue.value = items.toList() }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var worker: Job? = null
    private var currentJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_CURRENT -> { currentPipeline?.paused?.value = false; currentJob?.cancel() }
            ACTION_PAUSE -> currentPipeline?.paused?.value = true
            ACTION_RESUME -> currentPipeline?.paused?.value = false
            ACTION_CANCEL_ALL -> {
                items.filter { it.status == QueuedJob.Status.WAITING }.forEach { it.status = QueuedJob.Status.CANCELLED }
                publish(); currentJob?.cancel()
            }
            ACTION_REMOVE -> {
                val id = intent.getLongExtra("id", -1)
                items.firstOrNull { it.id == id && it.status == QueuedJob.Status.WAITING }?.let { items.remove(it); publish() }
                if (items.none { it.status == QueuedJob.Status.WAITING || it.status == QueuedJob.Status.RUNNING }) stopSelfSafely()
            }
            ACTION_ENQUEUE -> {
                val job = UpscaleJob(
                    inputUri = Uri.parse(intent.getStringExtra("uri")!!),
                    model = UpscaleModel.fromName(intent.getStringExtra("model")),
                    preset = QualityPreset.fromName(intent.getStringExtra("preset")),
                    target = TargetResolution.fromName(intent.getStringExtra("target")),
                    sharpen = intent.getFloatExtra("sharpen", 0.3f),
                    antiFlicker = intent.getBooleanExtra("antiFlicker", true),
                    preferHevc = intent.getBooleanExtra("hevc", true),
                    useGpu = intent.getBooleanExtra("gpu", true),
                    startMs = intent.getLongExtra("startMs", 0),
                    endMs = intent.getLongExtra("endMs", 0),
                    colorMode = ColorMode.fromName(intent.getStringExtra("color")),
                    compute = ComputeMode.fromName(intent.getStringExtra("compute")),
                )
                items.add(QueuedJob(nextId++, job, intent.getStringExtra("name") ?: "video"))
                publish()
                startInForeground(buildNotification(getString(R.string.notif_title), 0, true))
                if (worker?.isActive != true) startWorker()
            }
        }
        return START_NOT_STICKY
    }

    private fun startWorker() {
        acquireWakeLock()
        worker = scope.launch {
            while (true) {
                val next = items.firstOrNull { it.status == QueuedJob.Status.WAITING } ?: break
                next.status = QueuedJob.Status.RUNNING; publish()
                val p = UpscalePipeline(this@UpscaleService)
                currentPipeline = p
                val queuedLeft = items.count { it.status == QueuedJob.Status.WAITING }
                currentJob = launch {
                    val collector = launch {
                        p.state.collect { st -> _state.value = st; updateNotification(st, next.displayName, queuedLeft) }
                    }
                    try { p.run(next.job) } finally { collector.cancel() }
                }
                currentJob?.join()
                currentPipeline = null
                val res = p.state.value
                next.result = res
                next.status = when (res) {
                    is UpscaleState.Done -> {
                        Prefs.addHistory(this@UpscaleService, Prefs.HistoryItem(res.outputUri, res.outputPath, res.outputRes,
                            res.elapsedSeconds, res.sizeBytes, System.currentTimeMillis(), next.job.preset.name, next.job.model.name))
                        QueuedJob.Status.DONE
                    }
                    is UpscaleState.Cancelled -> QueuedJob.Status.CANCELLED
                    else -> QueuedJob.Status.FAILED
                }
                _state.value = res
                publish()
                updateNotification(res, next.displayName, items.count { it.status == QueuedJob.Status.WAITING })
            }
            stopSelfSafely()
        }
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else startForeground(NOTIF_ID, n)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoUpscalerAI:upscale").also {
            it.setReferenceCounted(false)
            it.acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean, done: Boolean = false, title: String? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, UpscaleService::class.java).apply { action = ACTION_CANCEL_CURRENT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pausedNow = isPaused
        val pauseToggle = PendingIntent.getService(this, 2,
            Intent(this, UpscaleService::class.java).apply { action = if (pausedNow) ACTION_RESUME else ACTION_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, UpscalerApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title ?: getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(!done)
            .setSilent(true)
        if (!done) {
            b.setProgress(100, progress, indeterminate)
            b.addAction(0, if (pausedNow) "▶" else "⏸", pauseToggle)
            b.addAction(0, getString(R.string.notif_cancel), cancel)
        }
        return b.build()
    }

    private fun updateNotification(st: UpscaleState, name: String, left: Int) {
        val suffix = if (left > 0) "  (+$left)" else ""
        val n = when (st) {
            is UpscaleState.Preparing -> buildNotification(st.message, 0, true, title = name + suffix)
            is UpscaleState.Running -> buildNotification(
                "${(st.progress * 100).toInt()}%  •  ${st.outputRes}  •  ⏳ ${fmt(st.etaSeconds)}",
                (st.progress * 100).toInt(), false, title = name + suffix)
            is UpscaleState.Done -> buildNotification(getString(R.string.notif_done) + " • ${st.outputRes}", 100, false, done = left == 0, title = name)
            is UpscaleState.Failed -> buildNotification(getString(R.string.notif_failed) + ": ${st.error}", 0, false, done = left == 0, title = name)
            else -> return
        }
        try { NotificationManagerCompat.from(this).notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }

    private fun fmt(s: Long): String = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

    private fun stopSelfSafely() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        wakeLock = null
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        currentJob?.cancel(); worker?.cancel(); scope.cancel()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Throwable) {}
        super.onDestroy()
    }
}
