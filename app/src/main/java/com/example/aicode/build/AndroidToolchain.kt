package com.example.aicode.build

import android.content.Context
import java.io.File
import java.util.Properties

data class BuildToolchain(
    val sdkDir: File,
    val javaHome: File,
    val javaBinary: File,
    val gradleUserHome: File,
    val tmpDir: File,
    val aapt2Binary: File?,
)

data class ToolchainResolution(
    val toolchain: BuildToolchain?,
    val problems: List<String>,
)

object AndroidToolchainLocator {

    fun resolve(context: Context, projectDir: File): ToolchainResolution {
        BuildEnvironment.init(context)

        val localProps = loadLocalProperties(projectDir)
        val sdkDir = resolveDirectory(
            candidates = listOf(
                localProps.getProperty("sdk.dir"),
                localProps.getProperty("android.sdk.path"),
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                BuildEnvironment.androidSdkDir.absolutePath,
            ),
        )

        val javaHome = resolveDirectory(
            candidates = listOf(
                localProps.getProperty("java.home"),
                System.getenv("JAVA_HOME"),
                BuildEnvironment.defaultJavaHome.absolutePath,
            ),
        )

        val problems = mutableListOf<String>()
        if (sdkDir == null) {
            problems += "Не найден Android SDK. Нет sdk.dir и нет доступного runtime SDK."
        }
        if (javaHome == null) {
            problems += "Не найден Java runtime. Нет JAVA_HOME и нет встроенного JDK."
        }

        val javaBinary = javaHome?.let { File(it, "bin/java") }
        if (javaHome != null && (javaBinary == null || !javaBinary.exists())) {
            problems += "Не найден java binary в ${javaHome.absolutePath}"
        }

        val wrapper = File(projectDir, "gradlew")
        if (!wrapper.exists()) {
            problems += "В проекте нет gradlew. Для локальной сборки нужен Gradle wrapper."
        }

        if (problems.isNotEmpty() || sdkDir == null || javaHome == null || javaBinary == null || !javaBinary.exists()) {
            return ToolchainResolution(toolchain = null, problems = problems)
        }

        return ToolchainResolution(
            toolchain = BuildToolchain(
                sdkDir = sdkDir,
                javaHome = javaHome,
                javaBinary = javaBinary,
                gradleUserHome = BuildEnvironment.gradleUserHome,
                tmpDir = BuildEnvironment.tmpDir,
                aapt2Binary = resolveAapt2(sdkDir),
            ),
            problems = emptyList(),
        )
    }

    private fun loadLocalProperties(projectDir: File): Properties {
        val properties = Properties()
        val file = File(projectDir, "local.properties")
        if (!file.exists()) {
            return properties
        }

        file.inputStream().use(properties::load)
        return properties
    }

    private fun resolveDirectory(candidates: List<String?>): File? {
        return candidates
            .asSequence()
            .filterNotNull()
            .map(::normalizePath)
            .filter { it.isNotBlank() }
            .map(::File)
            .firstOrNull { it.exists() && it.isDirectory }
    }

    private fun normalizePath(raw: String): String {
        return raw
            .replace("\\:", ":")
            .replace("\\\\", "\\")
            .trim()
            .removeSurrounding("\"")
    }

    private fun resolveAapt2(sdkDir: File): File? {
        val buildToolsDir = File(sdkDir, "build-tools")
        val versions = buildToolsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            .orEmpty()

        return versions
            .asSequence()
            .map { File(it, "aapt2") }
            .firstOrNull { it.exists() && it.isFile }
    }
}
