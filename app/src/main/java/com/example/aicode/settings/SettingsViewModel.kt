package com.example.aicode.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicode.build.BuildEnvironment
import com.example.aicode.build.AndroidRuntimeInstaller
import com.example.aicode.onboarding.OnboardingStateStore
import com.example.aicode.pi.ApiKeyStore
import com.example.aicode.pi.PiRuntimeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class EnvironmentStatusItem(
    val title: String,
    val ready: Boolean,
    val details: String,
    val updateAvailable: Boolean = false,
    val actionId: String? = null,
)

data class EnvironmentSummary(
    val selectedRuntime: AgentRuntimeType = AgentRuntimeType.PI,
    val selectedRuntimeInstalled: Boolean = false,
    val selectedRuntimeAuthenticated: Boolean = true,
    val selectedRuntimeAuthMessage: String = "",
    val runtimeReady: Boolean = false,
    val gitReady: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val runtimeRoot: String = "",
    val projectsRoot: String = "",
    val sdkRoot: String = "",
    val javaBinary: String = "",
    val gitBinary: String = "",
    val checks: List<EnvironmentStatusItem> = emptyList(),
)

data class SettingsUiState(
    val settings: AppSettingsState = AppSettingsState(),
    val environment: EnvironmentSummary = EnvironmentSummary(),
    val apiKeyConfigured: Boolean = false,
    val piInstallInProgress: Boolean = false,
    val piInstallMessage: String = "",
)

class SettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val store = AppSettingsStore(appContext)
    private val onboardingStateStore = OnboardingStateStore(appContext)
    private val piRuntimeManager = PiRuntimeManager(appContext)
    private val runtimeInstaller = AndroidRuntimeInstaller(appContext)
    private val apiKeyStore = ApiKeyStore(appContext)
    private val _environment = MutableStateFlow(EnvironmentSummary())
    private val _uiState = MutableStateFlow(SettingsUiState())

    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.state.collect { settings ->
                _uiState.update { current ->
                    current.copy(settings = settings, apiKeyConfigured = apiKeyStore.hasKey(settings.providerId))
                }
            }
        }
        viewModelScope.launch {
            _environment.collect { environment ->
                _uiState.update { current ->
                    current.copy(environment = environment)
                }
            }
        }
        refreshEnvironment()
    }

    fun setAutoBuild(value: Boolean) = store.setAutoBuild(value)
    fun setAutoInstall(value: Boolean) = store.setAutoInstall(value)
    fun setAutoLaunch(value: Boolean) = store.setAutoLaunch(value)
    fun setSelectedRuntime(value: AgentRuntimeType) = store.setSelectedRuntime(value)
    fun setAllowAgentWrite(value: Boolean) = store.setAllowAgentWrite(value)
    fun setAllowAgentShell(value: Boolean) = store.setAllowAgentShell(value)
    fun setShowVerboseLogs(value: Boolean) = store.setShowVerboseLogs(value)
    fun setProviderId(value: String) = store.setProviderId(value)
    fun setModelId(value: String) = store.setModelId(value)
    fun setBaseUrl(value: String) = store.setBaseUrl(value)
    fun setApiType(value: String) = store.setApiType(value)
    fun useModelPreset(preset: PiModelPreset) = store.useModelPreset(preset)

    fun saveApiKey(value: String) {
        val providerId = _uiState.value.settings.providerId
        apiKeyStore.save(value, providerId)
        _uiState.update { it.copy(apiKeyConfigured = apiKeyStore.hasKey(providerId)) }
    }

    fun installPi() {
        viewModelScope.launch {
            _uiState.update { it.copy(piInstallInProgress = true, piInstallMessage = "Начинаю установку Pi…") }
            val ready = piRuntimeManager.install(
                onProgress = { _, message -> _uiState.update { it.copy(piInstallMessage = message) } },
                onLog = { message -> _uiState.update { it.copy(piInstallMessage = message) } },
            )
            _uiState.update {
                it.copy(
                    piInstallInProgress = false,
                    piInstallMessage = if (ready) "Pi установлен" else "Установка Pi не завершилась — открой логи и повтори.",
                )
            }
            refreshEnvironment()
        }
    }

    fun installToolchain() {
        viewModelScope.launch {
            _uiState.update { it.copy(piInstallInProgress = true, piInstallMessage = "Устанавливаю SDK и инструменты…") }
            val ready = runtimeInstaller.install(
                onProgress = { _, message -> _uiState.update { it.copy(piInstallMessage = message) } },
                onLog = { message -> _uiState.update { it.copy(piInstallMessage = message) } },
            )
            _uiState.update {
                it.copy(
                    piInstallInProgress = false,
                    piInstallMessage = if (ready) "Инструменты установлены" else "Не удалось завершить установку инструментов",
                )
            }
            refreshEnvironment()
        }
    }

    fun resetOnboarding() {
        onboardingStateStore.reset()
        refreshEnvironment()
    }

    fun refreshEnvironment() {
        viewModelScope.launch {
            BuildEnvironment.init(appContext)
            val sdkDir = BuildEnvironment.androidSdkDir
            val javaBinary = BuildEnvironment.defaultJavaBinary
            val gitBinary = File(BuildEnvironment.binDir, "git")
            val selectedRuntime = AgentRuntimeType.PI
            val piStatus = piRuntimeManager.status()
            val runtimeInstalled = piStatus.piReady
            val runtimeAuthReady = apiKeyStore.hasKey(store.state.value.providerId)
            val runtimeAuthMessage = if (runtimeAuthReady) "API-ключ сохранён в защищённом хранилище Android." else "Добавь API-ключ ниже."
            val sdkReady = File(sdkDir, "cmdline-tools/latest").exists() &&
                sdkDir.resolve("build-tools").listFiles()?.any { it.isDirectory } == true &&
                sdkDir.resolve("platforms").listFiles()?.any { it.isDirectory } == true
            val checks = listOf(
                EnvironmentStatusItem(
                    title = "Shell runtime",
                    ready = piStatus.bootstrapReady,
                    details = if (piStatus.bootstrapReady) "Встроенный" else "Нужен для команд",
                ),
                EnvironmentStatusItem(
                    title = "Pi",
                    ready = runtimeInstalled,
                    details = "v${piStatus.piVersion ?: piStatus.bundledPiVersion}",
                    updateAvailable = piStatus.piUpdateAvailable,
                    actionId = if (!runtimeInstalled || piStatus.piUpdateAvailable) "pi" else null,
                ),
                EnvironmentStatusItem(
                    title = "Node.js",
                    ready = piStatus.nodeReady,
                    details = "v24.7.0",
                ),
                EnvironmentStatusItem(
                    title = "Git",
                    ready = gitBinary.exists(),
                    details = if (gitBinary.exists()) "Локальный" else "Для репозиториев",
                ),
                EnvironmentStatusItem(
                    title = "JDK",
                    ready = javaBinary.exists(),
                    details = if (javaBinary.exists()) "Java 17" else "Для Android-сборки",
                ),
                EnvironmentStatusItem(
                    title = "Android SDK",
                    ready = sdkReady,
                    details = if (sdkReady) "Platform + Build Tools" else "Можно установить",
                    actionId = if (sdkReady) null else "toolchain",
                ),
            )

            _environment.update {
                EnvironmentSummary(
                    selectedRuntime = selectedRuntime,
                    selectedRuntimeInstalled = runtimeInstalled,
                    selectedRuntimeAuthenticated = runtimeAuthReady,
                    selectedRuntimeAuthMessage = runtimeAuthMessage,
                    runtimeReady = piStatus.bootstrapReady && piStatus.nodeReady && piStatus.piReady,
                    gitReady = gitBinary.exists(),
                    onboardingCompleted = onboardingStateStore.isCompleted(),
                    runtimeRoot = BuildEnvironment.runtimeDir.absolutePath,
                    projectsRoot = BuildEnvironment.projectsDir.absolutePath,
                    sdkRoot = BuildEnvironment.androidSdkDir.absolutePath,
                    javaBinary = BuildEnvironment.defaultJavaBinary.absolutePath,
                    gitBinary = gitBinary.absolutePath,
                    checks = checks,
                )
            }
        }
    }
}
