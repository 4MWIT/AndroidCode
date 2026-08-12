package com.example.aicode.qwen

import android.content.Context
import com.example.aicode.acp.AcpCallback
import com.example.aicode.acp.AcpHttpClient
import com.example.aicode.acp.model.FileModification
import com.example.aicode.acp.model.PermissionRequestParams
import com.example.aicode.acp.model.PromptContext
import com.example.aicode.logging.AiCodeLog
import com.example.aicode.nodejs.NodejsRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class QwenAcpManager(private val context: Context) {

    private val nodejsRuntime = NodejsRuntime(context)
    private var acpHttpClient: AcpHttpClient? = null
    private var currentScope: CoroutineScope? = null
    private var currentCallback: QwenAcpCallback? = null
    private var projectRoot: File? = null

    @Volatile
    private var isRunningInternal = false
    val isRunning: Boolean
        get() = isRunningInternal

    fun setProjectRoot(path: String) {
        projectRoot = File(path)
        nodejsRuntime.setProjectRoot(projectRoot)
        AiCodeLog.agent("QwenAcpManager project root set: $path")
    }

    fun executeRequest(
        prompt: String,
        callback: QwenAcpCallback,
        promptContext: PromptContext? = null,
        allowFileWrite: Boolean = true,
        allowShellCommands: Boolean = true,
    ) {
        currentCallback = callback
        currentScope?.cancel()

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        currentScope = scope

        scope.launch {
            try {
                AiCodeLog.agent(
                    "Qwen request started. allowFileWrite=$allowFileWrite allowShellCommands=$allowShellCommands promptLength=${prompt.length}",
                )
                if (!nodejsRuntime.isInstalled()) {
                    withContext(Dispatchers.Main) { callback.onProcessing("Установка Node.js...") }
                    val ok = nodejsRuntime.install { _, _ -> }
                    if (!ok) {
                        withContext(Dispatchers.Main) { callback.onError("Не удалось установить Node.js") }
                        return@launch
                    }
                }

                if (!nodejsRuntime.isQwenCliInstalled()) {
                    withContext(Dispatchers.Main) { callback.onProcessing("Установка Qwen CLI...") }
                    val ok = nodejsRuntime.installQwenCli { _, _ -> }
                    if (!ok) {
                        withContext(Dispatchers.Main) { callback.onError("Не удалось установить Qwen CLI") }
                        return@launch
                    }
                }

                nodejsRuntime.setProjectRoot(projectRoot)
                withContext(Dispatchers.Main) { callback.onProcessing("Запуск bridge сервера...") }
                val bridgeStarted = nodejsRuntime.startBridge { _, _ -> }
                if (!bridgeStarted) {
                    withContext(Dispatchers.Main) { callback.onError("Не удалось запустить bridge сервер") }
                    cleanup()
                    return@launch
                }

                val httpClient = AcpHttpClient(
                    baseUrl = nodejsRuntime.getBridgeUrl(),
                    scope = scope,
                )
                acpHttpClient = httpClient

                httpClient.addCallback(object : AcpCallback {
                    override fun onProcessing(message: String) {
                        currentCallback?.onProcessing(message)
                    }

                    override fun onToken(token: String) {
                        currentCallback?.onToken(token)
                    }

                    override fun onComplete(response: String, modifications: List<FileModification>) {
                        isRunningInternal = false
                        currentCallback?.onComplete(response, modifications)
                    }

                    override fun onError(message: String) {
                        isRunningInternal = false
                        currentCallback?.onError(message)
                    }

                    override fun onPermissionRequest(request: PermissionRequestParams) {
                        when (request.type) {
                            "fileRead", "webSearch" -> {
                                scope.launch { httpClient.respondToPermission(request.id, true) }
                            }
                            "fileWrite" -> {
                                if (allowFileWrite) {
                                    scope.launch { httpClient.respondToPermission(request.id, true) }
                                } else {
                                    currentCallback?.onPermissionRequest(request)
                                }
                            }
                            "bash" -> {
                                if (allowShellCommands) {
                                    scope.launch { httpClient.respondToPermission(request.id, true) }
                                } else {
                                    currentCallback?.onPermissionRequest(request)
                                }
                            }
                            else -> currentCallback?.onPermissionRequest(request)
                        }
                    }

                    override fun onFileModifying(filePath: String, fileName: String) {
                        currentCallback?.onFileModifying(filePath, fileName)
                    }

                    override fun onFileModified(filePath: String, success: Boolean) {
                        currentCallback?.onFileModified(filePath, success)
                    }
                })

                httpClient.startStreaming()
                val root = (projectRoot ?: context.filesDir).absolutePath
                val initSuccess = httpClient.initialize(
                    projectRoot = root,
                    capabilities = buildList {
                        add("fileRead")
                        add("webSearch")
                        if (allowFileWrite) {
                            add("fileWrite")
                        }
                        if (allowShellCommands) {
                            add("bash")
                        }
                    },
                )
                if (!initSuccess) {
                    AiCodeLog.agentError("ACP initialize failed for root=$root")
                    callback.onError("Не удалось инициализировать ACP сессию")
                    cleanup()
                    return@launch
                }

                isRunningInternal = true
                callback.onProcessing("Отправляю задачу...")
                val sent = httpClient.sendPrompt(
                    prompt = prompt,
                    context = promptContext,
                    writeEnabled = allowFileWrite || allowShellCommands,
                )
                if (!sent) {
                    isRunningInternal = false
                    AiCodeLog.agentError("Failed to send prompt to Qwen CLI")
                    callback.onError("Не удалось отправить задачу в Qwen CLI")
                    cleanup()
                }
            } catch (error: Exception) {
                AiCodeLog.agentError("Exception executing Qwen request", error)
                callback.onError("Ошибка: ${error.message}")
                cleanup()
            }
        }
    }

    fun clear() {
        cleanup()
    }

    fun stop() {
        if (!isRunningInternal) {
            return
        }

        currentScope?.launch {
            try {
                acpHttpClient?.cancel()
            } catch (error: Exception) {
                AiCodeLog.agentError("Failed to cancel ACP request", error)
            }
        }
        cleanup()
    }

    fun respondToPermission(requestId: Long, granted: Boolean) {
        currentScope?.launch {
            try {
                acpHttpClient?.respondToPermission(requestId, granted)
            } catch (error: Exception) {
                AiCodeLog.agentError("Failed to respond to ACP permission requestId=$requestId granted=$granted", error)
            }
        }
    }

    private fun cleanup() {
        AiCodeLog.agent("Cleaning up Qwen ACP session")
        isRunningInternal = false
        acpHttpClient?.stopStreaming()
        acpHttpClient = null
        currentScope?.cancel()
        currentScope = null
        nodejsRuntime.stopBridge()
    }

    interface QwenAcpCallback {
        fun onProcessing(message: String) {}
        fun onToken(token: String) {}
        fun onComplete(response: String, modifications: List<FileModification>) {}
        fun onError(message: String) {}
        fun onPermissionRequest(request: PermissionRequestParams) {}
        fun onFileModifying(filePath: String, fileName: String) {}
        fun onFileModified(filePath: String, success: Boolean) {}
    }
}
