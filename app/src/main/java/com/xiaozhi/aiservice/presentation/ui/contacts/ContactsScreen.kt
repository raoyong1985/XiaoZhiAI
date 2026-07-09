package com.xiaozhi.aiservice.presentation.ui.contacts

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import com.xiaozhi.aiservice.BuildConfig
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.domain.model.InputMode
import com.xiaozhi.aiservice.domain.model.MessageDirection
import com.xiaozhi.aiservice.presentation.theme.AppShapes
import com.xiaozhi.aiservice.presentation.ui.components.bounceClick
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
    val lastMessages by viewModel.lastMessages.collectAsState()
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<BotContact?>(null) }
    var contactToDelete by remember { mutableStateOf<BotContact?>(null) }
    var contactToClear by remember { mutableStateOf<BotContact?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("联系人", fontSize = 18.sp, fontWeight = FontWeight.Medium)
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
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(contacts, key = { it.id }) { contact ->
                    ContactItem(
                        contact = contact,
                        lastMessage = lastMessages[contact.id],
                        onClick = { onContactClick(contact) },
                        onEdit = { editingContact = contact },
                        onDelete = { contactToDelete = contact },
                        onClearMessages = { contactToClear = contact },
                        onExportMessages = { viewModel.exportMessages(contact.id, context) },
                        onToggleInputMode = { viewModel.toggleInputMode(contact) }
                    )
                }
            }
        }
    }

    val wakeTemplatesMap by viewModel.wakeTemplates.collectAsState()
    val recordingContactId by viewModel.recordingTemplateId.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.toastEvent.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 添加联系人弹窗
    if (showAddDialog) {
        AddEditContactDialog(
            contact = null,
            viewModel = viewModel,
            wakeTemplatesMap = wakeTemplatesMap,
            recordingContactId = recordingContactId,
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
            viewModel = viewModel,
            wakeTemplatesMap = wakeTemplatesMap,
            recordingContactId = recordingContactId,
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
            shape = AppShapes.dialog,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("删除联系人")
                }
            },
            text = { Text("确定删除「${contact.name}」吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteContact(contact.id)
                        contactToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 清空聊天记录确认弹窗
    contactToClear?.let { contact ->
        AlertDialog(
            onDismissRequest = { contactToClear = null },
            shape = AppShapes.dialog,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("清空聊天记录")
                }
            },
            text = { Text("确定要清空「${contact.name}」的所有聊天记录吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearMessages(contact.id)
                        contactToClear = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { contactToClear = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun EmptyContactsView(modifier: Modifier = Modifier) {
    com.xiaozhi.aiservice.presentation.ui.components.EmptyStateView(
        icon = Icons.Default.SmartToy,
        title = "暂无智能体",
        subtitle = "点击 + 添加第一个智能体",
        modifier = modifier
    )
}

@Composable
private fun ContactItem(
    contact: BotContact,
    lastMessage: com.xiaozhi.aiservice.domain.model.ChatMessage? = null,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClearMessages: () -> Unit,
    onExportMessages: () -> Unit,
    onToggleInputMode: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        modifier = Modifier
            .bounceClick()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        headlineContent = {
            Text(
                text = contact.name,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp
            )
        },
        supportingContent = {
            Column {
                if (lastMessage != null && lastMessage.content.isNotEmpty()) {
                    val prefix = if (lastMessage.direction == MessageDirection.SENT) "我: " else ""
                    Text(
                        text = "$prefix${lastMessage.content}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (contact.inputMode == InputMode.VOICE)
                            Icons.Default.Mic else Icons.Default.Keyboard,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "ID: ${contact.deviceId}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        },
        leadingContent = {
            com.xiaozhi.aiservice.presentation.ui.components.AvatarView(
                name = contact.name,
                size = 48.dp,
                avatarColor = com.xiaozhi.aiservice.presentation.ui.components.BotColorScheme.forName(contact.name).avatarColor
            )
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
                        .clip(AppShapes.menu)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            AppShapes.menu
                        )
                ) {
                    SectionHeader("基本设置")
                    DropdownMenuItem(
                        text = { Text("编辑联系人") },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
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
                    MenuDivider()

                    SectionHeader("数据管理")
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
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    )
                    MenuDivider()

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
        modifier = Modifier.padding(start = 72.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        thickness = 0.5.dp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditContactDialog(
    contact: BotContact?,
    viewModel: ContactsViewModel,
    wakeTemplatesMap: Map<String, String>,
    recordingContactId: String?,
    onDismiss: () -> Unit,
    onSave: (BotContact) -> Unit
) {
    val isEditing = contact != null
    var name by remember { mutableStateOf(contact?.name ?: "") }
    var serverUrl by remember { mutableStateOf(contact?.serverUrl ?: BotContact().serverUrl) }
    var otaUrl by remember { mutableStateOf(contact?.otaUrl ?: BotContact().otaUrl) }
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

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
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

                    Text("语音唤醒", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    val contactId = contact?.id ?: newContactId
                    val exampleWake = if (name.isNotBlank()) "${name}${name}" else "悠悠悠悠"

                    val hasTemplate = wakeTemplatesMap.containsKey(contactId)
                    val isRecordingThis = recordingContactId == contactId

                    if (isRecordingThis) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "正在录制唤醒模板",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "请说出「${exampleWake}」",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (hasTemplate) Icons.Default.CheckCircle else Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (hasTemplate) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (hasTemplate) "本地唤醒模板已录制" else "未录制唤醒模板",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (hasTemplate) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "录制后可在离线时使用本地声纹匹配",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            FilledTonalButton(
                                onClick = { viewModel.recordWakeTemplate(contactId) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (hasTemplate) "重录" else "录制",
                                    fontSize = 12.sp
                                )
                            }
                            }
                        }

                    Divider()

                    Text("设备标识", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                    Text("版本：${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("设备ID：$generatedDeviceId", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("客户端ID：$generatedClientId", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

                    Spacer(modifier = Modifier.height(80.dp))
                }

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
                                    wakeWord = "${name.trim()}${name.trim()}"
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

// ═══════════════════════════════════════════
// 共享菜单辅助组件
// ═══════════════════════════════════════════

@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
fun MenuDivider() {
    Divider(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        thickness = 0.5.dp
    )
}
