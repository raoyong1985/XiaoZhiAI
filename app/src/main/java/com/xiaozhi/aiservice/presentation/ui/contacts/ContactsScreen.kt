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
                    Text("联系人", style = MaterialTheme.typography.titleLarge)
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
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
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
    val trainingContactId by viewModel.trainingContactId.collectAsState()
    val trainingStatusMap by viewModel.trainingStatus.collectAsState()

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
            trainingContactId = trainingContactId,
            trainingStatusMap = trainingStatusMap,
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
            trainingContactId = trainingContactId,
            trainingStatusMap = trainingStatusMap,
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
                style = MaterialTheme.typography.titleMedium
            )
        },
        supportingContent = {
            Column {
                if (lastMessage != null && lastMessage.content.isNotEmpty()) {
                    val prefix = if (lastMessage.direction == MessageDirection.SENT) "我: " else ""
                    Text(
                        text = "$prefix${lastMessage.content}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "ID: ${contact.deviceId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    onDismissRequest = { showMenu = false }
                ) {
                    SectionHeader("基本设置")
                    DropdownMenuItem(
                        text = { Text("编辑联系人") },
                        onClick = { showMenu = false; onEdit() },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    )
                    DropdownMenuItem(
                        text = {
                            Text(if (contact.inputMode == InputMode.VOICE) "切换为文字" else "切换为语音")
                        },
                        onClick = { showMenu = false; onToggleInputMode() },
                        leadingIcon = {
                            Icon(
                                if (contact.inputMode == InputMode.VOICE) Icons.Default.Keyboard else Icons.Default.Mic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    )
                    MenuDivider()

                    SectionHeader("数据管理")
                    DropdownMenuItem(
                        text = { Text("清空聊天记录") },
                        onClick = { showMenu = false; onClearMessages() },
                        leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    )
                    DropdownMenuItem(
                        text = { Text("导出聊天记录") },
                        onClick = { showMenu = false; onExportMessages() },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                    )
                    MenuDivider()

                    DropdownMenuItem(
                        text = { Text("删除联系人", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        }
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
    HorizontalDivider(
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
    trainingContactId: String?,
    trainingStatusMap: Map<String, String>,
    onDismiss: () -> Unit,
    onSave: (BotContact) -> Unit
) {
    val isEditing = contact != null
    val contactId = contact?.id ?: java.util.UUID.randomUUID().toString()
    var name by remember { mutableStateOf(contact?.name ?: "") }
    var serverUrl by remember { mutableStateOf(contact?.serverUrl ?: BotContact().serverUrl) }
    var otaUrl by remember { mutableStateOf(contact?.otaUrl ?: BotContact().otaUrl) }
    var wakeWord by remember { mutableStateOf(contact?.wakeWord ?: "") }
    val wakeWordAutoFilled = remember { mutableStateOf(false) }
    LaunchedEffect(name) {
        if (!wakeWordAutoFilled.value && wakeWord.isEmpty() && name.isNotBlank()) {
            wakeWord = "$name $name"
            wakeWordAutoFilled.value = true
        }
    }
    val trainingStatus = trainingStatusMap[contactId] ?: ""
    val generatedDeviceId: String = contact?.deviceId ?: DeviceIdHelper.generateContactMac()
    val generatedClientId: String = contact?.clientId ?: DeviceIdHelper.generateContactUuid(contactId)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            shape = AppShapes.dialog,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── Top bar ──
                TopAppBar(
                    title = {
                        Text(
                            text = if (isEditing) "编辑智能体" else "添加智能体",
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // ── Form content ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // 基本配置
                    FormSection(title = "基本配置") {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("例如：小智一号") },
                            shape = AppShapes.menu
                        )
                    }

                    // 服务器配置
                    FormSection(title = "服务器配置") {
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text("WebSocket 服务器") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("wss://api.tenclass.net/xiaozhi/v1/") },
                            shape = AppShapes.menu
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = otaUrl,
                            onValueChange = { otaUrl = it },
                            label = { Text("OTA 注册地址") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("https://api.tenclass.net/xiaozhi/ota/") },
                            shape = AppShapes.menu
                        )
                    }

                    // 语音唤醒
                    FormSection(title = "语音唤醒") {
                        val effectiveWake = wakeWord.ifBlank { "${name} ${name}" }

                        OutlinedTextField(
                            value = wakeWord,
                            onValueChange = { wakeWord = it },
                            label = { Text("唤醒词") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("例如：${name}${name}") },
                            shape = AppShapes.menu
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val isTrainingThis = trainingContactId == contactId

                        if (isTrainingThis) {
                            WakeStatusRow(
                                icon = {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                },
                                title = "正在自动训练唤醒模板",
                                subtitle = "合成「${effectiveWake}」音频并提取声纹特征",
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            FilledTonalButton(
                                onClick = { viewModel.trainWakeTemplate(contactId, effectiveWake) },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth(),
                                shape = AppShapes.menu
                            ) {
                                Text(
                                    "自动训练",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        // 训练状态文本（显示在弹窗内，不依赖 Toast/Snackbar）
                        if (trainingStatus.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = trainingStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (trainingStatus.startsWith("✅")) MaterialTheme.colorScheme.primary
                                       else if (trainingStatus.startsWith("❌")) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // 设备标识
                    FormSection(title = "设备标识") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("版本：${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("设备ID：$generatedDeviceId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("客户端ID：$generatedClientId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }

                // ── Bottom buttons ──
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .navigationBarsPadding(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("取消", style = MaterialTheme.typography.labelLarge)
                        }
                        TextButton(
                            onClick = {
                                if (name.isBlank()) return@TextButton
                                val newContact = BotContact(
                                    id = contactId,
                                    name = name.trim(),
                                    serverUrl = serverUrl.trim(),
                                    otaUrl = otaUrl.trim(),
                                    deviceId = generatedDeviceId,
                                    clientId = generatedClientId,
                                    wakeWord = wakeWord.trim().ifBlank { "${name.trim()} ${name.trim()}" }
                                )
                                onSave(newContact)
                            },
                            enabled = name.isNotBlank()
                        ) {
                            Text("保存", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

// ── 表单分区组件 ──

@Composable
private fun FormSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

@Composable
private fun WakeStatusRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppShapes.menu)
            .background(color.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = color
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
fun MenuDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
