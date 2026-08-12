package com.example.aicode.settings

enum class AgentRuntimeType(
    val storageValue: String,
    val displayName: String,
    val requiresAuth: Boolean,
    val authDisplayName: String,
    val installDisplayName: String,
) {
    PI(
        storageValue = "pi",
        displayName = "Pi",
        requiresAuth = false,
        authDisplayName = "API-ключ",
        installDisplayName = "Pi coding agent",
    ),
    OPENCODE(
        storageValue = "opencode",
        displayName = "OpenCode",
        requiresAuth = false,
        authDisplayName = "Вход не нужен",
        installDisplayName = "OpenCode CLI",
    ),
    QWEN(
        storageValue = "qwen",
        displayName = "Qwen Code",
        requiresAuth = true,
        authDisplayName = "Qwen OAuth",
        installDisplayName = "Qwen Code CLI",
    ),
    ;

    companion object {
        fun fromStorage(value: String?): AgentRuntimeType {
            return entries.firstOrNull { it.storageValue == value } ?: PI
        }
    }
}
