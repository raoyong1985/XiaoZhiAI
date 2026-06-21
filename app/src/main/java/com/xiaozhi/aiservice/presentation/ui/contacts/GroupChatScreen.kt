package com.xiaozhi.aiservice.presentation.ui.contacts

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.sp

import androidx.hilt.navigation.compose.hiltViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.xiaozhi.aiservice.presentation.ui.components.ChatMessageList
import com.xiaozhi.aiservice.presentation.ui.components.ChatMessageItem
import com.xiaozhi.aiservice.presentation.ui.components.InputBar
import com.xiaozhi.aiservice.presentation.viewmodel.GroupChatViewModel
import kotlinx.coroutines.launch

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
    val inputMode = group?.inputMode ?: 0
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    val snackbarHostState = remember { SnackbarHostState() }
    val ctx = LocalContext.current

    val isPingPong = remember(group?.botIds?.size) { group?.botIds?.size == 2 }
    val isAiResponding by viewModel.isAiResponding.collectAsState()

    // ── lifecycle：进入时连接，离开时断开 ──
    LaunchedEffect(groupId) {
        viewModel.connectGroup(groupId)
    }
    DisposableEffect(groupId) {
        onDispose {
            viewModel.disconnectGroup()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding().navigationBarsPadding(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            InputBar(
                isRecording = isRecording,
                inputMode = inputMode,
                isAiResponding = isPingPong && isAiResponding,
                onSendText = { text ->
                    coroutineScope.launch {
                        viewModel.sendMessage(groupId, text)
                        keyboardController?.hide()
                    }
                },
                onStartRecording = { viewModel.startVoiceInput(groupId) },
                onStopRecording = { viewModel.stopVoiceInput() }
            )
        }
    ) { padding ->
        ChatMessageList(
            messages = messages,
            listState = listState,
            coroutineScope = coroutineScope,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { message, index, isNewMessage ->
            val isDark = isSystemInDarkTheme()
            val botColor = remember(message.botName, isDark) {
                if (message.botName.isNotEmpty())
                    com.xiaozhi.aiservice.presentation.ui.components.BotColorScheme.forName(message.botName, isDark)
                else
                    com.xiaozhi.aiservice.presentation.ui.components.BotColorScheme.get(0, isDark)
            }
            ChatMessageItem(
                message = message,
                index = index,
                animateIn = isNewMessage,
                botColor = botColor,
                showTimestamp = true,
                onCopy = { text ->
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("chat", text))
                }
            )
        }
    }
}
