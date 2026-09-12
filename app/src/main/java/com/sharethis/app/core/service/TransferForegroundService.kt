package com.sharethis.app.core.service

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
import com.sharethis.app.R
import com.sharethis.app.core.engine.FastTransferEngine
import com.sharethis.app.core.engine.TransferProgress
import com.sharethis.app.data.enums.TransferState
import com.sharethis.app.ui.MainActivity

/**
 * Version-appropriate foreground service that keeps an active transfer
 * alive when the user leaves the app or the screen turns off.
 *
 * Design (deliberately minimal, preserving the existing ViewModel-owned
 * engine architecture):
 *  - the engine keeps living in [com.sharethis.app.ui.viewmodels.TransferViewModel];
 *  - this service only pins the process importance + shows progress with a
 *    CANCEL action that reaches the engine through [ActiveTransfer];
 *  - API-correct on every level: startForegroundService on 26+,
 *    dataSync foreground type on 29+ (declared + passed), silent
 *    notification (no sound spam), channel only created on 26+,
 *    PendingIntent flags without IMMUTABLE below API 23.
 *
 * Cancel propagation: the notification's Cancel button → [onStartCommand]
 * ACTION_CANCEL → [ActiveTransfer.engine]?.cancel() → the engine breaks out
 * of its loops, the ViewModel observes the cancelled result, and the service
 * is stopped by the ViewModel's terminal-state hook.
 */
class TransferForegroundService : Service() {

    /** The engine currently driving a session; registered by the ViewModel. */
    object ActiveTransfer {
        @Volatile
        var engine: FastTransferEngine? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                ActiveTransfer.engine?.cancel()
                // The ViewModel stops the service when the cancelled result
                // lands; also stop defensively in case it was reset already.
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val sending = intent?.getBooleanExtra(EXTRA_SENDING, true) ?: true
                startAsForeground(this, sending)
            }
        }
        return START_NOT_STICKY
    }

    companion object {

        private const val CHANNEL_ID = "sharethis_transfer"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_CANCEL =
            "com.sharethis.app.core.service.TransferForegroundService.CANCEL"
        private const val EXTRA_SENDING = "sending"

        @Volatile
        private var lastRole: String = "ShareThis transfer"

        // ------------------------------------------------------------ commands

        /** Called by the ViewModel when a send/receive session begins. */
        fun start(context: Context, sending: Boolean) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, TransferForegroundService::class.java).apply {
                putExtra(EXTRA_SENDING, sending)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (_: Exception) {
                // Background-start restrictions or process edge cases: the
                // transfer still runs, it is just not foreground-pinned.
            }
        }

        /** Progress refresh — safe from any thread, no service start needed. */
        fun update(context: Context, state: TransferState, progress: TransferProgress) {
            val appContext = context.applicationContext
            try {
                val nm =
                    appContext.getSystemService(Context.NOTIFICATION_SERVICE)
                        as? NotificationManager ?: return
                nm.notify(NOTIFICATION_ID, buildNotification(appContext, state, progress))
            } catch (_: Exception) { /* notification updates are best-effort */ }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            try {
                appContext.stopService(
                    Intent(appContext, TransferForegroundService::class.java)
                )
            } catch (_: Exception) { }
            try {
                (appContext.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager)?.cancel(NOTIFICATION_ID)
            } catch (_: Exception) { }
        }

        // -------------------------------------------------------- notification

        private fun pendingFlags(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

        private fun buildNotification(
            context: Context,
            state: TransferState,
            progress: TransferProgress
        ): Notification {
            val title = when (state) {
                TransferState.TRANSFERRING, TransferState.RECONNECTING -> {
                    val current = (progress.filesCompleted + 1)
                        .coerceAtMost(progress.filesTotal.coerceAtLeast(1))
                    "$lastRole — $current of ${progress.filesTotal}"
                }
                TransferState.VERIFYING -> "$lastRole — verifying…"
                TransferState.PAUSED -> "$lastRole — paused"
                TransferState.COMPLETED -> "$lastRole — complete"
                else -> lastRole
            }
            val text = if (progress.totalBytes > 0) {
                "${TransferProgress.formatSpeed(progress.bytesPerSecond)} · ${progress.percent}%"
            } else {
                "Keep both devices nearby"
            }
            val contentIntent = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java), pendingFlags()
            )
            val cancelIntent = PendingIntent.getService(
                context, 1,
                Intent(context, TransferForegroundService::class.java).apply {
                    action = ACTION_CANCEL
                },
                pendingFlags()
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_send)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent()
                .setProgress(100, progress.percent, progress.totalBytes <= 0L)
                .addAction(0, "Cancel", cancelIntent)
                .build()
        }

        // ------------------------------------------------------------ plumbing

        private fun startAsForeground(service: TransferForegroundService, sending: Boolean) {
            lastRole = if (sending) "Sending files" else "Receiving files"
            val notification = buildNotification(service, TransferState.CONNECTING, TransferProgress())
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(service, NOTIFICATION_ID, notification, type)
        }

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "File transfers",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress while files are being sent or received"
                    setShowBadge(false)
                }
                (context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager)?.createNotificationChannel(channel)
            }
        }
    }
}
