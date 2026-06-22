package com.xiaozhi.aiservice.presentation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiaozhi.aiservice.domain.model.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 共享消息列表示 + 自动滚到底部 + 回到底部FAB
 *
 * 私聊/群聊共用，通过 [messageContent] slot 自定义每条消息的渲染。
 */
@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    messageContent: @Composable (chatMessage: ChatMessage, index: Int) -> Unit
) {
    // ── 自动滚动到最新消息 ──
    // 首次加载无动画；后续若用户在底部则平滑滚动，若滚上去了则不打扰阅读
    var initialLoad by remember { mutableStateOf(true) }
    val initialItemCount by remember { mutableIntStateOf(messages.size) }
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
    // ── 回到底部按钮状态 ──
    // ⚠️ derivedStateOf 不套 remember(key)，否则每次消息变化重建 entire state
    // derivedStateOf 通过 Compose 快照系统追踪 listState.layoutInfo 的读取，
    // 滚动时自动更新，新增消息时 totalItemsCount 变化也自动触发
    val showScrollToBottom by remember {
        derivedStateOf {
            val visInfo = listState.layoutInfo.visibleItemsInfo
            val lastVisible = visInfo.lastOrNull()?.index ?: -1
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible < totalItems - 1
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 2.dp),
            state = listState
        ) {
            itemsIndexed(messages, key = { _, msg -> msg.id }) { index, message ->
                if (index == 0 || !isSameDay(messages[index - 1].timestamp, message.timestamp)) {
                    DateSeparator(message.timestamp)
                }
                if (index >= initialItemCount) {
                    // 新消息：轻量 fadeIn 入场，无 slide/spring 减少布局开销
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(200)),
                        exit = fadeOut(animationSpec = tween(200))
                    ) { messageContent(message, index) }
                } else {
                    // 历史消息（初始加载）：瞬间显示，零动画开销
                    messageContent(message, index)
                }
            }
        }

        // ── 回到底部浮动按钮 ──
        AnimatedVisibility(
            visible = showScrollToBottom,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp)
        ) {
            SmallFloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "回到底部")
            }
        }
    }
}
