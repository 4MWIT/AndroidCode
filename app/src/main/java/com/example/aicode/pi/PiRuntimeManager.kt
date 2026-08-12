package com.example.aicode.pi

import android.content.Context
import com.example.aicode.build.AndroidRuntimeInstaller
import com.example.aicode.build.BuildEnvironment
import com.example.aicode.logging.AiCodeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class PiRuntimeStatus(
    val bootstrapReady: Boolean,
    val nodeReady: Boolean,
    val piReady: Boolean,
    val bridgeReady: Boolean,
    val piVersion: String?,
    val bundledPiVersion: String,
    val piUpdateAvailable: Boolean,
    val details: String,
)

/** Owns the local, app-private Node + Pi installation. It never calls external Termux. */
class PiRuntimeManager(context: Context) {
    private val appContext = context.applicationContext
    private val runtimeInstaller = AndroidRuntimeInstaller(appContext)
    private val installDir: File
        get() = File(BuildEnvironment.homeDir, "pi-agent")
    private val bridgeFile: File
        get() = File(installDir, "pi-bridge.mjs")
    private val nodeBinary: File
        get() = File(BuildEnvironment.binDir, "node")
    private val dpkgBinary: File
        get() = File(BuildEnvironment.binDir, "dpkg")
    private val tarBinary: File
        get() = File(BuildEnvironment.binDir, "tar")
    private val piCli: File
        get() = File(installDir, "node_modules/@earendil-works/pi-coding-agent/dist/cli.js")

    @Volatile
    private var bridgeProcess: Process? = null

    suspend fun status(): PiRuntimeStatus = withContext(Dispatchers.IO) {
        BuildEnvironment.init(appContext)
        val bootstrapReady = File(BuildEnvironment.binDir, "bash").canExecute()
        val nodeReady = nodeBinary.canExecute()
        val piReady = piCli.isFile
        val piVersion = installedPiVersion()
        PiRuntimeStatus(
            bootstrapReady = bootstrapReady,
            nodeReady = nodeReady,
            piReady = piReady,
            bridgeReady = checkBridgeHealth(),
            piVersion = piVersion,
            bundledPiVersion = BUNDLED_PI_VERSION,
            piUpdateAvailable = piVersion != null && piVersion != BUNDLED_PI_VERSION,
            details = buildString {
                append("bash=${File(BuildEnvironment.binDir, "bash").exists()} ")
                append("node=${nodeBinary.exists()} pi=${piCli.exists()}")
            },
        )
    }

    suspend fun install(
        onProgress: (Float, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        BuildEnvironment.init(appContext)
        val shell = File(BuildEnvironment.binDir, "bash")
        if (!shell.canExecute()) {
            onProgress(0.02f, "Подготавливаю локальный runtime…")
            val baseReady = runtimeInstaller.install(
                onProgress = { progress, text -> onProgress(progress * 0.65f, text) },
                onLog = onLog,
            )
            if (!baseReady) return@withContext false
        }

        // Pi itself only requires a modern Node.js runtime. Installing Python and Git here
        // pulled a large OpenSSH/LLVM dependency tree before the agent could even start.
        // They belong to the project-toolchain setup, not the Pi bootstrap.
        onProgress(0.68f, "Распаковываю встроенный Node.js для Pi…")
        if (!installBundledNode(onLog)) {
            return@withContext false
        }
        if (!nodeBinary.canExecute()) {
            onLog("После распаковки не найден node в ${BuildEnvironment.binDir.absolutePath}")
            return@withContext false
        }

        installDir.mkdirs()
        copyBridgeIfNeeded()
        onProgress(0.85f, "Распаковываю встроенный Pi coding agent…")
        if (!installBundledPi(onLog)) {
            return@withContext false
        }
        val ready = piCli.isFile
        onProgress(if (ready) 1f else 0.9f, if (ready) "Pi готов к работе" else "Pi установлен не полностью")
        ready
    }

    suspend fun ensureBridge(onLog: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (checkBridgeHealth()) return@withContext true
        val runtime = status()
        if (!runtime.nodeReady || !runtime.piReady) {
            onLog("Pi runtime не готов: ${runtime.details}")
            return@withContext false
        }
        copyBridgeIfNeeded()
        bridgeProcess?.destroy()
        val process = ProcessBuilder(nodeBinary.absolutePath, bridgeFile.absolutePath)
            .directory(installDir)
            .redirectErrorStream(true)
            .apply {
                environment().putAll(BuildEnvironment.localRuntimeEnvironment())
                environment()["PI_INSTALL_DIR"] = installDir.absolutePath
                environment()["AI_CODE_PROJECTS_ROOT"] = BuildEnvironment.projectsDir.absolutePath
                environment()["AI_CODE_SESSION_ROOT"] = File(BuildEnvironment.homeDir, ".pi/sessions").absolutePath
                environment()["PI_CODING_AGENT_DIR"] = File(BuildEnvironment.homeDir, ".pi/agent").absolutePath
            }
            .start()
        bridgeProcess = process
        Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    AiCodeLog.agent("Pi bridge: $line")
                    onLog(line)
                }
            }
        }.apply { name = "pi-bridge"; isDaemon = true; start() }

        repeat(40) {
            if (checkBridgeHealth()) return@withContext true
            delay(250)
        }
        onLog("Pi bridge не ответил на health-check")
        false
    }

    fun stopBridge() {
        bridgeProcess?.destroy()
        bridgeProcess = null
    }

    private fun copyBridgeIfNeeded() {
        installDir.mkdirs()
        appContext.assets.open("pi-bridge.mjs").use { input ->
            bridgeFile.outputStream().use(input::copyTo)
        }
    }

    /** Installs pinned ARM64 Node packages already shipped inside the APK. No package mirror is contacted. */
    private fun installBundledNode(onLog: (String) -> Unit): Boolean {
        if (nodeBinary.canExecute()) return true
        if (!dpkgBinary.canExecute()) {
            onLog("Не найден встроенный dpkg: ${dpkgBinary.absolutePath}")
            return false
        }
        val packageDir = File(installDir, "bundled-node").apply { mkdirs() }
        val packages = listOf(
            "c-ares_1.34.5_aarch64.deb",
            "libicu_77.1-1_aarch64.deb",
            "libsqlite_3.50.4-1_aarch64.deb",
            "nodejs_24.7.0_aarch64.deb",
        ).map { assetName -> copyAsset("runtime/$assetName", File(packageDir, assetName)) }
        val command = "\"${dpkgBinary.absolutePath}\" -i " + packages.joinToString(" ") { "\"${it.absolutePath}\"" }
        return runShell(command, onLog)
    }

    /** Restores Pi and all of its JS dependencies that were packed at build time. */
    private fun installBundledPi(onLog: (String) -> Unit): Boolean {
        if (piCli.isFile && installedPiVersion() == BUNDLED_PI_VERSION) return true
        if (!tarBinary.canExecute()) {
            onLog("Не найден встроенный tar: ${tarBinary.absolutePath}")
            return false
        }
        // Keep the APK asset under a neutral extension. AAPT auto-decompresses .gz assets
        // and changes their visible name, while this file must stay gzip-compressed for tar.
        val archive = copyAsset("runtime/pi-agent-node-modules.bundle", File(installDir, "pi-agent.tar.gz"))
        return runShell("\"${tarBinary.absolutePath}\" -xzf \"${archive.absolutePath}\" -C \"${installDir.absolutePath}\"", onLog)
    }

    private fun installedPiVersion(): String? {
        val packageFile = File(installDir, "node_modules/@earendil-works/pi-coding-agent/package.json")
        if (!packageFile.isFile) return null
        return runCatching {
            org.json.JSONObject(packageFile.readText()).optString("version").ifBlank { null }
        }.getOrNull()
    }

    private fun copyAsset(assetName: String, destination: File): File {
        destination.parentFile?.mkdirs()
        appContext.assets.open(assetName).use { input ->
            destination.outputStream().use(input::copyTo)
        }
        return destination
    }

    private fun runShell(command: String, onLog: (String) -> Unit): Boolean {
        val shell = File(BuildEnvironment.binDir, "bash")
        val process = ProcessBuilder(shell.absolutePath, "-lc", command)
            .directory(BuildEnvironment.homeDir)
            .redirectErrorStream(true)
            .apply { environment().putAll(BuildEnvironment.localRuntimeEnvironment()) }
            .start()
        process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLog) }
        return process.waitFor() == 0
    }

    private fun checkBridgeHealth(): Boolean {
        return runCatching {
            val connection = URL("http://127.0.0.1:$BRIDGE_PORT/health").openConnection() as HttpURLConnection
            connection.connectTimeout = 700
            connection.readTimeout = 700
            connection.requestMethod = "GET"
            connection.responseCode == HttpURLConnection.HTTP_OK
        }.getOrDefault(false)
    }

    companion object {
        const val BRIDGE_PORT = 9877
        const val BUNDLED_PI_VERSION = "0.84.1"
    }
}
