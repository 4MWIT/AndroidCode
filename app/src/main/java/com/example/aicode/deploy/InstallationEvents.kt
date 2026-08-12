package com.example.aicode.deploy

object InstallationEvents {

    private val listeners = linkedSetOf<(InstallationResult) -> Unit>()

    fun addListener(listener: (InstallationResult) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (InstallationResult) -> Unit) {
        listeners -= listener
    }

    fun dispatch(result: InstallationResult) {
        listeners.toList().forEach { it(result) }
    }
}
