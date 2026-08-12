package com.example.aicode.build

import android.content.Context
import android.system.Os
import com.example.aicode.logging.AiCodeLog
import com.termux.app.TermuxInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class AndroidRuntimeSetupRequest(
    // idesetup expects the SDK/build-tools package version (for example 35.0.1),
    // not the Android API level alone.
    val sdkVersion: String = "35.0.1",
    val jdkVersion: String = "17",
    val installGit: Boolean = true,
    val installOpenSsh: Boolean = false,
)

class AndroidRuntimeInstaller(
    context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun install(
        request: AndroidRuntimeSetupRequest = AndroidRuntimeSetupRequest(),
        onProgress: (Float, String) -> Unit,
        onLog: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        BuildEnvironment.init(appContext)
        AiCodeLog.setup("AndroidRuntimeInstaller.install started sdk=${request.sdkVersion} jdk=${request.jdkVersion}")
        AiCodeLog.setup(
            "Runtime context package=${appContext.packageName} filesDir=${appContext.filesDir.absolutePath}",
        )

        onProgress(0.03f, "Проверяю bootstrap runtime…")
        onLog("Подготовка bootstrap runtime в ${BuildEnvironment.runtimeDir.absolutePath}")
        if (!ensureBootstrap(onProgress, onLog)) {
            AiCodeLog.setupError("Bootstrap preparation failed")
            return@withContext false
        }

        onProgress(0.42f, "Запускаю Android runtime setup…")
        runIdeSetup(request, onProgress, onLog)
    }

    private fun ensureBootstrap(
        onProgress: (Float, String) -> Unit,
        onLog: (String) -> Unit,
    ): Boolean {
        val shell = File(BuildEnvironment.binDir, "sh")
        if (shell.exists()) {
            AiCodeLog.setup("Bootstrap shell already exists: ${shell.absolutePath}")
            onLog("Bootstrap уже распакован: ${shell.absolutePath}")

            repairBootstrapPermissions(onLog)
            if (isBootstrapHealthy()) {
                AiCodeLog.setup("Bootstrap health check passed after permission repair")
                return true
            }

            AiCodeLog.setupWarn("Bootstrap exists but is unhealthy, reinstalling prefix")
            onLog("Bootstrap найден, но повреждён. Переустанавливаю prefix…")
        }

        val stagingDir = File(BuildEnvironment.runtimeDir, "usr-staging")
        stagingDir.deleteRecursively()
        BuildEnvironment.usrDir.deleteRecursively()

        onProgress(0.08f, "Распаковываю bootstrap shell…")
        onLog("Загружаю bootstrap zip из native-библиотеки")
        AiCodeLog.setup("Extracting bootstrap archive into ${stagingDir.absolutePath}")

        val symlinks = mutableListOf<Pair<String, String>>()
        val zipBytes = TermuxInstaller.loadZipBytes()
        AiCodeLog.setup("Bootstrap archive loaded: bytes=${zipBytes.size}")

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipInput ->
            var entry: ZipEntry? = zipInput.nextEntry
            while (entry != null) {
                if (entry.name == "SYMLINKS.txt") {
                    val reader = BufferedReader(InputStreamReader(zipInput))
                    while (true) {
                        val line = reader.readLine() ?: break
                        val parts = line.split("←", limit = 2)
                        if (parts.size == 2) {
                            val targetPath = File(stagingDir, parts[1]).absolutePath
                            File(targetPath).parentFile?.mkdirs()
                            symlinks += parts[0] to targetPath
                        }
                    }
                } else {
                    val target = File(stagingDir, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            zipInput.copyTo(output)
                        }
                        if (shouldBeExecutable(entry.name)) {
                            runCatching { Os.chmod(target.absolutePath, 0b111000000) }
                        }
                    }
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        }

        symlinks.forEach { (oldPath, newPath) ->
            runCatching {
                Os.symlink(oldPath, newPath)
            }.onFailure {
                AiCodeLog.setupWarn("Failed to create symlink $newPath -> $oldPath: ${it.message}", it)
                onLog("Не удалось создать symlink $newPath -> $oldPath: ${it.message}")
            }
        }

        if (!stagingDir.renameTo(BuildEnvironment.usrDir)) {
            AiCodeLog.setupError("Failed to move bootstrap staging into ${BuildEnvironment.usrDir.absolutePath}")
            onLog("Не удалось переместить bootstrap в ${BuildEnvironment.usrDir.absolutePath}")
            return false
        }

        repairBootstrapPermissions(onLog)
        BuildEnvironment.binDir.mkdirs()
        BuildEnvironment.libDir.mkdirs()
        BuildEnvironment.tmpDir.mkdirs()
        AiCodeLog.setup("Bootstrap runtime extracted successfully")
        onLog("Bootstrap runtime распакован")
        onProgress(0.34f, "Bootstrap shell готов")
        return isBootstrapHealthy().also { healthy ->
            if (!healthy) {
                AiCodeLog.setupError("Bootstrap extracted but health check failed")
                onLog("Bootstrap распакован, но проверка исполнимости не прошла")
            }
        }
    }

    private fun runIdeSetup(
        request: AndroidRuntimeSetupRequest,
        onProgress: (Float, String) -> Unit,
        onLog: (String) -> Unit,
    ): Boolean {
        val setupBinary = extractIdeSetupBinary()
        val command = buildList {
            add(setupBinary.absolutePath)
            add("--install-dir")
            add(BuildEnvironment.homeDir.absolutePath)
            add("--sdk")
            add(request.sdkVersion)
            add("--jdk")
            add(request.jdkVersion)
            add("--assume-yes")
            if (request.installGit) add("--with-git")
            if (request.installOpenSsh) add("--with-openssh")
        }

        onLog("Запуск: ${command.joinToString(" ")}")
        AiCodeLog.setup("Launching idesetup: ${command.joinToString(" ")}")

        val env = mutableMapOf<String, String>().apply {
            put("HOME", BuildEnvironment.homeDir.absolutePath)
            put("PREFIX", BuildEnvironment.usrDir.absolutePath)
            put("ANDROID_HOME", BuildEnvironment.androidSdkDir.absolutePath)
            put("ANDROID_SDK_ROOT", BuildEnvironment.androidSdkDir.absolutePath)
            put("TMPDIR", BuildEnvironment.tmpDir.absolutePath)
            put("LD_LIBRARY_PATH", BuildEnvironment.libDir.absolutePath)
            put(
                "PATH",
                listOf(
                    BuildEnvironment.binDir.absolutePath,
                    System.getenv("PATH").orEmpty(),
                ).filter { it.isNotBlank() }.joinToString(File.pathSeparator),
            )
            put("TERMUX_PKG_NO_MIRROR_SELECT", "true")
        }
        AiCodeLog.setup(
            "idesetup environment HOME=${env["HOME"]} PREFIX=${env["PREFIX"]} ANDROID_HOME=${env["ANDROID_HOME"]} PATH=${env["PATH"]}",
        )

        val process = try {
            ProcessBuilder(command)
                .directory(BuildEnvironment.homeDir)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(env)
                }
                .start()
        } catch (error: Exception) {
            AiCodeLog.setupError("Failed to start idesetup process: ${error.message}", error)
            onLog("Ошибка запуска idesetup: ${error.message}")
            return false
        }

        var progress = 0.45f
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                AiCodeLog.setup("idesetup output: $line")
                onLog(line)
                if (progress < 0.92f) {
                    progress += 0.01f
                    onProgress(progress, line)
                }
            }
        }

        val exitCode = process.waitFor()
        AiCodeLog.setup("idesetup finished with exitCode=$exitCode")
        onLog("idesetup завершился с кодом $exitCode")
        onProgress(if (exitCode == 0) 1f else progress, if (exitCode == 0) "Android runtime установлен" else "idesetup завершился с ошибкой")
        return exitCode == 0
    }

    private fun extractIdeSetupBinary(): File {
        val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)
        val nativeContents = nativeDir.listFiles()?.joinToString { it.name } ?: "<empty>"
        AiCodeLog.setup("nativeLibraryDir=${nativeDir.absolutePath} contents=$nativeContents")
        val nativeBinary = File(nativeDir, "libidesetup.so")
        if (nativeBinary.exists()) {
            AiCodeLog.setup("Using packaged idesetup from nativeLibraryDir: ${nativeBinary.absolutePath}")
            nativeBinary.setReadable(true, true)
            nativeBinary.setExecutable(true, true)
            return nativeBinary
        }

        val tempDir = File(appContext.filesDir, "temp").apply { mkdirs() }
        val binary = File(tempDir, "idesetup")
        appContext.assets.open("data/common/arm64/idesetup").use { input ->
            FileOutputStream(binary).use { output ->
                input.copyTo(output)
            }
        }
        AiCodeLog.setupWarn(
            "Packaged idesetup not found in nativeLibraryDir, falling back to temp asset copy: ${binary.absolutePath}",
        )
        binary.setReadable(true, true)
        binary.setExecutable(true, true)
        binary.setWritable(true, true)
        return binary
    }

    private fun shouldBeExecutable(entryName: String): Boolean {
        return entryName.startsWith("bin/") ||
            entryName.startsWith("libexec") ||
            entryName.startsWith("lib/apt/apt-helper") ||
            entryName.startsWith("lib/apt/methods")
    }

    private fun repairBootstrapPermissions(
        onLog: (String) -> Unit,
    ) {
        val directories = listOf(
            BuildEnvironment.rootDir,
            BuildEnvironment.usrDir,
            BuildEnvironment.binDir,
            BuildEnvironment.libDir,
            BuildEnvironment.homeDir,
            BuildEnvironment.tmpDir,
            File(BuildEnvironment.runtimeDir, "usr-staging"),
        )
        directories.forEach { directory ->
            if (directory.exists()) {
                chmod(directory, 0b111000000, "directory", onLog)
            }
        }

        val executableCandidates = listOf(
            File(BuildEnvironment.binDir, "sh"),
            File(BuildEnvironment.binDir, "bash"),
            File(BuildEnvironment.binDir, "login"),
        )
        executableCandidates.forEach { executable ->
            if (executable.exists()) {
                chmod(executable, 0b111000000, "executable", onLog)
            }
        }

        listOf(
            File(BuildEnvironment.usrDir, "libexec"),
            File(BuildEnvironment.usrDir, "lib/apt"),
            File(BuildEnvironment.usrDir, "lib/apt/methods"),
        ).forEach { directory ->
            if (directory.exists()) {
                chmod(directory, 0b111000000, "directory", onLog)
                directory.walkTopDown()
                    .filter { it.isFile }
                    .forEach { file ->
                        chmod(file, 0b111000000, "bootstrap tool", onLog)
                    }
            }
        }
    }

    private fun isBootstrapHealthy(): Boolean {
        val shell = File(BuildEnvironment.binDir, "sh")
        val bash = File(BuildEnvironment.binDir, "bash")
        val login = File(BuildEnvironment.binDir, "login")

        val shellHealthy = shell.exists() && shell.canExecute()
        val bashHealthy = bash.exists() && bash.canExecute()
        val loginHealthy = !login.exists() || login.canExecute()

        AiCodeLog.setup(
            "Bootstrap health sh=${shell.exists()}/${shell.canExecute()} bash=${bash.exists()}/${bash.canExecute()} login=${login.exists()}/${login.canExecute()}",
        )
        return shellHealthy && bashHealthy && loginHealthy
    }

    private fun chmod(
        file: File,
        mode: Int,
        label: String,
        onLog: (String) -> Unit,
    ) {
        runCatching { Os.chmod(file.absolutePath, mode) }
            .onFailure { error ->
                AiCodeLog.setupWarn("Failed to chmod $label ${file.absolutePath}: ${error.message}", error)
                onLog("Не удалось выставить права на ${file.absolutePath}: ${error.message}")
            }
    }
}
