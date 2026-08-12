package com.example.aicode.pi

import com.example.aicode.acp.model.FileModification
import com.example.aicode.logging.AiCodeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit

data class PiConnectionConfig(
    val projectDir: File,
    val providerId: String,
    val modelId: String,
    val baseUrl: String,
    val apiType: String,
    val apiKey: String,
    val allowFileWrite: Boolean,
    val allowShellCommands: Boolean,
)

interface PiCallback {
    fun onProcessing(message: String) {}
    fun onToken(token: String) {}
    fun onToolCall(toolName: String, details: String) {}
    fun onFileModifying(filePath: String, fileName: String) {}
    fun onFileModified(filePath: String, success: Boolean) {}
    fun onComplete(response: String, modifications: List<FileModification>) {}
    fun onError(message: String) {}
}

class PiHttpClient {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val callbacks = CopyOnWriteArraySet<PiCallback>()
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var eventSource: EventSource? = null
    private val baseUrl = "http://127.0.0.1:${PiRuntimeManager.BRIDGE_PORT}"

    fun addCallback(callback: PiCallback) { callbacks += callback }
    fun removeCallback(callback: PiCallback) { callbacks -= callback }

    fun startStreaming() {
        if (eventSource != null) return
        val request = Request.Builder().url("$baseUrl/events").build()
        eventSource = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) = Unit

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                dispatchRawEvent(data)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                dispatchError(t?.message ?: "Соединение с Pi потеряно")
            }
        })
    }

    fun stopStreaming() {
        eventSource?.cancel()
        eventSource = null
    }

    suspend fun createSession(config: PiConnectionConfig): Boolean = post("/session", PiSessionRequest(
        projectDir = config.projectDir.absolutePath,
        providerId = config.providerId,
        modelId = config.modelId,
        baseUrl = config.baseUrl,
        apiType = config.apiType,
        apiKey = config.apiKey,
        allowFileWrite = config.allowFileWrite,
        allowShellCommands = config.allowShellCommands,
    ))

    suspend fun sendPrompt(text: String, streamingBehavior: String? = null): Boolean = post(
        "/prompt",
        PiPromptRequest(text, streamingBehavior),
    )

    suspend fun abort(): Boolean = post("/abort", EmptyRequest())

    private suspend inline fun <reified T> post(path: String, payload: T): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(baseUrl + path)
                .post(json.encodeToString(payload).toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        }.onFailure { AiCodeLog.agentError("Pi request $path failed", it) }.getOrDefault(false)
    }

    private fun dispatchRawEvent(raw: String) {
        val event = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse {
            dispatchProcessing(raw)
            return
        }
        when (event.string("type")) {
            "agent_start" -> Unit
            "agent_settled" -> callbacks.forEach { it.onComplete("", emptyList()) }
            "message_update" -> {
                val delta = event["assistantMessageEvent"]?.jsonObject
                if (delta?.string("type") == "text_delta") dispatchToken(delta.string("delta").orEmpty())
            }
            "tool_execution_start" -> {
                val tool = event.string("toolName").orEmpty()
                val args = event["args"]?.jsonObject
                val path = args?.string("path") ?: args?.string("filePath") ?: ""
                val details = path.ifBlank { args?.string("command").orEmpty() }
                callbacks.forEach { it.onToolCall(tool, details) }
                when (tool) {
                    "write", "edit" -> callbacks.forEach { it.onFileModifying(path, path.substringAfterLast('/')) }
                    else -> Unit
                }
            }
            "tool_execution_end" -> {
                val tool = event.string("toolName").orEmpty()
                if (tool == "write" || tool == "edit") {
                    callbacks.forEach { it.onFileModified("", event["isError"]?.jsonPrimitive?.content != "true") }
                }
            }
            "extension_error" -> dispatchError(event.string("error") ?: "Ошибка Pi extension")
            "bridge_error" -> dispatchError(event.string("message") ?: "Pi bridge завершился с ошибкой")
            "bridge_exit" -> dispatchError("Pi завершился раньше окончания задачи")
            "bridge_log" -> Unit
            "error" -> dispatchError(event.string("message") ?: raw)
        }
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun dispatchProcessing(message: String) = callbacks.forEach { it.onProcessing(message) }
    private fun dispatchToken(token: String) = callbacks.forEach { it.onToken(token) }
    private fun dispatchError(message: String) = callbacks.forEach { it.onError(message) }
}

@Serializable private data class PiSessionRequest(
    val projectDir: String,
    val providerId: String,
    val modelId: String,
    val baseUrl: String,
    val apiType: String,
    val apiKey: String,
    val allowFileWrite: Boolean,
    val allowShellCommands: Boolean,
)
@Serializable private data class PiPromptRequest(val message: String, val streamingBehavior: String? = null)
@Serializable private class EmptyRequest
