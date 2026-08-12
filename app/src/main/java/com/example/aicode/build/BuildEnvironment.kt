package com.example.aicode.build

import android.annotation.SuppressLint
import android.content.Context
import com.example.aicode.logging.AiCodeLog
import java.io.File
import java.util.UUID

@SuppressLint("SdCardPath")
object BuildEnvironment {

    private var initialized = false
    private const val LEGACY_RUNTIME_DIR_NAME = "runtime"

    lateinit var rootDir: File
        private set
    lateinit var runtimeDir: File
        private set
    lateinit var usrDir: File
        private set
    lateinit var homeDir: File
        private set
    lateinit var androidUserHomeDir: File
        private set
    lateinit var tmpDir: File
        private set
    lateinit var binDir: File
        private set
    lateinit var libDir: File
        private set
    lateinit var androidSdkDir: File
        private set
    lateinit var gradleUserHome: File
        private set
    lateinit var projectsDir: File
        private set
    lateinit var defaultJavaHome: File
        private set
    lateinit var defaultJavaBinary: File
        private set

    @Synchronized
    fun init(context: Context) {
        if (initialized) {
            return
        }

        rootDir = context.filesDir
        migrateLegacyRuntimeLayout(rootDir)

        // Termux/bootstrap/idesetup expect the classic layout directly under files/:
        //   files/usr
        //   files/home
        //   files/usr-staging
        runtimeDir = rootDir
        usrDir = ensureDir(File(rootDir, "usr"))
        homeDir = ensureDir(File(rootDir, "home"))
        androidUserHomeDir = ensureDir(File(homeDir, ".android"))
        tmpDir = ensureDir(File(usrDir, "tmp"))
        binDir = ensureDir(File(usrDir, "bin"))
        libDir = ensureDir(File(usrDir, "lib"))
        androidSdkDir = ensureDir(File(homeDir, "android-sdk"))
        gradleUserHome = ensureDir(File(homeDir, ".gradle"))
        // The agent and its shell live in this app's process. Keeping the workspaces
        // private avoids the Android/data cross-app storage wall and keeps executable
        // project tools off noexec shared storage.
        projectsDir = ensureDir(File(rootDir, "projects"))

        val java17 = File(usrDir, "lib/jvm/java-17-openjdk")
        val java21 = File(usrDir, "lib/jvm/java-21-openjdk")
        defaultJavaHome = when {
            java17.exists() -> java17
            java21.exists() -> java21
            else -> java17
        }
        defaultJavaBinary = File(defaultJavaHome, "bin/java")
        AiCodeLog.setup(
            "BuildEnvironment initialized root=${rootDir.absolutePath} usr=${usrDir.absolutePath} home=${homeDir.absolutePath} projects=${projectsDir.absolutePath}",
        )
        initialized = true
    }

    fun ensureInitialized() {
        check(initialized) { "BuildEnvironment.init(context) must be called before use" }
    }

    fun buildProcessEnvironment(
        projectDir: File,
        toolchain: BuildToolchain,
    ): MutableMap<String, String> {
        ensureInitialized()

        val env = mutableMapOf<String, String>()
        env["HOME"] = homeDir.absolutePath
        env["PREFIX"] = usrDir.absolutePath
        env["ANDROID_HOME"] = toolchain.sdkDir.absolutePath
        env["ANDROID_SDK_ROOT"] = toolchain.sdkDir.absolutePath
        env["ANDROID_USER_HOME"] = androidUserHomeDir.absolutePath
        env["JAVA_HOME"] = toolchain.javaHome.absolutePath
        env["GRADLE_USER_HOME"] = toolchain.gradleUserHome.absolutePath
        env["TMPDIR"] = toolchain.tmpDir.absolutePath
        env["PROJECT_ROOT"] = projectDir.absolutePath

        env.putAll(TermuxLikeShellEnvironment.create(toolchain))
        return env
    }

    fun createTempFile(prefix: String = "build"): File {
        ensureInitialized()
        var candidate = File(tmpDir, "$prefix-${UUID.randomUUID()}")
        while (candidate.exists()) {
            candidate = File(tmpDir, "$prefix-${UUID.randomUUID()}")
        }
        return candidate
    }

    fun localRuntimeEnvironment(projectDir: File? = null): MutableMap<String, String> {
        ensureInitialized()
        return mutableMapOf<String, String>().apply {
            put("HOME", homeDir.absolutePath)
            put("PREFIX", usrDir.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            put("ANDROID_HOME", androidSdkDir.absolutePath)
            put("ANDROID_SDK_ROOT", androidSdkDir.absolutePath)
            put("PATH", listOf(binDir.absolutePath, System.getenv("PATH").orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(File.pathSeparator))
            put("LD_LIBRARY_PATH", listOf(libDir.absolutePath, System.getenv("LD_LIBRARY_PATH").orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(File.pathSeparator))
            projectDir?.let { put("PROJECT_ROOT", it.absolutePath) }
        }
    }

    private fun ensureDir(file: File): File {
        if (!file.exists()) {
            file.mkdirs()
        }
        return file
    }

    private fun migrateLegacyRuntimeLayout(rootDir: File) {
        val legacyRuntimeDir = File(rootDir, LEGACY_RUNTIME_DIR_NAME)
        if (!legacyRuntimeDir.exists() || !legacyRuntimeDir.isDirectory) {
            return
        }

        AiCodeLog.setup("Migrating legacy runtime layout from ${legacyRuntimeDir.absolutePath}")
        moveIfNeeded(File(legacyRuntimeDir, "usr"), File(rootDir, "usr"))
        moveIfNeeded(File(legacyRuntimeDir, "home"), File(rootDir, "home"))

        // Old experiments stored temp/runtime state here. Keep it if still useful,
        // otherwise clean up the empty container.
        if (legacyRuntimeDir.listFiles().isNullOrEmpty()) {
            legacyRuntimeDir.delete()
        }
    }

    private fun moveIfNeeded(from: File, to: File) {
        if (!from.exists() || to.exists()) {
            return
        }

        AiCodeLog.setup("Moving runtime path ${from.absolutePath} -> ${to.absolutePath}")
        to.parentFile?.mkdirs()
        if (!from.renameTo(to)) {
            AiCodeLog.setupWarn("Rename failed for ${from.absolutePath}, copying recursively instead")
            copyRecursively(from, to)
            from.deleteRecursively()
        }
    }

    private fun copyRecursively(from: File, to: File) {
        if (from.isDirectory) {
            if (!to.exists()) {
                to.mkdirs()
            }
            from.listFiles()?.forEach { child ->
                copyRecursively(child, File(to, child.name))
            }
            return
        }

        to.parentFile?.mkdirs()
        from.inputStream().use { input ->
            to.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        to.setExecutable(from.canExecute(), false)
        to.setReadable(true, false)
        to.setWritable(true, true)
    }
}
