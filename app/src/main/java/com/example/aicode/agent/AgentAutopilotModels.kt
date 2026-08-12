package com.example.aicode.agent

import com.example.aicode.acp.model.FileModification
import com.example.aicode.acp.model.PermissionRequestParams
import com.example.aicode.acp.model.PromptContext
import com.example.aicode.build.ApkBuildResult
import com.example.aicode.build.BuildRequest
import com.example.aicode.deploy.DeployResult
import com.example.aicode.settings.AgentRuntimeType
import java.io.File

data class AgentRunRequest(
    val runtimeType: AgentRuntimeType = AgentRuntimeType.PI,
    val prompt: String,
    val projectDir: File,
    val promptContext: PromptContext? = PromptContext(openFiles = emptyList()),
    val allowFileWrite: Boolean = true,
    val allowShellCommands: Boolean = true,
    val autoBuild: Boolean = true,
    val autoInstall: Boolean = true,
    val autoLaunch: Boolean = true,
    val packageNameHint: String? = null,
    val buildRequest: BuildRequest = BuildRequest(projectDir = projectDir),
)

data class AgentRunResult(
    val runtimeType: AgentRuntimeType,
    val prompt: String,
    val response: String,
    val modifications: List<FileModification>,
    val buildResult: ApkBuildResult? = null,
    val deployResult: DeployResult? = null,
)

enum class AgentStage {
    PREPARING,
    RUNNING_AGENT,
    BUILDING,
    INSTALLING,
    LAUNCHING,
    FINISHED,
}

sealed interface AgentRunEvent {
    data class StageChanged(val stage: AgentStage, val message: String) : AgentRunEvent
    data class Processing(val message: String) : AgentRunEvent
    data class Token(val token: String) : AgentRunEvent
    data class ToolCalling(val toolName: String, val details: String) : AgentRunEvent
    data class PermissionRequested(val request: PermissionRequestParams) : AgentRunEvent
    data class FileModifying(val filePath: String, val fileName: String) : AgentRunEvent
    data class FileModified(val filePath: String, val success: Boolean) : AgentRunEvent
    data class AgentFinished(val response: String, val modifications: List<FileModification>) : AgentRunEvent
    data class BuildStarted(val request: BuildRequest, val command: List<String>) : AgentRunEvent
    data class BuildOutput(val line: String) : AgentRunEvent
    data class BuildFinished(val result: ApkBuildResult) : AgentRunEvent
    data class InstallStarted(val apkFile: File) : AgentRunEvent
    data class InstallStatus(val status: String) : AgentRunEvent
    data class LaunchStarted(val packageName: String) : AgentRunEvent
    data class LaunchFinished(val packageName: String, val success: Boolean) : AgentRunEvent
    data class Completed(val result: AgentRunResult) : AgentRunEvent
    data class Failed(val message: String) : AgentRunEvent
}

fun interface AgentRunListener {
    fun onEvent(event: AgentRunEvent)
}
