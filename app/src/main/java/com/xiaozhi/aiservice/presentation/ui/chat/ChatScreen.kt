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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaozhi.aiservice.di.AppContainer
import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.repository.ConnectionState
import com.xiaozhi.aiservice.presentation.viewmodel.ChatUiState
import com.xiaozhi.aiservice.presentation.viewmodel.ChatViewModel
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    @Suppress("UNUSED_PARAMETER") onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    var serverUrl by remember { mutableStateOf("wss://api.tenclass.net/xiaozhi/v1/") }
    var deviceId by remember { mutableStateOf(AppContainer.deviceMac) }
    var clientId by remember { mutableStateOf(AppContainer.clientId) }
    var inputMode by remember { mutableIntStateOf(0) } // 0=语音模式, 1=文本模式
    var showSettings by remember { mutableStateOf(false) }

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

    // 自动滚动到底部当新消息到达
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.connect(serverUrl, deviceId, clientId)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 左侧：连接状态 + 小智AI
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(connectionColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "小智 AI",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // 中间：状态文字（居中）
                        if (stateText.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (stateIcon != null) {
                                    Icon(
                                        imageVector = stateIcon,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(16.dp)
                                            .scale(stateScale),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Text(
                                    text = stateText,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
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
                inputMode = inputMode,
                onSendText = { text ->
                    viewModel.sendTextMessage(text)
                    keyboardController?.hide()
                },
                onStartRecording = { viewModel.startListening() },
                onStopRecording = { viewModel.stopListening() }
            )
        }
    }
    
    if (showSettings) {
        SettingsDialog(
            serverUrl = serverUrl,
            deviceId = deviceId,
            clientId = clientId,
            inputMode = inputMode,
            onServerUrlChange = { serverUrl = it },
            onDeviceIdChange = { deviceId = it },
            onClientIdChange = { clientId = it },
            onInputModeChange = { inputMode = it },
            onDismiss = { showSettings = false },
            onConnect = {
                viewModel.connect(serverUrl, deviceId, clientId)
                showSettings = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    connectionState: ConnectionState,
    aiState: AiState,
    onSettingsClick: () -> Unit
) {
    val connectionColor by animateColorAsState(
        targetValue = when (connectionState) {
            is ConnectionState.Connected -> Color(0xFF4CAF50)
            is ConnectionState.Connecting -> Color(0xFFFFC107)
            is ConnectionState.Error -> Color(0xFFF44336)
            else -> Color(0xFF9E9E9E)
        },
        label = "connection_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "ai_state")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val stateText = when (aiState) {
        AiState.LISTENING -> "正在倾听..."
        AiState.SPEAKING -> "正在回答..."
        AiState.PROCESSING -> "思考中..."
        else -> ""
    }

    val stateIcon = when (aiState) {
        AiState.LISTENING -> Icons.Default.Mic
        AiState.SPEAKING -> Icons.Default.VolumeUp
        AiState.PROCESSING -> Icons.Default.Psychology
        else -> null
    }

    val statusBarsPadding = WindowInsets.statusBars.asPaddingValues()
    val topPadding = statusBarsPadding.calculateTopPadding().let { maxOf(it, 4.dp) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding),
        shadowElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：连接状态 + 小智AI
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(connectionColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("小智 AI")
            }

            // 中间：状态文字（居中）
            if (stateText.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (stateIcon != null) {
                        Icon(
                            imageVector = stateIcon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp)
                                .scale(scale),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = stateText,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 13.sp
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // 右侧：设置按钮
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isFromMe = message.direction == MessageDirection.SENT
    
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
                .background(if (isFromMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
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
private fun AiStateIndicator(state: AiState) {
    val infiniteTransition = rememberInfiniteTransition(label = "ai_state")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    val stateText = when (state) {
        AiState.LISTENING -> "正在倾听..."
        AiState.SPEAKING -> "正在回答..."
        AiState.PROCESSING -> "思考中..."
        else -> ""
    }
    
    val stateIcon = when (state) {
        AiState.LISTENING -> Icons.Default.Mic
        AiState.SPEAKING -> Icons.Default.VolumeUp
        AiState.PROCESSING -> Icons.Default.Psychology
        else -> null
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (stateIcon != null) {
                Icon(
                    imageVector = stateIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .scale(scale),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = stateText,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun ChatInputArea(
    isRecording: Boolean,
    aiState: AiState,
    inputMode: Int, // 0=麦克风模式, 1=文本输入模式
    onSendText: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
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
    val buttonColor = when {
        isRecording -> Color(0xFFF44336)  // 录音中 - 红色
        isAiWorking -> Color(0xFF9E9E9E)   // AI 工作中 - 灰色
        else -> MaterialTheme.colorScheme.primary  // 正常 - 主题色
    }

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
                                // 录音图标根据状态变化
                                val micIcon = when {
                                    isRecording -> Icons.Default.Stop
                                    isAiWorking -> Icons.Default.Block
                                    else -> Icons.Default.Mic
                                }

                                val contentDesc = when {
                                    isRecording -> "停止录音"
                                    isAiWorking -> "AI工作中..."
                                    else -> "开始录音"
                                }

                                IconButton(
                                    onClick = {
                                        if (isRecording) {
                                            onStopRecording()
                                        } else if (!isAiWorking) {
                                            onStartRecording()
                                        }
                                    },
                                    enabled = !isAiWorking || isRecording,
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
                                            if (text.isNotBlank() && !sending) {
                                                sending = true
                                                onSendText(text.trim())
                                                text = ""
                                            }
                                        }
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                FilledIconButton(
                                    onClick = {
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
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDialog(
    serverUrl: String,
    deviceId: String,
    clientId: String,
    inputMode: Int,
    onServerUrlChange: (String) -> Unit,
    onDeviceIdChange: (String) -> Unit,
    onClientIdChange: (String) -> Unit,
    onInputModeChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    onConnect: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("连接设置") },
        text = {
            Column {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = onServerUrlChange,
                    label = { Text("服务器地址 (WebSocket URL)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("wss://xiaozhi.me/ws") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = onDeviceIdChange,
                    label = { Text("设备ID (MAC)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("XX:XX:XX:XX:XX:XX") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = clientId,
                    onValueChange = onClientIdChange,
                    label = { Text("客户端ID (UUID)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("UUID") }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("输入模式", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = inputMode == 0,
                        onClick = { onInputModeChange(0) },
                        label = { Text("语音") },
                        leadingIcon = {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = inputMode == 1,
                        onClick = { onInputModeChange(1) },
                        label = { Text("文字") },
                        leadingIcon = {
                            Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "默认连接 xiaozhi.me 官方服务器",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConnect) {
                Text("连接")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
