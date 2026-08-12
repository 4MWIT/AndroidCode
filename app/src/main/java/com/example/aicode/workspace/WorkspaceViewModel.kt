package com.example.aicode.workspace

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aicode.acp.model.PermissionRequestParams
import com.example.aicode.agent.AgentAutopilotOrchestrator
import com.example.aicode.agent.AgentRunEvent
import com.example.aicode.agent.AgentRunRequest
import com.example.aicode.build.ApkBuildResult
import com.example.aicode.build.BuildListener
import com.example.aicode.build.BuildRequest
import com.example.aicode.build.BuildEnvironment
import com.example.aicode.build.GradleBuildManager
import com.example.aicode.doctor.DoctorCheck
import com.example.aicode.doctor.SystemDoctor
import com.example.aicode.logging.AiCodeLog
import com.example.aicode.settings.AgentRuntimeType
import com.example.aicode.settings.AppSettingsStore
import com.example.aicode.pi.ApiKeyStore
import com.example.aicode.pi.PiRuntimeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class WorkspaceProject(
    val id: String,
    val name: String,
    val path: String,
    val description: String,
    val chatIds: List<String>,
)

data class WorkspaceChatThread(
    val id: String,
    val projectId: String,
    val title: String,
    val summary: String,
)

enum class WorkspaceMessageAuthor {
    USER,
    ASSISTANT,
    SYSTEM,
}

data class WorkspaceMessage(
    val id: String,
    val chatId: String,
    val author: WorkspaceMessageAuthor,
    val text: String,
    val timeLabel: String,
    val toolCalls: List<String> = emptyList(),
)

data class WorkspaceBuildState(
    val isBuilding: Boolean = false,
    val status: String = "Сборка ещё не запускалась",
    val logs: List<String> = emptyList(),
    val lastApkPath: String? = null,
)

data class WorkspaceAuthState(
    val isAuthenticated: Boolean = false,
    val isAuthorizing: Boolean = false,
    val status: String = "Движок ещё не готов",
    val browserUrl: String? = null,
    val userCode: String? = null,
    val logs: List<String> = emptyList(),
    val error: String? = null,
)

data class WorkspaceUiState(
    val selectedRuntime: AgentRuntimeType = AgentRuntimeType.PI,
    val projects: List<WorkspaceProject> = emptyList(),
    val chats: List<WorkspaceChatThread> = emptyList(),
    val messages: List<WorkspaceMessage> = emptyList(),
    val selectedProjectId: String? = null,
    val selectedChatId: String? = null,
    val composerText: String = "",
    val agentStatus: String = "",
    val isAgentRunning: Boolean = false,
    val pendingPermission: PermissionRequestParams? = null,
    val auth: WorkspaceAuthState = WorkspaceAuthState(),
    val buildState: WorkspaceBuildState = WorkspaceBuildState(),
    val doctorChecks: List<DoctorCheck> = emptyList(),
)

class WorkspaceViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val buildManager = GradleBuildManager(appContext)
    private val systemDoctor = SystemDoctor(appContext)
    private val piRuntimeManager = PiRuntimeManager(appContext)
    private val apiKeyStore = ApiKeyStore(appContext)
    private val orchestrator = AgentAutopilotOrchestrator(appContext)
    private val settingsStore = AppSettingsStore(appContext)
    private val workspaceStateStore = appContext.getSharedPreferences("workspace_state", Application.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val extraChatsByProject = loadSavedChats().groupBy { it.projectId }
        .mapValues { (_, chats) -> chats.toMutableList() }
        .toMutableMap()
    private var projectToOpen: String? = null
    private var pendingMessageSave: Job? = null

    private val _uiState = MutableStateFlow(
        WorkspaceUiState(
            messages = loadSavedMessages(),
            selectedProjectId = workspaceStateStore.getString("selectedProjectId", null),
            selectedChatId = workspaceStateStore.getString("selectedChatId", null),
        ),
    )
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        AiCodeLog.agent("WorkspaceViewModel initialized")
        viewModelScope.launch {
            settingsStore.state.collect { settings ->
                _uiState.update { it.copy(selectedRuntime = settings.selectedRuntime) }
                refreshAuthStatus()
                refreshWorkspace()
            }
        }
        refreshWorkspace()
        refreshAuthStatus()
        preparePiRuntime()
    }

    /** The coding engine is mandatory, so it is unpacked immediately on application start. */
    private fun preparePiRuntime() {
        viewModelScope.launch {
            val status = piRuntimeManager.status()
            if (status.piReady) {
                _uiState.update { it.copy(agentStatus = "") }
                return@launch
            }
            _uiState.update { it.copy(agentStatus = "") }
            val ready = piRuntimeManager.install(
                onProgress = { _, _ -> Unit },
                onLog = { _ -> Unit },
            )
            _uiState.update {
                it.copy(
                    agentStatus = if (ready) "" else "Ошибка: не удалось подготовить движок",
                )
            }
            refreshAuthStatus()
        }
    }

    fun refreshWorkspace() {
        viewModelScope.launch {
            BuildEnvironment.init(appContext)
            val projects = loadProjects()
            migrateChatsToRealFolders(projects)
            val chats = buildChats(projects)
            val selectedProject = projectToOpen
                ?.let { requested -> projects.firstOrNull { it.id == requested } }
                ?: _uiState.value.selectedProjectId
                ?.let { current -> projects.firstOrNull { it.id == current } }
                ?: projects.firstOrNull()
            val selectedChat = _uiState.value.selectedChatId
                ?.let { current -> chats.firstOrNull { it.id == current } }
                ?: selectedProject?.let { project -> chats.firstOrNull { it.projectId == project.id } }

            val seededMessages = seedMessages(chats)
            val report = systemDoctor.collectReport()
            AiCodeLog.agent(
                "Workspace refreshed: projects=${projects.size} chats=${chats.size} selectedProject=${selectedProject?.name}",
            )

            _uiState.update { current ->
                current.copy(
                    projects = projects,
                    chats = chats,
                    messages = mergeMessages(current.messages, seededMessages),
                    selectedProjectId = selectedProject?.id,
                    selectedChatId = selectedChat?.id,
                    doctorChecks = report.checks,
                )
            }
            saveSelection()
            projectToOpen = null
        }
    }

    /** Adds the real folder selected by the user through Android's system picker. */
    fun importProject(treeUri: Uri) {
        viewModelScope.launch {
            val importer = WorkspaceFolderImporter(appContext)
            val directory = runCatching {
                withContext(Dispatchers.IO) { importer.addWorkspace(treeUri) }
            }
                .getOrElse { error ->
                    _uiState.update { it.copy(agentStatus = "Ошибка: ${error.message ?: "не удалось добавить папку"}") }
                    return@launch
                }
            projectToOpen = directory.absolutePath
            AiCodeLog.agent("Added user workspace folder ${directory.absolutePath}")
            refreshWorkspace()
        }
    }

    /** A chat is a separate prompt history, but always keeps the selected folder as agent context. */
    fun createChat(projectId: String? = _uiState.value.selectedProjectId) {
        val project = _uiState.value.projects.firstOrNull { it.id == projectId } ?: return
        val number = (extraChatsByProject[project.id]?.size ?: 0) + 1
        val chat = WorkspaceChatThread(
            id = "chat:${project.path}:user:${UUID.randomUUID()}",
            projectId = project.id,
            title = if (number == 1) "Новый чат" else "Новый чат $number",
            summary = "Контекст папки: ${project.name}",
        )
        extraChatsByProject.getOrPut(project.id) { mutableListOf() }.add(chat)
        _uiState.update { current ->
            current.copy(
                chats = current.chats + chat,
                selectedProjectId = project.id,
                selectedChatId = chat.id,
                messages = current.messages,
            )
        }
        saveChats()
        saveSelection()
        AiCodeLog.agent("Created chat ${chat.id} for project=${project.name}")
    }

    /** Removes only the folder reference from the app. The user's files are never deleted. */
    fun removeProject(projectId: String) {
        val project = _uiState.value.projects.firstOrNull { it.id == projectId } ?: return
        viewModelScope.launch {
            val removed = runCatching {
                withContext(Dispatchers.IO) {
                    WorkspaceFolderImporter(appContext).forgetWorkspace(project.path)
                }
            }.isSuccess
            if (!removed) {
                _uiState.update { it.copy(agentStatus = "Ошибка: не удалось удалить папку") }
                return@launch
            }
            extraChatsByProject.remove(project.id)
            saveChats()
            if (_uiState.value.selectedProjectId == project.id) projectToOpen = null
            _uiState.update { current ->
                current.copy(
                    selectedProjectId = null,
                    selectedChatId = null,
                    messages = current.messages.filterNot { message ->
                        current.chats.firstOrNull { it.id == message.chatId }?.projectId == project.id
                    },
                )
            }
            saveMessages()
            saveSelection()
            refreshWorkspace()
        }
    }

    /** Deletes one conversation and its local history. Project files are untouched. */
    fun removeChat(chatId: String) {
        val state = _uiState.value
        val chat = state.chats.firstOrNull { it.id == chatId } ?: return
        if (state.selectedChatId == chatId && state.isAgentRunning) orchestrator.cancel()
        extraChatsByProject[chat.projectId]?.removeAll { it.id == chatId }
        val remainingChats = state.chats.filterNot { it.id == chatId }
        val nextChatId = if (state.selectedChatId == chatId) {
            remainingChats.firstOrNull { it.projectId == chat.projectId }?.id
        } else {
            state.selectedChatId
        }
        _uiState.update {
            it.copy(
                chats = remainingChats,
                messages = it.messages.filterNot { message -> message.chatId == chatId },
                selectedChatId = nextChatId,
                isAgentRunning = if (state.selectedChatId == chatId) false else it.isAgentRunning,
                agentStatus = if (state.selectedChatId == chatId) "" else it.agentStatus,
            )
        }
        saveChats()
        saveMessages()
        saveSelection()
    }

    fun refreshAuthStatus() {
        viewModelScope.launch {
            val runtime = piRuntimeManager.status()
            val keyReady = apiKeyStore.hasKey(settingsStore.state.value.providerId)
            _uiState.update {
                it.copy(
                    auth = it.auth.copy(
                        isAuthenticated = keyReady,
                        isAuthorizing = false,
                        status = when {
                            !keyReady -> "Добавь API-ключ в настройках."
                            !runtime.piReady -> "API-ключ сохранён. Движок ещё готовится."
                            else -> "Всё готово."
                        },
                        error = null,
                        browserUrl = null,
                        userCode = null,
                    ),
                )
            }
        }
    }

    fun probeAuthStatus() {
        refreshAuthStatus()
    }

    fun selectProject(projectId: String) {
        AiCodeLog.agent("Selecting project id=$projectId")
        _uiState.update { current ->
            current.projects.firstOrNull { it.id == projectId } ?: return@update current
            val nextChatId = current.chats.firstOrNull { it.projectId == projectId }?.id
            current.copy(
                selectedProjectId = projectId,
                selectedChatId = nextChatId,
            )
        }
        saveSelection()
    }

    fun selectChat(chatId: String) {
        AiCodeLog.agent("Selecting chat id=$chatId")
        _uiState.update { current ->
            val chat = current.chats.firstOrNull { it.id == chatId } ?: return@update current
            current.copy(
                selectedProjectId = chat.projectId,
                selectedChatId = chatId,
            )
        }
        saveSelection()
    }

    fun updateComposer(text: String) {
        _uiState.update { it.copy(composerText = text) }
    }

    fun sendMessage() {
        var state = _uiState.value
        if (state.selectedChatId == null) {
            createChat(state.selectedProjectId)
            state = _uiState.value
        }
        val chatId = state.selectedChatId ?: return
        val text = state.composerText.trim()
        if (text.isBlank()) {
            return
        }
        if (!state.auth.isAuthenticated) {
            AiCodeLog.agentWarn("Agent prompt blocked because Pi API key is missing")
            appendSystemMessage(chatId, "Ошибка: сначала добавь API-ключ в настройках.")
            return
        }
        AiCodeLog.agent("Chat message sent for chatId=$chatId length=${text.length}")

        val now = timeLabel()
        val selectedProject = state.projects.firstOrNull { it.id == state.selectedProjectId }
        if (selectedProject == null) {
            appendSystemMessage(chatId, "Сначала выбери проект слева.")
            return
        }
        applyAutomaticChatTitle(chatId, text)

        val userMessage = WorkspaceMessage(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            author = WorkspaceMessageAuthor.USER,
            text = text,
            timeLabel = now,
        )
        val assistantMessageId = UUID.randomUUID().toString()
        val assistantMessage = WorkspaceMessage(
            id = assistantMessageId,
            chatId = chatId,
            author = WorkspaceMessageAuthor.ASSISTANT,
            text = "",
            timeLabel = now,
        )

        _uiState.update { current ->
            current.copy(
                composerText = "",
                agentStatus = "Думает",
                isAgentRunning = true,
                pendingPermission = null,
                messages = current.messages + userMessage + assistantMessage,
            )
        }
        saveMessages()

        val settings = settingsStore.state.value
        orchestrator.execute(
            request = AgentRunRequest(
                runtimeType = state.selectedRuntime,
                prompt = text,
                projectDir = File(selectedProject.path),
                allowFileWrite = settings.allowAgentWrite,
                allowShellCommands = settings.allowAgentShell,
                autoBuild = settings.autoBuild,
                autoInstall = settings.autoInstall,
                autoLaunch = settings.autoLaunch,
            ),
            listener = { event ->
                handleAgentEvent(
                    chatId = chatId,
                    assistantMessageId = assistantMessageId,
                    event = event,
                )
            },
        )
    }

    fun cancelAgent() {
        orchestrator.cancel()
        _uiState.update { it.copy(isAgentRunning = false, agentStatus = "") }
        saveMessages()
    }

    fun startRuntimeAuth() {
        refreshAuthStatus()
    }

    fun respondToPermission(granted: Boolean) {
        val request = _uiState.value.pendingPermission ?: return
        AiCodeLog.agent("Responding to ACP permission id=${request.id} granted=$granted")
        orchestrator.respondToPermission(request.id, granted)
        _uiState.update {
            it.copy(
                pendingPermission = null,
                agentStatus = if (granted) "Разрешение выдано, агент продолжает работу" else "Разрешение отклонено",
            )
        }
    }

    fun triggerBuild() {
        val state = _uiState.value
        val project = state.projects.firstOrNull { it.id == state.selectedProjectId }
        if (project == null) {
            AiCodeLog.agentWarn("Build requested without selected project")
            appendBuildLog("Сначала выбери проект.")
            return
        }

        val projectDir = File(project.path)
        val wrapper = File(projectDir, "gradlew")
        if (!wrapper.exists()) {
            AiCodeLog.agentWarn("Build requested but gradlew missing in ${projectDir.absolutePath}")
            _uiState.update {
                it.copy(
                    buildState = WorkspaceBuildState(
                        isBuilding = false,
                        status = "В выбранном проекте пока нет Gradle wrapper",
                        logs = appendLine(it.buildState.logs, "Не найден gradlew в ${projectDir.absolutePath}"),
                        lastApkPath = it.buildState.lastApkPath,
                    ),
                )
            }
            return
        }

        AiCodeLog.agent("Starting build for project=${project.name} path=${project.path}")
        _uiState.update {
            it.copy(
                buildState = WorkspaceBuildState(
                    isBuilding = true,
                    status = "Собираю APK для ${project.name}",
                    logs = listOf("Старт build pipeline для ${project.name}"),
                    lastApkPath = it.buildState.lastApkPath,
                ),
            )
        }

        buildManager.build(
            request = BuildRequest(projectDir = projectDir),
            listener = object : BuildListener {
                override fun onBuildStarted(request: BuildRequest, command: List<String>) {
                    AiCodeLog.agent("Build command started: ${command.joinToString(" ")}")
                    appendBuildLog("Команда: ${command.joinToString(" ")}")
                }

                override fun onBuildOutput(line: String) {
                    AiCodeLog.agent("Build output: $line")
                    appendBuildLog(line)
                }

                override fun onBuildFinished(result: ApkBuildResult) {
                    AiCodeLog.agent(
                        "Build finished success=${result.success} apk=${result.apkFile?.absolutePath} failure=${result.failureReason}",
                    )
                    _uiState.update { current ->
                        current.copy(
                            buildState = current.buildState.copy(
                                isBuilding = false,
                                status = when {
                                    result.success -> "APK собран"
                                    else -> result.failureReason ?: "Сборка завершилась ошибкой"
                                },
                                logs = appendLine(
                                    current.buildState.logs,
                                    if (result.success) {
                                        "Готово: ${result.apkFile?.absolutePath}"
                                    } else {
                                        "Ошибка: ${result.failureReason ?: "неизвестно"}"
                                    },
                                ),
                                lastApkPath = result.apkFile?.absolutePath ?: current.buildState.lastApkPath,
                            ),
                        )
                    }
                }
            },
        )
    }

    private fun appendBuildLog(line: String) {
        _uiState.update { current ->
            current.copy(
                buildState = current.buildState.copy(
                    logs = appendLine(current.buildState.logs, line),
                ),
            )
        }
    }

    private fun handleAgentEvent(
        chatId: String,
        assistantMessageId: String,
        event: AgentRunEvent,
    ) {
        when (event) {
            is AgentRunEvent.StageChanged -> {
                AiCodeLog.agent("Agent stage=${event.stage} message=${event.message}")
                _uiState.update {
                    it.copy(
                        agentStatus = if (event.stage == com.example.aicode.agent.AgentStage.FINISHED) "" else "Думает",
                        isAgentRunning = event.stage != com.example.aicode.agent.AgentStage.FINISHED,
                    )
                }
            }
            is AgentRunEvent.Processing -> {
                _uiState.update { it.copy(agentStatus = "Думает") }
            }
            is AgentRunEvent.Token -> {
                updateMessageText(assistantMessageId) { current -> current + event.token }
            }
            is AgentRunEvent.ToolCalling -> {
                addToolCall(assistantMessageId, event.toolName, event.details)
            }
            is AgentRunEvent.PermissionRequested -> {
                appendSystemMessage(chatId, "Агент просит доступ: ${event.request.description}")
                _uiState.update {
                    it.copy(
                        pendingPermission = event.request,
                        agentStatus = "Нужно подтвердить действие агента",
                    )
                }
            }
            is AgentRunEvent.FileModifying -> {
                appendBuildLog("Агент меняет: ${event.fileName}")
            }
            is AgentRunEvent.FileModified -> {
                appendBuildLog(
                    if (event.success) {
                        "Файл обновлён: ${event.filePath}"
                    } else {
                        "Не удалось обновить: ${event.filePath}"
                    },
                )
            }
            is AgentRunEvent.AgentFinished -> {
                if (event.response.isNotBlank()) {
                    updateMessageText(assistantMessageId) { event.response }
                }
            }
            is AgentRunEvent.BuildStarted -> {
                _uiState.update {
                    it.copy(
                        buildState = it.buildState.copy(
                            isBuilding = true,
                            status = "Сборка началась",
                            logs = appendLine(
                                appendLine(it.buildState.logs, "Команда: ${event.command.joinToString(" ")}"),
                                "Старт build pipeline",
                            ),
                        ),
                    )
                }
            }
            is AgentRunEvent.BuildOutput -> appendBuildLog(event.line)
            is AgentRunEvent.BuildFinished -> {
                _uiState.update {
                    it.copy(
                        buildState = it.buildState.copy(
                            isBuilding = false,
                            status = if (event.result.success) "APK собран" else event.result.failureReason ?: "Сборка упала",
                            logs = appendLine(
                                it.buildState.logs,
                                if (event.result.success) {
                                    "APK готов: ${event.result.apkFile?.absolutePath}"
                                } else {
                                    "Сборка завершилась ошибкой: ${event.result.failureReason}"
                                },
                            ),
                            lastApkPath = event.result.apkFile?.absolutePath ?: it.buildState.lastApkPath,
                        ),
                    )
                }
            }
            is AgentRunEvent.InstallStarted -> appendBuildLog("Установка APK: ${event.apkFile.absolutePath}")
            is AgentRunEvent.InstallStatus -> appendBuildLog(event.status)
            is AgentRunEvent.LaunchStarted -> appendBuildLog("Запускаю ${event.packageName}")
            is AgentRunEvent.LaunchFinished -> appendBuildLog(
                if (event.success) "Приложение запущено: ${event.packageName}" else "Не удалось запустить: ${event.packageName}",
            )
            is AgentRunEvent.Completed -> {
                _uiState.update {
                    it.copy(
                        agentStatus = "",
                        isAgentRunning = false,
                        pendingPermission = null,
                    )
                }
                saveMessages()
                refreshWorkspace()
            }
            is AgentRunEvent.Failed -> {
                appendSystemMessage(chatId, "Ошибка: ${event.message}")
                _uiState.update {
                    it.copy(
                        agentStatus = "Ошибка: ${event.message}",
                        isAgentRunning = false,
                        pendingPermission = null,
                    )
                }
                saveMessages()
            }
        }
    }

    private fun appendSystemMessage(chatId: String, text: String) {
        _uiState.update {
            it.copy(
                messages = it.messages + WorkspaceMessage(
                    id = UUID.randomUUID().toString(),
                    chatId = chatId,
                    author = WorkspaceMessageAuthor.SYSTEM,
                    text = text,
                    timeLabel = timeLabel(),
                ),
            )
        }
        saveMessages()
    }

    private fun addToolCall(messageId: String, toolName: String, details: String) {
        val compactDetails = details
            .replace('\n', ' ')
            .take(56)
            .ifBlank { "без параметров" }
        _uiState.update {
            it.copy(
                messages = it.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(toolCalls = message.toolCalls + "$toolName · $compactDetails")
                    } else message
                },
            )
        }
        scheduleMessageSave()
    }

    private fun updateMessageText(messageId: String, reducer: (String) -> String) {
        _uiState.update { current ->
            current.copy(
                messages = current.messages.map { message ->
                    if (message.id == messageId) {
                        message.copy(text = reducer(message.text))
                    } else {
                        message
                    }
                },
            )
        }
        scheduleMessageSave()
    }

    private fun loadProjects(): List<WorkspaceProject> {
        BuildEnvironment.init(appContext)
        // Legacy test workspace from the old automatic setup. It was never user-selected.
        File(BuildEnvironment.projectsDir, "starter-workspace").takeIf { it.exists() }?.deleteRecursively()
        val projectDirs = WorkspaceFolderImporter(appContext).workspaces()
            .filter { it.isDirectory }
            .sortedByDescending { it.lastModified() }

        return projectDirs.map { dir ->
            WorkspaceProject(
                id = dir.absolutePath,
                name = prettifyProjectName(dir.name),
                path = dir.absolutePath,
                description = describeProjectDir(dir),
                chatIds = emptyList(),
            )
        }
    }

    private fun buildChats(projects: List<WorkspaceProject>): List<WorkspaceChatThread> {
        return projects.flatMap { project ->
            extraChatsByProject[project.id].orEmpty()
        }
    }

    private fun loadSavedChats(): List<WorkspaceChatThread> {
        val raw = appContext.getSharedPreferences("workspace_chats", Application.MODE_PRIVATE)
            .getString("items", "[]") ?: "[]"
        return runCatching {
            val array = org.json.JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                WorkspaceChatThread(
                    id = item.getString("id"),
                    projectId = item.getString("projectId"),
                    title = item.getString("title"),
                    summary = item.getString("summary"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveChats() {
        val array = org.json.JSONArray()
        extraChatsByProject.values.flatten().forEach { chat ->
            array.put(org.json.JSONObject().apply {
                put("id", chat.id)
                put("projectId", chat.projectId)
                put("title", chat.title)
                put("summary", chat.summary)
            })
        }
        appContext.getSharedPreferences("workspace_chats", Application.MODE_PRIVATE)
            .edit().putString("items", array.toString()).apply()
    }

    private fun applyAutomaticChatTitle(chatId: String, firstPrompt: String) {
        if (_uiState.value.messages.any { it.chatId == chatId && it.author == WorkspaceMessageAuthor.USER }) return
        val currentChat = _uiState.value.chats.firstOrNull { it.id == chatId } ?: return
        if (!currentChat.title.startsWith("Новый чат")) return
        val clean = firstPrompt.replace(Regex("\\s+"), " ").trim()
        val title = if (clean.length <= 20) clean else clean.take(19).trimEnd() + "…"
        if (title.isBlank()) return
        val renamed = currentChat.copy(title = title)
        extraChatsByProject[currentChat.projectId]?.replaceAll { chat ->
            if (chat.id == chatId) renamed else chat
        }
        _uiState.update { state ->
            state.copy(chats = state.chats.map { chat -> if (chat.id == chatId) renamed else chat })
        }
        saveChats()
    }

    private fun loadSavedMessages(): List<WorkspaceMessage> {
        val raw = appContext.getSharedPreferences("workspace_messages", Application.MODE_PRIVATE)
            .getString("items", "[]") ?: "[]"
        return runCatching {
            val array = org.json.JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val tools = item.optJSONArray("toolCalls") ?: org.json.JSONArray()
                WorkspaceMessage(
                    id = item.getString("id"),
                    chatId = item.getString("chatId"),
                    author = WorkspaceMessageAuthor.valueOf(item.getString("author")),
                    text = item.optString("text"),
                    timeLabel = item.optString("timeLabel"),
                    toolCalls = List(tools.length()) { toolIndex -> tools.getString(toolIndex) },
                )
            }.filterNot { message ->
                message.author == WorkspaceMessageAuthor.ASSISTANT &&
                    message.text.isBlank() && message.toolCalls.isEmpty()
            }
        }.getOrDefault(emptyList())
    }

    private fun saveMessages() {
        pendingMessageSave?.cancel()
        pendingMessageSave = null
        val array = org.json.JSONArray()
        _uiState.value.messages.forEach { message ->
            array.put(org.json.JSONObject().apply {
                put("id", message.id)
                put("chatId", message.chatId)
                put("author", message.author.name)
                put("text", message.text)
                put("timeLabel", message.timeLabel)
                put("toolCalls", org.json.JSONArray(message.toolCalls))
            })
        }
        appContext.getSharedPreferences("workspace_messages", Application.MODE_PRIVATE)
            .edit().putString("items", array.toString()).apply()
    }

    /** Keeps streaming responsive while still checkpointing a long answer during generation. */
    private fun scheduleMessageSave() {
        pendingMessageSave?.cancel()
        pendingMessageSave = viewModelScope.launch {
            delay(400)
            saveMessages()
        }
    }

    private fun saveSelection() {
        workspaceStateStore.edit()
            .putString("selectedProjectId", _uiState.value.selectedProjectId)
            .putString("selectedChatId", _uiState.value.selectedChatId)
            .apply()
    }

    /** Maps chats created by the old private-copy implementation onto the matching real folder. */
    private fun migrateChatsToRealFolders(projects: List<WorkspaceProject>) {
        val knownProjectIds = projects.map { it.id }.toSet()
        val orphanKeys = extraChatsByProject.keys.filterNot { it in knownProjectIds }
        var changed = false
        orphanKeys.forEach { oldProjectId ->
            val oldChats = extraChatsByProject[oldProjectId].orEmpty()
            val oldName = File(oldProjectId).name
            val matches = projects.filter { File(it.path).name == oldName }
            if (oldChats.isNotEmpty() && matches.size == 1) {
                val target = matches.single()
                extraChatsByProject.remove(oldProjectId)
                extraChatsByProject.getOrPut(target.id) { mutableListOf() }
                    .addAll(oldChats.map { it.copy(projectId = target.id) })
                changed = true
            }
        }
        if (changed) saveChats()
    }

    /** A new chat starts clean. Service messages are reserved for actual errors only. */
    private fun seedMessages(chats: List<WorkspaceChatThread>): List<WorkspaceMessage> = emptyList()

    private fun mergeMessages(
        current: List<WorkspaceMessage>,
        seeded: List<WorkspaceMessage>,
    ): List<WorkspaceMessage> {
        val currentIds = current.map { it.id }.toSet()
        return current + seeded.filterNot { it.id in currentIds }
    }

    private fun describeProjectDir(dir: File): String {
        val hasGradle = File(dir, "gradlew").exists()
        return if (hasGradle) {
            "Готов к сборке и агентным правкам."
        } else {
            "Workspace создан, но Android-проект внутри ещё не развёрнут."
        }
    }

    private fun prettifyProjectName(raw: String): String {
        return raw
            .replace('-', ' ')
            .replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }

    private fun timeLabel(): String = dateFormat.format(Date())

    private fun appendLine(existing: List<String>, line: String): List<String> {
        if (line.isBlank()) return existing
        return (existing + line).takeLast(16)
    }

    override fun onCleared() {
        saveMessages()
        saveSelection()
        orchestrator.clear()
        super.onCleared()
    }
}
