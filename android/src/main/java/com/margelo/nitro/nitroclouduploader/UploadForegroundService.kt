package com.margelo.nitro.nitroclouduploader

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
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process alive while uploads run and owns the one
 * upload notification. Started per upload and stopped when the last upload finishes, so
 * two concurrent uploads no longer kill each other's foreground state.
 *
 * Progress updates arrive at most twice per second (throttled by the uploader).
 */
class UploadForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "cloud_uploader_foreground"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_START = "com.margelo.nitro.nitroclouduploader.START_UPLOAD"
        private const val ACTION_UPDATE = "com.margelo.nitro.nitroclouduploader.UPDATE_PROGRESS"
        private const val ACTION_FINISH = "com.margelo.nitro.nitroclouduploader.FINISH_UPLOAD"
        private const val EXTRA_UPLOAD_ID = "upload_id"
        private const val EXTRA_PROGRESS = "progress"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_SUCCESS = "success"

        fun start(context: Context, uploadId: String) {
            val intent = Intent(context, UploadForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_UPLOAD_ID, uploadId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun updateProgress(context: Context, uploadId: String, percent: Int, message: String) {
            val intent = Intent(context, UploadForegroundService::class.java)
                .setAction(ACTION_UPDATE)
                .putExtra(EXTRA_UPLOAD_ID, uploadId)
                .putExtra(EXTRA_PROGRESS, percent)
                .putExtra(EXTRA_MESSAGE, message)
            context.startService(intent)
        }

        /**
         * Ends [uploadId]'s share of the service. With a [message] the notification is left
         * behind as a dismissible terminal state ("Upload complete" / "Upload failed");
         * without one (cancel) it is removed.
         */
        fun finish(context: Context, uploadId: String, message: String?, success: Boolean) {
            val intent = Intent(context, UploadForegroundService::class.java)
                .setAction(ACTION_FINISH)
                .putExtra(EXTRA_UPLOAD_ID, uploadId)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_SUCCESS, success)
            context.startService(intent)
        }
    }

    private val activeUploadIds = LinkedHashSet<String>()
    private lateinit var notificationManager: NotificationManager
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uploadId = intent?.getStringExtra(EXTRA_UPLOAD_ID)
        when (intent?.action) {
            ACTION_START -> {
                if (uploadId != null) activeUploadIds.add(uploadId)
                enterForeground(buildNotification(progress = 0, message = "Starting upload…", ongoing = true))
            }

            ACTION_UPDATE -> {
                if (isForeground) {
                    val percent = intent.getIntExtra(EXTRA_PROGRESS, 0)
                    val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Uploading…"
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(percent, message, ongoing = true))
                }
            }

            ACTION_FINISH -> {
                if (uploadId != null) activeUploadIds.remove(uploadId)
                if (activeUploadIds.isEmpty()) {
                    val message = intent.getStringExtra(EXTRA_MESSAGE)
                    val success = intent.getBooleanExtra(EXTRA_SUCCESS, false)
                    leaveForeground(terminalMessage = message, success = success)
                    stopSelf()
                }
            }

            else -> {
                // Started by the system without an action (should not happen with START_NOT_STICKY);
                // satisfy the startForeground() deadline anyway, then go away.
                enterForeground(buildNotification(progress = 0, message = "Uploading…", ongoing = true))
                if (activeUploadIds.isEmpty()) {
                    leaveForeground(terminalMessage = null, success = false)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
    }

    private fun enterForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            println("❌ UploadForegroundService: startForeground failed: ${e.message}")
        }
    }

    private fun leaveForeground(terminalMessage: String?, success: Boolean) {
        if (!isForeground) return
        isForeground = false
        if (terminalMessage == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        // Keep the notification, but as a normal dismissible one with the progress bar gone.
        stopForeground(STOP_FOREGROUND_DETACH)
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(progress = if (success) 100 else -1, message = terminalMessage, ongoing = false),
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Uploads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Upload progress"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(progress: Int, message: String, ongoing: Boolean): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cloud Uploader")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(contentIntent)
            .apply {
                if (ongoing && progress in 0..100) {
                    setProgress(100, progress, false)
                }
            }
            .build()
    }
}
