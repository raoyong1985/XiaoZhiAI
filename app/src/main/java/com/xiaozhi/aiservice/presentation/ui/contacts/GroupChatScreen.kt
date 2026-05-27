package com.xiaozhi.aiservice.presentation.ui.contacts

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

import androidx.hilt.navigation.compose.hiltViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.presentation.viewmodel.GroupChatViewModel
import kotlinx.coroutines.launch

// ── 气泡颜色盘 ──
// 每个 bot 在群聊中有独立的背景色，帮助用户区分不同的智能体
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun GroupChatScreen(
    groupId: String,
    viewModel: GroupChatViewModel = hiltViewModel(key = groupId),
    onNavigateBack: () -> Unit
) {
    val groups by viewModel.groups.collectAsState()
    val group = groups.find { it.id == groupId }
    val messages by viewModel.getGroupMessages(groupId).collectAsState(initial = emptyList())
    val isRecording by viewModel.isRecording.collectAsState()
    val inputMode = group?.inputMode ?: 0  // 默认语音输入
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── lifecycle：进入时连接，离开时断开 ──
    LaunchedEffect(groupId) {
        viewModel.connectGroup(groupId)
    }
    DisposableEffect(groupId) {
        onDispose {
            viewModel.disconnectGroup()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    // ── 自动滚动到最新消息 ──
    // initialLoad=true 时直接跳到底部（无动画）
    // 后续如果用户已经在底部则 animateScroll（平滑滚动）
    // 如果用户手动滚上去了则保持不动（不打扰阅读）
    var initialLoad by remember { mutableStateOf(true) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (initialLoad) {
                initialLoad = false
                listState.scrollToItem(messages.size - 1)
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                if (lastVisible >= messages.size - 2) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding().navigationBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group?.name ?: "群聊", fontSize = 18.sp)
                        Text(
                            "${group?.botIds?.size ?: 0} 个成员",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            GroupChatInputArea(
                inputMode = inputMode,
                isRecording = isRecording,
                inputText = inputText,
                onInputTextChange = { inputText = it },
                onSendText = {
                    if (inputText.isNotBlank()) {
                        coroutineScope.launch {
                            viewModel.sendMessage(groupId, inputText)
                            inputText = ""
                            keyboardController?.hide()
                        }
                    }
                },
                onStartRecording = { viewModel.startVoiceInput(groupId) },
                onStopRecording = { viewModel.stopVoiceInput() }
            )
        }
    ) { padding ->
        // ── 消息列表示 ──
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 2.dp),
            state = listState
        ) {
            items(messages, key = { it.id }) { message ->
                // 根据 botId 在群聊成员列表中的位置分配颜色
                val botIndex = group?.botIds?.indexOf(message.botId) ?: -1
                ChatMessageItem(
                    message = message,
                    botColorIndex = botIndex,
                    onCopy = { text ->
                        // 静默复制到剪贴板（无 Toast/Snackbar）
                        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("chat", text))
                    }
                )
            }
        }
    }
}

/**
 * 单条群聊消息气泡
 *
 * 根据 direction 决定气泡位置：
 * - SENT（我发送的）：靠右，主色背景
 * - RECEIVED（bot 回复）：靠左，根据 botColorIndex 分配不同颜色
 *
 * bot 名称显示在 bot 回复的气泡上方（便于区分不同智能体）
 * 长按复制文本内容
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(message: ChatMessage, botColorIndex: Int = -1, onCopy: ((String) -> Unit)? = null) {
    val isFromMe = message.direction == com.xiaozhi.aiservice.domain.model.MessageDirection.SENT

    val isDark = isSystemInDarkTheme()
    val botColors = if (isDark) {
        listOf(
            Color(0xFF2D5A3A),  // 深绿
            Color(0xFF2A3F6B),  // 深蓝
            Color(0xFF5C4A1F),  // 深琥珀
            Color(0xFF4A2D5C),  // 深紫
            Color(0xFF1F4A5C),  // 深青
            Color(0xFF5C2A2A),  // 深红
        )
    } else {
        listOf(
            Color(0xFFE8F5E9),  // 浅绿
            Color(0xFFE3F2FD),  // 浅蓝
            Color(0xFFFFF3E0),  // 浅橙
            Color(0xFFF3E5F5),  // 浅紫
            Color(0xFFE0F7FA),  // 浅青
            Color(0xFFFFEBEE),  // 浅红
        )
    }
    val bubbleColor = when {
        isFromMe -> MaterialTheme.colorScheme.primary
        botColorIndex >= 0 && botColorIndex < botColors.size -> botColors[botColorIndex]
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
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
                .background(bubbleColor)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        onCopy?.invoke(message.content)
                    }
                )
                .padding(12.dp)
        ) {
            Column {
                // bot 回复显示名称（用户消息不显示）
                if (!isFromMe) {
                    Text(
                        text = message.botName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                Text(
                    text = message.content,
                    color = if (isFromMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp
                )
            }
        }
    }
}

/**
 * 群聊底部输入区
 *
 * 两种模式（通过 AnimatedContent 平滑切换）：
 * 0 - 语音模式：显示一个大的录音按钮（带脉动动画）
 * 1 - 文字模式：显示 TextField + 发送按钮
 *
 * 按钮动画：
 * - 录音中：红色背景 + 1.0↔1.15 缩放脉动
 * - 空闲时：主色背景，正常大小
 */
@Composable
private fun GroupChatInputArea(
    inputMode: Int,
    isRecording: Boolean,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onSendText: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    // 录音按钮脉动动画：isRecording=true 时在 1.0 和 1.15 之间循环缩放
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
            // AnimatedContent：语音/文字之间平滑切换
            AnimatedContent(
                targetState = inputMode,
                transitionSpec = {
                    if (targetState > initialState) {
                        (slideInHorizontally { it } + fadeIn()).togetherWith(
                            slideOutHorizontally { -it } + fadeOut()
                        )
                    } else {
                        (slideInHorizontally { -it } + fadeIn()).togetherWith(
                            slideOutHorizontally { it } + fadeOut()
                        )
                    }
                },
                label = "input_mode",
                modifier = Modifier.fillMaxWidth()
            ) { mode ->
                when (mode) {
                    0 -> {
                        // ── 语音模式：大录音按钮 ──
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            IconButton(
                                onClick = {
                                    if (isRecording) onStopRecording() else onStartRecording()
                                },
                                modifier = Modifier
                                    .size(72.dp)
                                    .scale(if (isRecording) scale else 1f)
                                    .clip(CircleShape)
                                    .background(if (isRecording) Color(0xFFF44336) else MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(
                                    imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                    contentDescription = if (isRecording) "停止录音" else "开始录音",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }
                    1 -> {
                        // ── 文字模式：输入框 + 发送按钮 ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = inputText,
                                onValueChange = onInputTextChange,
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("输入消息...", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    cursorColor = MaterialTheme.colorScheme.primary,
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                ),
                                shape = RoundedCornerShape(12.dp),
                                textStyle = MaterialTheme.typography.bodyMedium,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { onSendText() })
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledIconButton(
                                onClick = onSendText,
                                enabled = inputText.isNotBlank(),
                                modifier = Modifier.size(52.dp),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "发送", modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
