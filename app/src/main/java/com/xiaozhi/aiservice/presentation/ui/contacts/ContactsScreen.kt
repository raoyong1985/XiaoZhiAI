package com.xiaozhi.aiservice.presentation.ui.contacts

import androidx.compose.foundation.Canvas
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
                    IconButton(onClick = onGroupChatClick) {
                        Icon(
                            imageVector = Icons.Default.Group,
                            contentDescription = "群聊",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加联系人")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
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
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(contacts, key = { it.id }) { contact ->
                    ContactItem(
                        contact = contact,
                        onClick = { onContactClick(contact) },
                        onEdit = { editingContact = contact },
                        onDelete = { contactToDelete = contact },
                        onClearMessages = { viewModel.clearMessages(contact.id) },
                        onExportMessages = { viewModel.exportMessages(contact.id, context) },
                        onToggleInputMode = { viewModel.toggleInputMode(contact) }
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
        // 两个重叠的对话气泡
        Canvas(modifier = Modifier.size(80.dp)) {
            val c = androidx.compose.ui.graphics.Color(0xFFBDBDBD).copy(alpha = 0.4f)
            drawRoundRect(
                color = c,
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.05f, size.height * 0.25f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.65f, size.height * 0.5f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f)
            )
            drawRoundRect(
                color = c,
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.3f, size.height * 0.1f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.65f, size.height * 0.5f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.12f)
            )
        }
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
    onDelete: () -> Unit,
    onClearMessages: () -> Unit,
    onExportMessages: () -> Unit,
    onToggleInputMode: () -> Unit
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (contact.inputMode == InputMode.VOICE)
                        Icons.Default.Mic else Icons.Default.Keyboard,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    text = "ID: ${contact.deviceId}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.firstOrNull()?.toString() ?: "?",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        trailingContent = {
            Box(modifier = Modifier.padding(start = 8.dp)) {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    // 基本设置
                    Text(
                        "基本设置",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    DropdownMenuItem(
                        text = { Text("编辑联系人") },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF2196F3)) }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (contact.inputMode == InputMode.VOICE) "切换为文字" else "切换为语音"
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleInputMode()
                        },
                        leadingIcon = {
                            Icon(
                                if (contact.inputMode == InputMode.VOICE) Icons.Default.Keyboard else Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50)
                            )
                        }
                    )
                    Divider(modifier = Modifier.padding(vertical = 4.dp))

                    // 数据管理
                    Text(
                        "数据管理",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    DropdownMenuItem(
                        text = { Text("清空聊天记录") },
                        onClick = {
                            showMenu = false
                            onClearMessages()
                        },
                        leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color(0xFFFF9800)) }
                    )
                    DropdownMenuItem(
                        text = { Text("导出聊天记录") },
                        onClick = {
                            showMenu = false
                            onExportMessages()
                        },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = Color(0xFF9C27B0)) }
                    )
                    Divider(modifier = Modifier.padding(vertical = 4.dp))

                    // 危险操作
                    DropdownMenuItem(
                        text = { Text("删除联系人", color = MaterialTheme.colorScheme.error) },
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
    onSave: (BotContact) -> Unit
) {
    val isEditing = contact != null
    var name by remember { mutableStateOf(contact?.name ?: "") }
    var serverUrl by remember { mutableStateOf(contact?.serverUrl ?: com.xiaozhi.aiservice.domain.model.BotContact().serverUrl) }
    var otaUrl by remember { mutableStateOf(contact?.otaUrl ?: com.xiaozhi.aiservice.domain.model.BotContact().otaUrl) }

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
                                    clientId = generatedClientId
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
