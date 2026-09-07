package com.margelo.nitro.nitroclouduploader

import java.io.IOException

/**
 * Non-2xx response for a part upload.
 *
 * Transient statuses are retried with backoff. Anything else (403 SignatureDoesNotMatch,
 * 400 bad request, 404, ...) fails the upload immediately so a wrong presigned URL or a
 * Content-Type that doesn't match the signature is reported on the first attempt instead
 * of after three identical failures.
 */
internal class UploadHttpException(
    val statusCode: Int,
    private val body: String,
) : IOException("HTTP $statusCode: ${body.ifBlank { "<empty body>" }.take(500)}") {

    val isRetryable: Boolean
        get() = statusCode == 408 ||
            statusCode == 429 ||
            statusCode >= 500 ||
            // S3 answers a stalled body with HTTP 400 <Code>RequestTimeout</Code>.
            (statusCode == 400 && body.contains("RequestTimeout"))
}
