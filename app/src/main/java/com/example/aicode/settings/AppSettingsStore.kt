package com.example.aicode.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppSettingsState(
    val selectedRuntime: AgentRuntimeType = AgentRuntimeType.PI,
    val providerId: String = PiModelCatalog.default.providerId,
    val modelId: String = PiModelCatalog.default.id,
    val baseUrl: String = PiModelCatalog.default.baseUrl,
    val apiType: String = PiModelCatalog.default.apiType,
    val autoBuild: Boolean = false,
    val autoInstall: Boolean = false,
    val autoLaunch: Boolean = false,
    val allowAgentWrite: Boolean = true,
    val allowAgentShell: Boolean = true,
    val showVerboseLogs: Boolean = true,
)

class AppSettingsStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettingsState> = _state.asStateFlow()

    fun setSelectedRuntime(value: AgentRuntimeType) {
        prefs.edit().putString("selected_runtime", value.storageValue).apply()
        _state.update { it.copy(selectedRuntime = value) }
    }
    fun setProviderId(value: String) = updateString("provider_id", value) { it.copy(providerId = value) }
    fun setModelId(value: String) = updateString("model_id", value) { it.copy(modelId = value) }
    fun setBaseUrl(value: String) = updateString("base_url", value) { it.copy(baseUrl = value) }
    fun setApiType(value: String) = updateString("api_type", value) { it.copy(apiType = value) }
    fun useModelPreset(preset: PiModelPreset) {
        prefs.edit()
            .putString("provider_id", preset.providerId)
            .putString("model_id", preset.id)
            .putString("base_url", preset.baseUrl)
            .putString("api_type", preset.apiType)
            .apply()
        _state.update {
            it.copy(
                providerId = preset.providerId,
                modelId = preset.id,
                baseUrl = preset.baseUrl,
                apiType = preset.apiType,
            )
        }
    }
    fun setAutoBuild(value: Boolean) = update("auto_build", value) { it.copy(autoBuild = value) }
    fun setAutoInstall(value: Boolean) = update("auto_install", value) { it.copy(autoInstall = value) }
    fun setAutoLaunch(value: Boolean) = update("auto_launch", value) { it.copy(autoLaunch = value) }
    fun setAllowAgentWrite(value: Boolean) = update("allow_agent_write", value) { it.copy(allowAgentWrite = value) }
    fun setAllowAgentShell(value: Boolean) = update("allow_agent_shell", value) { it.copy(allowAgentShell = value) }
    fun setShowVerboseLogs(value: Boolean) = update("show_verbose_logs", value) { it.copy(showVerboseLogs = value) }

    private fun load(): AppSettingsState {
        return AppSettingsState(
            // Pi is the only supported runtime in the rebuilt app. Old selections are migrated.
            selectedRuntime = AgentRuntimeType.PI,
            providerId = prefs.getString("provider_id", PiModelCatalog.default.providerId).orEmpty().ifBlank { PiModelCatalog.default.providerId },
            modelId = prefs.getString("model_id", PiModelCatalog.default.id).orEmpty().ifBlank { PiModelCatalog.default.id },
            baseUrl = prefs.getString("base_url", PiModelCatalog.default.baseUrl).orEmpty().ifBlank { PiModelCatalog.default.baseUrl },
            apiType = prefs.getString("api_type", PiModelCatalog.default.apiType).orEmpty().ifBlank { PiModelCatalog.default.apiType },
            autoBuild = prefs.getBoolean("auto_build", false),
            autoInstall = prefs.getBoolean("auto_install", false),
            autoLaunch = prefs.getBoolean("auto_launch", false),
            allowAgentWrite = prefs.getBoolean("allow_agent_write", true),
            allowAgentShell = prefs.getBoolean("allow_agent_shell", true),
            showVerboseLogs = prefs.getBoolean("show_verbose_logs", true),
        )
    }

    private inline fun update(
        key: String,
        value: Boolean,
        reducer: (AppSettingsState) -> AppSettingsState,
    ) {
        prefs.edit().putBoolean(key, value).apply()
        _state.update(reducer)
    }

    private inline fun updateString(
        key: String,
        value: String,
        reducer: (AppSettingsState) -> AppSettingsState,
    ) {
        prefs.edit().putString(key, value.trim()).apply()
        _state.update(reducer)
    }
}
