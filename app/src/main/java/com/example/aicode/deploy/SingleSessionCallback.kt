package com.example.aicode.deploy

import android.content.pm.PackageInstaller

open class SingleSessionCallback : PackageInstaller.SessionCallback() {
    override fun onCreated(sessionId: Int) = Unit
    override fun onBadgingChanged(sessionId: Int) = Unit
    override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit
    override fun onProgressChanged(sessionId: Int, progress: Float) = Unit
    override fun onFinished(sessionId: Int, success: Boolean) = Unit
}
