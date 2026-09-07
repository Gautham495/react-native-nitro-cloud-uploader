package com.margelo.nitro.nitroclouduploader

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import okhttp3.Call

/**
 * Mutable bookkeeping for one in-progress upload.
 *
 * `bytesUploaded` = bytes of parts the server has acknowledged + bytes written so far for
 * parts still in flight, so progress moves at buffer granularity (64 KB) for a 300 KB file
 * and a 3 GB file alike. The two throttles keep that granularity from turning into a flood
 * of JS callbacks (max ~20/s) or notification updates (max ~2/s, and only when the integer
 * percentage changed — Android drops notifications posted faster than ~5/s).
 */
internal class ActiveUpload(
    val uploadId: String,
    val file: File,
    val parts: List<UploadPart>,
    val showNotification: Boolean,
) {
    val totalBytes: Long = file.length()
    val pauseGate = PauseGate()

    /** partNumber -> ETag of every acknowledged part. */
    val etags = ConcurrentHashMap<Int, String>()

    /** partNumber -> the PUT currently on the wire; cancelled on network loss / cancel. */
    val inFlightCalls = ConcurrentHashMap<Int, Call>()

    lateinit var job: Job

    @Volatile
    var foregroundServiceStarted = false

    private val inFlightBytes = ConcurrentHashMap<Int, Long>()
    private val completedBytes = AtomicLong(0)
    private val lastProgressEmitNanos = AtomicLong(System.nanoTime() - PROGRESS_INTERVAL_NANOS)
    private val lastNotificationNanos = AtomicLong(System.nanoTime() - NOTIFICATION_INTERVAL_NANOS)

    @Volatile
    private var lastNotifiedPercent = -1

    val bytesUploaded: Long
        get() = (completedBytes.get() + inFlightBytes.values.sum()).coerceAtMost(totalBytes)

    val progress: Double
        get() = if (totalBytes > 0) bytesUploaded.toDouble() / totalBytes else 0.0

    val isMultipart: Boolean get() = parts.size > 1

    fun updateInFlight(partNumber: Int, bytesSent: Long) {
        inFlightBytes[partNumber] = bytesSent
    }

    fun resetInFlight(partNumber: Int) {
        inFlightBytes.remove(partNumber)
    }

    fun markCompleted(part: UploadPart, etag: String) {
        inFlightBytes.remove(part.partNumber)
        if (etags.put(part.partNumber, etag) == null) {
            completedBytes.addAndGet(part.size)
        }
    }

    /** ETags in part order; "" only for a single-URL upload whose server sent none. */
    fun etagsInPartOrder(): Array<String> = parts.map { etags[it.partNumber] ?: "" }.toTypedArray()

    /** Rate-limits progress events to ~20 Hz. Forced emits (part completion) always pass. */
    fun shouldEmitProgress(force: Boolean): Boolean {
        val now = System.nanoTime()
        while (true) {
            val last = lastProgressEmitNanos.get()
            if (!force && now - last < PROGRESS_INTERVAL_NANOS) return false
            if (lastProgressEmitNanos.compareAndSet(last, now)) return true
        }
    }

    fun shouldUpdateNotification(percent: Int, force: Boolean): Boolean {
        val now = System.nanoTime()
        if (!force) {
            if (percent == lastNotifiedPercent) return false
            if (now - lastNotificationNanos.get() < NOTIFICATION_INTERVAL_NANOS) return false
        }
        lastNotifiedPercent = percent
        lastNotificationNanos.set(now)
        return true
    }

    private companion object {
        const val PROGRESS_INTERVAL_NANOS = 50_000_000L // 20 Hz
        const val NOTIFICATION_INTERVAL_NANOS = 500_000_000L // 2 Hz
    }
}
