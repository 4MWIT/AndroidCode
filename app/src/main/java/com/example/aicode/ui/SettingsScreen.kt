@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.aicode.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aicode.settings.EnvironmentStatusItem
import com.example.aicode.settings.AgentRuntimeType
import com.example.aicode.settings.PiModelCatalog
import com.example.aicode.settings.PiModelPreset
import com.example.aicode.settings.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color(0xFFF8F9FC),
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFF9EFD9), Color(0xFFEAF0F8)),
                ),
            ),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF8F9FC)),
                title = {
                    Text(
                        text = "Настройки",
                        fontWeight = FontWeight.Bold,
                    )
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = innerPadding.calculateTopPadding() + 12.dp,
                end = 20.dp,
                bottom = innerPadding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsCard(
                    title = "Компоненты",
                    subtitle = "Что уже стоит и что можно доставить или обновить.",
                ) {
                    state.environment.checks.forEach { item ->
                        EnvironmentStatusRow(
                            item = item,
                            actionInProgress = state.piInstallInProgress,
                            onAction = { actionId ->
                                when (actionId) {
                                    "pi" -> viewModel.installPi()
                                    "toolchain" -> viewModel.installToolchain()
                                }
                            },
                        )
                    }
                    if (state.piInstallMessage.isNotBlank()) {
                        Text(state.piInstallMessage, color = Color(0xFF6B7280), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                SettingsCard(
                    title = "Настройки модели",
                    subtitle = "Выбери бесплатную модель и сохрани API-ключ.",
                ) {
                    PiApiConfig(
                        providerId = state.settings.providerId,
                        modelId = state.settings.modelId,
                        baseUrl = state.settings.baseUrl,
                        apiType = state.settings.apiType,
                        apiKeyConfigured = state.apiKeyConfigured,
                        onProviderChange = viewModel::setProviderId,
                        onModelChange = viewModel::setModelId,
                        onBaseUrlChange = viewModel::setBaseUrl,
                        onApiTypeChange = viewModel::setApiType,
                        onUseModelPreset = viewModel::useModelPreset,
                        onSaveApiKey = viewModel::saveApiKey,
                    )
                }
            }

        }
    }
}

@Composable
private fun SettingsHeroCard(
    selectedRuntime: AgentRuntimeType,
    runtimeInstalled: Boolean,
    runtimeAuthenticated: Boolean,
    runtimeReady: Boolean,
    gitReady: Boolean,
    onboardingCompleted: Boolean,
    onRefresh: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(34.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF172033),
        ),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Служебный центр",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = "Здесь уже можно быстро понять, готово ли приложение к полному циклу: агент, сборка, установка и запуск.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD1D5DB),
            )
            EnvironmentPillRow(
                title = selectedRuntime.displayName,
                ready = runtimeInstalled,
            )
            EnvironmentPillRow(
                title = selectedRuntime.authDisplayName,
                ready = runtimeAuthenticated,
            )
            EnvironmentPillRow(
                title = "Runtime",
                ready = runtimeReady,
            )
            EnvironmentPillRow(
                title = "Git",
                ready = gitReady,
            )
            EnvironmentPillRow(
                title = "Onboarding",
                ready = onboardingCompleted,
            )
            TextButton(onClick = onRefresh) {
                Text("Освежить статус", color = Color(0xFFFDE68A))
            }
        }
    }
}

@Composable
private fun PiApiConfig(
    providerId: String,
    modelId: String,
    baseUrl: String,
    apiType: String,
    apiKeyConfigured: Boolean,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiTypeChange: (String) -> Unit,
    onUseModelPreset: (PiModelPreset) -> Unit,
    onSaveApiKey: (String) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var modelsExpanded by remember { mutableStateOf(false) }
    val selectedModel = PiModelCatalog.available.firstOrNull {
        it.id == modelId && it.providerId == providerId
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable { modelsExpanded = !modelsExpanded },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Бесплатная модель", color = Color(0xFF64748B), style = MaterialTheme.typography.labelMedium)
                    Text(selectedModel?.title ?: modelId, fontWeight = FontWeight.SemiBold, color = Color(0xFF172033), maxLines = 1)
                }
                Text(if (modelsExpanded) "⌃" else "⌄", color = Color(0xFF64748B), style = MaterialTheme.typography.titleLarge)
            }
        }
        if (modelsExpanded) {
            PiModelCatalog.available.forEach { preset ->
                val selected = modelId == preset.id && providerId == preset.providerId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onUseModelPreset(preset)
                            modelsExpanded = false
                        }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        preset.title,
                        modifier = Modifier.weight(1f),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = Color(0xFF172033),
                    )
                    if (selected) Text("✓", color = Color(0xFF0F766E), fontWeight = FontWeight.Bold)
                }
            }
        }
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(if (apiKeyConfigured) "Новый API-ключ (текущий сохранён)" else "API-ключ") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { if (key.isNotBlank()) { onSaveApiKey(key); key = "" } }) {
            Text(if (apiKeyConfigured) "Заменить API-ключ" else "Сохранить API-ключ")
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.92f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827),
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF4B5563),
                )
                content()
            },
        )
    }
}

@Composable
private fun EnvironmentPillRow(
    title: String,
    ready: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready) Color(0xFF0F766E) else Color(0xFF7C2D12),
        ),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = "$title: ${if (ready) "готово" else "ещё не готово"}",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun EnvironmentStatusRow(
    item: EnvironmentStatusItem,
    actionInProgress: Boolean,
    onAction: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF111827),
            )
            Text(
                text = item.details,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B7280),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = when {
                    item.updateAvailable -> "Можно обновить"
                    item.ready -> "Установлено"
                    else -> "Можно установить"
                },
                color = when {
                    item.updateAvailable -> Color(0xFF075E83)
                    item.ready -> Color(0xFF0F766E)
                    else -> Color(0xFFB45309)
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            item.actionId?.let { actionId ->
                TextButton(
                    enabled = !actionInProgress,
                    onClick = { onAction(actionId) },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Text(if (item.updateAvailable) "Обновить" else "Установить")
                }
            }
        }
    }
}

@Composable
private fun SettingInfoRow(
    title: String,
    value: String,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = Color(0xFF92400E),
        )
        Text(
            text = value.ifBlank { "Пока пусто" },
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF4B5563),
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B7280),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}
