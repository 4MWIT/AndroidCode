package com.example.aicode.git

import android.content.Context
import com.example.aicode.build.BuildEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class GitCommandResult(
    val success: Boolean,
    val exitCode: Int,
    val output: List<String>,
)

data class GitStatusEntry(
    val code: String,
    val path: String,
)

data class GitRepositoryStatus(
    val isRepository: Boolean,
    val branch: String?,
    val remotes: List<String>,
    val changes: List<GitStatusEntry>,
)

data class GitSyncResult(
    val success: Boolean,
    val pullOutput: List<String>,
    val pushOutput: List<String>,
)

class GitRepositoryManager(
    context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun isGitAvailable(): Boolean = withContext(Dispatchers.IO) {
        resolveGitBinary().exists()
    }

    suspend fun initRepository(
        projectDir: File,
        branchName: String = "main",
    ): GitCommandResult = withContext(Dispatchers.IO) {
        ensureProjectDir(projectDir)
        val initResult = runGit(projectDir, "init")
        if (!initResult.success) {
            return@withContext initResult
        }
        val branchResult = runGit(projectDir, "branch", "-M", branchName)
        ensureGitIgnore(projectDir)
        if (!File(projectDir, ".gitkeep").exists()) {
            File(projectDir, ".gitkeep").writeText("")
        }
        return@withContext if (branchResult.success) {
            branchResult
        } else {
            initResult
        }
    }

    suspend fun getStatus(projectDir: File): GitRepositoryStatus = withContext(Dispatchers.IO) {
        if (!File(projectDir, ".git").exists()) {
            return@withContext GitRepositoryStatus(
                isRepository = false,
                branch = null,
                remotes = emptyList(),
                changes = emptyList(),
            )
        }

        val branch = runGit(projectDir, "rev-parse", "--abbrev-ref", "HEAD")
            .output
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val remotes = runGit(projectDir, "remote", "-v")
            .output
            .mapNotNull { line ->
                line.substringBefore("\t")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .distinct()

        val changes = runGit(projectDir, "status", "--porcelain")
            .output
            .mapNotNull { parseStatusLine(it) }

        GitRepositoryStatus(
            isRepository = true,
            branch = branch,
            remotes = remotes,
            changes = changes,
        )
    }

    suspend fun setRemoteOrigin(
        projectDir: File,
        remoteUrl: String,
    ): GitCommandResult = withContext(Dispatchers.IO) {
        ensureProjectDir(projectDir)
        val remoteStatus = runGit(projectDir, "remote")
        if (!remoteStatus.success) {
            return@withContext remoteStatus
        }

        return@withContext if (remoteStatus.output.any { it.trim() == "origin" }) {
            runGit(projectDir, "remote", "set-url", "origin", remoteUrl)
        } else {
            runGit(projectDir, "remote", "add", "origin", remoteUrl)
        }
    }

    suspend fun commitAll(
        projectDir: File,
        message: String,
        authorName: String = "AiCode",
        authorEmail: String = "aicode@local",
    ): GitCommandResult = withContext(Dispatchers.IO) {
        ensureProjectDir(projectDir)
        ensureGitIgnore(projectDir)

        val addResult = runGit(projectDir, "add", ".")
        if (!addResult.success) {
            return@withContext addResult
        }

        val status = runGit(projectDir, "status", "--porcelain")
        if (!status.success) {
            return@withContext status
        }
        if (status.output.isEmpty()) {
            return@withContext GitCommandResult(
                success = true,
                exitCode = 0,
                output = listOf("Нет изменений для коммита"),
            )
        }

        runGit(
            projectDir = projectDir,
            "commit",
            "-m",
            message,
            extraEnvironment = mapOf(
                "GIT_AUTHOR_NAME" to authorName,
                "GIT_AUTHOR_EMAIL" to authorEmail,
                "GIT_COMMITTER_NAME" to authorName,
                "GIT_COMMITTER_EMAIL" to authorEmail,
            ),
        )
    }

    suspend fun syncWithOrigin(
        projectDir: File,
        branchName: String = "main",
    ): GitSyncResult = withContext(Dispatchers.IO) {
        ensureProjectDir(projectDir)
        val pull = runGit(projectDir, "pull", "--rebase", "origin", branchName)
        if (!pull.success) {
            return@withContext GitSyncResult(
                success = false,
                pullOutput = pull.output,
                pushOutput = emptyList(),
            )
        }

        val push = runGit(projectDir, "push", "origin", "HEAD:$branchName")
        GitSyncResult(
            success = push.success,
            pullOutput = pull.output,
            pushOutput = push.output,
        )
    }

    private fun ensureGitIgnore(projectDir: File) {
        val gitIgnore = File(projectDir, ".gitignore")
        val requiredLines = listOf(
            ".gradle/",
            "build/",
            "*/build/",
            "local.properties",
            ".idea/",
            "*.iml",
        )
        val existing = if (gitIgnore.exists()) gitIgnore.readLines() else emptyList()
        val merged = (existing + requiredLines).distinct()
        gitIgnore.writeText(merged.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun resolveGitBinary(): File {
        BuildEnvironment.init(appContext)
        return File(BuildEnvironment.binDir, "git")
    }

    private fun parseStatusLine(line: String): GitStatusEntry? {
        if (line.length < 4) return null
        return GitStatusEntry(
            code = line.take(2).trim(),
            path = line.drop(3).trim(),
        )
    }

    private fun ensureProjectDir(projectDir: File) {
        if (!projectDir.exists()) {
            projectDir.mkdirs()
        }
    }

    private fun runGit(
        projectDir: File,
        vararg args: String,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): GitCommandResult {
        val gitBinary = resolveGitBinary()
        if (!gitBinary.exists()) {
            return GitCommandResult(
                success = false,
                exitCode = -1,
                output = listOf("Git binary не найден: ${gitBinary.absolutePath}"),
            )
        }

        val command = listOf(gitBinary.absolutePath) + args
        val process = ProcessBuilder(command)
            .directory(projectDir)
            .redirectErrorStream(true)
            .apply {
                environment().putAll(buildGitEnvironment())
                environment().putAll(extraEnvironment)
            }
            .start()

        val output = process.inputStream.bufferedReader().useLines { lines ->
            lines.toList()
        }
        val exitCode = process.waitFor()
        return GitCommandResult(
            success = exitCode == 0,
            exitCode = exitCode,
            output = output,
        )
    }

    private fun buildGitEnvironment(): Map<String, String> {
        BuildEnvironment.init(appContext)
        val path = buildList {
            add(BuildEnvironment.binDir.absolutePath)
            System.getenv("PATH")
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }.joinToString(File.pathSeparator)

        val ldLibraryPath = buildList {
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
            "LD_LIBRARY_PATH" to ldLibraryPath,
            "TERMUX_PKG_NO_MIRROR_SELECT" to "true",
            "GIT_TERMINAL_PROMPT" to "0",
        )
    }
}
