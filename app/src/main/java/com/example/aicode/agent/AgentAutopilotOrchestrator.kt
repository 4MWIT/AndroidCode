package com.example.aicode.agent

import android.content.Context
import android.content.pm.PackageInstaller
import com.example.aicode.build.ApkBuildResult
import com.example.aicode.build.BuildListener
import com.example.aicode.build.BuildRequest
import com.example.aicode.build.GradleBuildManager
import com.example.aicode.deploy.DeployListener
import com.example.aicode.deploy.DeployManager
import com.example.aicode.deploy.DeployRequest
import com.example.aicode.deploy.DeployResult
import com.example.aicode.deploy.InstallationResult
import com.example.aicode.settings.AgentRuntimeType
import com.example.aicode.pi.PiAgentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AgentAutopilotOrchestrator(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val piAgentManager = PiAgentManager(appContext)
    private val gradleBuildManager = GradleBuildManager(appContext)
    private val deployManager = DeployManager(appContext)

    @Volatile
    private var activeJob: kotlinx.coroutines.Job? = null

    fun execute(request: AgentRunRequest, listener: AgentRunListener) {
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                emit(listener, AgentRunEvent.StageChanged(AgentStage.PREPARING, "Готовлю агентный цикл"))
                val agentResult = runAgentStep(request, listener)
                emit(listener, AgentRunEvent.AgentFinished(agentResult.response, agentResult.modifications))

                var buildResult: ApkBuildResult? = null
                var deployResult: DeployResult? = null

                if (request.autoBuild) {
                    emit(listener, AgentRunEvent.StageChanged(AgentStage.BUILDING, "Собираю APK"))
                    buildResult = runBuildStep(request.buildRequest, listener)
                    emit(listener, AgentRunEvent.BuildFinished(buildResult))
                    if (!buildResult.success) {
                        throw IllegalStateException(buildResult.failureReason ?: "Сборка APK не удалась")
                    }
                }

                if (request.autoInstall) {
                    val apkFile = buildResult?.apkFile
                        ?: throw IllegalStateException("Нет APK для установки")
                    emit(listener, AgentRunEvent.StageChanged(AgentStage.INSTALLING, "Устанавливаю APK"))
                    deployResult = deployManager.deploy(
                        request = DeployRequest(
                            apkFile = apkFile,
                            packageNameHint = request.packageNameHint,
                            launchAfterInstall = request.autoLaunch,
                        ),
                        listener = object : DeployListener {
                            override fun onInstallStarted(request: DeployRequest) {
                                scope.launch {
                                    emit(listener, AgentRunEvent.InstallStarted(request.apkFile))
                                }
                            }

                            override fun onInstallStatus(result: InstallationResult) {
                                scope.launch {
                                    emit(listener, AgentRunEvent.InstallStatus(formatInstallStatus(result)))
                                }
                            }

                            override fun onLaunchStarted(packageName: String) {
                                scope.launch {
                                    emit(listener, AgentRunEvent.StageChanged(AgentStage.LAUNCHING, "Запускаю $packageName"))
                                    emit(listener, AgentRunEvent.LaunchStarted(packageName))
                                }
                            }

                            override fun onLaunchFinished(packageName: String, launched: Boolean) {
                                scope.launch {
                                    emit(listener, AgentRunEvent.LaunchFinished(packageName, launched))
                                }
                            }
                        },
                    )

                    if (deployResult.installResult.status != PackageInstaller.STATUS_SUCCESS) {
                        throw IllegalStateException(deployResult.failureReason ?: "Установка APK не удалась")
                    }
                    if (request.autoLaunch && !deployResult.launched) {
                        throw IllegalStateException(deployResult.failureReason ?: "Приложение не запустилось после установки")
                    }
                }

                val finalResult = AgentRunResult(
                    runtimeType = request.runtimeType,
                    prompt = request.prompt,
                    response = agentResult.response,
                    modifications = agentResult.modifications,
                    buildResult = buildResult,
                    deployResult = deployResult,
                )
                emit(listener, AgentRunEvent.StageChanged(AgentStage.FINISHED, "Цикл завершён"))
                emit(listener, AgentRunEvent.Completed(finalResult))
            } catch (error: Exception) {
                emit(listener, AgentRunEvent.Failed(error.message ?: "Неизвестная ошибка"))
            }
        }
    }

    fun cancel() {
        gradleBuildManager.cancelCurrentBuild()
        activeJob?.cancel()
        activeJob = null
    }

    fun clear() {
        cancel()
    }

    fun respondToPermission(requestId: Long, granted: Boolean) {
        // Pi is restricted to the tools enabled before a run starts. Per-tool approvals
        // will be added through its RPC extension API.
    }

    private suspend fun runAgentStep(
        request: AgentRunRequest,
        listener: AgentRunListener,
    ): AgentRunResult {
        check(request.runtimeType == AgentRuntimeType.PI) {
            "Сейчас в приложении поддерживается только Pi. Выбери Pi в настройках."
        }
        emit(listener, AgentRunEvent.StageChanged(AgentStage.RUNNING_AGENT, "Запускаю Pi локально на телефоне"))
        val result = piAgentManager.runPrompt(
            request = request,
            onProcessing = { message -> scope.launch { emit(listener, AgentRunEvent.Processing(message)) } },
            onToken = { token -> scope.launch { emit(listener, AgentRunEvent.Token(token)) } },
            onToolCall = { tool, details -> scope.launch { emit(listener, AgentRunEvent.ToolCalling(tool, details)) } },
            onFileModifying = { path, name -> scope.launch { emit(listener, AgentRunEvent.FileModifying(path, name)) } },
            onFileModified = { path, success -> scope.launch { emit(listener, AgentRunEvent.FileModified(path, success)) } },
        )
        return AgentRunResult(
            runtimeType = request.runtimeType,
            prompt = request.prompt,
            response = result.response,
            modifications = result.modifications,
        )
    }

    private suspend fun runBuildStep(
        request: BuildRequest,
        listener: AgentRunListener,
    ): ApkBuildResult {
        return suspendCancellableCoroutine { continuation ->
            gradleBuildManager.build(
                request,
                object : BuildListener {
                    override fun onBuildStarted(request: BuildRequest, command: List<String>) {
                        scope.launch { emit(listener, AgentRunEvent.BuildStarted(request, command)) }
                    }

                    override fun onBuildOutput(line: String) {
                        scope.launch { emit(listener, AgentRunEvent.BuildOutput(line)) }
                    }

                    override fun onBuildFinished(result: ApkBuildResult) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                },
            )

            continuation.invokeOnCancellation {
                gradleBuildManager.cancelCurrentBuild()
            }
        }
    }

    private suspend fun emit(listener: AgentRunListener, event: AgentRunEvent) {
        withContext(Dispatchers.Main.immediate) {
            listener.onEvent(event)
        }
    }

    private fun formatInstallStatus(result: InstallationResult): String {
        val base = result.message ?: "Статус установки: ${result.status}"
        return if (result.packageName.isNullOrBlank()) {
            base
        } else {
            "$base (${result.packageName})"
        }
    }
}
