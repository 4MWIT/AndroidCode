package com.example.aicode.onboarding

import android.content.Context
import java.io.File

class OnboardingStateStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val markerFile = File(appContext.noBackupFilesDir, ".onboarding_completed")

    fun isCompleted(): Boolean = markerFile.exists()

    fun markCompleted() {
        markerFile.parentFile?.mkdirs()
        if (!markerFile.exists()) {
            markerFile.createNewFile()
        }
    }

    fun reset() {
        if (markerFile.exists()) {
            markerFile.delete()
        }
    }
}
