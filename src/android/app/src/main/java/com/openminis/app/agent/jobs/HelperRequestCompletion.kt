package com.openminis.app.agent.jobs

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One accepted prompt, including setup, compaction and queued delivery. Idle is not completion. */
class HelperRequestCompletion {
    enum class Result { PENDING, COMPLETE, FAILED, CANCELLED }
    val id: Long = sequence.incrementAndGet()
    private val _result = MutableStateFlow(Result.PENDING)
    val result: StateFlow<Result> = _result
    @Volatile private var owner: Job? = null

    @Synchronized
    fun bind(runner: Job) {
        if (_result.value != Result.PENDING) {
            runner.cancel()
            return
        }
        owner = runner
        runner.invokeOnCompletion { cause ->
            synchronized(this) {
                if (owner === runner) {
                    finish(when (cause) {
                        null -> Result.COMPLETE
                        is java.util.concurrent.CancellationException -> Result.CANCELLED
                        else -> Result.FAILED
                    })
                }
            }
        }
    }

    /** Compaction can hand off to a queue while another stream owns the VM. */
    @Synchronized
    fun parkForDelivery() {
        if (_result.value == Result.PENDING) owner = null
    }

    @Synchronized
    fun finish(result: Result) {
        require(result != Result.PENDING)
        if (_result.value == Result.PENDING) _result.value = result
    }

    @Synchronized
    fun cancel() {
        finish(Result.CANCELLED)
        owner?.cancel()
    }

    companion object { private val sequence = AtomicLong() }
}
