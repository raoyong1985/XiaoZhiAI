package com.xiaozhi.aiservice.presentation.ui.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.xiaozhi.aiservice.presentation.viewmodel.ContactsViewModel
import com.xiaozhi.aiservice.utils.DeviceIdHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    onContactClick: (BotContact) -> Unit
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
                    Text("智能体", fontSize = 20.sp, fontWeight = FontWeight.Medium)
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
                            imageVector = Icons.Default.Settings,
                            contentDescription = "控制台",
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
    onSave: (BotContact) -> Unit
) {
    val isEditing = contact != null
    var name by remember { mutableStateOf(contact?.name ?: "") }
    var serverUrl by remember { mutableStateOf(contact?.serverUrl ?: "wss://api.tenclass.net/xiaozhi/v1/") }
    var otaUrl by remember { mutableStateOf(contact?.otaUrl ?: "https://api.tenclass.net/xiaozhi/ota/") }
    var inputMode by remember { mutableStateOf(contact?.inputMode ?: InputMode.VOICE) }

    // 新建时预生成唯一ID（用于生成稳定的MAC/UUID）；编辑时使用已有值
    val newContactId: String = contact?.id ?: java.util.UUID.randomUUID().toString()
    val generatedDeviceId: String = contact?.deviceId ?: DeviceIdHelper.generateContactMac()
    val generatedClientId: String = contact?.clientId ?: DeviceIdHelper.generateContactUuid(newContactId)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "编辑智能体" else "添加智能体") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称 *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("例如：小智一号") }
                )

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("WebSocket服务器地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("wss://api.tenclass.net/xiaozhi/v1/") }
                )

                OutlinedTextField(
                    value = otaUrl,
                    onValueChange = { otaUrl = it },
                    label = { Text("OTA注册地址") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("https://api.tenclass.net/xiaozhi/ota/") }
                )

                // 设备ID - 只读显示（Box叠加实现，不依赖disabled颜色）
                Box {
                    OutlinedTextField(
                        value = generatedDeviceId,
                        onValueChange = {},
                        label = { Text("设备ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledContainerColor = Color.Transparent
                        )
                    )
                    // 点击整行复制（可选UX增强）
                }

                // 客户端ID - 只读显示
                Box {
                    OutlinedTextField(
                        value = generatedClientId,
                        onValueChange = {},
                        label = { Text("客户端ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledContainerColor = Color.Transparent
                        )
                    )
                }

                Text(
                    "输入模式",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = inputMode == InputMode.VOICE,
                        onClick = { inputMode = InputMode.VOICE },
                        label = { Text("语音") },
                        leadingIcon = {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = inputMode == InputMode.TEXT,
                        onClick = { inputMode = InputMode.TEXT },
                        label = { Text("文字") },
                        leadingIcon = {
                            Icon(Icons.Default.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) return@TextButton
                    val newContact = BotContact(
                        id = newContactId,
                        name = name.trim(),
                        serverUrl = serverUrl.trim(),
                        otaUrl = otaUrl.trim(),
                        deviceId = generatedDeviceId,
                        clientId = generatedClientId,
                        inputMode = inputMode
                    )
                    onSave(newContact)
                },
                enabled = name.isNotBlank()
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
