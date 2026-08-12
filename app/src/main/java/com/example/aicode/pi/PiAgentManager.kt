package com.example.aicode.pi

import android.content.Context
import com.example.aicode.acp.model.FileModification
import com.example.aicode.agent.AgentRunRequest
import com.example.aicode.logging.AiCodeLog
import com.example.aicode.settings.AppSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PiAgentResult(
    val response: String,
    val modifications: List<FileModification>,
)

/** Kotlin-facing adapter for the Pi RPC process. Pi itself keeps ownership of tools and planning. */
class PiAgentManager(context: Context) {
    private val appContext = context.applicationContext
    private val runtime = PiRuntimeManager(appContext)
    private val settingsStore = AppSettingsStore(appContext)
    private val apiKeyStore = ApiKeyStore(appContext)

    suspend fun runPrompt(
        request: AgentRunRequest,
        onProcessing: (String) -> Unit,
        onToken: (String) -> Unit,
        onToolCall: (String, String) -> Unit,
        onFileModifying: (String, String) -> Unit,
        onFileModified: (String, Boolean) -> Unit,
    ): PiAgentResult = suspendCancellableCoroutine { continuation ->
        val response = StringBuilder()
        val before = snapshot(request.projectDir)
        val client = PiHttpClient()
        val cleanedUp = AtomicBoolean(false)
        lateinit var callback: PiCallback
        fun cleanup() {
            if (cleanedUp.compareAndSet(false, true)) {
                client.removeCallback(callback)
                client.stopStreaming()
            }
        }
        callback = object : PiCallback {
            override fun onProcessing(message: String) = onProcessing(message)
            override fun onToolCall(toolName: String, details: String) = onToolCall(toolName, details)
            override fun onToken(token: String) {
                response.append(token)
                onToken(token)
            }
            override fun onFileModifying(filePath: String, fileName: String) = onFileModifying(filePath, fileName)
            override fun onFileModified(filePath: String, success: Boolean) = onFileModified(filePath, success)
            override fun onComplete(unused: String, unusedModifications: List<FileModification>) {
                if (continuation.isActive) {
                    cleanup()
                    continuation.resume(PiAgentResult(response.toString(), collectModifications(request.projectDir, before)))
                }
            }
            override fun onError(message: String) {
                if (continuation.isActive) {
                    cleanup()
                    continuation.resumeWithException(IllegalStateException(message))
                }
            }
        }
        client.addCallback(callback)
        continuation.invokeOnCancellation {
            cleanup()
        }

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsStore.state.value
                val apiKey = apiKeyStore.read(settings.providerId).orEmpty()
                require(apiKey.isNotBlank()) { "Добавь API-ключ в настройках Pi" }
                require(runtime.ensureBridge { onProcessing(it) }) {
                    "Локальный Pi runtime ещё готовится. Подожди завершения подготовки на стартовом экране."
                }
                client.startStreaming()
                val started = client.createSession(
                    PiConnectionConfig(
                        projectDir = request.projectDir,
                        providerId = settings.providerId,
                        modelId = settings.modelId,
                        baseUrl = settings.baseUrl,
                        apiType = settings.apiType,
                        apiKey = apiKey,
                        allowFileWrite = request.allowFileWrite,
                        allowShellCommands = request.allowShellCommands,
                    ),
                )
                check(started) { "Не удалось запустить Pi-сессию" }
                check(client.sendPrompt(request.prompt)) { "Pi не принял задачу" }
            } catch (error: Throwable) {
                AiCodeLog.agentError("Pi prompt failed", error)
                cleanup()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private fun snapshot(projectDir: File): Map<String, Long> = projectDir
        .walkTopDown()
        .filter { it.isFile && !it.invariantSeparatorsPath.contains("/.gradle/") }
        .associate { it.relativeTo(projectDir).invariantSeparatorsPath to it.lastModified() }

    private fun collectModifications(projectDir: File, before: Map<String, Long>): List<FileModification> = projectDir
        .walkTopDown()
        .filter { it.isFile && !it.invariantSeparatorsPath.contains("/.gradle/") }
        .mapNotNull { file ->
            val relative = file.relativeTo(projectDir).invariantSeparatorsPath
            val oldTime = before[relative]
            if (oldTime == null || oldTime != file.lastModified()) {
                FileModification(filePath = relative, operation = if (oldTime == null) "create" else "modify")
            } else null
        }
        .toList()
}
