package com.example.aicode.qwen

import android.content.Context
import java.io.File

class QwenAuthStateStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("qwen_auth_state", Context.MODE_PRIVATE)
    private val authMarker = File(context.applicationContext.noBackupFilesDir, ".qwen_auth_completed")

    fun read(): QwenAuthSnapshot {
        val authenticated = authMarker.exists() && prefs.getBoolean(KEY_AUTHENTICATED, false)
        return QwenAuthSnapshot(
            authenticated = authenticated,
            authType = prefs.getString(KEY_AUTH_TYPE, null),
            message = prefs.getString(KEY_MESSAGE, null)
                ?: if (authenticated) "Qwen уже авторизован" else "Qwen ещё не авторизован",
        )
    }

    fun write(snapshot: QwenAuthSnapshot) {
        prefs.edit()
            .putBoolean(KEY_AUTHENTICATED, snapshot.authenticated)
            .putString(KEY_AUTH_TYPE, snapshot.authType)
            .putString(KEY_MESSAGE, snapshot.message)
            .apply()

        if (snapshot.authenticated) {
            authMarker.parentFile?.mkdirs()
            if (!authMarker.exists()) {
                authMarker.createNewFile()
            }
        } else if (authMarker.exists()) {
            authMarker.delete()
        }
    }

    fun reset() {
        prefs.edit().clear().apply()
        if (authMarker.exists()) {
            authMarker.delete()
        }
    }

    private companion object {
        private const val KEY_AUTHENTICATED = "authenticated"
        private const val KEY_AUTH_TYPE = "auth_type"
        private const val KEY_MESSAGE = "message"
    }
}
