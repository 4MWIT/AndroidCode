package com.example.aicode.deploy

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller

data class InstallationResult(
    val packageName: String?,
    val status: Int,
    val message: String?,
)

object InstallationResultHandler {

    private const val REQUEST_CODE_INSTALL = 2304
    const val INSTALL_PACKAGE_ACTION = "com.example.aicode.action.INSTALL_PACKAGE"

    fun createInstallResultSender(context: Context): IntentSender {
        val intent = Intent(context, InstallationResultReceiver::class.java).apply {
            action = INSTALL_PACKAGE_ACTION
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_INSTALL,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).intentSender
    }

    fun parseResult(context: Context, intent: Intent): InstallationResult? {
        if (intent.action != INSTALL_PACKAGE_ACTION) {
            return null
        }

        val extras = intent.extras ?: return null
        val packageName = extras.getString(PackageInstaller.EXTRA_PACKAGE_NAME)
        val status = extras.getInt(PackageInstaller.EXTRA_STATUS)
        val message = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmationIntent = extras.get(Intent.EXTRA_INTENT) as? Intent
            confirmationIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            confirmationIntent?.let(context::startActivity)
            return InstallationResult(packageName, status, message)
        }

        return InstallationResult(packageName, status, message)
    }
}
