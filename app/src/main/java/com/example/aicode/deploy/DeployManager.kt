package com.example.aicode.deploy

import android.content.Context
import android.content.pm.PackageInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

data class DeployRequest(
    val apkFile: File,
    val packageNameHint: String? = null,
    val launchAfterInstall: Boolean = true,
    val timeoutMillis: Long = 180_000L,
)

data class DeployResult(
    val installResult: InstallationResult,
    val resolvedPackageName: String?,
    val launchRequested: Boolean,
    val launched: Boolean,
    val failureReason: String? = null,
) {
    val success: Boolean
        get() = installResult.status == PackageInstaller.STATUS_SUCCESS && (!launchRequested || launched)
}

interface DeployListener {
    fun onInstallStarted(request: DeployRequest) {}
    fun onInstallStatus(result: InstallationResult) {}
    fun onLaunchStarted(packageName: String) {}
    fun onLaunchFinished(packageName: String, launched: Boolean) {}
}

class DeployManager(
    context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun deploy(
        request: DeployRequest,
        listener: DeployListener = object : DeployListener {},
    ): DeployResult = withContext(Dispatchers.IO) {
        listener.onInstallStarted(request)

        val sender = InstallationResultHandler.createInstallResultSender(appContext)
        val deferred = CompletableDeferred<InstallationResult>()
        val eventListener: (InstallationResult) -> Unit = { result ->
            listener.onInstallStatus(result)
            if (isTerminal(result.status) && !deferred.isCompleted) {
                deferred.complete(result)
            }
        }

        InstallationEvents.addListener(eventListener)
        try {
            ApkInstaller.installApk(appContext, sender, request.apkFile)
            val installResult = withTimeout(request.timeoutMillis) { deferred.await() }
            val packageName = installResult.packageName
                ?: request.packageNameHint
                ?: resolvePackageName(request.apkFile)

            if (installResult.status != PackageInstaller.STATUS_SUCCESS) {
                return@withContext DeployResult(
                    installResult = installResult,
                    resolvedPackageName = packageName,
                    launchRequested = request.launchAfterInstall,
                    launched = false,
                    failureReason = installResult.message ?: "Установка завершилась со статусом ${installResult.status}",
                )
            }

            if (!request.launchAfterInstall) {
                return@withContext DeployResult(
                    installResult = installResult,
                    resolvedPackageName = packageName,
                    launchRequested = false,
                    launched = false,
                    failureReason = null,
                )
            }

            if (packageName == null) {
                return@withContext DeployResult(
                    installResult = installResult,
                    resolvedPackageName = null,
                    launchRequested = true,
                    launched = false,
                    failureReason = "После установки не удалось определить package name",
                )
            }

            listener.onLaunchStarted(packageName)
            val launched = AppLauncher.launchApp(appContext, packageName)
            listener.onLaunchFinished(packageName, launched)

            DeployResult(
                installResult = installResult,
                resolvedPackageName = packageName,
                launchRequested = true,
                launched = launched,
                failureReason = if (launched) null else "Приложение установилось, но не запустилось",
            )
        } finally {
            InstallationEvents.removeListener(eventListener)
        }
    }

    private fun resolvePackageName(apkFile: File): String? {
        return appContext.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)?.packageName
    }

    private fun isTerminal(status: Int): Boolean {
        return status != PackageInstaller.STATUS_PENDING_USER_ACTION
    }
}
