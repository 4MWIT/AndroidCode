package com.example.aicode.acp.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: Long,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

const val METHOD_INITIALIZE = "initialize"
const val METHOD_SEND_PROMPT = "sendPrompt"
const val METHOD_RESPOND_PERMISSION = "respondPermission"
const val METHOD_CANCEL = "cancel"

const val METHOD_NOTIFY_PROCESSING = "notify/processing"
const val METHOD_NOTIFY_TOKEN = "notify/token"
const val METHOD_NOTIFY_COMPLETE = "notify/complete"
const val METHOD_NOTIFY_ERROR = "notify/error"
const val METHOD_REQUEST_PERMISSION = "request/permission"
const val METHOD_NOTIFY_FILE_MODIFY = "notify/fileModify"
const val METHOD_NOTIFY_FILE_MODIFIED = "notify/fileModified"

@Serializable
data class InitializeParams(
    val projectRoot: String,
    val model: String? = null,
    val capabilities: List<String> = listOf("fileRead", "fileWrite", "bash", "webSearch"),
)

@Serializable
data class SendPromptParams(
    val prompt: String,
    val context: PromptContext? = null,
)

@Serializable
data class PromptContext(
    val activeFile: String? = null,
    val selectedCode: String? = null,
    val openFiles: List<String> = emptyList(),
)

@Serializable
data class PermissionRequestParams(
    val id: Long,
    val type: String,
    val description: String,
    val details: Map<String, String> = emptyMap(),
)

@Serializable
data class PermissionResponse(
    val id: Long,
    val granted: Boolean,
)

@Serializable
data class ProcessingNotification(
    val message: String,
)

@Serializable
data class TokenNotification(
    val token: String,
)

@Serializable
data class CompleteNotification(
    val response: String,
    val modifications: List<FileModification>? = null,
)

@Serializable
data class ErrorNotification(
    val message: String,
    val code: Int? = null,
)

@Serializable
data class FileModifyNotification(
    val filePath: String,
    val fileName: String,
    val operation: String,
)

@Serializable
data class FileModifiedNotification(
    val filePath: String,
    val success: Boolean,
    val error: String? = null,
)

@Serializable
data class FileModification(
    val filePath: String,
    val operation: String,
    val diff: String? = null,
    val newContent: String? = null,
)
