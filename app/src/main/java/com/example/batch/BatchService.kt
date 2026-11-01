package com.example.batch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps a batch running (and visible in the notification shade) when the app is in the background. */
class BatchService : Service() {

    private var scope: CoroutineScope? = null
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val first = buildNotification(BatchManager.state.value, true)
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, first, type)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (watcher == null) {
            val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            scope = s
            watcher = s.launch {
                BatchManager.state.collect { st ->
                    val manager = getSystemService(NotificationManager::class.java)
                    if (st.running) {
                        manager.notify(NOTIFICATION_ID, buildNotification(st, true))
                    } else {
                        ServiceCompat.stopForeground(this@BatchService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        manager.notify(DONE_ID, buildNotification(st, false))
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        watcher = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(CHANNEL_ID, "Batch tasks", NotificationManager.IMPORTANCE_LOW)
                channel.description = "Progress of batch conversions and edits"
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(st: BatchState, ongoing: Boolean): Notification {
        val title = st.operation?.title ?: "Batch task"
        val total = st.jobs.size
        val finished = st.jobs.count { it.status != JobStatus.QUEUED && it.status != JobStatus.RUNNING }
        val text = if (ongoing) {
            "Working on file ${minOf(finished + 1, maxOf(total, 1))} of $total"
        } else {
            "${st.doneCount} done" + (if (st.failedCount > 0) ", ${st.failedCount} failed" else "")
        }
        val open = Intent(this, MainActivity::class.java)
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_batch)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (ongoing) {
            builder.setProgress(100, (st.overallProgress * 100f).toInt(), total == 0)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "pdfkit_batch"
        private const val NOTIFICATION_ID = 4101
        private const val DONE_ID = 4102

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, BatchService::class.java))
            } catch (e: Exception) {
                // The batch still runs in-process if the service cannot start.
            }
        }
    }
}
