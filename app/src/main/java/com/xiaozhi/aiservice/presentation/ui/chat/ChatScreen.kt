package com.xiaozhi.aiservice.presentation.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.InputMode
import com.xiaozhi.aiservice.domain.model.BotConnectionState
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.presentation.ui.components.ChatMessageList
import com.xiaozhi.aiservice.presentation.ui.components.ChatMessageItem
import com.xiaozhi.aiservice.presentation.ui.components.InputBar
import com.xiaozhi.aiservice.presentation.viewmodel.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    contact: BotContact,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val isVoiceMode = contact.inputMode == InputMode.VOICE
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 屏幕保持：聊天界面常亮不熄屏
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 计算展示用的唤醒词：优先用 wakeWord，否则用名字重复两次
    val displayWakeWord = remember(contact.wakeWord, contact.name) {
        if (contact.wakeWord.isNotBlank()) contact.wakeWord
        else "${contact.name}${contact.name}"
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId, contact.id, contact.voiceMode)
        viewModel.setCurrentWakeWord(displayWakeWord)
        viewModel.checkWakeTemplate(displayWakeWord)
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.disconnect()
        }
    }

    val connectionColor by animateColorAsState(
        targetValue = when (uiState.connectionState) {
            is BotConnectionState.Connected -> MaterialTheme.colorScheme.primary
            is BotConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
            is BotConnectionState.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        },
        label = "connection_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "ai_state")
    val stateScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val stateText = when (uiState.aiState) {
        AiState.LISTENING -> if (isVoiceMode) "正在倾听..." else ""
        AiState.SPEAKING -> "正在回答..."
        AiState.PROCESSING -> "思考中..."
        else -> ""
    }

    val stateIcon = when (uiState.aiState) {
        AiState.LISTENING -> if (isVoiceMode) Icons.Default.Mic else null
        AiState.SPEAKING -> Icons.Default.VolumeUp
        AiState.PROCESSING -> Icons.Default.Psychology
        else -> null
    }

    val ctx = LocalContext.current
    val isDark = isSystemInDarkTheme()
    val botColor = remember(contact.name, isDark) {
        com.xiaozhi.aiservice.presentation.ui.components.BotColorScheme.forName(contact.name, isDark)
    }
    LaunchedEffect(uiState.alert) {
        uiState.alert?.let { alertText ->
            snackbarHostState.showSnackbar(
                message = alertText,
                duration = SnackbarDuration.Short
            )
            viewModel.dismissAlert()
        }
    }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { errorText ->
            snackbarHostState.showSnackbar(
                message = errorText,
                duration = SnackbarDuration.Short
            )
            viewModel.dismissError()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding().navigationBarsPadding(),
        bottomBar = {
            InputBar(
                isRecording = uiState.isRecording,
                inputMode = if (isVoiceMode) 0 else 1,
                isAiResponding = uiState.isAiResponding,
                onSendText = { text ->
                    viewModel.sendTextWithReconnect(text)
                    keyboardController?.hide()
                },
                onStartRecording = { viewModel.startListeningWithReconnect() },
                onStopRecording = { viewModel.stopListening() },
                onTryReconnect = { viewModel.tryReconnect() },
                isOffline = uiState.connectionState is BotConnectionState.Disconnected ||
                        uiState.connectionState is BotConnectionState.Error
            )
        },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        stateIcon?.let { icon ->
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .scale(stateScale),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = if (stateText.isNotEmpty()) "${contact.name} · $stateText" else contact.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val context = LocalContext.current
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://xiaozhi.me/console/agents"))
                        ContextCompat.startActivity(context, intent, null)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "控制台",
                            modifier = Modifier.size(24.dp),
                            tint = connectionColor
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // 唤醒词提示横幅（仅提示，录制入口在联系人编辑页）
            AnimatedVisibility(visible = uiState.needsWakeRecording) {
                WakeWordHintBanner(
                    onDismiss = { viewModel.dismissWakeRecording() }
                )
            }

            ChatMessageList(
                messages = uiState.messages,
                listState = listState,
                coroutineScope = coroutineScope,
                modifier = Modifier.weight(1f)
            ) { message, _ ->
                ChatMessageItem(
                    message = message,
                    botColor = botColor,
                    contactName = contact.name,
                    showTimestamp = true,
                    onCopy = { text ->
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("chat", text))
                    }
                )
            }
        }
    }
}

// ============================================================
// 唤醒词提示横幅（仅提示，录制在联系人编辑页）
// ============================================================

@Composable
private fun WakeWordHintBanner(
    onDismiss: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "请在联系人编辑页录制唤醒模板，启用语音唤醒",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
