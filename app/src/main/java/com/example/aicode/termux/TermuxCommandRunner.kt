package com.example.aicode.termux

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.aicode.logging.AiCodeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class TermuxCommandRequest(
    val commandPath: String,
    val arguments: Array<String> = emptyArray(),
    val stdin: String? = null,
    val workdir: String = "~/",
    val background: Boolean = true,
    val commandLabel: String = "AiCode command",
    val commandDescription: String = "",
    val timeoutMs: Long = 300_000,
)

class TermuxCommandRunner(
    private val context: Context,
) {
    private val appContext = context.applicationContext

    fun isTermuxInstalled(): Boolean {
        return runCatching {
            appContext.packageManager.getPackageInfo(TermuxConstants.PACKAGE_NAME, 0)
            true
        }.getOrDefault(false)
    }

    fun isRunCommandPermissionGranted(): Boolean {
        return appContext.packageManager.checkPermission(
            TermuxConstants.RUN_COMMAND_PERMISSION,
            appContext.packageName,
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun runCommand(request: TermuxCommandRequest): TermuxCommandResult = withContext(Dispatchers.IO) {
        if (!isTermuxInstalled()) {
            return@withContext TermuxCommandResult(
                executionId = -1,
                stdout = "",
                stderr = "",
                exitCode = -1,
                errCode = -1,
                errMsg = "Termux не установлен",
            )
        }
        if (!isRunCommandPermissionGranted()) {
            return@withContext TermuxCommandResult(
                executionId = -1,
                stdout = "",
                stderr = "",
                exitCode = -1,
                errCode = -1,
                errMsg = "Для работы нужен permission com.termux.permission.RUN_COMMAND",
            )
        }

        val (executionId, deferred) = TermuxCommandResultStore.createRequest()
        val callbackIntent = Intent(appContext, TermuxCommandResultReceiver::class.java).apply {
            setPackage(appContext.packageName)
            putExtra(TermuxConstants.EXTRA_EXECUTION_ID, executionId)
        }
        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            executionId,
            callbackIntent,
            flags,
        )

        val intent = Intent().apply {
            component = ComponentName(TermuxConstants.PACKAGE_NAME, TermuxConstants.RUN_COMMAND_SERVICE)
            action = TermuxConstants.ACTION_RUN_COMMAND
            putExtra(TermuxConstants.EXTRA_COMMAND_PATH, request.commandPath)
            putExtra(TermuxConstants.EXTRA_ARGUMENTS, request.arguments)
            putExtra(TermuxConstants.EXTRA_WORKDIR, request.workdir)
            putExtra(TermuxConstants.EXTRA_BACKGROUND, request.background)
            putExtra(TermuxConstants.EXTRA_SESSION_ACTION, TermuxConstants.SESSION_ACTION_OPEN)
            putExtra(TermuxConstants.EXTRA_COMMAND_LABEL, request.commandLabel)
            putExtra(TermuxConstants.EXTRA_COMMAND_DESCRIPTION, request.commandDescription)
            putExtra(TermuxConstants.EXTRA_PENDING_INTENT, pendingIntent)
            request.stdin?.let { putExtra(TermuxConstants.EXTRA_STDIN, it) }
        }

        return@withContext runCatching {
            AiCodeLog.agent(
                "Sending Termux command executionId=$executionId label=${request.commandLabel} background=${request.background}",
            )
            appContext.startService(intent)
            withTimeout(request.timeoutMs) {
                deferred.await()
            }
        }.getOrElse { error ->
            AiCodeLog.agentError("Termux command failed to start: ${error.message}", error)
            TermuxCommandResultStore.fail(executionId, error.message ?: "Не удалось запустить команду в Termux")
            TermuxCommandResult(
                executionId = executionId,
                stdout = "",
                stderr = "",
                exitCode = -1,
                errCode = -1,
                errMsg = error.message ?: "Не удалось запустить команду в Termux",
            )
        }
    }
}
