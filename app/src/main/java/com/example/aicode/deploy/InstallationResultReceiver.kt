package com.example.aicode.deploy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class InstallationResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        InstallationEvents.dispatch(InstallationResultHandler.parseResult(context, intent) ?: return)
    }
}
