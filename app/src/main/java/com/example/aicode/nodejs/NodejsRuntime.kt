package com.example.aicode.nodejs

import android.content.Context
import com.example.aicode.build.BuildEnvironment
import com.example.aicode.logging.AiCodeLog
import com.example.aicode.settings.AgentRuntimeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class NodejsRuntime(private val context: Context) {

    companion object {
        const val QWEN_CLI_PACKAGE = "@qwen-code/qwen-code"
        const val QWEN_CLI_VERSION = "0.1.0"
        const val OPENCODE_CLI_PACKAGE = "opencode-ai"
        const val OPENCODE_CLI_VERSION = "0.3.105"
        private const val NODEJS_DIR_NAME = "nodejs"
        const val BRIDGE_PORT = 9876
        const val BRIDGE_HOST = "127.0.0.1"
        private val bridgeLock = Any()
        @Volatile
        private var globalBridgeThread: Thread? = null
        @Volatile
        private var globalBridgeRunning = false
        @Volatile
        private var globalBridgeStarting = false
    }

    private val nodejsDir = File(context.filesDir, NODEJS_DIR_NAME)

    @Volatile
    private var projectRoot: File = nodejsDir

    fun setProjectRoot(root: File?) {
        projectRoot = root?.takeIf { it.exists() && it.isDirectory } ?: nodejsDir
        AiCodeLog.agent("NodejsRuntime project root set to ${projectRoot.absolutePath}")
    }

    fun getQwenHomeDir(): File {
        BuildEnvironment.init(context)
        return BuildEnvironment.homeDir
    }

    private fun downloadFile(url: String, target: File, progressCallback: ((Float) -> Unit)? = null) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            connection.connectTimeout = 60000
            connection.readTimeout = 120000
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw java.io.IOException("Download failed: HTTP ${connection.responseCode} from $url")
            }
            val contentLength = connection.contentLength
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    var lastProgress = 0f
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (contentLength > 0) {
                            val progress = totalBytesRead.toFloat() / contentLength
                            if (progress - lastProgress > 0.05f) {
                                lastProgress = progress
                                progressCallback?.invoke(progress)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    fun isInstalled(): Boolean = NodeJsEngine.isAvailable()

    suspend fun install(progressCallback: ((progress: Float, message: String) -> Unit)? = null): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                progressCallback?.invoke(0.1f, "Инициализация Node.js...")
                if (!nodejsDir.exists()) {
                    nodejsDir.mkdirs()
                }
                val version = NodeJsEngine.getNodeVersion()
                AiCodeLog.agent("Node.js JNI loaded, version=$version")
                progressCallback?.invoke(1.0f, "Node.js готов: $version")
                true
            } catch (error: Exception) {
                AiCodeLog.agentError("Failed to initialize Node.js", error)
                false
            }
        }
    }

    private fun extractBridgeServer(): Boolean {
        return try {
            val bridgeTarget = File(nodejsDir, "bridge-server.js")
            context.assets.open("bridge-server.js").use { input ->
                FileOutputStream(bridgeTarget).use { output -> input.copyTo(output) }
            }
            AiCodeLog.agent("Bridge server extracted to ${bridgeTarget.absolutePath}")
            true
        } catch (error: Exception) {
            AiCodeLog.agentError("Failed to extract bridge server", error)
            false
        }
    }

    fun startBridge(progressCallback: ((progress: Float, message: String) -> Unit)? = null): Boolean {
        val callerTrace = Throwable().stackTrace
            .drop(1)
            .take(6)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        synchronized(bridgeLock) {
            if (globalBridgeRunning) {
                AiCodeLog.agent("Bridge already running; caller=$callerTrace")
                return true
            }
            if (globalBridgeStarting) {
                AiCodeLog.agent("Bridge startup already in progress, waiting for health; caller=$callerTrace")
                repeat(20) {
                    if (checkBridgeHealth()) {
                        globalBridgeRunning = true
                        globalBridgeStarting = false
                        progressCallback?.invoke(1.0f, "Bridge сервер уже поднялся")
                        return true
                    }
                    Thread.sleep(500)
                }
                globalBridgeStarting = false
                AiCodeLog.agentError("Bridge startup wait timed out")
                return false
            }

            if (!isInstalled()) {
                AiCodeLog.agentError("Node.js not installed, cannot start bridge")
                return false
            }
            if (!extractBridgeServer()) {
                return false
            }

            progressCallback?.invoke(0.0f, "Запуск bridge сервера...")
            AiCodeLog.agent("Starting bridge server for qwen at root=${projectRoot.absolutePath}; caller=$callerTrace")
            globalBridgeStarting = true

            val cliJsPath = File(nodejsDir, "lib/node_modules/$QWEN_CLI_PACKAGE/cli.js").absolutePath
            val wrapperJs = File(nodejsDir, "run-bridge.js")
            val bridgeLogFile = File(nodejsDir, "bridge-debug.log")
            bridgeLogFile.parentFile?.mkdirs()
            wrapperJs.writeText(
                buildString {
                    appendLine("// Auto-generated wrapper to set environment variables")
                    appendLine("process.env.QWEN_BRIDGE_PORT = '${BRIDGE_PORT}';")
                    appendLine("process.env.QWEN_CLI_PATH = '${cliJsPath.replace("\\", "\\\\")}';")
                    appendLine("process.env.QWEN_PROJECT_ROOT = '${projectRoot.absolutePath.replace("\\", "\\\\")}';")
                    appendLine("process.env.QWEN_NODE_DIR = '${File(nodejsDir, "lib").absolutePath.replace("\\", "\\\\")}';")
                    appendLine("process.env.HOME = '${getQwenHomeDir().absolutePath.replace("\\", "\\\\")}';")
                    appendLine("process.env.NODE_PATH = '${File(nodejsDir, "lib/node_modules").absolutePath.replace("\\", "\\\\")}';")
                    appendLine("process.env.QWEN_BRIDGE_LOG_FILE = '${bridgeLogFile.absolutePath.replace("\\", "\\\\")}';")
                    appendLine("require('./bridge-server.js');")
                },
            )

            globalBridgeThread = Thread {
                try {
                    val exitCode = NodeJsEngine.startNodeWithArguments(arrayOf("node", wrapperJs.absolutePath))
                    AiCodeLog.agent("Bridge server exited with code=$exitCode")
                } catch (error: Exception) {
                    AiCodeLog.agentError("Bridge server failed to start", error)
                } finally {
                    globalBridgeRunning = false
                    globalBridgeStarting = false
                }
            }.apply {
                name = "qwen-bridge"
                isDaemon = true
                start()
            }
        }

        repeat(20) {
            if (checkBridgeHealth()) {
                globalBridgeRunning = true
                globalBridgeStarting = false
                AiCodeLog.agent("Bridge health check passed on port $BRIDGE_PORT")
                progressCallback?.invoke(1.0f, "Bridge сервер запущен на порту $BRIDGE_PORT")
                return true
            }
            Thread.sleep(500)
        }

        AiCodeLog.agentError("Bridge health check failed after startup")
        dumpBridgeLog()
        val threadAlive = globalBridgeThread?.isAlive == true
        if (threadAlive) {
            AiCodeLog.agentWarn("Bridge health check failed, but bridge thread is still alive. Continuing like donor runtime.")
            globalBridgeRunning = true
            globalBridgeStarting = false
            progressCallback?.invoke(1.0f, "Bridge сервер поднят, хотя health-check не ответил")
            return true
        }
        globalBridgeRunning = false
        globalBridgeStarting = false
        return false
    }

    private fun checkBridgeHealth(): Boolean {
        return try {
            val url = URL("http://$BRIDGE_HOST:$BRIDGE_PORT/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val healthy = connection.responseCode == HttpURLConnection.HTTP_OK
            connection.disconnect()
            healthy
        } catch (_: Exception) {
            false
        }
    }

    fun stopBridge() {
        synchronized(bridgeLock) {
            if (!globalBridgeRunning && !globalBridgeStarting) {
                return
            }
            try {
                val url = URL("http://$BRIDGE_HOST:$BRIDGE_PORT/cancel")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 3000
                connection.connect()
                connection.disconnect()
            } catch (error: Exception) {
                AiCodeLog.agentWarn("Failed to gracefully stop bridge", error)
            }
            globalBridgeThread?.interrupt()
            globalBridgeThread = null
            globalBridgeRunning = false
            globalBridgeStarting = false
            AiCodeLog.agent("Bridge stopped")
        }
    }

    fun getBridgeUrl(): String = "http://$BRIDGE_HOST:$BRIDGE_PORT"

    private fun dumpBridgeLog() {
        try {
            val bridgeLogFile = File(nodejsDir, "bridge-debug.log")
            if (!bridgeLogFile.exists()) {
                AiCodeLog.agentWarn("Bridge debug log file does not exist: ${bridgeLogFile.absolutePath}")
                return
            }
            val lines = bridgeLogFile.readLines().takeLast(20)
            if (lines.isEmpty()) {
                AiCodeLog.agentWarn("Bridge debug log is empty: ${bridgeLogFile.absolutePath}")
                return
            }
            AiCodeLog.agent("Bridge debug log tail:")
            lines.forEach { line ->
                AiCodeLog.agent(line)
            }
        } catch (error: Exception) {
            AiCodeLog.agentWarn("Failed to dump bridge debug log", error)
        }
    }

    suspend fun uninstall(): Boolean = withContext(Dispatchers.IO) {
        try {
            stopBridge()
            if (nodejsDir.exists()) {
                nodejsDir.deleteRecursively()
            }
            AiCodeLog.agent("Node.js runtime data removed")
            true
        } catch (error: Exception) {
            AiCodeLog.agentError("Failed to uninstall Node.js data", error)
            false
        }
    }

    suspend fun isQwenCliInstalled(): Boolean {
        return File(nodejsDir, "lib/node_modules/$QWEN_CLI_PACKAGE/cli.js").exists()
    }

    suspend fun isOpenCodeCliInstalled(): Boolean {
        val packageDir = File(nodejsDir, "lib/node_modules/$OPENCODE_CLI_PACKAGE")
        return File(packageDir, "package.json").exists()
    }

    suspend fun isRuntimeCliInstalled(runtimeType: AgentRuntimeType): Boolean {
        return when (runtimeType) {
            AgentRuntimeType.PI -> false
            AgentRuntimeType.OPENCODE -> isOpenCodeCliInstalled()
            AgentRuntimeType.QWEN -> isQwenCliInstalled()
        }
    }

    suspend fun installQwenCli(progressCallback: ((progress: Float, message: String) -> Unit)? = null): Boolean {
        return installCliPackage(
            packageName = QWEN_CLI_PACKAGE,
            packageVersion = QWEN_CLI_VERSION,
            archiveFileName = "qwen-code.tgz",
            progressTitle = "Qwen Code",
            readyCheck = { targetDir -> File(targetDir, "cli.js").exists() },
            progressCallback = progressCallback,
        )
    }

    suspend fun installOpenCodeCli(progressCallback: ((progress: Float, message: String) -> Unit)? = null): Boolean {
        return installCliPackage(
            packageName = OPENCODE_CLI_PACKAGE,
            packageVersion = OPENCODE_CLI_VERSION,
            archiveFileName = "opencode-ai.tgz",
            progressTitle = "OpenCode",
            readyCheck = { targetDir -> File(targetDir, "package.json").exists() },
            progressCallback = progressCallback,
        )
    }

    suspend fun installRuntimeCli(
        runtimeType: AgentRuntimeType,
        progressCallback: ((progress: Float, message: String) -> Unit)? = null,
    ): Boolean {
        return when (runtimeType) {
            AgentRuntimeType.PI -> false
            AgentRuntimeType.OPENCODE -> installOpenCodeCli(progressCallback)
            AgentRuntimeType.QWEN -> installQwenCli(progressCallback)
        }
    }

    private suspend fun installCliPackage(
        packageName: String,
        packageVersion: String,
        archiveFileName: String,
        progressTitle: String,
        readyCheck: (File) -> Boolean,
        progressCallback: ((progress: Float, message: String) -> Unit)? = null,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!isInstalled()) {
                    AiCodeLog.agentError("Node.js is not installed, cannot install $progressTitle CLI")
                    return@withContext false
                }
                if (!nodejsDir.exists()) {
                    nodejsDir.mkdirs()
                }

                val packageTarget = File(nodejsDir, "lib/node_modules/$packageName")
                if (packageTarget.exists()) {
                    if (readyCheck(packageTarget)) {
                        return@withContext true
                    }
                    packageTarget.deleteRecursively()
                }

                progressCallback?.invoke(0.0f, "Скачиваю $progressTitle CLI v$packageVersion...")
                val tarballName = packageName.substringAfterLast('/')
                val tgzUrl = "https://registry.npmjs.org/$packageName/-/$tarballName-$packageVersion.tgz"
                val tgzFile = File(nodejsDir, archiveFileName)
                AiCodeLog.agent("Downloading $progressTitle CLI from $tgzUrl")
                downloadFile(tgzUrl, tgzFile) { progress ->
                    progressCallback?.invoke(progress * 0.6f, "Скачиваю... ${String.format("%.0f%%", progress * 100)}")
                }

                progressCallback?.invoke(0.65f, "Распаковываю $progressTitle...")
                packageTarget.mkdirs()
                GzipCompressorInputStream(tgzFile.inputStream()).use { gzip ->
                    TarArchiveInputStream(gzip).use { tar ->
                        var entry = tar.nextTarEntry
                        while (entry != null) {
                            val relativePath = entry.name.replaceFirst(Regex("^package/"), "")
                            val outFile = File(packageTarget, relativePath)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { tar.copyTo(it) }
                            }
                            entry = tar.nextTarEntry
                        }
                    }
                }
                tgzFile.delete()

                if (readyCheck(packageTarget)) {
                    AiCodeLog.agent("$progressTitle CLI installed successfully at ${packageTarget.absolutePath}")
                    progressCallback?.invoke(1.0f, "$progressTitle CLI установлен!")
                    true
                } else {
                    AiCodeLog.agentError("$progressTitle CLI install finished but validation failed")
                    progressCallback?.invoke(0.0f, "Ошибка: $progressTitle установлен не полностью")
                    false
                }
            } catch (error: Exception) {
                AiCodeLog.agentError("Failed to install $progressTitle CLI", error)
                progressCallback?.invoke(0.0f, "Ошибка: ${error.message}")
                false
            }
        }
    }

    suspend fun installAll(progressCallback: ((progress: Float, message: String) -> Unit)? = null): Boolean {
        if (!isInstalled()) {
            val nodeInstalled = install { progress, message ->
                progressCallback?.invoke(progress * 0.3f, message)
            }
            if (!nodeInstalled) {
                return false
            }
        }
        if (!isQwenCliInstalled()) {
            val qwenInstalled = installQwenCli { progress, message ->
                progressCallback?.invoke(0.3f + progress * 0.7f, message)
            }
            if (!qwenInstalled) {
                return false
            }
        }
        return true
    }

    suspend fun installEngine(
        runtimeType: AgentRuntimeType,
        progressCallback: ((progress: Float, message: String) -> Unit)? = null,
    ): Boolean {
        if (!isInstalled()) {
            val nodeInstalled = install { progress, message ->
                progressCallback?.invoke(progress * 0.3f, message)
            }
            if (!nodeInstalled) {
                return false
            }
        }
        if (!isRuntimeCliInstalled(runtimeType)) {
            val cliInstalled = installRuntimeCli(runtimeType) { progress, message ->
                progressCallback?.invoke(0.3f + progress * 0.7f, message)
            }
            if (!cliInstalled) {
                return false
            }
        }
        return true
    }
}
