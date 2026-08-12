package com.example.aicode.acp

import com.example.aicode.acp.model.FileModification
import com.example.aicode.acp.model.PermissionRequestParams
import com.example.aicode.acp.model.PromptContext
import com.example.aicode.logging.AiCodeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

class AcpHttpClient(
    private val baseUrl: String = "http://127.0.0.1:9876",
    private val scope: CoroutineScope,
) {
    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(130, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var eventSource: EventSource? = null
    private val callbacks = mutableListOf<AcpCallback>()

    fun startStreaming() {
        val request = Request.Builder()
            .url("$baseUrl/stream")
            .get()
            .build()

        eventSource = EventSources.createFactory(client).newEventSource(
            request,
            object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    AiCodeLog.agent("ACP SSE stream opened")
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    try {
                        val event = json.decodeFromString(BridgeEvent.serializer(), data)
                        dispatchEvent(event)
                    } catch (error: Exception) {
                        AiCodeLog.agentWarn("Failed to parse SSE event: $data", error)
                    }
                }

                override fun onClosed(eventSource: EventSource) {
                    AiCodeLog.agent("ACP SSE stream closed")
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    AiCodeLog.agentError("ACP SSE stream failure: ${t?.message}", t)
                }
            },
        )
    }

    fun stopStreaming() {
        eventSource?.cancel()
        eventSource = null
    }

    suspend fun initialize(
        projectRoot: String,
        model: String? = null,
        capabilities: List<String> = listOf("fileRead", "fileWrite", "bash", "webSearch"),
    ): Boolean {
        return try {
            val body = json.encodeToString(
                BridgeInitializeRequest(
                    projectRoot = projectRoot,
                    model = model,
                    capabilities = capabilities,
                ),
            ).toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url("$baseUrl/initialize")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (error: Exception) {
            AiCodeLog.agentError("ACP initialize failed", error)
            false
        }
    }

    suspend fun fetchAuthStatus(): BridgeAuthStatus? {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/auth/status")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return null
                }
                val body = response.body?.string().orEmpty()
                json.decodeFromString(BridgeAuthStatus.serializer(), body)
            }
        } catch (error: Exception) {
            AiCodeLog.agentError("Failed to fetch Qwen auth status", error)
            null
        }
    }

    suspend fun startQwenOAuth(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/auth/qwen-oauth/start")
                .post(ByteArray(0).toRequestBody())
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (error: Exception) {
            AiCodeLog.agentError("Failed to start Qwen OAuth", error)
            false
        }
    }

    suspend fun cancelAuth(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/auth/cancel")
                .post(ByteArray(0).toRequestBody())
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (error: Exception) {
            AiCodeLog.agentError("Failed to cancel Qwen auth", error)
            false
        }
    }

    suspend fun sendPrompt(
        prompt: String,
        context: PromptContext? = null,
        writeEnabled: Boolean = true,
    ): Boolean {
        return try {
            val body = json.encodeToString(
                BridgePromptRequest(
                    prompt = prompt,
                    context = context,
                    writeEnabled = writeEnabled,
                ),
            ).toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url("$baseUrl/prompt")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (error: Exception) {
            AiCodeLog.agentError("ACP send prompt failed", error)
            dispatchError("Failed to send prompt: ${error.message}")
            false
        }
    }

    suspend fun cancel() {
        try {
            val request = Request.Builder()
                .url("$baseUrl/cancel")
                .post(ByteArray(0).toRequestBody())
                .build()
            client.newCall(request).execute().close()
        } catch (error: Exception) {
            AiCodeLog.agentError("ACP cancel failed", error)
        }
    }

    suspend fun respondToPermission(requestId: Long, granted: Boolean): Boolean {
        return try {
            val body = json.encodeToString(
                BridgePermissionResponse(id = requestId, granted = granted),
            ).toRequestBody(JSON_MEDIA)

            val request = Request.Builder()
                .url("$baseUrl/permission")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (error: Exception) {
            AiCodeLog.agentError("ACP permission response failed", error)
            false
        }
    }

    fun addCallback(callback: AcpCallback) {
        synchronized(callbacks) {
            callbacks.add(callback)
        }
    }

    fun removeCallback(callback: AcpCallback) {
        synchronized(callbacks) {
            callbacks.remove(callback)
        }
    }

    private fun dispatchEvent(event: BridgeEvent) {
        val dataStr = when (val data = event.data) {
            is kotlinx.serialization.json.JsonPrimitive -> data.content
            is kotlinx.serialization.json.JsonObject -> json.encodeToString(data)
            null -> null
            else -> data.toString()
        }

        when (event.type) {
            "processing", "info" -> dispatchProcessing(dataStr ?: "Processing...")
            "token", "raw_output" -> dispatchToken(dataStr ?: "")
            "complete" -> {
                val parsed = dataStr?.let {
                    runCatching {
                        json.decodeFromString(CompleteData.serializer(), it)
                    }.getOrNull()
                }
                dispatchComplete(
                    response = parsed?.response ?: dataStr ?: "Done",
                    modifications = parsed?.modifications.orEmpty(),
                )
            }
            "error", "stderr" -> dispatchError(dataStr ?: "Unknown error")
            "qwen_exit" -> dispatchError("Qwen process exited with code: $dataStr")
            "permission_request" -> {
                dataStr?.let {
                    runCatching {
                        json.decodeFromString(PermissionRequestData.serializer(), it)
                    }.onSuccess { parsed ->
                        dispatchPermissionRequest(
                            PermissionRequestParams(
                                id = parsed.id,
                                type = parsed.type,
                                description = parsed.description,
                            ),
                        )
                    }
                }
            }
            "file_modify" -> {
                dataStr?.let {
                    runCatching {
                        json.decodeFromString(FileModifyData.serializer(), it)
                    }.onSuccess { parsed ->
                        dispatchFileModifying(parsed.filePath, parsed.fileName)
                    }
                }
            }
            "file_modified" -> {
                dataStr?.let {
                    runCatching {
                        json.decodeFromString(FileModifiedData.serializer(), it)
                    }.onSuccess { parsed ->
                        dispatchFileModified(parsed.filePath, parsed.success)
                    }
                }
            }
            "auth_log" -> dispatchAuthLog(dataStr ?: "")
            "auth_url" -> {
                dataStr?.let {
                    runCatching {
                        json.decodeFromString(AuthUrlData.serializer(), it)
                    }.onSuccess { parsed ->
                        dispatchAuthUrl(parsed.url, parsed.userCode)
                    }
                }
            }
            "auth_complete" -> {
                dataStr?.let {
                    runCatching {
                        json.decodeFromString(AuthCompleteData.serializer(), it)
                    }.onSuccess { parsed ->
                        dispatchAuthCompleted(
                            success = parsed.success,
                            authenticated = parsed.authenticated,
                            authType = parsed.authType,
                            message = parsed.message,
                        )
                    }
                }
            }
        }
    }

    private fun dispatchProcessing(message: String) {
        synchronized(callbacks) { callbacks.forEach { it.onProcessing(message) } }
    }

    private fun dispatchToken(token: String) {
        synchronized(callbacks) { callbacks.forEach { it.onToken(token) } }
    }

    private fun dispatchComplete(response: String, modifications: List<FileModification>) {
        synchronized(callbacks) { callbacks.forEach { it.onComplete(response, modifications) } }
    }

    private fun dispatchError(message: String) {
        synchronized(callbacks) { callbacks.forEach { it.onError(message) } }
    }

    private fun dispatchPermissionRequest(request: PermissionRequestParams) {
        synchronized(callbacks) { callbacks.forEach { it.onPermissionRequest(request) } }
    }

    private fun dispatchFileModifying(filePath: String, fileName: String) {
        synchronized(callbacks) { callbacks.forEach { it.onFileModifying(filePath, fileName) } }
    }

    private fun dispatchFileModified(filePath: String, success: Boolean) {
        synchronized(callbacks) { callbacks.forEach { it.onFileModified(filePath, success) } }
    }

    private fun dispatchAuthLog(line: String) {
        synchronized(callbacks) { callbacks.forEach { it.onAuthLog(line) } }
    }

    private fun dispatchAuthUrl(url: String, userCode: String?) {
        synchronized(callbacks) { callbacks.forEach { it.onAuthUrl(url, userCode) } }
    }

    private fun dispatchAuthCompleted(
        success: Boolean,
        authenticated: Boolean,
        authType: String?,
        message: String?,
    ) {
        synchronized(callbacks) {
            callbacks.forEach {
                it.onAuthCompleted(
                    success = success,
                    authenticated = authenticated,
                    authType = authType,
                    message = message,
                )
            }
        }
    }
}

@Serializable
data class BridgeEvent(
    val type: String,
    val data: kotlinx.serialization.json.JsonElement? = null,
    val timestamp: Long = 0,
)

@Serializable
data class PermissionRequestData(
    val id: Long,
    val type: String,
    val description: String,
)

@Serializable
data class FileModifyData(
    val filePath: String,
    val fileName: String,
    val operation: String = "modify",
)

@Serializable
data class FileModifiedData(
    val filePath: String,
    val success: Boolean,
    val error: String? = null,
)

@Serializable
data class BridgePromptRequest(
    val prompt: String,
    val context: PromptContext? = null,
    val writeEnabled: Boolean = true,
)

@Serializable
data class BridgeInitializeRequest(
    val projectRoot: String,
    val model: String? = null,
    val capabilities: List<String> = listOf("fileRead", "fileWrite", "bash", "webSearch"),
)

@Serializable
data class BridgePermissionResponse(
    val id: Long,
    val granted: Boolean,
)

@Serializable
data class BridgeAuthStatus(
    val authenticated: Boolean = false,
    val authType: String? = null,
    val message: String = "",
)

@Serializable
data class AuthUrlData(
    val url: String,
    val userCode: String? = null,
)

@Serializable
data class AuthCompleteData(
    val success: Boolean,
    val authenticated: Boolean,
    val authType: String? = null,
    val message: String? = null,
)

@Serializable
data class CompleteData(
    val response: String,
    val modifications: List<FileModification> = emptyList(),
)
