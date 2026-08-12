package com.example.aicode.build

import java.io.File

object TermuxLikeShellEnvironment {

    fun create(toolchain: BuildToolchain): Map<String, String> {
        BuildEnvironment.ensureInitialized()

        val pathEntries = buildList {
            add(File(toolchain.javaHome, "bin").absolutePath)
            add(BuildEnvironment.binDir.absolutePath)
            add(File(toolchain.sdkDir, "cmdline-tools/latest/bin").absolutePath)
            add(File(toolchain.sdkDir, "platform-tools").absolutePath)
            System.getenv("PATH")
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it) }
        }

        val ldEntries = buildList {
            add(BuildEnvironment.libDir.absolutePath)
            add(File(toolchain.javaHome, "lib").absolutePath)
            System.getenv("LD_LIBRARY_PATH")
                ?.takeIf { it.isNotBlank() }
                ?.let { add(it) }
        }

        return mapOf(
            "PATH" to pathEntries.joinToString(File.pathSeparator),
            "LD_LIBRARY_PATH" to ldEntries.joinToString(File.pathSeparator),
            "TERMUX_PKG_NO_MIRROR_SELECT" to "true",
        )
    }
}
