package com.margelo.nitro.nitroclouduploader

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Executes the call asynchronously and suspends until the response arrives.
 * Cancelling the coroutine cancels the HTTP call, which closes the socket and
 * aborts the body write — the previous blocking `execute()` could not be interrupted.
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isActive) return
            continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { this@await.cancel() }
}
