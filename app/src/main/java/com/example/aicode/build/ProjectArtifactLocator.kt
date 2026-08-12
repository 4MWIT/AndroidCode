package com.example.aicode.build

import java.io.File

object ProjectArtifactLocator {

    fun findNewestApk(projectDir: File): File? {
        val apkRoot = File(projectDir, "app/build/outputs/apk")
        if (!apkRoot.exists()) {
            return null
        }

        return apkRoot
            .walkTopDown()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .maxByOrNull { it.lastModified() }
    }

    fun findApkOutputRoot(projectDir: File): File {
        return File(projectDir, "app/build/outputs/apk")
    }
}
