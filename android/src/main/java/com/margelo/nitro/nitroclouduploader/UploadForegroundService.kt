package com.margelo.nitro.nitroclouduploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service for background uploads
 * Keeps the app alive while uploads are in progress
 * Similar to iOS URLSession background configuration
 */
class UploadForegroundService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "cloud_uploader_foreground"
        private const val NOTIFICATION_ID = 1002
        const val ACTION_START_UPLOAD = "com.margelo.nitro.nitroclouduploader.START_UPLOAD"
        const val ACTION_STOP_UPLOAD = "com.margelo.nitro.nitroclouduploader.STOP_UPLOAD"
        const val ACTION_UPDATE_PROGRESS = "com.margelo.nitro.nitroclouduploader.UPDATE_PROGRESS"
        const val EXTRA_UPLOAD_ID = "upload_id"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MESSAGE = "message"

        /**
         * Start the foreground service for an upload
         */
        fun startUploadService(context: Context, uploadId: String) {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                action = ACTION_START_UPLOAD
                putExtra(EXTRA_UPLOAD_ID, uploadId)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground service
         */
        fun stopUploadService(context: Context) {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                action = ACTION_STOP_UPLOAD
            }
            context.startService(intent)
        }

        /**
         * Update upload progress in the service notification
         */
        fun updateProgress(context: Context, uploadId: String, progress: Int, message: String) {
            val intent = Intent(context, UploadForegroundService::class.java).apply {
                action = ACTION_UPDATE_PROGRESS
                putExtra(EXTRA_UPLOAD_ID, uploadId)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_MESSAGE, message)
            }
            context.startService(intent)
        }
    }

    private val binder = LocalBinder()
    private var currentUploadId: String? = null
    private var currentProgress: Int = 0
    private lateinit var notificationManager: NotificationManager
    private var isServiceStarted = false

    inner class LocalBinder : Binder() {
        fun getService(): UploadForegroundService = this@UploadForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        println("🚀 UploadForegroundService created")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_UPLOAD -> {
                val uploadId = intent.getStringExtra(EXTRA_UPLOAD_ID)
                println("📤 Starting foreground service for upload: $uploadId")
                startForegroundService(uploadId)
            }
            
            ACTION_UPDATE_PROGRESS -> {
                val uploadId = intent.getStringExtra(EXTRA_UPLOAD_ID)
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "Uploading..."
                updateNotification(uploadId, progress, message)
            }
            
            ACTION_STOP_UPLOAD -> {
                println("🛑 Stopping foreground service")
                stopForegroundService()
            }
        }
        
        // If service is killed by system, do not restart
        return START_NOT_STICKY
    }

    private fun startForegroundService(uploadId: String?) {
        if (isServiceStarted) {
            println("⚠️ Service already started")
            return
        }

        currentUploadId = uploadId
        currentProgress = 0
        isServiceStarted = true

        val notification = createNotification(uploadId, 0, "Starting upload...")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            println("✅ Foreground service started")
        } catch (e: Exception) {
            println("❌ Failed to start foreground service: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun updateNotification(uploadId: String?, progress: Int, message: String) {
        if (!isServiceStarted) {
            return
        }

        currentUploadId = uploadId
        currentProgress = progress

        val notification = createNotification(uploadId, progress, message)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun stopForegroundService() {
        if (!isServiceStarted) {
            return
        }

        isServiceStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        println("✅ Foreground service stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Upload Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background upload service"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(uploadId: String?, progress: Int, message: String): android.app.Notification {
        // Create intent to open app when notification is tapped
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cloud Uploader")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)

        if (progress in 0..100) {
            builder.setProgress(100, progress, false)
        }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        println("🧹 UploadForegroundService destroyed")
        isServiceStarted = false
    }
}
