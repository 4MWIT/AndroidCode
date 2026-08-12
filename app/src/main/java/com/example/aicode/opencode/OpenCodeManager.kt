package com.example.aicode.opencode

import android.content.Context
import com.example.aicode.build.BuildEnvironment
import com.example.aicode.logging.AiCodeLog
import com.example.aicode.acp.model.FileModification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

data class OpenCodeRunResult(
    val success: Boolean,
    val response: String,
    val output: List<String>,
    val modifications: List<FileModification>,
    val exitCode: Int,
)

class OpenCodeManager(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        probeBinary()
    }

    suspend fun install(
        onProgress: ((Float, String) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        BuildEnvironment.init(appContext)

        val bash = File(BuildEnvironment.binDir, "bash")
        if (!bash.exists() || !bash.canExecute()) {
            AiCodeLog.agentError("OpenCode install blocked: runtime shell is not ready")
            onLog?.invoke("Сначала нужен рабочий Android runtime: bash пока не готов.")
            return@withContext false
        }

        val installDir = ensureOpenCodeHome()
        onProgress?.invoke(0.05f, "Готовлю окружение OpenCode…")
        onLog?.invoke("Готовлю каталог OpenCode: ${installDir.absolutePath}")

        val script = """
            set -e
            mkdir -p "${'$'}HOME/.local/bin" "${'$'}HOME/.config/opencode" "${'$'}HOME/.local/share" "${'$'}HOME/.local/state" "${'$'}HOME/.opencode/bin"
            curl -fsSL https://opencode.ai/install | bash
        """.trimIndent()

        val process = ProcessBuilder(
            listOf(
                bash.absolutePath,
                "-lc",
                script,
            ),
        )
            .directory(BuildEnvironment.homeDir)
            .redirectErrorStream(true)
            .apply {
                environment().putAll(buildEnvironment())
            }
            .start()

        val output = mutableListOf<String>()
        var progress = 0.08f
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                output += line
                AiCodeLog.agent("OpenCode install: $line")
                onLog?.invoke(line)
                if (progress < 0.9f) {
                    progress = (progress + 0.02f).coerceAtMost(0.9f)
                    onProgress?.invoke(progress, line.ifBlank { "Ставлю OpenCode…" })
                }
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            AiCodeLog.agentError("OpenCode install failed with exitCode=$exitCode")
            onLog?.invoke("Установщик OpenCode завершился с кодом $exitCode")
            return@withContext false
        }

        val installed = probeBinary()
        if (installed) {
            AiCodeLog.agent("OpenCode installed successfully")
            onProgress?.invoke(1f, "OpenCode установлен")
            onLog?.invoke("Готово: OpenCode отвечает на --version")
        } else {
            AiCodeLog.agentError("OpenCode install finished but binary validation failed")
            onLog?.invoke("Установка завершилась, но OpenCode не запустился из shell.")
            onLog?.invoke(describeInstallTree())
        }
        installed
    }

    suspend fun runPrompt(
        projectDir: File,
        prompt: String,
        onOutput: ((String) -> Unit)? = null,
    ): OpenCodeRunResult = withContext(Dispatchers.IO) {
        BuildEnvironment.init(appContext)
        ensureProjectConfig(projectDir)

        if (!probeBinary()) {
            return@withContext OpenCodeRunResult(
                success = false,
                response = "",
                output = listOf("OpenCode не отвечает из shell", describeInstallTree()),
                modifications = emptyList(),
                exitCode = -1,
            )
        }

        val beforeSnapshot = captureProjectSnapshot(projectDir)
        val command = listOf(
            resolveShell().absolutePath,
            "-lc",
            buildRunCommand(prompt),
        )

        AiCodeLog.agent("Running OpenCode prompt in ${projectDir.absolutePath}: ${command.joinToString(" ")}")
        val process = ProcessBuilder(command)
            .directory(projectDir)
            .redirectErrorStream(true)
            .apply {
                environment().putAll(buildEnvironment())
            }
            .start()

        val output = mutableListOf<String>()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                output += line
                AiCodeLog.agent("OpenCode run: $line")
                onOutput?.invoke(line)
            }
        }

        val exitCode = process.waitFor()
        val afterSnapshot = captureProjectSnapshot(projectDir)
        val modifications = buildModifications(beforeSnapshot, afterSnapshot)
        val response = output
            .joinToString("\n")
            .trim()

        OpenCodeRunResult(
            success = exitCode == 0,
            response = response,
            output = output,
            modifications = modifications,
            exitCode = exitCode,
        )
    }

    fun getInstallLocation(): String {
        BuildEnvironment.init(appContext)
        return binaryCandidates().joinToString(" | ") { it.absolutePath }
    }

    private fun buildEnvironment(): Map<String, String> {
        BuildEnvironment.init(appContext)
        val path = buildList {
            add(File(BuildEnvironment.homeDir, ".local/bin").absolutePath)
            add(File(BuildEnvironment.homeDir, ".opencode/bin").absolutePath)
            add(BuildEnvironment.binDir.absolutePath)
            System.getenv("PATH")
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }.joinToString(File.pathSeparator)

        val ldPath = buildList {
            add(BuildEnvironment.libDir.absolutePath)
            System.getenv("LD_LIBRARY_PATH")
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }.joinToString(File.pathSeparator)

        return mapOf(
            "HOME" to BuildEnvironment.homeDir.absolutePath,
            "PREFIX" to BuildEnvironment.usrDir.absolutePath,
            "TMPDIR" to BuildEnvironment.tmpDir.absolutePath,
            "PATH" to path,
            "LD_LIBRARY_PATH" to ldPath,
            "XDG_CONFIG_HOME" to File(BuildEnvironment.homeDir, ".config").absolutePath,
            "XDG_DATA_HOME" to File(BuildEnvironment.homeDir, ".local/share").absolutePath,
            "XDG_STATE_HOME" to File(BuildEnvironment.homeDir, ".local/state").absolutePath,
            "TERMUX_PKG_NO_MIRROR_SELECT" to "true",
            "NO_COLOR" to "1",
        )
    }

    private fun ensureOpenCodeHome(): File {
        val dir = File(BuildEnvironment.homeDir, ".opencode")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        File(BuildEnvironment.homeDir, ".local/bin").mkdirs()
        File(BuildEnvironment.homeDir, ".config/opencode").mkdirs()
        File(BuildEnvironment.homeDir, ".local/share").mkdirs()
        File(BuildEnvironment.homeDir, ".local/state").mkdirs()
        return dir
    }

    private fun binaryCandidates(): List<File> {
        BuildEnvironment.init(appContext)
        return listOf(
            File(BuildEnvironment.homeDir, ".local/bin/opencode"),
            File(BuildEnvironment.homeDir, ".opencode/bin/opencode"),
            File(BuildEnvironment.binDir, "opencode"),
        )
    }

    private fun resolveShell(): File {
        BuildEnvironment.init(appContext)
        return File(BuildEnvironment.binDir, "bash")
    }

    private fun probeBinary(): Boolean {
        return runCatching {
            val process = ProcessBuilder(
                listOf(
                    resolveShell().absolutePath,
                    "-lc",
                    "command -v opencode >/dev/null 2>&1 && opencode --version",
                ),
            )
                .directory(BuildEnvironment.homeDir)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(buildEnvironment())
                }
                .start()
            val lines = process.inputStream.bufferedReader().readLines()
            lines.forEach { line ->
                AiCodeLog.agent("OpenCode probe: $line")
            }
            process.waitFor() == 0
        }.getOrElse {
            AiCodeLog.agentWarn("OpenCode probe failed: ${it.message}", it)
            false
        }
    }

    private fun buildRunCommand(prompt: String): String {
        val escapedPrompt = prompt
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
        return "opencode run \"$escapedPrompt\""
    }

    private fun describeInstallTree(): String {
        val lines = binaryCandidates().map { candidate ->
            val exists = candidate.exists()
            val executable = candidate.canExecute()
            "candidate=${candidate.absolutePath} exists=$exists executable=$executable"
        }
        return lines.joinToString(separator = "\n")
    }

    private fun ensureProjectConfig(projectDir: File) {
        val configFile = File(projectDir, ".opencode.json")
        if (configFile.exists()) {
            return
        }

        val config = buildJsonObject {
            put("\$schema", "https://opencode.ai/config.json")
            put("permission", "allow")
        }
        configFile.writeText(json.encodeToString(JsonObject.serializer(), config))
        AiCodeLog.agent("Created OpenCode project config at ${configFile.absolutePath}")
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
                !before.containsKey(path) && after.containsKey(path) -> {
                    FileModification(filePath = path, operation = "create")
                }
                before.containsKey(path) && !after.containsKey(path) -> {
                    FileModification(filePath = path, operation = "delete")
                }
                before[path] != after[path] -> {
                    FileModification(filePath = path, operation = "modify")
                }
                else -> null
            }
        }
    }
}
