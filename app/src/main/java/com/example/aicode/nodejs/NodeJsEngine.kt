package com.example.aicode.nodejs

import com.example.aicode.logging.AiCodeLog

object NodeJsEngine {

    private var isLoaded = false

    init {
        try {
            System.loadLibrary("native-lib")
            System.loadLibrary("node")
            isLoaded = true
        } catch (error: UnsatisfiedLinkError) {
            AiCodeLog.agentError("Failed to load native libraries for NodeJsEngine", error)
            isLoaded = false
        }
    }

    fun isAvailable(): Boolean = isLoaded

    external fun startNodeWithArguments(args: Array<String>): Int

    external fun getNodeVersion(): String
}
