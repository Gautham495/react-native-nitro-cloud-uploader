package com.margelo.nitro.nitroclouduploader

import com.facebook.proguard.annotations.DoNotStrip
import androidx.annotation.Keep
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import androidx.core.content.ContextCompat
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.margelo.nitro.core.Promise
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
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
 * Note: Nitro uses no-arg constructor via JNI, so context must be nullable
 */
@DoNotStrip
@Keep
class NitroCloudUploader(
    private val injectedContext: Context? = null
) : HybridNitroCloudUploaderSpec() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "cloud_uploader"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_CHUNK_SIZE = 5 * 1024 * 1024 // 5MB minimum for S3
        private const val MAX_RETRIES = 3
    }
    
    // ✅ Get context from injected param or ContentProvider (auto-initialized on app start)
    private val appContext: Context?
        get() = injectedContext ?: ContextProvider.appContext
    
    // ✅ Nullable with lazy initialization (Nitro JNI bridge bypasses normal initialization)
    private var _activeUploads: ConcurrentHashMap<String, UploadJob>? = null
    private var _uploadStates: ConcurrentHashMap<String, UploadStateData>? = null
    private var _eventListeners: ConcurrentHashMap<String, MutableList<(UploadProgressEvent) -> Unit>>? = null
    
    // Thread-safe lazy getters
    private val activeUploads: ConcurrentHashMap<String, UploadJob>
        get() {
            if (_activeUploads == null) {
                synchronized(this) {
                    if (_activeUploads == null) {
                        _activeUploads = ConcurrentHashMap()
                    }
                }
            }
            return _activeUploads!!
        }
    
    private val uploadStates: ConcurrentHashMap<String, UploadStateData>
        get() {
            if (_uploadStates == null) {
                synchronized(this) {
                    if (_uploadStates == null) {
                        _uploadStates = ConcurrentHashMap()
                    }
                }
            }
            return _uploadStates!!
        }
    
    private val eventListeners: ConcurrentHashMap<String, MutableList<(UploadProgressEvent) -> Unit>>
        get() {
            if (_eventListeners == null) {
                synchronized(this) {
                    if (_eventListeners == null) {
                        _eventListeners = ConcurrentHashMap()
                    }
                }
            }
            return _eventListeners!!
        }
    
    @Volatile
    private var isNetworkAvailable = true
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    // ✅ Nullable with double-checked locking (by lazy doesn't work with Nitro JNI)
    @Volatile private var _uploadExecutor: java.util.concurrent.ExecutorService? = null
    @Volatile private var _uploadScope: CoroutineScope? = null
    @Volatile private var _notificationManager: NotificationManager? = null
    @Volatile private var _connectivityManager: ConnectivityManager? = null
    @Volatile private var _mainHandler: Handler? = null
    @Volatile private var _httpClient: OkHttpClient? = null
    
    private val uploadExecutor: java.util.concurrent.ExecutorService
        get() {
            if (_uploadExecutor == null) {
                synchronized(this) {
                    if (_uploadExecutor == null) {
                        _uploadExecutor = Executors.newFixedThreadPool(4)
                    }
                }
            }
            return _uploadExecutor!!
        }
    
    private val uploadScope: CoroutineScope
        get() {
            if (_uploadScope == null) {
                synchronized(this) {
                    if (_uploadScope == null) {
                        _uploadScope = CoroutineScope(uploadExecutor.asCoroutineDispatcher() + SupervisorJob())
                    }
                }
            }
            return _uploadScope!!
        }
    
    private val notificationManager: NotificationManager
        get() {
            if (_notificationManager == null) {
                val ctx = appContext  // Cache to avoid smart cast issues
                if (ctx != null) {
                    synchronized(this) {
                        if (_notificationManager == null) {
                            _notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        }
                    }
                }
            }
            return _notificationManager!!
        }
    
    private val connectivityManager: ConnectivityManager
        get() {
            if (_connectivityManager == null) {
                val ctx = appContext  // Cache to avoid smart cast issues
                if (ctx != null) {
                    synchronized(this) {
                        if (_connectivityManager == null) {
                            _connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        }
                    }
                }
            }
            return _connectivityManager!!
        }
    
    private val mainHandler: Handler
        get() {
            if (_mainHandler == null) {
                synchronized(this) {
                    if (_mainHandler == null) {
                        _mainHandler = Handler(Looper.getMainLooper())
                    }
                }
            }
            return _mainHandler!!
        }
    
    private val httpClient: OkHttpClient
        get() {
            if (_httpClient == null) {
                synchronized(this) {
                    if (_httpClient == null) {
                        _httpClient = OkHttpClient.Builder()
                            .connectTimeout(120, TimeUnit.SECONDS)
                            .writeTimeout(120, TimeUnit.SECONDS)
                            .readTimeout(120, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                            .build()
                    }
                }
            }
            return _httpClient!!
        }
    
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
        println("🚀 NitroCloudUploader init started (using lazy initialization)")
        
        // ✅ Setup notifications and network monitoring
        // Note: Properties are now initialized lazily or at declaration
        try {
            setupNotifications()
            setupNetworkMonitoring()
            println("✅ NitroCloudUploader initialized successfully")
        } catch (e: Exception) {
            println("❌ NitroCloudUploader initialization failed: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setupNotifications() {
        try {
            val ctx = appContext  // Cache to avoid smart cast issues
            if (ctx == null) {
                println("⚠️ appContext is null, skipping notification setup")
                return
            }
            
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
            val ctx = appContext  // Cache to avoid smart cast issues
            if (ctx == null) {
                println("⚠️ appContext is null, skipping network monitoring setup")
                return
            }
            
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

            // ✅ Calculate and validate chunk size (match iOS logic)
            val calculatedChunkSize = Math.ceil(fileSize.toDouble() / uploadUrls.size).toLong()
            val chunkSize = Math.max(calculatedChunkSize, MIN_CHUNK_SIZE.toLong())
            
            // Validate that file is large enough for the number of chunks requested
            val requiredMinimumSize = (uploadUrls.size - 1) * MIN_CHUNK_SIZE.toLong()
            if (fileSize < requiredMinimumSize) {
                throw IllegalArgumentException(
                    "File size ($fileSize bytes) is too small for ${uploadUrls.size} chunks. " +
                    "Minimum required: $requiredMinimumSize bytes (5 MB per chunk except last)"
                )
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
                    
                    // ✅ Stop foreground service on success
                    val ctx = appContext  // Cache to avoid smart cast issues
                    if (ctx != null) {
                        try {
                            UploadForegroundService.stopUploadService(ctx)
                        } catch (e: Exception) {
                            println("⚠️ Failed to stop foreground service: ${e.message}")
                        }
                    }
                    
                    promise.resolve(result)
                } catch (e: CancellationException) {
                    // ✅ Cancellation is intentional, resolve with cancelled result
                    println("⏸️ Upload cancelled: ${e.message}")
                    promise.resolve(UploadResult(
                        uploadId = uploadId,
                        success = false,
                        etags = emptyArray()
                    ))
                } catch (e: Exception) {
                    println("❌ Upload failed: ${e.message}")
                    
                    if (shouldNotify) {
                        showNotification(uploadId, -1, "Upload failed", isComplete = true)
                    }
                    
                    // ✅ Stop foreground service on failure
                    val ctx = appContext  // Cache to avoid smart cast issues
                    if (ctx != null) {
                        try {
                            UploadForegroundService.stopUploadService(ctx)
                        } catch (serviceError: Exception) {
                            println("⚠️ Failed to stop foreground service: ${serviceError.message}")
                        }
                    }
                    
                    promise.reject(e)
                } finally {
                    cleanup(uploadId)
                }
            }

            activeUploads[uploadId] = UploadJob(uploadId, job)

            // ✅ Start foreground service for background upload support
            val ctx = appContext  // Cache to avoid smart cast issues
            if (shouldNotify && ctx != null) {
                try {
                    UploadForegroundService.startUploadService(ctx, uploadId)
                } catch (e: Exception) {
                    println("⚠️ Failed to start foreground service: ${e.message}")
                }
            }

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
            // ✅ Calculate chunk size (match iOS logic)
            val calculatedChunkSize = Math.ceil(fileSize.toDouble() / uploadUrls.size).toLong()
            val chunkSize = Math.max(calculatedChunkSize, MIN_CHUNK_SIZE.toLong())
            
            // ✅ Create part info with proper offset calculation
            val parts = uploadUrls.mapIndexed { index, url ->
                val partNumber = index + 1
                val offset = index * chunkSize
                val size = if (index == uploadUrls.size - 1) {
                    // Last chunk gets remaining bytes
                    fileSize - offset
                } else {
                    chunkSize
                }
                
                PartInfo(partNumber, url, offset, size)
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

                // Stream the chunk from disk to avoid allocating the full part in memory.
                val request = Request.Builder()
                    .url(part.url)
                    .put(streamingFileChunkBody(filePath, part.offset, part.size))
                    .addHeader("Content-Type", "application/octet-stream")
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
                    throw Exception("No ETag in response for part ${part.partNumber}")
                }

                // ✅ Update state - double-check not already counted
                synchronized(state) {
                    if (!state.partETags.containsKey(part.partNumber)) {
                        state.partETags[part.partNumber] = etag
                        state.completedChunks++
                        state.bytesUploaded += part.size
                        state.failedChunks.remove(part.partNumber)
                    } else {
                        println("⚠️ Part ${part.partNumber} was already marked as completed, skipping state update")
                    }
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
                    
                    // ✅ Update foreground service notification
                    val ctx = appContext  // Cache to avoid smart cast issues
                    if (ctx != null) {
                        try {
                            UploadForegroundService.updateProgress(
                                ctx,
                                uploadId,
                                progressPercent,
                                "Uploading... ${progressPercent}%"
                            )
                        } catch (e: Exception) {
                            println("⚠️ Failed to update foreground service: ${e.message}")
                        }
                    }
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

    /**
     * Streams a byte range from disk into an OkHttp RequestBody.
     *
     * Each write uses a fixed-size buffer instead of allocating the full chunk.
     * The file is reopened for each write so the body can be replayed if OkHttp retries.
     */
    private fun streamingFileChunkBody(filePath: String, offset: Long, size: Long): RequestBody {
        val mediaType = "application/octet-stream".toMediaType()
        return object : RequestBody() {
            override fun contentType() = mediaType
            override fun contentLength() = size
            override fun writeTo(sink: okio.BufferedSink) {
                RandomAccessFile(File(filePath), "r").use { raf ->
                    if (offset + size > raf.length()) {
                        throw IllegalArgumentException(
                            "Invalid chunk bounds: offset=$offset, size=$size, file=${raf.length()}",
                        )
                    }
                    raf.seek(offset)
                    val buffer = ByteArray(256 * 1024) // 256 KB
                    var remaining = size
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = raf.read(buffer, 0, toRead)
                        if (read <= 0) {
                            throw java.io.IOException(
                                "Unexpected EOF at offset ${offset + (size - remaining)}",
                            )
                        }
                        sink.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
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
            
            // ✅ Cancel job safely
            if (uploadJob != null) {
                try {
                    if (uploadJob.job.isActive) {
                        uploadJob.job.cancel()  // Don't pass exception, just cancel
                        println("✅ Upload job cancelled")
                    } else {
                        println("⚠️ Upload job is not active, skipping cancellation")
                    }
                } catch (e: Exception) {
                    println("⚠️ Error cancelling job: ${e.message}")
                }
            } else {
                println("⚠️ No active upload found for: $uploadId")
            }
            
            // ✅ Cleanup before emitting events (prevent accessing disposed resources)
            cleanup(uploadId)
            cancelNotification()
            
            // ✅ Stop foreground service on cancel
            val ctx = appContext  // Cache to avoid smart cast issues
            if (ctx != null) {
                try {
                    UploadForegroundService.stopUploadService(ctx)
                } catch (e: Exception) {
                    println("⚠️ Failed to stop foreground service: ${e.message}")
                }
            }
            
            // ✅ Emit event after cleanup
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
        // ⚠️ Emit on main thread to ensure React Native bridge compatibility (matches iOS behavior)
        mainHandler.post {
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
    }

    private fun showNotification(uploadId: String, progress: Int, message: String, isComplete: Boolean = false) {
        try {
            // ✅ Check if context is available and cache it
            val ctx = appContext
            if (ctx == null) {
                println("⚠️ appContext is null, cannot show notification")
                return
            }
            
            // ✅ Check notification permission for Android 13+ (API 33+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    ctx,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                
                if (!hasPermission) {
                    println("⚠️ POST_NOTIFICATIONS permission not granted. Notifications will not be shown.")
                    println("   Add permission request in your app: <uses-permission android:name=\"android.permission.POST_NOTIFICATIONS\" />")
                    return
                }
            }
            
            // ✅ Create intent to open app when notification is tapped
            val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            
            val pendingIntent = if (launchIntent != null) {
                PendingIntent.getActivity(
                    ctx,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            } else {
                null
            }
            
            val notification = NotificationCompat.Builder(ctx, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Uploading File")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(!isComplete)
                .setAutoCancel(isComplete)
                .setContentIntent(pendingIntent)
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
                _connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                println("⚠️ Unregister network callback error: ${e.message}")
            }
        }
        
        // Cancel all active uploads
        _activeUploads?.values?.forEach { it.job.cancel() }
        _activeUploads?.clear()
        _uploadStates?.clear()
        _eventListeners?.clear()
        
        // Shutdown executor
        _uploadScope?.cancel()
        _uploadExecutor?.shutdown()
    }

    private data class PartInfo(
        val partNumber: Int,
        val url: String,
        val offset: Long,
        val size: Long
    )
}