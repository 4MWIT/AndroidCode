package com.example.aicode.acp

import com.example.aicode.acp.model.FileModification
import com.example.aicode.acp.model.PermissionRequestParams

interface AcpCallback {
    fun onProcessing(message: String) {}
    fun onToken(token: String) {}
    fun onComplete(response: String, modifications: List<FileModification>) {}
    fun onError(message: String) {}
    fun onPermissionRequest(request: PermissionRequestParams) {}
    fun onFileModifying(filePath: String, fileName: String) {}
    fun onFileModified(filePath: String, success: Boolean) {}
    fun onAuthLog(line: String) {}
    fun onAuthUrl(url: String, userCode: String?) {}
    fun onAuthCompleted(
        success: Boolean,
        authenticated: Boolean,
        authType: String?,
        message: String?,
    ) {
    }
}
