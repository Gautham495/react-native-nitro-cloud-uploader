package com.margelo.nitro.nitroclouduploader

import com.facebook.proguard.annotations.DoNotStrip
import androidx.annotation.Keep
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import com.margelo.nitro.core.Promise
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections

/**
 * Cloud uploader using Coroutines - No WorkManager dependency
 */
@DoNotStrip
@Keep
class NitroCloudUploader(
    private val appContext: Context
) : HybridNitroCloudUploaderSpec() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "cloud_uploader"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_CHUNK_SIZE = 5 * 1024 * 1024 // 5MB minimum for S3
        private const val MAX_RETRIES = 3
    }
    
    // ✅ Declare as lateinit - initialize in init block
    private lateinit var activeUploads: ConcurrentHashMap<String, UploadJob>
    private lateinit var uploadStates: ConcurrentHashMap<String, UploadStateData>
    private lateinit var eventListeners: ConcurrentHashMap<String, MutableList<(UploadProgressEvent) -> Unit>>
    
    @Volatile
    private var isNetworkAvailable = true
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    // ✅ Declare as lateinit - initialize in init block
    private lateinit var uploadExecutor: java.util.concurrent.ExecutorService
    private lateinit var uploadScope: CoroutineScope
    
    private lateinit var notificationManager: NotificationManager
    private lateinit var connectivityManager: ConnectivityManager
    
    // ✅ Declare as lateinit - initialize in init block
    private lateinit var httpClient: OkHttpClient
    
    data class UploadJob(
        val uploadId: String,
        val job: Job,
        val isPaused: AtomicBoolean = AtomicBoolean(false)
    )
    
    data class PartETag(
        val partNumber: Int,
        val eTag: String
    )
    
    data class UploadStateData(
        val totalBytes: Long,
        var bytesUploaded: Long = 0,
        var completedChunks: Int = 0,
        var totalChunks: Int = 0,
        val partETags: MutableMap<Int, String> = Collections.synchronizedMap(mutableMapOf()),
        val failedChunks: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
    )

    init {
        println("🚀 NitroCloudUploader init started (Coroutines-based)")
        
        // ✅ Initialize all properties explicitly in init block
        try {
            println("   Initializing collections...")
            activeUploads = ConcurrentHashMap()
            uploadStates = ConcurrentHashMap()
            eventListeners = ConcurrentHashMap()
            
            println("   Initializing system services...")
            notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            println("   Initializing executor and scope...")
            uploadExecutor = Executors.newFixedThreadPool(4)
            uploadScope = CoroutineScope(uploadExecutor.asCoroutineDispatcher() + SupervisorJob())
            
            println("   Initializing HTTP client...")
            httpClient = OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build()
            
            println("   Setting up notifications and network monitoring...")
            setupNotifications()
            setupNetworkMonitoring()
            
            println("✅ NitroCloudUploader initialized successfully")
        } catch (e: Exception) {
            println("❌ NitroCloudUploader initialization failed: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    private fun setupNotifications() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Cloud Uploader",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Upload progress"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        } catch (e: Exception) {
            println("⚠️ Notification channel error: ${e.message}")
        }
    }

    private fun setupNetworkMonitoring() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (!isNetworkAvailable) {
                        println("📡 Network restored")
                        isNetworkAvailable = true
                        
                        activeUploads.keys.forEach { uploadId ->
                            emitEvent(
                                UploadProgressEvent(
                                    type = "network-restored",
                                    uploadId = uploadId,
                                    progress = null,
                                    bytesUploaded = null,
                                    totalBytes = null,
                                    chunkIndex = null,
                                    errorMessage = null
                                )
                            )
                        }
                    }
                }

                override fun onLost(network: Network) {
                    println("📡 Network lost")
                    isNetworkAvailable = false
                    
                    activeUploads.keys.forEach { uploadId ->
                        emitEvent(
                            UploadProgressEvent(
                                type = "network-lost",
                                uploadId = uploadId,
                                progress = null,
                                bytesUploaded = null,
                                totalBytes = null,
                                chunkIndex = null,
                                errorMessage = null
                            )
                        )
                    }
                }
            }

            connectivityManager.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            println("⚠️ Network monitoring error: ${e.message}")
        }
    }

    override fun startUpload(
        uploadId: String,
        filePath: String,
        uploadUrls: Array<String>,
        maxParallel: Double?,
        showNotification: Boolean?
    ): Promise<UploadResult> {
        val promise = Promise<UploadResult>()
        
        try {
            println("🔍 Checking upload state for: $uploadId")
            println("   activeUploads: $activeUploads")
            println("   uploadStates: $uploadStates")
            
            if (activeUploads.containsKey(uploadId)) {
                throw IllegalStateException("Upload already in progress: $uploadId")
            }

            val parallel = maxParallel?.toInt()?.coerceIn(1, 10) ?: 3
            val shouldNotify = showNotification ?: true

            println("🚀 Starting upload: $uploadId")
            println("   File: $filePath")
            println("   Parts: ${uploadUrls.size}")
            println("   Parallel: $parallel")

            // ✅ Decode and validate file path
            val decodedPath = java.net.URLDecoder.decode(filePath, "UTF-8")
                .removePrefix("file://")
            val file = File(decodedPath)

            if (!file.exists()) {
                throw IllegalArgumentException("File not found: $decodedPath")
            }

            if (!file.canRead()) {
                throw IllegalArgumentException("Cannot read file: $decodedPath")
            }

            val fileSize = file.length()
            
            if (fileSize == 0L) {
                throw IllegalArgumentException("File is empty")
            }

            // ✅ Create upload state
            uploadStates[uploadId] = UploadStateData(
                totalBytes = fileSize,
                totalChunks = uploadUrls.size
            )

            // ✅ Launch upload job
            val job = uploadScope.launch {
                try {
                    val result = performUpload(
                        uploadId = uploadId,
                        filePath = decodedPath,
                        uploadUrls = uploadUrls,
                        fileSize = fileSize,
                        maxParallel = parallel,
                        showNotification = shouldNotify
                    )
                    
                    if (shouldNotify) {
                        showNotification(uploadId, 100, "Upload complete!", isComplete = true)
                    }
                    
                    promise.resolve(result)
                } catch (e: Exception) {
                    println("❌ Upload failed: ${e.message}")
                    
                    if (shouldNotify) {
                        showNotification(uploadId, -1, "Upload failed", isComplete = true)
                    }
                    
                    promise.reject(e)
                } finally {
                    cleanup(uploadId)
                }
            }

            activeUploads[uploadId] = UploadJob(uploadId, job)

            emitEvent(
                UploadProgressEvent(
                    type = "upload-started",
                    uploadId = uploadId,
                    progress = 0.0,
                    bytesUploaded = 0.0,
                    totalBytes = fileSize.toDouble(),
                    chunkIndex = null,
                    errorMessage = null
                )
            )

            if (shouldNotify) {
                showNotification(uploadId, 0, "Starting upload...")
            }

        } catch (e: Exception) {
            println("❌ Start upload error: ${e.message}")
            e.printStackTrace()
            cleanup(uploadId)
            promise.reject(e)
        }
        
        return promise
    }

    private suspend fun performUpload(
        uploadId: String,
        filePath: String,
        uploadUrls: Array<String>,
        fileSize: Long,
        maxParallel: Int,
        showNotification: Boolean
    ): UploadResult = withContext(Dispatchers.IO) {
        val state = uploadStates[uploadId] ?: throw IllegalStateException("Upload state not found")
        val uploadJob = activeUploads[uploadId] ?: throw IllegalStateException("Upload job not found")
        
        try {
            // ✅ Create part info
            val parts = uploadUrls.mapIndexed { index, url ->
                val partNumber = index + 1
                val offset = (fileSize * index) / uploadUrls.size
                val nextOffset = if (index == uploadUrls.size - 1) {
                    fileSize
                } else {
                    (fileSize * (index + 1)) / uploadUrls.size
                }
                val chunkSize = nextOffset - offset
                
                PartInfo(partNumber, url, offset, chunkSize)
            }

            // ✅ Upload parts with limited parallelism
            val semaphore = Semaphore(maxParallel)
            
            val uploadJobs = parts.map { part ->
                async<Boolean> {
                    semaphore.withPermit {
                        uploadPart(uploadId, filePath, part, fileSize, uploadJob, showNotification)
                    }
                }
            }

            // Wait for all parts to complete
            val results = uploadJobs.awaitAll()

            // ✅ Check if all parts succeeded
            if (state.completedChunks != uploadUrls.size) {
                val failedParts = state.failedChunks.sorted().joinToString()
                throw Exception("Upload incomplete. Failed parts: $failedParts")
            }

            // ✅ Sort ETags by part number
            val sortedETags = state.partETags.toSortedMap().values.toTypedArray()
            
            println("✅ Upload complete with ${sortedETags.size} ETags")
            
            emitEvent(
                UploadProgressEvent(
                    type = "upload-completed",
                    uploadId = uploadId,
                    progress = 1.0,
                    bytesUploaded = fileSize.toDouble(),
                    totalBytes = fileSize.toDouble(),
                    chunkIndex = null,
                    errorMessage = null
                )
            )

            UploadResult(
                uploadId = uploadId,
                success = true,
                etags = sortedETags
            )
        } catch (e: CancellationException) {
            println("⏸️ Upload cancelled: $uploadId")
            throw e
        } catch (e: Exception) {
            println("❌ Upload error: ${e.message}")
            
            emitEvent(
                UploadProgressEvent(
                    type = "upload-failed",
                    uploadId = uploadId,
                    progress = null,
                    bytesUploaded = null,
                    totalBytes = fileSize.toDouble(),
                    chunkIndex = null,
                    errorMessage = e.message
                )
            )
            
            throw e
        }
    }

    private suspend fun uploadPart(
        uploadId: String,
        filePath: String,
        part: PartInfo,
        fileSize: Long,
        uploadJob: UploadJob,
        showNotification: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val state = uploadStates[uploadId] ?: return@withContext false
        
        // ✅ Check if already uploaded
        if (state.partETags.containsKey(part.partNumber)) {
            println("⏭️ Part ${part.partNumber} already uploaded, skipping")
            return@withContext true
        }

        var retries = 0
        var lastError: Exception? = null

        while (retries < MAX_RETRIES) {
            // Check if paused
            if (uploadJob.isPaused.get()) {
                println("⏸️ Upload paused, waiting...")
                delay(1000)
                continue
            }

            // Check if cancelled
            if (!isActive) {
                println("🛑 Upload cancelled")
                return@withContext false
            }

            try {
                println("📤 Uploading part ${part.partNumber}/${state.totalChunks} (attempt ${retries + 1})")

                // ✅ Read chunk
                val chunkData = readChunk(filePath, part.offset, part.size)

                // ✅ Upload chunk
                val request = Request.Builder()
                    .url(part.url)
                    .put(chunkData.toRequestBody("application/octet-stream".toMediaType()))
                    .addHeader("Content-Type", "application/octet-stream")
                    .addHeader("Content-Length", part.size.toString())
                    .build()

                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    throw Exception("HTTP ${response.code}: $errorBody")
                }

                // ✅ Extract ETag
                val etag = response.header("ETag")?.trim('"') 
                    ?: response.header("etag")?.trim('"')
                    ?: ""

                if (etag.isEmpty()) {
                    println("⚠️ No ETag received for part ${part.partNumber}")
                }

                // ✅ Update state
                synchronized(state) {
                    state.partETags[part.partNumber] = etag
                    state.completedChunks++
                    state.bytesUploaded += part.size
                    state.failedChunks.remove(part.partNumber)
                }

                val progress = state.bytesUploaded.toDouble() / state.totalBytes

                println("✅ Part ${part.partNumber} uploaded - ETag: $etag (${state.completedChunks}/${state.totalChunks})")

                // ✅ Emit events
                emitEvent(
                    UploadProgressEvent(
                        type = "chunk-completed",
                        uploadId = uploadId,
                        progress = null,
                        bytesUploaded = null,
                        totalBytes = null,
                        chunkIndex = (part.partNumber - 1).toDouble(),
                        errorMessage = null
                    )
                )

                emitEvent(
                    UploadProgressEvent(
                        type = "upload-progress",
                        uploadId = uploadId,
                        progress = progress,
                        bytesUploaded = state.bytesUploaded.toDouble(),
                        totalBytes = state.totalBytes.toDouble(),
                        chunkIndex = null,
                        errorMessage = null
                    )
                )

                if (showNotification) {
                    val progressPercent = (progress * 100).toInt()
                    showNotification(uploadId, progressPercent, "Uploading... ${progressPercent}%")
                }

                return@withContext true

            } catch (e: Exception) {
                lastError = e
                retries++
                
                println("❌ Part ${part.partNumber} failed (attempt $retries): ${e.message}")
                
                if (retries < MAX_RETRIES) {
                    delay(1000L * retries) // Exponential backoff
                } else {
                    state.failedChunks.add(part.partNumber)
                    
                    emitEvent(
                        UploadProgressEvent(
                            type = "chunk-failed",
                            uploadId = uploadId,
                            progress = null,
                            bytesUploaded = null,
                            totalBytes = null,
                            chunkIndex = (part.partNumber - 1).toDouble(),
                            errorMessage = e.message
                        )
                    )
                }
            }
        }

        println("❌ Part ${part.partNumber} failed after $MAX_RETRIES attempts")
        false
    }

    private fun readChunk(filePath: String, offset: Long, size: Long): ByteArray {
        return RandomAccessFile(File(filePath), "r").use { raf ->
            if (offset + size > raf.length()) {
                throw IllegalArgumentException("Invalid chunk bounds: offset=$offset, size=$size, file=${raf.length()}")
            }
            
            raf.seek(offset)
            val buffer = ByteArray(size.toInt())
            val bytesRead = raf.read(buffer)
            
            if (bytesRead != size.toInt()) {
                throw Exception("Read $bytesRead bytes, expected $size")
            }
            
            buffer
        }
    }

    override fun pauseUpload(uploadId: String): Promise<Unit> {
        val promise = Promise<Unit>()
        
        try {
            println("⏸️ Pausing upload: $uploadId")
            val uploadJob = activeUploads[uploadId]
            
            if (uploadJob == null) {
                promise.reject(Exception("Upload not found: $uploadId"))
                return promise
            }
            
            uploadJob.isPaused.set(true)
            
            emitEvent(
                UploadProgressEvent(
                    type = "upload-paused",
                    uploadId = uploadId,
                    progress = null,
                    bytesUploaded = null,
                    totalBytes = null,
                    chunkIndex = null,
                    errorMessage = null
                )
            )
            
            promise.resolve(Unit)
        } catch (e: Exception) {
            println("❌ Pause error: ${e.message}")
            promise.reject(e)
        }
        
        return promise
    }

    override fun resumeUpload(uploadId: String): Promise<Unit> {
        val promise = Promise<Unit>()
        
        try {
            println("▶️ Resuming upload: $uploadId")
            val uploadJob = activeUploads[uploadId]
            
            if (uploadJob == null) {
                promise.reject(Exception("Upload not found: $uploadId"))
                return promise
            }
            
            uploadJob.isPaused.set(false)
            
            emitEvent(
                UploadProgressEvent(
                    type = "upload-resumed",
                    uploadId = uploadId,
                    progress = null,
                    bytesUploaded = null,
                    totalBytes = null,
                    chunkIndex = null,
                    errorMessage = null
                )
            )
            
            promise.resolve(Unit)
        } catch (e: Exception) {
            println("❌ Resume error: ${e.message}")
            promise.reject(e)
        }
        
        return promise
    }

    override fun cancelUpload(uploadId: String): Promise<Unit> {
        val promise = Promise<Unit>()
        
        try {
            println("🛑 Cancelling upload: $uploadId")
            val uploadJob = activeUploads[uploadId]
            
            if (uploadJob != null) {
                uploadJob.job.cancel()
            }
            
            emitEvent(
                UploadProgressEvent(
                    type = "upload-cancelled",
                    uploadId = uploadId,
                    progress = null,
                    bytesUploaded = null,
                    totalBytes = null,
                    chunkIndex = null,
                    errorMessage = null
                )
            )
            
            cleanup(uploadId)
            cancelNotification()
            promise.resolve(Unit)
        } catch (e: Exception) {
            println("❌ Cancel error: ${e.message}")
            promise.reject(e)
        }
        
        return promise
    }

    override fun getUploadState(uploadId: String): Promise<UploadState> {
        val promise = Promise<UploadState>()
        
        try {
            val state = uploadStates[uploadId]
                ?: throw IllegalStateException("No upload found: $uploadId")

            val uploadJob = activeUploads[uploadId]
            val isPaused = uploadJob?.isPaused?.get() ?: false

            val progress = if (state.totalBytes > 0) {
                state.bytesUploaded.toDouble() / state.totalBytes
            } else 0.0

            promise.resolve(
                UploadState(
                    uploadId = uploadId,
                    state = when {
                        isPaused -> "paused"
                        state.failedChunks.isNotEmpty() -> "failed"
                        state.completedChunks == state.totalChunks -> "completed"
                        else -> "uploading"
                    },
                    progress = progress,
                    bytesUploaded = state.bytesUploaded.toDouble(),
                    totalBytes = state.totalBytes.toDouble(),
                    isPaused = isPaused,
                    isNetworkAvailable = isNetworkAvailable
                )
            )
        } catch (e: Exception) {
            promise.reject(e)
        }
        
        return promise
    }

    override fun addListener(eventType: String, callback: (UploadProgressEvent) -> Unit) {
        try {
            println("✅ Adding listener: $eventType")
            eventListeners.getOrPut(eventType) { mutableListOf() }.add(callback)
        } catch (e: Exception) {
            println("⚠️ Add listener error: ${e.message}")
        }
    }

    override fun removeListener(eventType: String) {
        try {
            println("✅ Removing listener: $eventType")
            eventListeners.remove(eventType)
        } catch (e: Exception) {
            println("⚠️ Remove listener error: ${e.message}")
        }
    }

    private fun emitEvent(event: UploadProgressEvent) {
        try {
            eventListeners[event.type]?.forEach { 
                try {
                    it(event)
                } catch (e: Exception) {
                    println("⚠️ Event callback error: ${e.message}")
                }
            }
            
            eventListeners["all"]?.forEach { 
                try {
                    it(event)
                } catch (e: Exception) {
                    println("⚠️ Event callback error: ${e.message}")
                }
            }
        } catch (e: Exception) {
            println("⚠️ Emit event error: ${e.message}")
        }
    }

    private fun showNotification(uploadId: String, progress: Int, message: String, isComplete: Boolean = false) {
        try {
            val notification = NotificationCompat.Builder(appContext, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Uploading File")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(!isComplete)
                .setAutoCancel(isComplete)
                .apply {
                    if (progress in 0..100) {
                        setProgress(100, progress, false)
                    }
                }
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            println("⚠️ Notification error: ${e.message}")
        }
    }

    private fun cancelNotification() {
        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            println("⚠️ Cancel notification error: ${e.message}")
        }
    }

    private fun cleanup(uploadId: String) {
        activeUploads.remove(uploadId)
        uploadStates.remove(uploadId)
    }

    fun destroy() {
        println("🧹 Destroying NitroCloudUploader")
        
        networkCallback?.let { 
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                println("⚠️ Unregister network callback error: ${e.message}")
            }
        }
        
        // Cancel all active uploads
        activeUploads.values.forEach { it.job.cancel() }
        activeUploads.clear()
        uploadStates.clear()
        eventListeners.clear()
        
        // Shutdown executor
        uploadScope.cancel()
        uploadExecutor.shutdown()
    }

    private data class PartInfo(
        val partNumber: Int,
        val url: String,
        val offset: Long,
        val size: Long
    )
}