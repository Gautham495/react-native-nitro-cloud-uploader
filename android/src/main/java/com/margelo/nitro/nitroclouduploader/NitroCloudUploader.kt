package com.margelo.nitro.nitroclouduploader

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import com.facebook.proguard.annotations.DoNotStrip
import com.margelo.nitro.core.Promise
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Resumable, background-friendly uploads to presigned (or unsigned) URLs.
 *
 * One part per URL: a single URL is a plain PUT of the whole file, several URLs are an
 * S3-style multipart upload. Progress is reported per 64 KB written (throttled to ~20 Hz),
 * not per finished part.
 *
 * Note: Nitro constructs this via its no-arg constructor, so the context is nullable.
 */
@DoNotStrip
@Keep
class NitroCloudUploader(
    private val injectedContext: Context? = null,
) : HybridNitroCloudUploaderSpec() {

    private val appContext: Context?
        get() = injectedContext ?: ContextProvider.appContext ?: NitroCloudUploaderPackage.appContext

    private val activeUploads = ConcurrentHashMap<String, ActiveUpload>()
    private val listeners = ConcurrentHashMap<String, CopyOnWriteArrayList<(UploadProgressEvent) -> Unit>>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Null when there is no application context or the callback could not be registered. */
    private val networkMonitor: NetworkMonitor? = appContext?.let { NetworkMonitor(it) }?.takeIf { monitor ->
        runCatching { monitor.start() }
            .onFailure { println("⚠️ NitroCloudUploader: network monitoring unavailable: ${it.message}") }
            .isSuccess
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    init {
        val monitor = networkMonitor
        if (monitor == null) {
            println("⚠️ NitroCloudUploader: network monitoring disabled (no application context or no callback)")
        } else {
            scope.launch { observeNetwork(monitor) }
        }
    }

    // region Public API

    override fun startUpload(
        uploadId: String,
        filePath: String,
        uploadUrls: Array<String>,
        maxParallel: Double?,
        showNotification: Boolean?,
    ): Promise<UploadResult> {
        if (uploadId.isBlank()) {
            return Promise.rejected(IllegalArgumentException("Upload ID cannot be empty"))
        }
        if (activeUploads.containsKey(uploadId)) {
            // Do NOT touch the running upload's state here (the old code wiped it).
            return Promise.rejected(IllegalStateException("Upload already in progress: $uploadId"))
        }

        val upload: ActiveUpload
        try {
            val file = UploadFileResolver.resolve(filePath)
            val parts = UploadPartPlan.plan(file.length(), uploadUrls)
            upload = ActiveUpload(uploadId, file, parts, showNotification ?: true)
        } catch (e: Exception) {
            return Promise.rejected(e)
        }

        val parallel = maxParallel?.toInt()?.coerceIn(1, 10) ?: DEFAULT_PARALLEL
        val mediaType = DEFAULT_CONTENT_TYPE.toMediaTypeOrNull()

        println("🚀 Starting upload $uploadId: ${upload.totalBytes} bytes, ${upload.parts.size} part(s), parallel=$parallel, contentType=$mediaType")

        // Lazy so the upload cannot finish (and remove itself) before it is registered.
        val deferred = scope.async(start = CoroutineStart.LAZY) { runUpload(upload, parallel, mediaType) }
        upload.job = deferred
        activeUploads[uploadId] = upload
        deferred.start()

        return Promise.async {
            try {
                deferred.await()
            } catch (e: CancellationException) {
                // cancelUpload() already emitted "upload-cancelled"; resolve instead of rejecting.
                UploadResult(uploadId = uploadId, success = false, etags = emptyArray())
            }
        }
    }

    override fun pauseUpload(uploadId: String): Promise<Unit> {
        val upload = activeUploads[uploadId]
            ?: return Promise.rejected(IllegalStateException("Upload not found: $uploadId"))
        upload.pauseGate.pause()
        emit(event("upload-paused", uploadId))
        return Promise.resolved(Unit)
    }

    override fun resumeUpload(uploadId: String): Promise<Unit> {
        val upload = activeUploads[uploadId]
            ?: return Promise.rejected(IllegalStateException("Upload not found: $uploadId"))
        upload.pauseGate.resume()
        emit(event("upload-resumed", uploadId))
        return Promise.resolved(Unit)
    }

    override fun cancelUpload(uploadId: String): Promise<Unit> {
        val upload = activeUploads.remove(uploadId) ?: return Promise.resolved(Unit)
        println("🛑 Cancelling upload $uploadId")
        upload.job.cancel()
        upload.inFlightCalls.values.forEach { it.cancel() }
        // Release a writer blocked in the pause gate so it notices the closed socket.
        upload.pauseGate.resume()
        finishForegroundService(upload, terminalMessage = null, success = false)
        emit(event("upload-cancelled", uploadId))
        return Promise.resolved(Unit)
    }

    override fun getUploadState(uploadId: String): Promise<UploadState> {
        val upload = activeUploads[uploadId]
            ?: return Promise.rejected(IllegalStateException("No upload found: $uploadId"))
        val isPaused = upload.pauseGate.isPaused.value
        return Promise.resolved(
            UploadState(
                uploadId = uploadId,
                state = if (isPaused) "paused" else "uploading",
                progress = upload.progress,
                bytesUploaded = upload.bytesUploaded.toDouble(),
                totalBytes = upload.totalBytes.toDouble(),
                isPaused = isPaused,
                isNetworkAvailable = networkMonitor?.isOnline?.value ?: true,
            ),
        )
    }

    override fun addListener(eventType: String, callback: (UploadProgressEvent) -> Unit) {
        listeners.getOrPut(eventType) { CopyOnWriteArrayList() }.add(callback)
    }

    override fun removeListener(eventType: String) {
        listeners.remove(eventType)
    }

    /** Cancels everything and releases the network callback. Not part of the JS spec. */
    fun destroy() {
        networkMonitor?.stop()
        activeUploads.values.forEach { it.job.cancel() }
        activeUploads.clear()
        listeners.clear()
        scope.cancel()
    }

    // endregion

    // region Upload pipeline

    private suspend fun runUpload(upload: ActiveUpload, maxParallel: Int, mediaType: MediaType?): UploadResult {
        try {
            emit(
                event(
                    "upload-started",
                    upload.uploadId,
                    progress = 0.0,
                    bytesUploaded = 0.0,
                    totalBytes = upload.totalBytes.toDouble(),
                ),
            )
            if (upload.showNotification) startForegroundService(upload)

            val result = uploadAllParts(upload, maxParallel, mediaType)

            println("✅ Upload ${upload.uploadId} complete (${upload.parts.size} part(s))")
            emit(
                event(
                    "upload-completed",
                    upload.uploadId,
                    progress = 1.0,
                    bytesUploaded = upload.totalBytes.toDouble(),
                    totalBytes = upload.totalBytes.toDouble(),
                ),
            )
            finishForegroundService(upload, terminalMessage = "Upload complete", success = true)
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("❌ Upload ${upload.uploadId} failed: ${e.message}")
            emit(event("upload-failed", upload.uploadId, totalBytes = upload.totalBytes.toDouble(), errorMessage = e.message))
            finishForegroundService(upload, terminalMessage = "Upload failed", success = false)
            throw e
        } finally {
            activeUploads.remove(upload.uploadId, upload)
        }
    }

    private suspend fun uploadAllParts(
        upload: ActiveUpload,
        maxParallel: Int,
        mediaType: MediaType?,
    ): UploadResult = coroutineScope {
        val permits = Semaphore(maxParallel)
        upload.parts
            .map { part -> async { permits.withPermit { uploadPart(upload, part, mediaType) } } }
            .awaitAll()
        UploadResult(uploadId = upload.uploadId, success = true, etags = upload.etagsInPartOrder())
    }

    /** Uploads one part, retrying transient failures. Throws when the part cannot be uploaded. */
    private suspend fun uploadPart(upload: ActiveUpload, part: UploadPart, mediaType: MediaType?) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            upload.pauseGate.awaitResumed()
            networkMonitor?.isOnline?.first { online -> online }
            attempt++

            try {
                val etag = putPart(upload, part, mediaType)
                upload.markCompleted(part, etag)
                emitProgress(upload, force = true)
                emit(event("chunk-completed", upload.uploadId, chunkIndex = part.index.toDouble()))
                println("✅ Part ${part.partNumber}/${upload.parts.size} done (${upload.etags.size}/${upload.parts.size})")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                upload.resetInFlight(part.partNumber)

                val tornDownByNetworkLoss = e is IOException && networkMonitor?.isOnline?.value == false
                if (tornDownByNetworkLoss) {
                    // Not the server's fault: wait for connectivity, then try again without
                    // consuming an attempt.
                    attempt--
                    println("📡 Part ${part.partNumber} interrupted by network loss; waiting for connectivity")
                    continue
                }

                if (isRetryable(e) && attempt < MAX_ATTEMPTS) {
                    val backoffMs = RETRY_BASE_DELAY_MS shl (attempt - 1)
                    println("⚠️ Part ${part.partNumber} attempt $attempt failed (${e.message}); retrying in ${backoffMs}ms")
                    delay(backoffMs)
                    continue
                }

                emit(event("chunk-failed", upload.uploadId, chunkIndex = part.index.toDouble(), errorMessage = e.message))
                throw IOException("Part ${part.partNumber} failed after $attempt attempt(s): ${e.message}", e)
            }
        }
    }

    /** One PUT of [part]. Returns the ETag ("" only for single-URL uploads without one). */
    private suspend fun putPart(upload: ActiveUpload, part: UploadPart, mediaType: MediaType?): String {
        val body = FileRangeRequestBody(upload.file, part.offset, part.size, mediaType, upload.pauseGate) { bytesSent ->
            upload.updateInFlight(part.partNumber, bytesSent)
            emitProgress(upload, force = false)
        }
        val request = Request.Builder().url(part.url).put(body).build()
        val call = httpClient.newCall(request)
        upload.inFlightCalls[part.partNumber] = call
        try {
            call.await().use { response ->
                if (!response.isSuccessful) {
                    throw UploadHttpException(response.code, response.body?.string().orEmpty())
                }
                val etag = response.header("ETag")?.trim()?.trim('"').orEmpty()
                if (etag.isEmpty() && upload.isMultipart) {
                    // Not retryable: the server is not S3-compatible, or a proxy strips the header.
                    throw IllegalStateException(
                        "No ETag header in the response for part ${part.partNumber}; " +
                            "completing a multipart upload needs one",
                    )
                }
                return etag
            }
        } finally {
            upload.inFlightCalls.remove(part.partNumber)
        }
    }

    private fun isRetryable(e: Exception): Boolean = when (e) {
        is UploadHttpException -> e.isRetryable
        is IOException -> true
        else -> false
    }

    private suspend fun observeNetwork(monitor: NetworkMonitor) {
        monitor.isOnline.drop(1).collect { online ->
            val type = if (online) "network-restored" else "network-lost"
            println(if (online) "📡 Network restored" else "📡 Network lost")
            for (upload in activeUploads.values) {
                if (!online) {
                    // The sockets are dead; tear the PUTs down now instead of waiting on a
                    // write timeout. uploadPart() restarts them once we are back online.
                    upload.inFlightCalls.values.forEach { it.cancel() }
                }
                emit(event(type, upload.uploadId))
            }
        }
    }

    // endregion

    // region Events

    private fun emitProgress(upload: ActiveUpload, force: Boolean) {
        if (!upload.shouldEmitProgress(force)) return
        emit(
            event(
                "upload-progress",
                upload.uploadId,
                progress = upload.progress,
                bytesUploaded = upload.bytesUploaded.toDouble(),
                totalBytes = upload.totalBytes.toDouble(),
            ),
        )
        if (upload.showNotification) updateNotification(upload, force)
    }

    private fun emit(event: UploadProgressEvent) {
        val targets = listeners[event.type].orEmpty() + listeners["all"].orEmpty()
        if (targets.isEmpty()) return
        mainHandler.post {
            for (callback in targets) {
                try {
                    callback(event)
                } catch (e: Exception) {
                    println("⚠️ Event callback error: ${e.message}")
                }
            }
        }
    }

    private fun event(
        type: String,
        uploadId: String,
        progress: Double? = null,
        bytesUploaded: Double? = null,
        totalBytes: Double? = null,
        chunkIndex: Double? = null,
        errorMessage: String? = null,
    ): UploadProgressEvent = UploadProgressEvent(
        type = type,
        uploadId = uploadId,
        progress = progress,
        bytesUploaded = bytesUploaded,
        totalBytes = totalBytes,
        chunkIndex = chunkIndex,
        errorMessage = errorMessage,
    )

    // endregion

    // region Foreground service / notification

    private fun startForegroundService(upload: ActiveUpload) {
        val ctx = appContext
        if (ctx == null) {
            println("⚠️ No application context; upload runs without a foreground service / notification")
            return
        }
        try {
            UploadForegroundService.start(ctx, upload.uploadId)
            upload.foregroundServiceStarted = true
        } catch (e: Exception) {
            println("⚠️ Failed to start foreground service: ${e.message}")
        }
    }

    private fun updateNotification(upload: ActiveUpload, force: Boolean) {
        if (!upload.foregroundServiceStarted) return
        val percent = (upload.progress * 100).toInt()
        if (!upload.shouldUpdateNotification(percent, force)) return
        val ctx = appContext ?: return
        try {
            UploadForegroundService.updateProgress(ctx, upload.uploadId, percent, "Uploading… $percent%")
        } catch (e: Exception) {
            println("⚠️ Failed to update notification: ${e.message}")
        }
    }

    private fun finishForegroundService(upload: ActiveUpload, terminalMessage: String?, success: Boolean) {
        if (!upload.foregroundServiceStarted) return
        upload.foregroundServiceStarted = false
        val ctx = appContext ?: return
        try {
            UploadForegroundService.finish(ctx, upload.uploadId, terminalMessage, success)
        } catch (e: Exception) {
            println("⚠️ Failed to stop foreground service: ${e.message}")
        }
    }

    // endregion

    private companion object {
        const val DEFAULT_PARALLEL = 3
        const val MAX_ATTEMPTS = 3
        const val RETRY_BASE_DELAY_MS = 1_000L
        const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
    }
}
