package com.example.aicode.logging

import android.util.Log

object AiCodeLog {
    const val SETUP_TAG = "AiCodeSetup"
    const val AGENT_TAG = "AiCodeAgent"

    fun setup(message: String) {
        Log.d(SETUP_TAG, message)
    }

    fun setupWarn(message: String, error: Throwable? = null) {
        Log.w(SETUP_TAG, message, error)
    }

    fun setupError(message: String, error: Throwable? = null) {
        Log.e(SETUP_TAG, message, error)
    }

    fun agent(message: String) {
        Log.d(AGENT_TAG, message)
    }

    fun agentWarn(message: String, error: Throwable? = null) {
        Log.w(AGENT_TAG, message, error)
    }

    fun agentError(message: String, error: Throwable? = null) {
        Log.e(AGENT_TAG, message, error)
    }
}
