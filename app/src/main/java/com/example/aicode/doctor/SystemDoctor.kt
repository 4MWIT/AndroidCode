package com.example.aicode.doctor

import android.content.Context
import com.example.aicode.build.BuildEnvironment
import com.example.aicode.git.GitRepositoryManager
import com.example.aicode.pi.ApiKeyStore
import com.example.aicode.pi.PiRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DoctorCheck(
    val title: String,
    val ready: Boolean,
    val details: String,
)

data class SystemDoctorReport(
    val checks: List<DoctorCheck>,
) {
    val isReadyForAutopilot: Boolean
        get() = checks.all { it.ready }

    val blockingIssues: List<DoctorCheck>
        get() = checks.filterNot { it.ready }
}

class SystemDoctor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val piRuntimeManager = PiRuntimeManager(appContext)
    private val apiKeyStore = ApiKeyStore(appContext)
    private val gitRepositoryManager = GitRepositoryManager(appContext)

    suspend fun collectReport(): SystemDoctorReport = withContext(Dispatchers.IO) {
        BuildEnvironment.init(appContext)

        val piStatus = piRuntimeManager.status()
        val checks = listOf(
            DoctorCheck(
                title = "Локальный shell runtime",
                ready = piStatus.bootstrapReady,
                details = piStatus.details,
            ),
            DoctorCheck(
                title = "Разрешение на установку APK",
                ready = appContext.packageManager.canRequestPackageInstalls(),
                details = "Нужно для auto-install результата на устройстве.",
            ),
            DoctorCheck(
                title = "Node.js runtime",
                ready = piStatus.nodeReady,
                details = "Node.js установлен внутри приватной папки приложения.",
            ),
            DoctorCheck(
                title = "Pi coding agent",
                ready = piStatus.piReady,
                details = if (piStatus.piReady) "Pi готов." else "Установи Pi в настройках.",
            ),
            DoctorCheck(
                title = "API-ключ Pi",
                ready = apiKeyStore.hasKey(),
                details = if (apiKeyStore.hasKey()) "Ключ сохранён в Android Keystore." else "Добавь ключ в настройках.",
            ),
            DoctorCheck(
                title = "Git",
                ready = gitRepositoryManager.isGitAvailable(),
                details = File(BuildEnvironment.binDir, "git").absolutePath,
            ),
            DoctorCheck(
                title = "JDK",
                ready = BuildEnvironment.defaultJavaBinary.exists(),
                details = BuildEnvironment.defaultJavaBinary.absolutePath,
            ),
            DoctorCheck(
                title = "Android SDK cmdline-tools",
                ready = File(BuildEnvironment.androidSdkDir, "cmdline-tools/latest").exists(),
                details = File(BuildEnvironment.androidSdkDir, "cmdline-tools/latest").absolutePath,
            ),
            DoctorCheck(
                title = "Android platform tools",
                ready = File(BuildEnvironment.androidSdkDir, "platform-tools").exists(),
                details = File(BuildEnvironment.androidSdkDir, "platform-tools").absolutePath,
            ),
            DoctorCheck(
                title = "Android build tools",
                ready = BuildEnvironment.androidSdkDir.resolve("build-tools").listFiles()?.any { it.isDirectory } == true,
                details = BuildEnvironment.androidSdkDir.resolve("build-tools").absolutePath,
            ),
            DoctorCheck(
                title = "Android platforms",
                ready = BuildEnvironment.androidSdkDir.resolve("platforms").listFiles()?.any { it.isDirectory } == true,
                details = BuildEnvironment.androidSdkDir.resolve("platforms").absolutePath,
            ),
        )

        SystemDoctorReport(checks = checks)
    }
}
