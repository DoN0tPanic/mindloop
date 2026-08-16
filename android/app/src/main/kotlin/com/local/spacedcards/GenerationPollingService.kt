package com.local.spacedcards

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.local.spacedcards.data.lan.GenerationJobStore
import com.local.spacedcards.data.lan.LanClient
import com.local.spacedcards.data.lan.LanError
import com.local.spacedcards.data.quiz.QuizRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class GenerationPollingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var polling: Job? = null
    private lateinit var jobs: GenerationJobStore

    override fun onCreate() { super.onCreate(); jobs = GenerationJobStore(this); createChannel() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { jobs.current()?.let { jobs.updatePollingActive(false) }; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val job = jobs.current() ?: run { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, ongoingNotification())
        jobs.updatePollingActive(true)
        if (polling?.isActive != true) polling = scope.launch { poll(job.id) }
        return START_NOT_STICKY
    }
    override fun onDestroy() { polling?.cancel(); jobs.current()?.let { jobs.updatePollingActive(false) }; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun poll(jobId: String) {
        val client = LanClient(); val repository = QuizRepository(applicationContext)
        var failures = 0
        while (scope.coroutineContext.isActive) {
            val job = jobs.current() ?: return
            if (job.id != jobId || jobs.isImported(jobId)) return
            val status = client.status(job.host, job.port, job.code, job.id)
            val value = status.getOrNull()
            if (value == null) {
                if (status.exceptionOrNull() === LanError.JobNotFound) {
                    jobs.clearAsMissing(job)
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
                }
                failures++
                jobs.updateStatus(job.progress, job.stage, getString(R.string.generation_retrying))
                if (failures >= MAX_FAILURES) { jobs.updatePollingActive(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
                delay((POLL_MS * (1L shl minOf(failures, 5))).coerceAtMost(MAX_BACKOFF_MS)); continue
            }
            failures = 0; jobs.updateStatus(value.progress, value.stage, value.message)
            if (value.state.equals("error", true)) { jobs.updatePollingActive(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
            if (!value.state.equals("done", true)) { delay(POLL_MS); continue }
            val file = File.createTempFile("mindloop-lan-", ".qzd", cacheDir)
            try {
                val downloaded = client.downloadResult(job.host, job.port, job.code, job.id, file).getOrNull()
                if (downloaded == null) { delay(POLL_MS); continue }
                if (!jobs.isImported(jobId)) {
                    val payload = repository.importQzd(android.net.Uri.fromFile(downloaded))
                    repository.attachPackToRaccolta(payload.packUid, job.raccoltaUid)
                    jobs.markImported(jobId)
                }
                jobs.clearCurrent(jobId); showReadyNotification(job); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
            } catch (_: Throwable) {
                failures++; if (failures >= MAX_FAILURES) { jobs.updatePollingActive(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }; delay(MAX_BACKOFF_MS)
            } finally { file.delete() }
        }
    }

    private fun ongoingNotification() = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle(getString(R.string.generation_notification_title)).setContentText(getString(R.string.generation_notification_text))
        .setOngoing(true).addAction(0, getString(R.string.generation_notification_cancel), cancelIntent()).build()
    private fun showReadyNotification(job: com.local.spacedcards.data.lan.GenerationJob) {
        val open = Intent(this, MainActivity::class.java).putExtra(EXTRA_COLLECTION_UID, job.raccoltaUid).putExtra(EXTRA_COLLECTION_NAME, job.raccoltaName)
        val pending = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        (getSystemService(NotificationManager::class.java)).notify(READY_NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle(getString(R.string.generation_ready_title))
            .setContentText(getString(R.string.generation_ready_text)).setAutoCancel(true).setContentIntent(pending).build())
    }
    private fun cancelIntent() = PendingIntent.getService(this, 2, Intent(this, javaClass).setAction(ACTION_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun createChannel() { (getSystemService(NotificationManager::class.java)).createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.generation_notification_channel), NotificationManager.IMPORTANCE_LOW)) }
    companion object {
        const val EXTRA_COLLECTION_UID = "generation_collection_uid"; const val EXTRA_COLLECTION_NAME = "generation_collection_name"
        private const val ACTION_CANCEL = "com.local.spacedcards.CANCEL_GENERATION_POLLING"; private const val CHANNEL_ID = "quiz_generation"; private const val NOTIFICATION_ID = 41; private const val READY_NOTIFICATION_ID = 42
        private const val POLL_MS = 1_500L; private const val MAX_BACKOFF_MS = 60_000L; private const val MAX_FAILURES = 8
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, GenerationPollingService::class.java))
        }.isSuccess
        fun cancel(context: Context) = context.startService(Intent(context, GenerationPollingService::class.java).setAction(ACTION_CANCEL))
    }
}
