package com.xiaozhi.aiservice.presentation.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.InputMode
import com.xiaozhi.aiservice.domain.model.VoiceMode
import com.xiaozhi.aiservice.presentation.viewmodel.ContactsViewModel
import com.xiaozhi.aiservice.utils.DeviceIdHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    onContactClick: (BotContact) -> Unit,
    onGroupChatClick: () -> Unit
) {
    val contacts by viewModel.contacts.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<BotContact?>(null) }
    var contactToDelete by remember { mutableStateOf<BotContact?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("联系人", fontSize = 20.sp, fontWeight = FontWeight.Medium)
                },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://xiaozhi.me/console/agents"))
                        ContextCompat.startActivity(context, intent, null)
                    }) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "控制台",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FloatingActionButton(
                    onClick = onGroupChatClick,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.Group, contentDescription = "群聊")
                }
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "添加联系人")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { padding ->
        if (contacts.isEmpty()) {
            EmptyContactsView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(contacts, key = { it.id }) { contact ->
                    ContactItem(
                        contact = contact,
                        onClick = { onContactClick(contact) },
                        onEdit = { editingContact = contact },
                        onDelete = { contactToDelete = contact }
                    )
                }
            }
        }
    }

    // 添加联系人弹窗
    if (showAddDialog) {
        AddEditContactDialog(
            contact = null,
            onDismiss = { showAddDialog = false },
            onSave = { contact ->
                viewModel.saveContact(contact)
                showAddDialog = false
            }
        )
    }

    // 编辑联系人弹窗
    editingContact?.let { contact ->
        AddEditContactDialog(
            contact = contact,
            onDismiss = { editingContact = null },
            onSave = { updated ->
                viewModel.saveContact(updated)
                editingContact = null
            },
            onClearMessages = {
                viewModel.clearMessages(contact.id)
                editingContact = null
            }
        )
    }

    // 删除确认弹窗
    contactToDelete?.let { contact ->
        AlertDialog(
            onDismissRequest = { contactToDelete = null },
            title = { Text("删除联系人") },
            text = { Text("确定删除 \"${contact.name}\" 吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteContact(contact.id)
                        contactToDelete = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun EmptyContactsView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.PersonAdd,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无智能体",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "点击 + 添加第一个智能体",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun ContactItem(
    contact: BotContact,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = contact.name,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = contact.serverUrl,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (contact.inputMode == InputMode.VOICE) "语音" else "文字",
                                fontSize = 11.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (contact.inputMode == InputMode.VOICE)
                                    Icons.Default.Mic else Icons.Default.Keyboard,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.take(1).uppercase(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
    Divider(
        modifier = Modifier.padding(start = 80.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditContactDialog(
    contact: BotContact?,
    onDismiss: () -> Unit,
    onSave: (BotContact) -> Unit,
    onClearMessages: (() -> Unit)? = null
) {
    val isEditing = contact != null
    var name by remember { mutableStateOf(contact?.name ?: "") }
    var serverUrl by remember { mutableStateOf(contact?.serverUrl ?: com.xiaozhi.aiservice.domain.model.BotContact().serverUrl) }
    var otaUrl by remember { mutableStateOf(contact?.otaUrl ?: com.xiaozhi.aiservice.domain.model.BotContact().otaUrl) }
    var inputMode by remember { mutableStateOf(contact?.inputMode ?: InputMode.VOICE) }
    var voiceMode by remember { mutableStateOf(contact?.voiceMode ?: VoiceMode.REALTIME) }

    val newContactId: String = contact?.id ?: java.util.UUID.randomUUID().toString()
    val generatedDeviceId: String = contact?.deviceId ?: DeviceIdHelper.generateContactMac()
    val generatedClientId: String = contact?.clientId ?: DeviceIdHelper.generateContactUuid(newContactId)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部导航栏
                TopAppBar(
                    title = {
                        Text(
                            text = if (isEditing) "编辑智能体" else "添加智能体",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // 可滚动内容区
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 基本配置
                    Text("基本配置", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("例如：小智一号") }
                    )

                    // 输入模式
                    Text("输入模式", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = inputMode == InputMode.VOICE,
                            onClick = { inputMode = InputMode.VOICE },
                            label = {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("语音模式", fontSize = 14.sp)
                                }
                            },
                            modifier = Modifier.height(40.dp).weight(1f),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        )
                        FilterChip(
                            selected = inputMode == InputMode.TEXT,
                            onClick = { inputMode = InputMode.TEXT },
                            label = {
                                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Text("文字模式", fontSize = 14.sp)
                                }
                            },
                            modifier = Modifier.height(40.dp).weight(1f),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        )
                    }

                    // 语音模式（仅语音输入模式时显示）
                    if (inputMode == InputMode.VOICE) {
                        Text("语音模式", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (mode in VoiceMode.entries) {
                                val isSelected = voiceMode == mode
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { voiceMode = mode },
                                    label = {
                                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text(mode.displayName, fontSize = 14.sp)
                                        }
                                    },
                                    modifier = Modifier.height(40.dp).weight(1f),
                                    border = FilterChipDefaults.filterChipBorder(
                                        borderColor = MaterialTheme.colorScheme.outline,
                                        selectedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                    )
                                )
                            }
                        }
                    }

                    Divider()

                    // 服务器配置
                    Text("服务器配置", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("WebSocket 服务器") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("wss://api.tenclass.net/xiaozhi/v1/") }
                    )
                    OutlinedTextField(
                        value = otaUrl,
                        onValueChange = { otaUrl = it },
                        label = { Text("OTA 注册地址") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://api.tenclass.net/xiaozhi/ota/") }
                    )

                    Divider()

                    // ID 信息（只读）
                    Text("设备标识", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    Text("设备ID：$generatedDeviceId", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("客户端ID：$generatedClientId", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

                    // 清除聊天记录（仅编辑时）
                    if (isEditing && onClearMessages != null) {
                        Divider()
                        OutlinedButton(
                            onClick = onClearMessages,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("清除聊天记录")
                        }
                    }

                    Spacer(modifier = Modifier.height(80.dp))
                }

                // 底部固定按钮栏
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .navigationBarsPadding(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.height(48.dp).weight(1f)
                        ) {
                            Text("取消", fontSize = 16.sp)
                        }
                        Button(
                            onClick = {
                                if (name.isBlank()) return@Button
                                val newContact = BotContact(
                                    id = newContactId,
                                    name = name.trim(),
                                    serverUrl = serverUrl.trim(),
                                    otaUrl = otaUrl.trim(),
                                    deviceId = generatedDeviceId,
                                    clientId = generatedClientId,
                                    inputMode = inputMode,
                                    voiceMode = voiceMode
                                )
                                onSave(newContact)
                            },
                            enabled = name.isNotBlank(),
                            modifier = Modifier.height(48.dp).weight(1f)
                        ) {
                            Text("保存", fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}
