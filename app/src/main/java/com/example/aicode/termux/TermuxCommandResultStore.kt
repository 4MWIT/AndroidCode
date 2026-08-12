package com.example.aicode.termux

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

data class TermuxCommandResult(
    val executionId: Int,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val errCode: Int,
    val errMsg: String,
) {
    val success: Boolean
        get() = errCode == -1 && exitCode == 0
}

object TermuxCommandResultStore {
    private val nextId = AtomicInteger(1000)
    private val pendingResults = ConcurrentHashMap<Int, CompletableDeferred<TermuxCommandResult>>()

    fun createRequest(): Pair<Int, CompletableDeferred<TermuxCommandResult>> {
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<TermuxCommandResult>()
        pendingResults[id] = deferred
        return id to deferred
    }

    fun complete(result: TermuxCommandResult) {
        pendingResults.remove(result.executionId)?.complete(result)
    }

    fun fail(executionId: Int, message: String) {
        pendingResults.remove(executionId)?.complete(
            TermuxCommandResult(
                executionId = executionId,
                stdout = "",
                stderr = "",
                exitCode = -1,
                errCode = -1,
                errMsg = message,
            ),
        )
    }
}
