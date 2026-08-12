package com.example.aicode.workspace

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Keeps the folders chosen by the user. A project is never imported or copied:
 * Pi works in this exact directory on the phone's shared storage.
 */
class WorkspaceFolderImporter(
    private val context: Context,
) {
    private val folders = context.getSharedPreferences("workspace_folders", Context.MODE_PRIVATE)
    // Folders picked before the direct-access update were stored under this name.
    // Re-resolve their original URI once, so they now point to the real folder too.
    private val legacyImports = context.getSharedPreferences("workspace_folder_imports", Context.MODE_PRIVATE)

    fun addWorkspace(treeUri: Uri): File {
        val access = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, access) }

        val directory = resolveLocalDirectory(treeUri)
        require(directory.isDirectory) { "Папка недоступна" }
        folders.edit().putString(treeUri.toString(), directory.absolutePath).apply()
        return directory
    }

    fun workspaces(): List<File> {
        val knownUris = folders.all.keys + legacyImports.all.keys
        val updated = knownUris.mapNotNull { rawUri ->
            runCatching { Uri.parse(rawUri) to resolveLocalDirectory(Uri.parse(rawUri)) }.getOrNull()
        }
        folders.edit().apply {
            clear()
            updated.forEach { (uri, directory) -> putString(uri.toString(), directory.absolutePath) }
            apply()
        }
        legacyImports.edit().clear().apply()
        return updated.map { it.second }.distinctBy { it.absolutePath }
    }

    fun forgetWorkspace(workspacePath: String) {
        val editor = folders.edit()
        folders.all
            .filterValues { it == workspacePath }
            .keys
            .forEach(editor::remove)
        editor.apply()
        // Legacy entries contain a former private path, so clearing them is safe.
        legacyImports.edit().clear().apply()
    }

    private fun resolveLocalDirectory(treeUri: Uri): File {
        require(treeUri.authority == EXTERNAL_STORAGE_DOCUMENTS) {
            "Выбери папку во внутреннем хранилище телефона"
        }
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val separator = documentId.indexOf(':')
        require(separator > 0) { "Не удалось определить путь папки" }
        val volume = documentId.substring(0, separator)
        require(volume.equals("primary", ignoreCase = true)) {
            "Пока поддерживается только внутренняя память телефона"
        }
        val relativePath = documentId.substring(separator + 1)
        return if (relativePath.isBlank()) {
            Environment.getExternalStorageDirectory()
        } else {
            File(Environment.getExternalStorageDirectory(), relativePath)
        }
    }

    private companion object {
        const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"
    }
}
