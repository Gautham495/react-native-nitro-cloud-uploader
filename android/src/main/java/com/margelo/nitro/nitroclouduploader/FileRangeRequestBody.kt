package com.margelo.nitro.nitroclouduploader

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

/**
 * Streams [size] bytes starting at [offset] of [file] into the request.
 *
 * Reads in fixed-size buffers, so a part is never held in memory in full. The cumulative
 * number of bytes handed to the socket is reported through [onBytesWritten] after every
 * buffer; that is what makes progress move continuously instead of once per finished part.
 * The count restarts at 0 on every [writeTo] because OkHttp may replay the body on a
 * transparent retry. [pauseGate] is consulted between writes so a paused upload stops
 * sending mid-part.
 */
internal class FileRangeRequestBody(
    private val file: File,
    private val offset: Long,
    private val size: Long,
    private val mediaType: MediaType?,
    private val pauseGate: PauseGate,
    private val onBytesWritten: (Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = size

    override fun writeTo(sink: BufferedSink) {
        onBytesWritten(0)
        RandomAccessFile(file, "r").use { raf ->
            if (offset + size > raf.length()) {
                throw IOException(
                    "Invalid part range: offset=$offset size=$size fileLength=${raf.length()}",
                )
            }
            raf.seek(offset)
            val buffer = ByteArray(BUFFER_SIZE)
            var written = 0L
            while (written < size) {
                pauseGate.blockWhilePaused()
                val toRead = minOf(buffer.size.toLong(), size - written).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read <= 0) {
                    throw IOException("Unexpected EOF at offset ${offset + written}")
                }
                sink.write(buffer, 0, read)
                written += read
                onBytesWritten(written)
            }
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
