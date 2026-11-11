package com.themastersahib.downloader.data.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.themastersahib.downloader.MainActivity
import com.themastersahib.downloader.MasterDownloaderApp
import com.themastersahib.downloader.R
import com.themastersahib.downloader.data.model.DownloadTask
import com.themastersahib.downloader.data.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app process alive while downloads run. It watches the shared
 * [DownloadManagerRepository], mirrors the progress in one notification and
 * stops itself as soon as no download is pending or running.
 */
class DownloadForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 1001
    }

    private data class Summary(val activeCount: Int, val title: String, val progress: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Must happen right away: the system kills services that are started
        // as "foreground" but do not show their notification within seconds.
        if (!promoteToForeground(buildNotification(Summary(1, "Preparing download", 0)))) {
            stopSelf()
            return
        }

        val repository = (application as MasterDownloaderApp).downloadRepository
        scope.launch {
            repository.tasks
                .map { summarize(it) }
                .distinctUntilChanged()
                .collect { summary ->
                    if (summary == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val manager = getSystemService(NotificationManager::class.java)
                        try {
                            manager?.notify(NOTIFICATION_ID, buildNotification(summary))
                        } catch (_: Exception) {
                        }
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** Android 15+: data-sync services get a daily time budget; stop cleanly when it runs out. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun summarize(tasks: List<DownloadTask>): Summary? {
        val active = tasks.filter { it.status == TaskStatus.DOWNLOADING || it.status == TaskStatus.PENDING }
        if (active.isEmpty()) return null
        val running = active.filter { it.status == TaskStatus.DOWNLOADING }
        val title = when {
            active.size > 1 -> "Downloading ${active.size} files"
            else -> active.first().title
        }
        val progress = if (running.isEmpty()) 0 else running.sumOf { it.progress } / running.size
        return Summary(active.size, title, progress)
    }

    private fun buildNotification(summary: Summary): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MasterDownloaderApp.DOWNLOAD_CHANNEL_ID)
            .setContentTitle(summary.title)
            .setContentText(if (summary.progress > 0) "${summary.progress}% completed" else "Connecting...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setProgress(100, summary.progress, summary.progress <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun promoteToForeground(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Android 12+ refuses foreground starts from the background.
            e.printStackTrace()
            false
        }
    }
}
