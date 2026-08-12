package com.example.aicode.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.aicode.onboarding.OnboardingUiState
import com.example.aicode.onboarding.OnboardingViewModel
import com.example.aicode.settings.AgentRuntimeType
import androidx.compose.material3.Typography

private enum class AppShellScreen {
    HOME,
    SETTINGS,
}

@Composable
fun AiCodeApp(
) {
    var shellScreen by rememberSaveable { mutableStateOf(AppShellScreen.HOME) }
    BackHandler(enabled = shellScreen == AppShellScreen.SETTINGS) {
        shellScreen = AppShellScreen.HOME
    }
    AiCodeTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            when (shellScreen) {
                AppShellScreen.HOME -> WorkspaceShellScreen(
                    onOpenSettings = { shellScreen = AppShellScreen.SETTINGS },
                )
                AppShellScreen.SETTINGS -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun OnboardingScreen(
    uiState: OnboardingUiState,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onComplete: () -> Unit,
    onSelectRuntime: (com.example.aicode.settings.AgentRuntimeType) -> Unit,
    onRefreshPermissions: () -> Unit,
    onStartAgentInstall: () -> Unit,
    onRefreshAuth: () -> Unit,
    onSyncAuth: () -> Unit,
    onStartQwenAuth: () -> Unit,
    onStartRuntimeInstall: () -> Unit,
    onRefreshRuntime: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val canAdvance = remember(
        uiState.currentStep,
        uiState.canInstallPackages,
        uiState.agentCli.isInstalled,
        uiState.authReady,
        uiState.runtimeStatus.fullyReady,
    ) {
        when (uiState.currentStep) {
            0 -> true
            1 -> uiState.canInstallPackages
            2 -> uiState.agentCli.isInstalled
            3 -> uiState.authReady
            else -> uiState.runtimeStatus.fullyReady
        }
    }
    val nextButtonLabel = remember(uiState.currentStep) {
        when (uiState.currentStep) {
            0 -> "Начать настройку"
            1 -> "Дальше"
            2 -> if (uiState.selectedRuntime.requiresAuth) "Перейти ко входу" else "Перейти к runtime"
            3 -> "Перейти к runtime"
            else -> "Закончить"
        }
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        onRefreshPermissions()
        onRefreshRuntime()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshPermissions()
                onSyncAuth()
                onRefreshRuntime()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val stepTitles = remember(uiState.selectedRuntime) {
        listOf(
            "Добро пожаловать",
            "Разрешения",
            "Скачивание ${uiState.selectedRuntime.displayName}",
            if (uiState.selectedRuntime.requiresAuth) "Авторизация ${uiState.selectedRuntime.displayName}" else "Доступ к движку",
            "Android runtime",
        )
    }

    var lastOpenedAuthUrl by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.qwenAuth.browserUrl, uiState.selectedRuntime) {
        val authUrl = uiState.qwenAuth.browserUrl
        if (!authUrl.isNullOrBlank() && authUrl != lastOpenedAuthUrl) {
            lastOpenedAuthUrl = authUrl
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFF6EBDD), Color(0xFFF3F5F8)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                text = "Первый запуск",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1F2A37),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Настроим приложение один раз: дадим нужные разрешения, скачаем ${uiState.selectedRuntime.displayName} и проверим Android runtime.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF4B5563),
            )
            Spacer(modifier = Modifier.height(18.dp))
            StepHeader(currentStep = uiState.currentStep, stepTitles = stepTitles)
            Spacer(modifier = Modifier.height(18.dp))

            when (uiState.currentStep) {
                0 -> WelcomeStep(
                    uiState = uiState,
                    onSelectRuntime = onSelectRuntime,
                )
                1 -> PermissionStep(
                    canInstallPackages = uiState.canInstallPackages,
                    onOpenPermission = {
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        settingsLauncher.launch(intent)
                    },
                )
                2 -> AgentInstallStep(
                    uiState = uiState,
                    onStartAgentInstall = onStartAgentInstall,
                )
                3 -> QwenAuthStep(
                    uiState = uiState,
                    onStartQwenAuth = onStartQwenAuth,
                    onRefreshAuth = onRefreshAuth,
                    onOpenBrowser = {
                        uiState.qwenAuth.browserUrl?.let { url ->
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    },
                )
                else -> RuntimeStep(
                    uiState = uiState,
                    onStartRuntimeInstall = onStartRuntimeInstall,
                    onRefreshRuntime = onRefreshRuntime,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            if (!canAdvance) {
                GuidanceCard(
                    text = when (uiState.currentStep) {
                        1 -> "Сначала нужно выдать разрешение на установку APK, иначе автопилот не сможет поставить собранное приложение."
                        2 -> "Этот шаг лучше не пропускать: без ${uiState.selectedRuntime.displayName} агентный цикл просто не стартует."
                        3 -> if (uiState.selectedRuntime.requiresAuth) {
                            "Сначала нужно войти в ${uiState.selectedRuntime.displayName}, иначе агент не сможет реально работать."
                        } else {
                            "${uiState.selectedRuntime.displayName} не требует отдельного входа, можно спокойно идти дальше."
                        }
                        else -> "Сначала нужно дотянуть Android runtime, чтобы сборка проекта на устройстве вообще заработала."
                    },
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState.currentStep > 0) {
                    OutlinedButton(onClick = onBack) {
                        Text("Назад")
                    }
                } else {
                    Spacer(modifier = Modifier.width(88.dp))
                }

                if (uiState.currentStep < OnboardingViewModel.LAST_STEP_INDEX) {
                    Button(
                        onClick = onNext,
                        enabled = canAdvance,
                    ) {
                        Text(nextButtonLabel)
                    }
                } else {
                    Button(
                        onClick = onComplete,
                        enabled = canAdvance,
                    ) {
                        Text(nextButtonLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(
    uiState: OnboardingUiState,
    onSelectRuntime: (com.example.aicode.settings.AgentRuntimeType) -> Unit,
) {
    StepCard(
        title = "Здесь пользователь не пишет код руками",
        subtitle = "Это мобильная среда для vibe coding: описываешь идею, Pi меняет проект, а приложение собирает и ставит APK прямо на телефоне.",
    ) {
        RuntimeChoiceRow(
            selectedRuntime = uiState.selectedRuntime,
            onSelectRuntime = onSelectRuntime,
        )
        Spacer(modifier = Modifier.height(14.dp))
        StatusRow(
            title = "Разрешение на установку APK",
            ready = uiState.canInstallPackages,
            details = if (uiState.canInstallPackages) "Разрешение уже есть." else "Понадобится на следующем шаге.",
        )
        Spacer(modifier = Modifier.height(10.dp))
        StatusRow(
            title = uiState.selectedRuntime.displayName,
            ready = uiState.agentCli.isInstalled,
            details = if (uiState.agentCli.isInstalled) {
                "Pi уже готов внутри приложения."
            } else {
                "Будет установлен в приватную среду приложения на следующем шаге."
            },
        )
        Spacer(modifier = Modifier.height(10.dp))
        StatusRow(
            title = "Android runtime",
            ready = uiState.runtimeStatus.fullyReady,
            details = if (uiState.runtimeStatus.fullyReady) "SDK/JDK уже готовы." else "Нужно дотянуть setup для локальной сборки.",
        )
        Spacer(modifier = Modifier.height(18.dp))
        Bullet("Ты пишешь, какое Android-приложение хочешь получить.")
        Bullet("AI сам меняет проект.")
        Bullet("На телефоне собирается APK.")
        Bullet("Потом APK ставится и запускается автоматически.")
    }
}

@Composable
private fun PermissionStep(
    canInstallPackages: Boolean,
    onOpenPermission: () -> Unit,
) {
    StepCard(
        title = "Разрешение на установку APK",
        subtitle = "Без этого шага приложение не сможет само поставить собранный APK на устройство.",
    ) {
        StatusRow(
            title = "Установка из этого приложения",
            ready = canInstallPackages,
            details = if (canInstallPackages) "Разрешение уже выдано." else "Нужно включить в системных настройках.",
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (!canInstallPackages) {
            Button(onClick = onOpenPermission) {
                Text("Открыть системные настройки")
            }
        } else {
            Text(
                text = "Разрешение уже есть, здесь всё хорошо.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF166534),
            )
        }
    }
}

@Composable
private fun AgentInstallStep(
    uiState: OnboardingUiState,
    onStartAgentInstall: () -> Unit,
) {
    StepCard(
        title = "Скачивание ${uiState.selectedRuntime.displayName}",
        subtitle = "Ставим Node.js, Python, Git и Pi в изолированную локальную среду приложения. Внешний Termux не нужен.",
    ) {
        Text(
            text = uiState.agentCli.headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1F2937),
        )
        Spacer(modifier = Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { uiState.agentCli.progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (uiState.agentCli.error != null) {
            Text(
                text = uiState.agentCli.error,
                color = Color(0xFFB91C1C),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (!uiState.agentCli.isInstalled) {
            Button(
                onClick = onStartAgentInstall,
                enabled = !uiState.agentCli.isInstalling,
            ) {
                Text(
                    if (uiState.agentCli.isInstalling) {
                        "Идёт установка…"
                    } else "Установить Pi локально",
                )
            }
        }
        if (uiState.agentCli.logs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    uiState.agentCli.logs.forEach { line ->
                        Text(
                            text = line,
                            color = Color(0xFFE5E7EB),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QwenAuthStep(
    uiState: OnboardingUiState,
    onStartQwenAuth: () -> Unit,
    onRefreshAuth: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    if (!uiState.selectedRuntime.requiresAuth) {
        StepCard(
            title = "Отдельный вход не нужен",
            subtitle = "${uiState.selectedRuntime.displayName} можно установить и использовать без дополнительной авторизации внутри onboarding.",
        ) {
            StatusRow(
                title = uiState.selectedRuntime.displayName,
                ready = true,
                details = "Для этого движка можно сразу переходить к Android runtime.",
            )
        }
        return
    }
    StepCard(
        title = "Авторизация через ${uiState.selectedRuntime.authDisplayName}",
        subtitle = "Не лепим свой fake-login. Если движку нужен официальный вход, просим его у самого CLI и открываем браузер.",
    ) {
        StatusRow(
            title = "Статус входа",
            ready = uiState.qwenAuth.isAuthenticated,
            details = uiState.qwenAuth.headline,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (!uiState.qwenAuth.userCode.isNullOrBlank()) {
            Text(
                text = "Код подтверждения: ${uiState.qwenAuth.userCode}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF92400E),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (uiState.qwenAuth.error != null) {
            Text(
                text = uiState.qwenAuth.error,
                color = Color(0xFFB91C1C),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStartQwenAuth,
                enabled = !uiState.qwenAuth.isAuthorizing && !uiState.qwenAuth.isAuthenticated,
            ) {
                Text(
                    if (uiState.qwenAuth.isAuthorizing) {
                        "Запускаю Termux…"
                    } else if (uiState.qwenAuth.isAuthenticated) {
                        "${uiState.selectedRuntime.displayName} уже подключен"
                    } else {
                        "Открыть вход через Termux"
                    },
                )
            }
            if (!uiState.qwenAuth.browserUrl.isNullOrBlank()) {
                OutlinedButton(onClick = onOpenBrowser) {
                    Text("Открыть браузер")
                }
            }
            OutlinedButton(onClick = onRefreshAuth) {
                Text("Проверить статус")
            }
        }
        if (uiState.qwenAuth.logs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF172033)),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    uiState.qwenAuth.logs.forEach { line ->
                        Text(
                            text = line,
                            color = Color(0xFFE5E7EB),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeStep(
    uiState: OnboardingUiState,
    onStartRuntimeInstall: () -> Unit,
    onRefreshRuntime: () -> Unit,
) {
    StepCard(
        title = "Android runtime для сборки",
        subtitle = "Теперь это уже настоящий setup-шаг: bootstrap shell + idesetup внутри приложения.",
    ) {
        uiState.runtimeStatus.checks.forEach { item ->
            StatusRow(
                title = item.title,
                ready = item.ready,
                details = item.details,
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = uiState.runtimeInstall.headline,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1F2937),
        )
        Spacer(modifier = Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { uiState.runtimeInstall.progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (uiState.runtimeInstall.error != null) {
            Text(
                text = uiState.runtimeInstall.error,
                color = Color(0xFFB91C1C),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStartRuntimeInstall,
                enabled = !uiState.runtimeInstall.isInstalling,
            ) {
                Text(if (uiState.runtimeInstall.isInstalling) "Идёт установка…" else "Установить Android runtime")
            }
            OutlinedButton(onClick = onRefreshRuntime) {
                Text("Проверить")
            }
        }
        if (uiState.runtimeInstall.logs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    uiState.runtimeInstall.logs.forEach { line ->
                        Text(
                            text = line,
                            color = Color(0xFFE2E8F0),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GuidanceCard(
    text: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4E5)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF92400E),
        )
    }
}

@Composable
private fun StepHeader(
    currentStep: Int,
    stepTitles: List<String>,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.8f)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Шаг ${currentStep + 1} из ${stepTitles.size}",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF92400E),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stepTitles[currentStep],
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (currentStep + 1f) / stepTitles.size.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.9f)),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111827),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF4B5563),
            )
            Spacer(modifier = Modifier.height(18.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(
    title: String,
    ready: Boolean,
    details: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (ready) "OK" else "...",
            color = if (ready) Color(0xFF166534) else Color(0xFF92400E),
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6B7280),
            )
        }
    }
}

@Composable
private fun RuntimeChoiceRow(
    selectedRuntime: AgentRuntimeType,
    onSelectRuntime: (AgentRuntimeType) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(AgentRuntimeType.PI).forEach { runtime ->
            Card(
                modifier = Modifier
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = if (runtime == selectedRuntime) Color(0xFF172033) else Color(0xFFF8FAFC),
                ),
                shape = RoundedCornerShape(22.dp),
                onClick = { onSelectRuntime(runtime) },
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = runtime.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (runtime == selectedRuntime) Color.White else Color(0xFF172033),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Работает внутри приложения через API-ключ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (runtime == selectedRuntime) Color(0xFFD1D5DB) else Color(0xFF667085),
                    )
                }
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFB45309),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF374151),
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun AiCodeTheme(
    content: @Composable () -> Unit,
) {
    val scheme = lightColorScheme(
        primary = Color(0xFFB45309),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFDE6C8),
        onPrimaryContainer = Color(0xFF7C2D12),
        secondary = Color(0xFF1D4ED8),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCEAFE),
        onSecondaryContainer = Color(0xFF1E3A8A),
        background = Color(0xFFF8F5EF),
        onBackground = Color(0xFF111827),
        surface = Color.White,
        onSurface = Color(0xFF111827),
        surfaceVariant = Color(0xFFF3F4F6),
        onSurfaceVariant = Color(0xFF4B5563),
        error = Color(0xFFB91C1C),
        onError = Color.White,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF7F1D1D),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content,
    )
}
