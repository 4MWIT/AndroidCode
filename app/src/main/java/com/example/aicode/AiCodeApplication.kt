package com.example.aicode

import android.app.Application
import com.example.aicode.build.BuildEnvironment
import com.example.aicode.logging.AiCodeLog

class AiCodeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BuildEnvironment.init(this)
        AiCodeLog.setup("Application started. filesDir=${filesDir.absolutePath}")
    }
}
