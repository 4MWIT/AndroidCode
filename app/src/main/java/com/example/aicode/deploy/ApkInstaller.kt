package com.example.aicode.deploy

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

object ApkInstaller {

    fun installApk(
        context: Context,
        sender: IntentSender,
        apk: File,
        callback: PackageInstaller.SessionCallback = SingleSessionCallback(),
    ) {
        require(apk.exists() && apk.isFile && apk.extension.equals("apk", ignoreCase = true)) {
            "File is not an APK: ${apk.absolutePath}"
        }

        var session: PackageInstaller.Session? = null
        try {
            val installer = context.packageManager.packageInstaller.apply {
                registerSessionCallback(callback)
            }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)
            addToSession(session, apk)
            session.commit(sender)
        } catch (error: Exception) {
            session?.abandon()
            fallbackInstall(context, apk, error)
        }
    }

    private fun addToSession(session: PackageInstaller.Session, apk: File) {
        val length = apk.length()
        if (length == 0L) {
            throw IOException("APK is empty: ${apk.absolutePath}")
        }

        session.openWrite(apk.name, 0, length).use { output ->
            apk.inputStream().use { input ->
                input.copyTo(output)
            }
            session.fsync(output)
        }
    }

    private fun fallbackInstall(
        context: Context,
        apk: File,
        originalError: Exception,
    ) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }

        runCatching {
            context.startActivity(intent)
        }.getOrElse {
            throw IllegalStateException(
                "Session install failed: ${originalError.message}; fallback install failed: ${it.message}",
                it,
            )
        }
    }
}
