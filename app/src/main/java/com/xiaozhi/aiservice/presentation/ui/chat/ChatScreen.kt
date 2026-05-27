package com.xiaozhi.aiservice.presentation.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xiaozhi.aiservice.domain.model.AiState
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.InputMode
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageType
import com.xiaozhi.aiservice.domain.model.BotConnectionState
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
    DisposableEffect(Unit) {
        onDispose {
            viewModel.disconnect()
        }
    }

    // 顶部栏状态
    val connectionColor by animateColorAsState(
        targetValue = when (uiState.connectionState) {
            is BotConnectionState.Connected -> Color(0xFF4CAF50)    // 绿色
            is BotConnectionState.Connecting -> Color(0xFFFFC107)   // 琥珀
            is BotConnectionState.Error -> Color(0xFFE53935)        // 红色
            else -> Color(0xFF9E9E9E)                               // 灰色
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
        AiState.LISTENING -> if (inputMode == 0) "正在倾听..." else ""  // 文本模式不显示
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

    // Alert Snackbar 状态
    val snackbarHostState = remember { SnackbarHostState() }
    val ctx = LocalContext.current
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
        modifier = Modifier.imePadding().navigationBarsPadding(),
        bottomBar = {
            InputBar(
                isRecording = uiState.isRecording,
                inputMode = inputMode,
                onSendText = { text ->
                    viewModel.sendTextMessage(text)
                    keyboardController?.hide()
                },
                onStartRecording = { viewModel.startListening() },
                onStopRecording = { viewModel.stopListening() },
                onTryReconnect = { viewModel.tryReconnect() }
            )
        },
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                state = listState,
                reverseLayout = false
            ) {
                item { Spacer(modifier = Modifier.height(8.dp)) }

                items(uiState.messages, key = { it.id }) { message ->
                    MessageBubble(
                        message = message,
                        onCopy = { text ->
                            val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("chat", text))
                        }
                    )
                }

                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, onCopy: ((String) -> Unit)? = null) {
    val isFromMe = message.direction == MessageDirection.SENT

    // 流式消息：透明度 0.5→1 呼吸动画
    val streamingAlpha by animateFloatAsState(
        targetValue = if (message.isStreaming) 1f else 0.5f,
        animationSpec = tween(400),
        label = "streaming_alpha"
    )

    when (message.type) {
        MessageType.IMAGE -> ImageBubble(message, isFromMe, streamingAlpha)
        else -> TextBubble(message, isFromMe, streamingAlpha, onCopy)
    }
}

@Composable
private fun ImageBubble(message: ChatMessage, isFromMe: Boolean, streamingAlpha: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(message.content)  // 本地文件路径
                .crossfade(true)
                .build(),
            contentDescription = "照片",
            modifier = Modifier
                .widthIn(max = 280.dp)
                .heightIn(max = 360.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isFromMe) 16.dp else 4.dp,
                        bottomEnd = if (isFromMe) 4.dp else 16.dp
                    )
                ),
            contentScale = ContentScale.Fit
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextBubble(message: ChatMessage, isFromMe: Boolean, streamingAlpha: Float, onCopy: ((String) -> Unit)? = null) {
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
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        onCopy?.invoke(message.content)
                    }
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


