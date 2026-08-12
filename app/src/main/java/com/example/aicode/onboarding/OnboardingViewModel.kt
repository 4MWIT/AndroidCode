package com.example.aicode.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicode.build.AndroidRuntimeInstaller
import com.example.aicode.build.AndroidRuntimeSetupRequest
import com.example.aicode.build.BuildEnvironment
import com.example.aicode.logging.AiCodeLog
import com.example.aicode.settings.AgentRuntimeType
import com.example.aicode.settings.AppSettingsStore
import com.example.aicode.pi.ApiKeyStore
import com.example.aicode.pi.PiRuntimeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private const val ONBOARDING_LAST_STEP_INDEX = 4

data class SetupCheckItem(
    val title: String,
    val ready: Boolean,
    val details: String,
)

data class AndroidRuntimeStatus(
    val jdkReady: Boolean,
    val sdkReady: Boolean,
    val checks: List<SetupCheckItem>,
) {
    val fullyReady: Boolean
        get() = jdkReady && sdkReady && checks.all { it.ready }
}

data class AgentCliInstallUiState(
    val isInstalled: Boolean = false,
    val isInstalling: Boolean = false,
    val progress: Float = 0f,
    val headline: String = "Ещё не скачано",
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

data class QwenAuthUiState(
    val isAuthenticated: Boolean = false,
    val isAuthorizing: Boolean = false,
    val browserUrl: String? = null,
    val userCode: String? = null,
    val headline: String = "Qwen ещё не авторизован",
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

data class RuntimeInstallUiState(
    val isInstalling: Boolean = false,
    val progress: Float = 0f,
    val headline: String = "Android runtime ещё не установлен",
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

data class OnboardingUiState(
    val onboardingCompleted: Boolean = false,
    val currentStep: Int = 0,
    val selectedRuntime: AgentRuntimeType = AgentRuntimeType.PI,
    val canInstallPackages: Boolean = false,
    val agentCli: AgentCliInstallUiState = AgentCliInstallUiState(),
    val qwenAuth: QwenAuthUiState = QwenAuthUiState(),
    val runtimeInstall: RuntimeInstallUiState = RuntimeInstallUiState(),
    val runtimeStatus: AndroidRuntimeStatus = AndroidRuntimeStatus(
        jdkReady = false,
        sdkReady = false,
        checks = emptyList(),
    ),
) {
    val authReady: Boolean
        get() = !selectedRuntime.requiresAuth || qwenAuth.isAuthenticated

    val setupReady: Boolean
        get() = canInstallPackages && agentCli.isInstalled && authReady && runtimeStatus.fullyReady

    val firstBlockingStep: Int
        get() = when {
            !canInstallPackages -> 1
            !agentCli.isInstalled -> 2
            selectedRuntime.requiresAuth && !qwenAuth.isAuthenticated -> 3
            !runtimeStatus.fullyReady -> 4
            else -> ONBOARDING_LAST_STEP_INDEX
        }
}

class OnboardingViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val stateStore = OnboardingStateStore(appContext)
    private val settingsStore = AppSettingsStore(appContext)
    private val piRuntimeManager = PiRuntimeManager(appContext)
    private val apiKeyStore = ApiKeyStore(appContext)
    private val androidRuntimeInstaller = AndroidRuntimeInstaller(appContext)

    private val _uiState = MutableStateFlow(
        OnboardingUiState(
            onboardingCompleted = false,
        ),
    )
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        AiCodeLog.setup("OnboardingViewModel initialized")
        viewModelScope.launch {
            settingsStore.state.collect { settings ->
                _uiState.update {
                    reconcileCompletion(it.copy(selectedRuntime = settings.selectedRuntime))
                }
                refreshAgentCliStatus()
                refreshAuthStatus()
            }
        }
        refreshAll()
    }

    fun refreshAll() {
        BuildEnvironment.init(appContext)
        AiCodeLog.setup("Refreshing onboarding state")
        refreshPermissionStatus()
        refreshAgentCliStatus()
        refreshAuthStatus()
        refreshRuntimeStatus()
    }

    fun setSelectedRuntime(runtimeType: AgentRuntimeType) {
        settingsStore.setSelectedRuntime(runtimeType)
    }

    fun refreshPermissionStatus() {
        val canInstallPackages = appContext.packageManager.canRequestPackageInstalls()
        AiCodeLog.setup("Permission check: canInstallPackages=$canInstallPackages")
        _uiState.update {
            reconcileCompletion(
                it.copy(
                canInstallPackages = canInstallPackages,
                ),
            )
        }
    }

    fun refreshAgentCliStatus() {
        viewModelScope.launch {
            val status = piRuntimeManager.status()
            val installed = status.piReady
            AiCodeLog.setup("Pi install check: installed=$installed")
            _uiState.update {
                reconcileCompletion(
                    it.copy(
                    agentCli = it.agentCli.copy(
                        isInstalled = installed,
                        headline = if (installed) "Pi готов внутри приложения" else it.agentCli.headline,
                    ),
                    ),
                )
            }
        }
    }

    fun refreshRuntimeStatus() {
        BuildEnvironment.init(appContext)

        val javaBinary = BuildEnvironment.defaultJavaBinary
        val sdkDir = BuildEnvironment.androidSdkDir
        val checks = listOf(
            SetupCheckItem(
                title = "JDK",
                ready = javaBinary.exists(),
                details = javaBinary.absolutePath,
            ),
            SetupCheckItem(
                title = "SDK cmdline-tools",
                ready = File(sdkDir, "cmdline-tools/latest").exists(),
                details = File(sdkDir, "cmdline-tools/latest").absolutePath,
            ),
            SetupCheckItem(
                title = "Platform tools",
                ready = File(sdkDir, "platform-tools").exists(),
                details = File(sdkDir, "platform-tools").absolutePath,
            ),
            SetupCheckItem(
                title = "Build tools",
                ready = sdkDir.resolve("build-tools").listFiles()?.any { it.isDirectory } == true,
                details = sdkDir.resolve("build-tools").absolutePath,
            ),
            SetupCheckItem(
                title = "Android platforms",
                ready = sdkDir.resolve("platforms").listFiles()?.any { it.isDirectory } == true,
                details = sdkDir.resolve("platforms").absolutePath,
            ),
        )

        AiCodeLog.setup(
            "Runtime check: java=${javaBinary.exists()} cmdline=${File(sdkDir, "cmdline-tools/latest").exists()} " +
                "platformTools=${File(sdkDir, "platform-tools").exists()} buildTools=${sdkDir.resolve("build-tools").listFiles()?.any { file -> file.isDirectory } == true} " +
                "platforms=${sdkDir.resolve("platforms").listFiles()?.any { file -> file.isDirectory } == true}",
        )
        _uiState.update {
            reconcileCompletion(
                it.copy(
                runtimeStatus = AndroidRuntimeStatus(
                    jdkReady = javaBinary.exists(),
                    sdkReady = checks.drop(1).all { item -> item.ready },
                    checks = checks,
                ),
                runtimeInstall = it.runtimeInstall.copy(
                    headline = if (checks.all { item -> item.ready }) {
                        "Android runtime уже готов"
                    } else {
                        it.runtimeInstall.headline
                    },
                ),
                ),
            )
        }
    }

    fun refreshAuthStatus() {
        viewModelScope.launch {
            _uiState.update {
                reconcileCompletion(
                    it.copy(
                        qwenAuth = it.qwenAuth.copy(
                            isAuthenticated = apiKeyStore.hasKey(),
                            isAuthorizing = false,
                            headline = if (apiKeyStore.hasKey()) "API-ключ Pi сохранён." else "API-ключ можно добавить после onboarding в настройках.",
                            error = null,
                            logs = emptyList(),
                        ),
                    ),
                )
            }
        }
    }

    fun probeAuthStatus() {
        refreshAuthStatus()
    }

    fun startAgentInstall() {
        val runtime = _uiState.value.selectedRuntime
        val current = _uiState.value.agentCli
        if (current.isInstalling || current.isInstalled) {
            AiCodeLog.setup("Skipping ${runtime.displayName} install: installing=${current.isInstalling} installed=${current.isInstalled}")
            return
        }

        AiCodeLog.setup("Starting ${runtime.displayName} install from onboarding")
        _uiState.update {
            reconcileCompletion(
                it.copy(
                agentCli = it.agentCli.copy(
                    isInstalling = true,
                    progress = 0f,
                    error = null,
                    logs = emptyList(),
                    headline = "Скачиваем и распаковываем ${runtime.displayName}…",
                ),
                ),
            )
        }

        viewModelScope.launch {
            val success = runCatching {
                piRuntimeManager.install(
                    onProgress = { progress, message ->
                        _uiState.update { state -> reconcileCompletion(state.copy(agentCli = state.agentCli.copy(
                            isInstalling = true, progress = progress, headline = message,
                            logs = appendLog(state.agentCli.logs, message.trim()),
                        ))) }
                    },
                    onLog = { message -> _uiState.update { state -> reconcileCompletion(state.copy(agentCli = state.agentCli.copy(logs = appendLog(state.agentCli.logs, message)))) } },
                )
            }.getOrElse {
                AiCodeLog.setupError("${runtime.displayName} install failed: ${it.message}", it)
                _uiState.update { state ->
                    reconcileCompletion(
                        state.copy(
                        agentCli = state.agentCli.copy(
                            isInstalling = false,
                            error = it.message ?: "Не удалось установить ${runtime.displayName}",
                            headline = "Установка сорвалась",
                            logs = appendLog(state.agentCli.logs, "Ошибка: ${it.message}"),
                        ),
                        ),
                    )
                }
                return@launch
            }

            refreshAgentCliStatus()
            AiCodeLog.setup("${runtime.displayName} install finished: success=$success")
            _uiState.update { state ->
                val nextState = reconcileCompletion(
                    state.copy(
                        agentCli = state.agentCli.copy(
                            isInstalling = false,
                            isInstalled = success,
                            progress = if (success) 1f else state.agentCli.progress,
                            headline = if (success) "${runtime.displayName} установлен" else "Установка не завершилась",
                            error = if (success) null else "Проверь интернет и попробуй ещё раз",
                            logs = appendLog(
                                state.agentCli.logs,
                                if (success) "Готово: ${runtime.displayName} установлен." else "Установка не завершилась.",
                            ),
                        ),
                    ),
                )
                if (success && nextState.currentStep == 2) {
                    nextState.copy(currentStep = if (runtime.requiresAuth) 3 else 4)
                } else {
                    nextState
                }
            }
        }
    }

    fun startQwenAuth() {
        refreshAuthStatus()
    }

    fun goNext() {
        AiCodeLog.setup("Onboarding next from step=${_uiState.value.currentStep}")
        _uiState.update {
            val stepIncrement = if (it.currentStep == 2 && !it.selectedRuntime.requiresAuth) 2 else 1
            it.copy(currentStep = (it.currentStep + stepIncrement).coerceAtMost(LAST_STEP_INDEX))
        }
    }

    fun goBack() {
        AiCodeLog.setup("Onboarding back from step=${_uiState.value.currentStep}")
        _uiState.update {
            it.copy(currentStep = (it.currentStep - 1).coerceAtLeast(0))
        }
    }

    fun completeOnboarding() {
        AiCodeLog.setup("Attempting to complete onboarding. setupReady=${_uiState.value.setupReady}")
        _uiState.update { current ->
            if (!current.setupReady) {
                AiCodeLog.setupWarn("Onboarding completion blocked. Redirecting to step=${current.firstBlockingStep}")
                reconcileCompletion(
                    current.copy(currentStep = current.firstBlockingStep),
                )
            } else {
                stateStore.markCompleted()
                AiCodeLog.setup("Onboarding completed successfully")
                reconcileCompletion(current)
            }
        }
    }

    fun restartOnboarding() {
        stateStore.reset()
        AiCodeLog.setup("Onboarding reset requested")
        _uiState.update {
            reconcileCompletion(
                it.copy(
                onboardingCompleted = false,
                currentStep = 0,
                ),
            )
        }
        refreshAll()
    }

    fun startRuntimeInstall() {
        val current = _uiState.value.runtimeInstall
        if (current.isInstalling) {
            AiCodeLog.setup("Skipping runtime install: already installing")
            return
        }

        AiCodeLog.setup("Starting Android runtime install")
        _uiState.update {
            reconcileCompletion(
                it.copy(
                runtimeInstall = it.runtimeInstall.copy(
                    isInstalling = true,
                    progress = 0f,
                    error = null,
                    logs = emptyList(),
                    headline = "Подготавливаем Android runtime…",
                ),
                ),
            )
        }

        viewModelScope.launch {
            val success = runCatching {
                androidRuntimeInstaller.install(
                    request = AndroidRuntimeSetupRequest(),
                    onProgress = { progress, message ->
                        AiCodeLog.setup("Runtime progress=${"%.2f".format(progress)} message=$message")
                        _uiState.update { state ->
                            reconcileCompletion(
                                state.copy(
                                runtimeInstall = state.runtimeInstall.copy(
                                    isInstalling = true,
                                    progress = progress.coerceIn(0f, 1f),
                                    headline = message,
                                ),
                                ),
                            )
                        }
                    },
                    onLog = { line ->
                        AiCodeLog.setup("Runtime log: $line")
                        _uiState.update { state ->
                            reconcileCompletion(
                                state.copy(
                                runtimeInstall = state.runtimeInstall.copy(
                                    logs = appendLog(state.runtimeInstall.logs, line),
                                ),
                                ),
                            )
                        }
                    },
                )
            }.getOrElse {
                AiCodeLog.setupError("Runtime install failed: ${it.message}", it)
                _uiState.update { state ->
                    reconcileCompletion(
                        state.copy(
                        runtimeInstall = state.runtimeInstall.copy(
                            isInstalling = false,
                            error = it.message ?: "Не удалось установить Android runtime",
                            headline = "Установка runtime сорвалась",
                            logs = appendLog(state.runtimeInstall.logs, "Ошибка: ${it.message}"),
                        ),
                        ),
                    )
                }
                return@launch
            }

            refreshRuntimeStatus()
            AiCodeLog.setup("Runtime install finished: success=$success")
            _uiState.update { state ->
                reconcileCompletion(
                    state.copy(
                    runtimeInstall = state.runtimeInstall.copy(
                        isInstalling = false,
                        progress = if (success) 1f else state.runtimeInstall.progress,
                        headline = if (success) "Android runtime установлен" else "Установка runtime не завершилась",
                        error = if (success) null else "Проверь лог установки и попробуй ещё раз",
                        logs = appendLog(
                            state.runtimeInstall.logs,
                            if (success) "Готово: Android runtime установлен." else "Установка runtime не завершилась.",
                        ),
                    ),
                    ),
                )
            }
        }
    }

    private fun appendLog(existing: List<String>, line: String): List<String> {
        if (line.isBlank()) return existing
        return (existing + line).takeLast(24)
    }

    private fun reconcileCompletion(state: OnboardingUiState): OnboardingUiState {
        val markerExists = stateStore.isCompleted()
        val completed = markerExists && state.setupReady
        val shouldForceAuthStep =
            state.canInstallPackages &&
                state.agentCli.isInstalled &&
                state.selectedRuntime.requiresAuth &&
                state.runtimeStatus.fullyReady &&
                !state.qwenAuth.isAuthenticated
        val shouldForceRuntimeStep =
            state.canInstallPackages &&
                state.agentCli.isInstalled &&
                state.authReady &&
                !state.runtimeStatus.fullyReady
        val currentStep = when {
            shouldForceAuthStep -> 3
            shouldForceRuntimeStep -> 4
            markerExists && !completed -> state.firstBlockingStep
            else -> state.currentStep.coerceIn(0, LAST_STEP_INDEX)
        }
        val nextState = state.copy(
            onboardingCompleted = completed,
            currentStep = currentStep,
        )
        AiCodeLog.setup(
            "Reconciled onboarding: marker=$markerExists setupReady=${state.setupReady} completed=$completed currentStep=$currentStep",
        )
        return nextState
    }

    companion object {
        const val LAST_STEP_INDEX = ONBOARDING_LAST_STEP_INDEX
    }
}
