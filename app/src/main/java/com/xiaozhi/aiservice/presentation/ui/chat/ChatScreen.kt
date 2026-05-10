package com.xiaozhi.aiservice.presentation.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.InputMode
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.VoiceMode
import com.xiaozhi.aiservice.domain.repository.ConnectionState
import com.xiaozhi.aiservice.presentation.viewmodel.ChatUiState
import com.xiaozhi.aiservice.presentation.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    contact: BotContact,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // 使用联系人配置
    var inputMode by remember { mutableIntStateOf(if (contact.inputMode == InputMode.VOICE) 0 else 1) }

    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // 禁用屏幕休眠
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    // 首次加载或新消息到达时滚动到底部（最新消息在底部）
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.connect(contact.serverUrl, contact.otaUrl, contact.deviceId, contact.clientId, contact.id, contact.voiceMode)
    }

    // 顶部栏状态
    val connectionColor by animateColorAsState(
        targetValue = when (uiState.connectionState) {
            is ConnectionState.Connected -> Color(0xFF4CAF50)
            is ConnectionState.Connecting -> Color(0xFFFFC107)
            is ConnectionState.Error -> Color(0xFFF44336)
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
        AiState.LISTENING -> "正在倾听..."
        AiState.SPEAKING -> "正在回答..."
        AiState.PROCESSING -> "思考中..."
        else -> ""
    }

    val stateIcon = when (uiState.aiState) {
        AiState.LISTENING -> Icons.Default.Mic
        AiState.SPEAKING -> Icons.Default.VolumeUp
        AiState.PROCESSING -> Icons.Default.Psychology
        else -> null
    }

    // Alert Snackbar 状态
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.alert) {
        uiState.alert?.let { alertText ->
            snackbarHostState.showSnackbar(
                message = alertText,
                duration = SnackbarDuration.Short
            )
            viewModel.dismissAlert()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // 左侧留空（给返回按钮+状态图标空间）
                        Spacer(modifier = Modifier.width(80.dp))
                        // 居中：名称/状态
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
                                fontWeight = FontWeight.Medium
                            )
                        }
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
                colors = TopAppBarDefaults.topAppBarColors(
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
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state = listState,
                reverseLayout = false
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                items(uiState.messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }

            // 输入区域
            ChatInputArea(
                isRecording = uiState.isRecording,
                aiState = uiState.aiState,
                connectionState = uiState.connectionState,
                inputMode = inputMode,
                onSendText = { text ->
                    viewModel.sendTextMessage(text)
                    keyboardController?.hide()
                },
                onStartRecording = { viewModel.startListening() },
                onStopRecording = { viewModel.stopListening() },
                onTryReconnect = { viewModel.tryReconnect() }
            )
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isFromMe = message.direction == MessageDirection.SENT

    // 流式消息：透明度 0.5→1 呼吸动画（统一的，避免每个气泡独立动画导致全局重绘闪烁）
    val streamingAlpha by animateFloatAsState(
        targetValue = if (message.isStreaming) 1f else 0.5f,
        animationSpec = tween(400),
        label = "streaming_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isFromMe) 16.dp else 4.dp,
                        bottomEnd = if (isFromMe) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isFromMe) MaterialTheme.colorScheme.primary.copy(alpha = streamingAlpha)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (message.isStreaming) streamingAlpha else 1f)
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                color = if (isFromMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun ChatInputArea(
    isRecording: Boolean,
    aiState: AiState,
    connectionState: ConnectionState,
    inputMode: Int, // 0=麦克风模式, 1=文本输入模式
    onSendText: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onTryReconnect: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    // 发送后重置 sending 状态
    LaunchedEffect(sending) {
        if (sending) {
            kotlinx.coroutines.delay(500)
            sending = false
        }
    }

    // AI 正在工作时禁用录音
    val isAiWorking = aiState == AiState.LISTENING || aiState == AiState.SPEAKING || aiState == AiState.PROCESSING

    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recording_scale"
    )

    // 根据状态决定按钮颜色
    val buttonColor = if (isRecording) Color(0xFFF44336) else MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedContent(
                    targetState = inputMode,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { it } + fadeIn(animationSpec = tween(200))).togetherWith(
                                slideOutHorizontally { -it } + fadeOut(animationSpec = tween(200))
                            )
                        } else {
                            (slideInHorizontally { -it } + fadeIn(animationSpec = tween(200))).togetherWith(
                                slideOutHorizontally { it } + fadeOut(animationSpec = tween(200))
                            )
                        }
                    },
                    label = "input_mode",
                    modifier = Modifier.fillMaxWidth()
                ) { mode ->
                    when (mode) {
                        0 -> {
                            // 麦克风模式 - 点击按钮
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // 录音图标：录音中=停止，平时=麦克风
                                val micIcon = if (isRecording) Icons.Default.Stop else Icons.Default.Mic

                                val contentDesc = if (isRecording) "停止录音" else "开始录音/中断"

                                IconButton(
                                    onClick = {
                                        val isOffline = connectionState is ConnectionState.Disconnected ||
                                                connectionState is ConnectionState.Error
                                        if (isOffline) {
                                            onTryReconnect()
                                            return@IconButton
                                        }
                                        if (isRecording) {
                                            onStopRecording()
                                        } else {
                                            onStartRecording()
                                        }
                                    },
                                    enabled = true,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .scale(if (isRecording) scale else 1f)
                                        .clip(CircleShape)
                                        .background(buttonColor)
                                ) {
                                    Icon(
                                        imageVector = micIcon,
                                        contentDescription = contentDesc,
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }
                        1 -> {
                            // 文本输入模式
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("输入消息...") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(
                                        onSend = {
                                            val isOffline = connectionState is ConnectionState.Disconnected ||
                                                    connectionState is ConnectionState.Error
                                            if (isOffline) {
                                                onTryReconnect()
                                                return@KeyboardActions
                                            }
                                            if (text.isNotBlank() && !sending) {
                                                sending = true
                                                onSendText(text.trim())
                                                text = ""
                                            }
                                        }
                                    )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                FilledIconButton(
                                    onClick = {
                                        val isOffline = connectionState is ConnectionState.Disconnected ||
                                                connectionState is ConnectionState.Error
                                        if (isOffline) {
                                            onTryReconnect()
                                            return@FilledIconButton
                                        }
                                        if (text.isNotBlank() && !sending) {
                                            sending = true
                                            onSendText(text.trim())
                                            text = ""
                                        }
                                    },
                                    enabled = text.isNotBlank() && !isAiWorking && !sending
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = "发送"
                                    )
                                }
                            }
                            // AI工作中时的提示文字（文字模式下显示）
                            if (isAiWorking && inputMode == 1) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = when (aiState) {
                                        AiState.LISTENING -> "正在倾听..."
                                        AiState.PROCESSING -> "思考中..."
                                        else -> ""
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
