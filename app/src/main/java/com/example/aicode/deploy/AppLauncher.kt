package com.example.aicode.deploy

import android.content.Context
import android.content.Intent
import android.os.Build

object AppLauncher {

    private const val REQUEST_CODE_LAUNCH = 223

    fun launchApp(context: Context, packageName: String): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            launchWithIntentSender(context, packageName)
        } else {
            launchWithIntent(context, packageName)
        }
    }

    private fun launchWithIntent(context: Context, packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private fun launchWithIntentSender(context: Context, packageName: String): Boolean {
        return runCatching {
            val sender = context.packageManager.getLaunchIntentSenderForPackage(packageName)
            sender.sendIntent(context, REQUEST_CODE_LAUNCH, null, null, null)
            true
        }.getOrDefault(false)
    }
}
