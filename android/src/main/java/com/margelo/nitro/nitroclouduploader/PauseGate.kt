package com.margelo.nitro.nitroclouduploader

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Pause switch shared by the upload coroutines and the OkHttp writer threads.
 *
 * Coroutines wait with [awaitResumed] before starting a part. The request body's writer
 * thread calls [blockWhilePaused] between buffer writes, so an in-flight PUT simply stops
 * sending bytes while paused instead of being torn down and restarted from zero.
 */
internal class PauseGate {
    private val lock = ReentrantLock()
    private val resumed = lock.newCondition()
    private val paused = MutableStateFlow(false)

    val isPaused: StateFlow<Boolean> get() = paused

    fun pause() {
        lock.withLock { paused.value = true }
    }

    fun resume() {
        lock.withLock {
            paused.value = false
            resumed.signalAll()
        }
    }

    suspend fun awaitResumed() {
        paused.first { isPaused -> !isPaused }
    }

    /** Blocks the calling (non-coroutine) thread until [resume] is called. */
    fun blockWhilePaused() {
        lock.withLock {
            while (paused.value) {
                resumed.await()
            }
        }
    }
}
