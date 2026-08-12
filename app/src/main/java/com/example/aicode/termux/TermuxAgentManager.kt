package com.example.aicode.termux

import android.content.Context
import com.example.aicode.acp.model.FileModification
import com.example.aicode.logging.AiCodeLog
import com.example.aicode.settings.AgentRuntimeType
import java.io.File

data class TermuxRuntimeStatus(
    val available: Boolean,
    val message: String,
    val logs: List<String> = emptyList(),
)

data class TermuxAgentRunResult(
    val success: Boolean,
    val response: String,
    val output: List<String>,
    val modifications: List<FileModification>,
    val exitCode: Int,
)

class TermuxAgentManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val commandRunner = TermuxCommandRunner(appContext)

    fun isTermuxInstalled(): Boolean = commandRunner.isTermuxInstalled()

    fun isRunCommandPermissionGranted(): Boolean = commandRunner.isRunCommandPermissionGranted()

    suspend fun getRuntimeStatus(runtimeType: AgentRuntimeType): TermuxRuntimeStatus {
        val shell = createShellCheck(runtimeType)
        val result = commandRunner.runCommand(
            TermuxCommandRequest(
                commandPath = "${'$'}PREFIX/bin/bash",
                arguments = arrayOf("-lc", shell),
                commandLabel = "Check ${runtimeType.displayName}",
                commandDescription = "Проверяю, установлен ли ${runtimeType.displayName} в Termux",
                timeoutMs = 60_000,
            ),
        )
        if (!commandRunner.isTermuxInstalled()) {
            return TermuxRuntimeStatus(
                available = false,
                message = "Сначала установи Termux на устройство.",
            )
        }
        if (!commandRunner.isRunCommandPermissionGranted()) {
            return TermuxRuntimeStatus(
                available = false,
                message = "Нужно выдать приложению permission на запуск команд в Termux.",
            )
        }
        val logs = buildList {
            if (result.stdout.isNotBlank()) add(result.stdout.trim())
            if (result.stderr.isNotBlank()) add(result.stderr.trim())
            if (result.errMsg.isNotBlank()) add(result.errMsg.trim())
        }
        return when {
            result.success -> TermuxRuntimeStatus(
                available = true,
                message = "${runtimeType.displayName} уже готов в Termux.",
                logs = logs,
            )
            else -> TermuxRuntimeStatus(
                available = false,
                message = when (runtimeType) {
                    AgentRuntimeType.PI -> "Pi работает внутри приложения, а не в Termux."
                    AgentRuntimeType.OPENCODE -> "OpenCode в Termux пока не найден."
                    AgentRuntimeType.QWEN -> "Qwen Code в Termux пока не найден."
                },
                logs = logs,
            )
        }
    }

    suspend fun installRuntime(
        runtimeType: AgentRuntimeType,
        onProgress: ((Float, String) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null,
    ): Boolean {
        val shellScript = when (runtimeType) {
            AgentRuntimeType.PI -> "echo 'Pi runs inside the app, not Termux'; exit 1"
            AgentRuntimeType.OPENCODE -> """
                pkg update -y
                pkg install -y nodejs git curl
                curl -fsSL https://opencode.ai/install | bash
                command -v opencode
                opencode --version
            """.trimIndent()
            AgentRuntimeType.QWEN -> """
                pkg update -y
                pkg install -y nodejs git curl
                npm install -g @qwen-code/qwen-code@0.1.0
                command -v qwen
                qwen --version
            """.trimIndent()
        }

        onProgress?.invoke(0.1f, "Отправляю установку ${runtimeType.displayName} в Termux…")
        val result = commandRunner.runCommand(
            TermuxCommandRequest(
                commandPath = "${'$'}PREFIX/bin/bash",
                arguments = arrayOf("-lc", shellScript),
                commandLabel = "Install ${runtimeType.displayName}",
                commandDescription = "Ставлю ${runtimeType.displayName} в Termux для работы агента",
                timeoutMs = 900_000,
            ),
        )
        streamResultLogs(result, onLog)
        onProgress?.invoke(
            if (result.success) 1f else 0f,
            if (result.success) "${runtimeType.displayName} установлен в Termux" else "Установка ${runtimeType.displayName} сорвалась",
        )
        return result.success
    }

    suspend fun getAuthStatus(runtimeType: AgentRuntimeType): TermuxRuntimeStatus {
        if (!runtimeType.requiresAuth) {
            return TermuxRuntimeStatus(
                available = true,
                message = "${runtimeType.displayName} не требует отдельного входа.",
            )
        }
        val result = commandRunner.runCommand(
            TermuxCommandRequest(
                commandPath = "${'$'}PREFIX/bin/bash",
                arguments = arrayOf("-lc", "qwen auth status"),
                commandLabel = "Qwen auth status",
                commandDescription = "Проверяю статус входа Qwen внутри Termux",
                timeoutMs = 60_000,
            ),
        )
        val joined = listOf(result.stdout, result.stderr, result.errMsg)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val authenticated = result.success &&
            !joined.contains("not authenticated", ignoreCase = true) &&
            !joined.contains("login required", ignoreCase = true)
        return TermuxRuntimeStatus(
            available = authenticated,
            message = if (authenticated) {
                "Qwen уже авторизован в Termux."
            } else {
                "Qwen в Termux ещё не авторизован."
            },
            logs = joined.lines().filter { it.isNotBlank() },
        )
    }

    suspend fun startAuth(runtimeType: AgentRuntimeType): TermuxRuntimeStatus {
        if (!runtimeType.requiresAuth) {
            return TermuxRuntimeStatus(
                available = true,
                message = "${runtimeType.displayName} не требует отдельного входа.",
            )
        }
        val result = commandRunner.runCommand(
            TermuxCommandRequest(
                commandPath = "${'$'}PREFIX/bin/bash",
                arguments = arrayOf("-lc", "qwen auth qwen-oauth"),
                background = false,
                commandLabel = "Qwen OAuth",
                commandDescription = "Откроет Termux и запустит официальный вход Qwen",
                timeoutMs = 900_000,
            ),
        )
        val logs = buildList {
            if (result.stdout.isNotBlank()) add(result.stdout.trim())
            if (result.stderr.isNotBlank()) add(result.stderr.trim())
            if (result.errMsg.isNotBlank()) add(result.errMsg.trim())
        }
        return TermuxRuntimeStatus(
            available = result.success,
            message = if (result.success) {
                "Команда входа запущена в Termux."
            } else {
                "Не удалось запустить вход Qwen в Termux."
            },
            logs = logs,
        )
    }

    suspend fun runPrompt(
        runtimeType: AgentRuntimeType,
        projectDir: File,
        prompt: String,
        onOutput: ((String) -> Unit)? = null,
    ): TermuxAgentRunResult {
        ensureProjectConfig(runtimeType, projectDir)
        val beforeSnapshot = captureProjectSnapshot(projectDir)
        val shellScript = when (runtimeType) {
            AgentRuntimeType.PI -> throw IllegalArgumentException("Pi does not run through Termux")
            AgentRuntimeType.OPENCODE -> {
                val escapedPrompt = escapeShellDoubleQuoted(prompt)
                "cd \"${projectDir.absolutePath}\" && opencode run \"$escapedPrompt\""
            }
            AgentRuntimeType.QWEN -> {
                val escapedPrompt = escapeShellDoubleQuoted(prompt)
                "cd \"${projectDir.absolutePath}\" && qwen -y -p \"$escapedPrompt\""
            }
        }
        AiCodeLog.agent("Running ${runtimeType.displayName} in Termux for project=${projectDir.absolutePath}")
        val result = commandRunner.runCommand(
            TermuxCommandRequest(
                commandPath = "${'$'}PREFIX/bin/bash",
                arguments = arrayOf("-lc", shellScript),
                workdir = projectDir.absolutePath,
                commandLabel = "${runtimeType.displayName} prompt",
                commandDescription = "Запускаю ${runtimeType.displayName} для правок проекта",
                timeoutMs = 1_800_000,
            ),
        )
        val outputLines = buildList {
            if (result.stdout.isNotBlank()) addAll(result.stdout.lines())
            if (result.stderr.isNotBlank()) addAll(result.stderr.lines())
            if (result.errMsg.isNotBlank()) add(result.errMsg)
        }.filter { it.isNotBlank() }
        outputLines.forEach { line ->
            AiCodeLog.agent("${runtimeType.displayName} output: $line")
            onOutput?.invoke(line)
        }
        val afterSnapshot = captureProjectSnapshot(projectDir)
        return TermuxAgentRunResult(
            success = result.success,
            response = result.stdout.ifBlank { outputLines.joinToString("\n") },
            output = outputLines,
            modifications = buildModifications(beforeSnapshot, afterSnapshot),
            exitCode = result.exitCode,
        )
    }

    private fun createShellCheck(runtimeType: AgentRuntimeType): String {
        return when (runtimeType) {
            AgentRuntimeType.PI -> "false"
            AgentRuntimeType.OPENCODE -> "command -v opencode >/dev/null 2>&1 && opencode --version"
            AgentRuntimeType.QWEN -> "command -v qwen >/dev/null 2>&1 && qwen --version"
        }
    }

    private fun ensureProjectConfig(runtimeType: AgentRuntimeType, projectDir: File) {
        if (runtimeType != AgentRuntimeType.OPENCODE) {
            return
        }
        val configFile = File(projectDir, ".opencode.json")
        if (configFile.exists()) {
            return
        }
        configFile.writeText(
            """
            {
              "${'$'}schema": "https://opencode.ai/config.json",
              "permission": "allow"
            }
            """.trimIndent(),
        )
    }

    private fun escapeShellDoubleQuoted(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
    }

    private fun captureProjectSnapshot(projectDir: File): Map<String, Long> {
        if (!projectDir.exists()) return emptyMap()
        return projectDir.walkTopDown()
            .filter { it.isFile }
            .associate { file ->
                file.relativeTo(projectDir).path.replace(File.separatorChar, '/') to file.lastModified()
            }
    }

    private fun buildModifications(
        before: Map<String, Long>,
        after: Map<String, Long>,
    ): List<FileModification> {
        val allPaths = (before.keys + after.keys).sorted()
        return allPaths.mapNotNull { path ->
            when {
                !before.containsKey(path) && after.containsKey(path) ->
                    FileModification(filePath = path, operation = "create")
                before.containsKey(path) && !after.containsKey(path) ->
                    FileModification(filePath = path, operation = "delete")
                before[path] != after[path] ->
                    FileModification(filePath = path, operation = "modify")
                else -> null
            }
        }
    }

    private fun streamResultLogs(
        result: TermuxCommandResult,
        onLog: ((String) -> Unit)?,
    ) {
        result.stdout.lines().filter { it.isNotBlank() }.forEach { onLog?.invoke(it) }
        result.stderr.lines().filter { it.isNotBlank() }.forEach { onLog?.invoke(it) }
        if (result.errMsg.isNotBlank()) {
            onLog?.invoke(result.errMsg)
        }
    }
}
