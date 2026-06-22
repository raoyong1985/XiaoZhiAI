package com.xiaozhi.aiservice.presentation.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xiaozhi.aiservice.presentation.theme.AppShapes
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.xiaozhi.aiservice.domain.model.ChatMessage
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.domain.model.MessageType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 共享消息气泡组件 — 群聊/私聊共用
 *
 * @param index 消息在列表中的位置（用于序列入场动画，越靠后延迟越大）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    message: ChatMessage,
    index: Int = 0,
    botColor: BotColorScheme.BotColor? = null,
    contactName: String = "",
    showTimestamp: Boolean = true,
    onCopy: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null
) {
    val isFromMe = message.direction == MessageDirection.SENT
    val isDark = isSystemInDarkTheme()
    val effectiveBotColor = remember(botColor, isDark) {
        botColor ?: BotColorScheme.get(0, isDark)
    }

    val bubbleColor = when {
        isFromMe -> if (isDark) Color(0xFF1E3A6F) else Color(0xFFB8D4FF)
        else -> effectiveBotColor.bubbleColor
    }

    val displayName = remember(message.botName, contactName) {
        message.botName.ifEmpty { contactName.ifEmpty { "" } }
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeText = remember(message.timestamp) { timeFormat.format(Date(message.timestamp)) }

    var showContextMenu by remember { mutableStateOf(false) }
    var showImageViewer by remember { mutableStateOf(false) }

    // ── 入场动画由 ChatMessageList 控制（仅新消息 fadeIn）──
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = if (isFromMe) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Bot头像（左）：有名称才显示，否则 44dp 占位保持对齐
        if (!isFromMe) {
            if (displayName.isNotEmpty()) {
                AvatarView(
                    name = displayName,
                    size = 36.dp,
                    avatarColor = effectiveBotColor.avatarColor
                )
                Spacer(modifier = Modifier.width(6.dp))
            } else {
                Spacer(modifier = Modifier.width(42.dp))
            }
        }

        // 气泡主体 + 上下文菜单
        Box {
            Box(
                modifier = Modifier
                    .widthIn(min = 60.dp, max = 320.dp)
                    .shadow(1.dp, AppShapes.bubble)
                    .clip(RoundedCornerShape(
                            topStart = 12.dp,
                            topEnd = 12.dp,
                            bottomStart = if (isFromMe) 12.dp else 3.dp,
                            bottomEnd = if (isFromMe) 3.dp else 12.dp
                        )
                    )
                    .background(bubbleColor)
                    .bounceClick(pressedScale = 0.97f, stiffness = androidx.compose.animation.core.Spring.StiffnessVeryLow)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showContextMenu = true }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
            Column {
                // bot 回复显示名称
                if (!isFromMe && displayName.isNotEmpty()) {
                    Text(
                        text = displayName,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = effectiveBotColor.nameColor
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                // 内容区：图片、语音或文字
                when (message.type) {
                    MessageType.IMAGE -> {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(message.content)
                                .crossfade(true)
                                .build(),
                            contentDescription = "照片",
                            modifier = Modifier
                                .widthIn(max = 220.dp)
                                .heightIn(max = 360.dp)
                                .clickable { showImageViewer = true },
                            contentScale = ContentScale.Fit
                        )
                    }
                    MessageType.VOICE -> {
                        val displayText = remember(message.content) {
                            if (message.content.startsWith("/") || message.content.startsWith("file:"))
                                "🎤 语音 ${message.duration?.let { "${it / 1000}秒" } ?: ""}"
                            else
                                message.content
                        }
                        Text(
                            text = displayText,
                            color = if (isFromMe) if (isDark) Color.White else Color(0xFF333333).copy(alpha = 0.85f)
                                    else if (isDark) Color.White.copy(alpha = 0.92f)
                                    else Color(0xFF333333).copy(alpha = 0.85f),
                            fontSize = 15.sp
                        )
                    }
                    else -> {
                        Text(
                            text = message.content,
                            color = if (isFromMe) if (isDark) Color.White else Color(0xFF333333).copy(alpha = 0.85f)
                                    else if (isDark) Color.White.copy(alpha = 0.92f)
                                    else Color(0xFF333333).copy(alpha = 0.85f),
                            fontSize = 15.sp
                        )
                    }
                }
                if (showTimestamp) {
                    Spacer(modifier = Modifier.height(2.dp))
                    val timestampColor = when {
                        isFromMe -> if (isDark) Color.White.copy(alpha = 0.7f) else Color(0xFF666666).copy(alpha = 0.65f)
                        isDark -> Color.White.copy(alpha = 0.7f)
                        else -> Color(0xFF666666).copy(alpha = 0.65f)
                    }
                    Text(
                        text = timeText,
                        color = timestampColor,
                        fontSize = 10.sp,
                        modifier = Modifier.align(if (isFromMe) Alignment.End else Alignment.Start)
                    )
                }
            }
        }
        // 上下文菜单
        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("复制") },
                onClick = {
                    showContextMenu = false
                    onCopy?.invoke(message.content)
                },
                leadingIcon = {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                }
            )
            if (onDelete != null) {
                Divider()
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        onDelete?.invoke(message.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                )
            }
        }
        }

        // 用户头像（右）
        if (isFromMe) {
            Spacer(modifier = Modifier.width(6.dp))
            AvatarView(
                name = "我",
                size = 36.dp,
                avatarColor = if (isDark) Color(0xFF3A6FDF) else Color(0xFF5B8DEF)
            )
        }
    }  // Row

    // 全屏图片查看器
    if (showImageViewer) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        Dialog(
            onDismissRequest = { showImageViewer = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    }
                    .clickable { showImageViewer = false },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(message.content)
                        .crossfade(true)
                        .build(),
                    contentDescription = "照片（点击关闭）",
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        ),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }
}

/**
 * 日期分割线（M月d日）
 */
@Composable
fun DateSeparator(timestamp: Long) {
    val dateFormat = remember { SimpleDateFormat("M月d日", Locale.getDefault()) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = dateFormat.format(Date(timestamp)),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

/**
 * 判断两个时间戳是否同一天
 */
fun isSameDay(t1: Long, t2: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = t1 }
    val b = Calendar.getInstance().apply { timeInMillis = t2 }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
           a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
