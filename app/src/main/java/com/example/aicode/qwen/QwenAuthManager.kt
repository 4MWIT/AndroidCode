package com.example.aicode.qwen

import android.content.Context
import com.example.aicode.acp.AcpCallback
import com.example.aicode.acp.AcpHttpClient
import com.example.aicode.acp.BridgeAuthStatus
import com.example.aicode.logging.AiCodeLog
import com.example.aicode.nodejs.NodejsRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QwenAuthSnapshot(
    val authenticated: Boolean = false,
    val authType: String? = null,
    val message: String = "Qwen ещё не авторизован",
)

class QwenAuthManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val nodejsRuntime = NodejsRuntime(appContext)
    private val stateStore = QwenAuthStateStore(appContext)
    private var callbackScope: CoroutineScope? = null
    private var authClient: AcpHttpClient? = null

    suspend fun getStatus(): QwenAuthSnapshot = withContext(Dispatchers.IO) {
        AiCodeLog.agent("QwenAuthManager.getStatus called (passive)")
        if (!nodejsRuntime.isQwenCliInstalled()) {
            val snapshot = QwenAuthSnapshot(
                authenticated = false,
                authType = null,
                message = "Qwen Code ещё не установлен",
            )
            stateStore.write(snapshot)
            return@withContext snapshot
        }
        stateStore.read()
    }

    suspend fun probeStatus(): QwenAuthSnapshot = withContext(Dispatchers.IO) {
        AiCodeLog.agent("QwenAuthManager.probeStatus called (live bridge check)")
        val ready = ensureBridgeReady(installIfMissing = false)
        if (!ready) {
            val snapshot = QwenAuthSnapshot(
                authenticated = false,
                authType = null,
                message = "Bridge для Qwen пока не готов",
            )
            stateStore.write(snapshot)
            return@withContext snapshot
        }

        val status = authClient?.fetchAuthStatus().toSnapshot()
        stateStore.write(status)
        status
    }

    fun startOAuthLogin(callback: QwenAuthCallback) {
        AiCodeLog.agent("QwenAuthManager.startOAuthLogin called")
        currentCallback = callback
        callbackScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        callbackScope = scope

        scope.launch {
            try {
                if (!ensureBridgeReady()) {
                    withContext(Dispatchers.Main) {
                        callback.onError("Не удалось поднять bridge для авторизации Qwen")
                    }
                    return@launch
                }

                val client = authClient
                if (client == null) {
                    withContext(Dispatchers.Main) {
                        callback.onError("Bridge-клиент авторизации не готов")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    callback.onStatus(stateStore.read())
                    callback.onLog("Запускаю официальный вход Qwen OAuth…")
                }

                val started = client.startQwenOAuth()
                if (!started) {
                    withContext(Dispatchers.Main) {
                        callback.onError("Qwen OAuth не стартовал")
                    }
                }
            } catch (error: Exception) {
                AiCodeLog.agentError("Failed to start Qwen OAuth", error)
                withContext(Dispatchers.Main) {
                    callback.onError(error.message ?: "Ошибка запуска Qwen OAuth")
                }
            }
        }
    }

    fun cancelLogin() {
        callbackScope?.launch {
            runCatching {
                authClient?.cancelAuth()
            }.onFailure { error ->
                AiCodeLog.agentWarn("Failed to cancel Qwen OAuth", error)
            }
        }
    }

    fun clear() {
        callbackScope?.cancel()
        callbackScope = null
        currentCallback = null
        authClient?.stopStreaming()
        authClient = null
        nodejsRuntime.stopBridge()
    }

    private suspend fun ensureBridgeReady(): Boolean {
        return ensureBridgeReady(installIfMissing = true)
    }

    private suspend fun ensureBridgeReady(installIfMissing: Boolean): Boolean {
        if (!nodejsRuntime.isInstalled()) {
            if (!installIfMissing) {
                return false
            }
            val nodeInstalled = nodejsRuntime.install { _, _ -> }
            if (!nodeInstalled) {
                return false
            }
        }
        if (!nodejsRuntime.isQwenCliInstalled()) {
            if (!installIfMissing) {
                return false
            }
            val qwenInstalled = nodejsRuntime.installQwenCli { _, _ -> }
            if (!qwenInstalled) {
                return false
            }
        }

        nodejsRuntime.setProjectRoot(null)
        if (!nodejsRuntime.startBridge { _, _ -> }) {
            return false
        }

        if (authClient == null) {
            authClient = AcpHttpClient(
                baseUrl = nodejsRuntime.getBridgeUrl(),
                scope = callbackScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ).also { client ->
                client.addCallback(
                    object : AcpCallback {
                        override fun onAuthLog(line: String) {
                            callbackScope?.launch(Dispatchers.Main) {
                                currentCallback?.onLog(line)
                            }
                        }

                        override fun onAuthUrl(url: String, userCode: String?) {
                            callbackScope?.launch(Dispatchers.Main) {
                                currentCallback?.onBrowserRequired(url, userCode)
                            }
                        }

                        override fun onAuthCompleted(
                            success: Boolean,
                            authenticated: Boolean,
                            authType: String?,
                            message: String?,
                        ) {
                            callbackScope?.launch(Dispatchers.Main) {
                                val snapshot = QwenAuthSnapshot(
                                    authenticated = authenticated,
                                    authType = authType,
                                    message = message ?: if (success) "Qwen OAuth готов" else "Qwen OAuth не завершился",
                                )
                                stateStore.write(snapshot)
                                currentCallback?.onStatus(snapshot)
                                currentCallback?.onCompleted(snapshot)
                            }
                        }
                    },
                )
                client.startStreaming()
            }
        }

        return true
    }

    @Volatile
    private var currentCallback: QwenAuthCallback? = null

    interface QwenAuthCallback {
        fun onStatus(status: QwenAuthSnapshot) {}
        fun onLog(line: String) {}
        fun onBrowserRequired(url: String, userCode: String?) {}
        fun onCompleted(status: QwenAuthSnapshot) {}
        fun onError(message: String) {}
    }

    private fun BridgeAuthStatus?.toSnapshot(): QwenAuthSnapshot {
        return if (this == null) {
            QwenAuthSnapshot(
                authenticated = false,
                authType = null,
                message = "Не удалось прочитать статус авторизации Qwen",
            )
        } else {
            QwenAuthSnapshot(
                authenticated = authenticated,
                authType = authType,
                message = message.ifBlank {
                    if (authenticated) "Qwen уже авторизован" else "Qwen пока не авторизован"
                },
            )
        }
    }
}
