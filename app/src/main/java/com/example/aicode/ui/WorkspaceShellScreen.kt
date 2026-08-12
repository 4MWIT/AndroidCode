package com.example.aicode.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aicode.workspace.WorkspaceChatThread
import com.example.aicode.workspace.WorkspaceMessage
import com.example.aicode.workspace.WorkspaceMessageAuthor
import com.example.aicode.workspace.WorkspaceProject
import com.example.aicode.workspace.WorkspaceUiState
import com.example.aicode.workspace.WorkspaceViewModel
import kotlinx.coroutines.launch

private val Ink = Color(0xFF17212B)
private val Canvas = Color(0xFFF8F9FC)
private val Drawer = Color(0xFFF8F9FC)
private val DrawerSelected = Color(0xFFDDEFFC)
private val Muted = Color(0xFF6B7280)
private val SkyContainer = Color(0xFFDDEFFC)
private val MintContainer = Color(0xFFD7F6EF)
private val CoralContainer = Color(0xFFFFE8DD)

@Composable
fun WorkspaceShellScreen(
    onOpenSettings: () -> Unit,
    viewModel: WorkspaceViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var openPickerAfterStoragePermission by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::importProject)
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.refreshWorkspace()
        if (granted && openPickerAfterStoragePermission) folderPicker.launch(null)
        openPickerAfterStoragePermission = false
    }
    var projectToDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<WorkspaceProject?>(null) }
    var chatToDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<WorkspaceChatThread?>(null) }

    // The API key is edited on the settings screen. Re-check it every time this
    // screen returns to composition so the very next prompt can use it.
    LaunchedEffect(Unit) {
        viewModel.probeAuthStatus()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ProjectDrawer(
                state = state,
                onSelectProject = { id ->
                    viewModel.selectProject(id)
                    scope.launch { drawerState.close() }
                },
                onSelectChat = { id ->
                    viewModel.selectChat(id)
                    scope.launch { drawerState.close() }
                },
                onCreateProject = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                        folderPicker.launch(null)
                    } else {
                        openPickerAfterStoragePermission = true
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                },
                onCreateChat = viewModel::createChat,
                onRequestDelete = { projectToDelete = it },
                onRequestDeleteChat = { chatToDelete = it },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    onOpenSettings()
                },
            )
        },
    ) {
        ChatScreen(
            state = state,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onCreateChat = viewModel::createChat,
            onTextChange = viewModel::updateComposer,
            onSend = viewModel::sendMessage,
            onStop = viewModel::cancelAgent,
            onRespondPermission = viewModel::respondToPermission,
        )
    }

    projectToDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text("Удалить папку?") },
            text = {
                Text("«${project.name}» исчезнет только из списка AIcode. Файлы в папке телефона останутся на месте.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeProject(project.id)
                    projectToDelete = null
                }) { Text("Удалить", color = Color(0xFFB3261E)) }
            },
            dismissButton = { TextButton(onClick = { projectToDelete = null }) { Text("Отмена") } },
        )
    }

    chatToDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { chatToDelete = null },
            title = { Text("Удалить чат?") },
            text = { Text("«${chat.title}» и вся его история будут удалены. Файлы проекта не изменятся.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeChat(chat.id)
                    chatToDelete = null
                }) { Text("Удалить", color = Color(0xFFB3261E)) }
            },
            dismissButton = { TextButton(onClick = { chatToDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun ProjectDrawer(
    state: WorkspaceUiState,
    onSelectProject: (String) -> Unit,
    onSelectChat: (String) -> Unit,
    onCreateProject: () -> Unit,
    onCreateChat: () -> Unit,
    onRequestDelete: (WorkspaceProject) -> Unit,
    onRequestDeleteChat: (WorkspaceChatThread) -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 332.dp),
        drawerContainerColor = Drawer,
        drawerContentColor = Ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("AIcode", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                Text("ПРОЕКТЫ", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable(onClick = onCreateProject),
                color = MintContainer,
                shape = RoundedCornerShape(22.dp),
            ) {
                Text(
                    "+  Добавить папку",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    color = Color(0xFF004D45),
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(12.dp))

            if (state.projects.isEmpty()) {
                Text(
                    "Здесь появятся папки с проектами.",
                    color = Muted,
                    modifier = Modifier.padding(12.dp),
                )
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                state.projects.forEach { project ->
                    item(key = project.id) {
                        ProjectSection(
                            project = project,
                            chats = state.chats.filter { it.projectId == project.id },
                            selectedProjectId = state.selectedProjectId,
                            selectedChatId = state.selectedChatId,
                            onSelectProject = onSelectProject,
                            onSelectChat = onSelectChat,
                            onCreateChat = onCreateChat,
                            onRequestDelete = onRequestDelete,
                            onRequestDeleteChat = onRequestDeleteChat,
                        )
                    }
                }
            }

            Divider(color = Color(0xFFD9E1EA), modifier = Modifier.padding(vertical = 10.dp))
            DrawerAction(label = "⚙  Настройки", onClick = onOpenSettings)
        }
    }
}

@Composable
private fun ProjectSection(
    project: WorkspaceProject,
    chats: List<WorkspaceChatThread>,
    selectedProjectId: String?,
    selectedChatId: String?,
    onSelectProject: (String) -> Unit,
    onSelectChat: (String) -> Unit,
    onCreateChat: () -> Unit,
    onRequestDelete: (WorkspaceProject) -> Unit,
    onRequestDeleteChat: (WorkspaceChatThread) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    onClick = { onSelectProject(project.id) },
                    onLongClick = { onRequestDelete(project) },
                )
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                project.name,
                modifier = Modifier.weight(1f),
                fontWeight = if (project.id == selectedProjectId) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "+",
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSelectProject(project.id); onCreateChat() }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                color = Muted,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        chats.forEach { chat ->
            val selected = chat.id == selectedChatId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) DrawerSelected else Color.Transparent)
                    .combinedClickable(
                        onClick = { onSelectChat(chat.id) },
                        onLongClick = { onRequestDeleteChat(chat) },
                    )
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    chat.title,
                    modifier = Modifier.weight(1f),
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun DrawerAction(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ChatScreen(
    state: WorkspaceUiState,
    onOpenDrawer: () -> Unit,
    onCreateChat: () -> Unit,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onRespondPermission: (Boolean) -> Unit,
) {
    val selectedProject = state.projects.firstOrNull { it.id == state.selectedProjectId }
    val messages = state.messages.filter { it.chatId == state.selectedChatId }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, messages.lastOrNull()?.text, messages.lastOrNull()?.toolCalls?.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas),
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
                        Text(
                            text = selectedProject?.let { "Что соберём сегодня в\n«${it.name}»?" } ?: "Сначала выбери папку",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Ink,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (state.auth.isAuthenticated) "Опиши задачу — агент работает в контексте выбранной папки."
                            else "Добавь API-ключ в настройках, затем опиши задачу.",
                            color = Muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        top = 56.dp,
                        end = 16.dp,
                        bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(messages.size, key = { messages[it].id }) { index ->
                        ChatMessageBubble(messages[index])
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactTopButton(onClick = onOpenDrawer) {
                    Text("☰", color = Ink, style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.weight(1f))
                CompactTopButton(
                    enabled = selectedProject != null,
                    onClick = onCreateChat,
                ) {
                    Text(
                        "✎",
                        color = if (selectedProject != null) Ink else Muted.copy(alpha = 0.45f),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }

        if (state.pendingPermission != null) {
            PermissionBar(
                text = state.pendingPermission.description,
                onGrant = { onRespondPermission(true) },
                onDeny = { onRespondPermission(false) },
            )
        }
        ChatComposer(
            value = state.composerText,
            enabled = state.selectedProjectId != null,
            isStreaming = state.isAgentRunning,
            onValueChange = onTextChange,
            onSend = onSend,
            onStop = onStop,
        )
    }
}

@Composable
private fun CompactTopButton(
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.94f),
        shadowElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun ChatMessageBubble(message: WorkspaceMessage) {
    val isUser = message.author == WorkspaceMessageAuthor.USER
    val isSystem = message.author == WorkspaceMessageAuthor.SYSTEM
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = RoundedCornerShape(if (isUser) 28.dp else 24.dp),
            color = when {
                isUser -> SkyContainer
                isSystem -> Color(0xFFFFF3CD)
                else -> Color.White
            },
        ) {
            Column(modifier = Modifier.padding(if (message.author == WorkspaceMessageAuthor.ASSISTANT) 16.dp else 14.dp)) {
                if (isSystem) Text("Система", color = Color(0xFF8A5A00), style = MaterialTheme.typography.labelSmall)
                if (message.toolCalls.isNotEmpty()) {
                    ToolCallsCompact(message.toolCalls)
                    Spacer(Modifier.height(10.dp))
                }
                MarkdownText(
                    text = message.text.ifBlank { "Думает…" },
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(message.timeLabel, color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ToolCallsCompact(toolCalls: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MintContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = "⌁ Инструменты · ${toolCalls.size}",
                color = Color(0xFF00695C),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = toolCalls.joinToString("  ·  "),
                color = Color(0xFF30655D),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PermissionBar(text: String, onGrant: () -> Unit, onDeny: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD)),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Агент просит доступ", fontWeight = FontWeight.Bold, color = Ink)
            Text(text, color = Ink, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = onGrant) { Text("Разрешить") }
                Button(onClick = onDeny, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B7280))) { Text("Запретить") }
            }
        }
    }
}

@Composable
private fun ChatComposer(
    value: String,
    enabled: Boolean,
    isStreaming: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = Color.Transparent,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 18.dp, top = 10.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                enabled = enabled && !isStreaming,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                decorationBox = { inner ->
                    if (value.isBlank()) Text("Сообщение…", color = Color(0xFF9CA3AF), style = MaterialTheme.typography.bodyLarge)
                    inner()
                },
            )
            Button(
                onClick = if (isStreaming) onStop else onSend,
                enabled = isStreaming || (enabled && value.isNotBlank()),
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (isStreaming) Color(0xFFB3261E) else Color(0xFF075E83)),
            ) { Text(if (isStreaming) "■" else "↑", style = MaterialTheme.typography.titleLarge) }
        }
    }
}
