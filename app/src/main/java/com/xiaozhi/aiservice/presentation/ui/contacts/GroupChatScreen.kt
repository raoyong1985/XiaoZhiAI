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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import androidx.hilt.navigation.compose.hiltViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.draw.scale
import androidx.core.content.ContextCompat
import com.xiaozhi.aiservice.domain.model.BotConnectionState
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
    val contacts by viewModel.contacts.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    val connectionColor by animateColorAsState(
        targetValue = when (connectionState) {
            is BotConnectionState.Connected -> MaterialTheme.colorScheme.primary
            is BotConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary
            is BotConnectionState.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        },
        label = "connection_color"
    )

    // ── 群成员名称列表（用于 @提及） ──
    val groupMemberNames = remember(group, contacts) {
        group?.botIds?.mapNotNull { botId ->
            contacts.find { it.id == botId }?.name
        } ?: emptyList()
    }

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
                        Text(group?.name ?: "群聊", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${group?.botIds?.size ?: 0} 个成员",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            InputBar(
                isRecording = isRecording,
                inputMode = inputMode,
                isAiResponding = isPingPong && isAiResponding,
                groupMembers = groupMemberNames,
                isGroup = true,
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
        ) { message, _ ->
            val isDark = isSystemInDarkTheme()
            val botColor = remember(message.botName, isDark) {
                if (message.botName.isNotEmpty())
                    com.xiaozhi.aiservice.presentation.ui.components.BotColorScheme.forName(message.botName, isDark)
                else
                    com.xiaozhi.aiservice.presentation.ui.components.BotColorScheme.get(0, isDark)
            }
            ChatMessageItem(
                message = message,
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
