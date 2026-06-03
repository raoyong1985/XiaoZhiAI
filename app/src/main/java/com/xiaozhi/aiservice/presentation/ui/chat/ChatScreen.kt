package com.xiaozhi.aiservice.presentation.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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

    var inputMode by remember { mutableIntStateOf(if (contact.inputMode == InputMode.VOICE) 0 else 1) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 禁用屏幕休眠
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(Unit) {
        viewModel.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId, contact.id, contact.voiceMode)
        viewModel.setCurrentWakeWord(contact.name)
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.disconnect()
        }
    }

    // 顶部栏状态
    val connectionColor by animateColorAsState(
        targetValue = when (uiState.connectionState) {
            is BotConnectionState.Connected -> Color(0xFF4CAF50)
            is BotConnectionState.Connecting -> Color(0xFFFFC107)
            is BotConnectionState.Error -> Color(0xFFE53935)
            else -> Color(0xFF9E9E9E)
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
        AiState.LISTENING -> if (inputMode == 0) "正在倾听..." else ""
        AiState.SPEAKING -> "正在回答..."
        AiState.PROCESSING -> "思考中..."
        else -> ""
    }

    val stateIcon = when (uiState.aiState) {
        AiState.LISTENING -> if (inputMode == 0) Icons.Default.Mic else null
        AiState.SPEAKING -> Icons.Default.VolumeUp
        AiState.PROCESSING -> Icons.Default.Psychology
        else -> null
    }

    val snackbarHostState = remember { SnackbarHostState() }
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
                inputMode = inputMode,
                isAiResponding = uiState.isAiResponding,
                onSendText = { text ->
                    viewModel.sendTextMessage(text)
                    keyboardController?.hide()
                },
                onStartRecording = { viewModel.startListening() },
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
        ChatMessageList(
            messages = uiState.messages,
            listState = listState,
            coroutineScope = coroutineScope,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { message, index ->
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
