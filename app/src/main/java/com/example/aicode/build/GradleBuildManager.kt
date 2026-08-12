package com.example.aicode.build

import android.content.Context
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class BuildRequest(
    val projectDir: File,
    val tasks: List<String> = listOf("assembleDebug"),
    val additionalArguments: List<String> = emptyList(),
)

data class ApkBuildResult(
    val success: Boolean,
    val apkFile: File?,
    val outputRoot: File,
    val failureReason: String?,
    val command: List<String>,
    val toolchain: BuildToolchain?,
)

interface BuildListener {
    fun onBuildStarted(request: BuildRequest, command: List<String>) {}
    fun onBuildOutput(line: String) {}
    fun onBuildFinished(result: ApkBuildResult) {}
}

class GradleBuildManager(
    private val context: Context,
) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var activeProcess: Process? = null

    fun build(request: BuildRequest, listener: BuildListener) {
        executor.execute {
            val resolution = AndroidToolchainLocator.resolve(context, request.projectDir)
            val outputRoot = ProjectArtifactLocator.findApkOutputRoot(request.projectDir)
            if (resolution.toolchain == null) {
                listener.onBuildFinished(
                    ApkBuildResult(
                        success = false,
                        apkFile = null,
                        outputRoot = outputRoot,
                        failureReason = resolution.problems.joinToString(separator = "\n"),
                        command = emptyList(),
                        toolchain = null,
                    ),
                )
                return@execute
            }

            val toolchain = resolution.toolchain
            val wrapper = File(request.projectDir, "gradlew")
            if (!wrapper.canExecute()) {
                wrapper.setExecutable(true)
            }

            val command = buildCommand(wrapper, request, toolchain)
            listener.onBuildStarted(request, command)

            val env = BuildEnvironment.buildProcessEnvironment(request.projectDir, toolchain)
            val processBuilder = ProcessBuilder(command)
                .directory(request.projectDir)
                .redirectErrorStream(true)

            processBuilder.environment().putAll(env)

            try {
                val process = processBuilder.start()
                activeProcess = process

                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach(listener::onBuildOutput)
                }

                val exitCode = process.waitFor()
                activeProcess = null

                val apk = if (exitCode == 0) {
                    ProjectArtifactLocator.findNewestApk(request.projectDir)
                } else {
                    null
                }

                listener.onBuildFinished(
                    ApkBuildResult(
                        success = exitCode == 0 && apk != null,
                        apkFile = apk,
                        outputRoot = outputRoot,
                        failureReason = when {
                            exitCode != 0 -> "Gradle завершился с кодом $exitCode"
                            apk == null -> "Сборка прошла, но APK не найден в ${outputRoot.absolutePath}"
                            else -> null
                        },
                        command = command,
                        toolchain = toolchain,
                    ),
                )
            } catch (error: Exception) {
                activeProcess = null
                listener.onBuildFinished(
                    ApkBuildResult(
                        success = false,
                        apkFile = null,
                        outputRoot = outputRoot,
                        failureReason = error.message ?: error::class.java.simpleName,
                        command = command,
                        toolchain = toolchain,
                    ),
                )
            }
        }
    }

    fun cancelCurrentBuild() {
        activeProcess?.destroy()
        activeProcess = null
    }

    private fun buildCommand(
        wrapper: File,
        request: BuildRequest,
        toolchain: BuildToolchain,
    ): List<String> {
        val command = mutableListOf<String>()
        val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        if (isWindows) {
            command += wrapper.absolutePath
        } else {
            command += "sh"
            command += wrapper.absolutePath
        }

        command += request.tasks
        command += request.additionalArguments
        command += "-Dorg.gradle.java.home=${toolchain.javaHome.absolutePath}"
        toolchain.aapt2Binary?.let { aapt2 ->
            command += "-Pandroid.aapt2FromMavenOverride=${aapt2.absolutePath}"
        }
        return command
    }
}
