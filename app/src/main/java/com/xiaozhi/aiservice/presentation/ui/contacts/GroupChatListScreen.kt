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
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.xiaozhi.aiservice.presentation.theme.AppShapes
import com.xiaozhi.aiservice.presentation.ui.components.AvatarView
import com.xiaozhi.aiservice.presentation.ui.components.BotColorScheme
import com.xiaozhi.aiservice.presentation.ui.components.bounceClick
import com.xiaozhi.aiservice.presentation.viewmodel.GroupChatViewModel
import kotlinx.coroutines.launch

/**
 * 群聊列表界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatListScreen(
    viewModel: GroupChatViewModel = hiltViewModel(),
    onGroupClick: (String) -> Unit,
    onCreateGroupClick: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val groups by viewModel.groups.collectAsState()
    val contacts by viewModel.contacts.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var editingGroup by remember { mutableStateOf<com.xiaozhi.aiservice.domain.model.GroupChat?>(null) }
    var deletingGroup by remember { mutableStateOf<com.xiaozhi.aiservice.domain.model.GroupChat?>(null) }
    var addingMemberGroup by remember { mutableStateOf<com.xiaozhi.aiservice.domain.model.GroupChat?>(null) }
    var removingMemberGroup by remember { mutableStateOf<com.xiaozhi.aiservice.domain.model.GroupChat?>(null) }
    var clearingGroup by remember { mutableStateOf<com.xiaozhi.aiservice.domain.model.GroupChat?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("群聊", fontSize = 18.sp, fontWeight = FontWeight.Medium) },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "联系人",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateGroupClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建群聊")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (groups.isEmpty()) {
            com.xiaozhi.aiservice.presentation.ui.components.EmptyStateView(
                icon = Icons.Default.Forum,
                title = "暂无群聊",
                subtitle = "点击 + 创建群聊，选择多个智能体加入",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(groups, key = { it.id }) { group ->
                    ListItem(
                        modifier = Modifier
                            .bounceClick()
                            .clickable { onGroupClick(group.id) }
                            .padding(horizontal = 4.dp),
                        headlineContent = {
                            Text(
                                text = group.name,
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
                                    imageVector = if (group.inputMode == 0) Icons.Default.Mic else Icons.Default.Keyboard,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = "${group.botIds.size} 个成员",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        leadingContent = {
                            AvatarView(
                                name = group.name,
                                size = 48.dp,
                                avatarColor = BotColorScheme.forName(group.name).avatarColor
                            )
                        },
                        trailingContent = {
                            Box(modifier = Modifier.padding(start = 8.dp)) {
                                var showMenu by remember { mutableStateOf(false) }
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "更多")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    SectionHeader("基本设置")
                                    DropdownMenuItem(
                                        text = { Text("编辑名称") },
                                        onClick = { showMenu = false; editingGroup = group },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (group.inputMode == 0) "切换为文字输入" else "切换为语音输入")
                                        },
                                        onClick = {
                                            showMenu = false
                                            val newMode = if (group.inputMode == 0) 1 else 0
                                            viewModel.setGroupInputMode(group.id, newMode)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (group.inputMode == 0) Icons.Default.Keyboard else Icons.Default.Mic,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    )
                                    MenuDivider()

                                    SectionHeader("成员管理")
                                    DropdownMenuItem(
                                        text = { Text("添加成员") },
                                        onClick = { showMenu = false; addingMemberGroup = group },
                                        leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除成员") },
                                        onClick = { showMenu = false; removingMemberGroup = group },
                                        leadingIcon = { Icon(Icons.Default.PersonRemove, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    )
                                    MenuDivider()

                                    SectionHeader("数据管理")
                                    DropdownMenuItem(
                                        text = { Text("清空群聊记录") },
                                        onClick = { showMenu = false; clearingGroup = group },
                                        leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("导出群聊记录") },
                                        onClick = { showMenu = false; viewModel.exportGroupMessages(group.id, context) },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                    )
                                    MenuDivider()

                                    DropdownMenuItem(
                                        text = { Text("解散该群", color = MaterialTheme.colorScheme.error) },
                                        onClick = { showMenu = false; deletingGroup = group },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
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
            }
        }
    }

    // ── 编辑群聊名称 ──
    editingGroup?.let { group ->
        var newName by remember(group.id) { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { editingGroup = null },
            shape = AppShapes.dialog,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("编辑群聊名称")
                }
            },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("群聊名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.menu
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            coroutineScope.launch {
                                viewModel.updateGroupName(group.id, newName.trim())
                                editingGroup = null
                            }
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingGroup = null }) { Text("取消") }
            }
        )
    }

    // ── 删除群聊 ──
    deletingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deletingGroup = null },
            shape = AppShapes.dialog,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("解散群聊")
                }
            },
            text = { Text("确定要解散「${group.name}」吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.deleteGroup(group.id)
                            deletingGroup = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("解散") }
            },
            dismissButton = {
                TextButton(onClick = { deletingGroup = null }) { Text("取消") }
            }
        )
    }

    // ── 添加成员 ──
    addingMemberGroup?.let { group ->
        val available = contacts.filter { c -> !group.botIds.contains(c.id) }
        val selectedToAdd = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { addingMemberGroup = null; selectedToAdd.clear() },
            shape = AppShapes.dialog,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("添加成员")
                }
            },
            text = {
                if (available.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text("没有可添加的智能体", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("选择要添加的智能体：", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            available.forEach { contact ->
                                val isChecked = selectedToAdd.contains(contact.id)
                                val botColor = BotColorScheme.forName(contact.name)
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        if (isChecked) selectedToAdd.remove(contact.id)
                                        else selectedToAdd.add(contact.id)
                                    },
                                    shape = AppShapes.menu,
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isChecked) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AvatarView(name = contact.name, size = 40.dp, avatarColor = botColor.avatarColor)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(contact.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                            Text("ID: ${contact.deviceId.takeLast(8)}", fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            imageVector = if (isChecked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = if (isChecked) "已选择" else null,
                                            tint = if (isChecked) MaterialTheme.colorScheme.primary
                                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedToAdd.isNotEmpty()) {
                            coroutineScope.launch {
                                viewModel.addBotsToGroup(group.id, selectedToAdd.toList())
                                addingMemberGroup = null
                            }
                        }
                    },
                    enabled = selectedToAdd.isNotEmpty()
                ) { Text("添加（${selectedToAdd.size}）") }
            },
            dismissButton = {
                TextButton(onClick = { addingMemberGroup = null; selectedToAdd.clear() }) { Text("取消") }
            }
        )
    }

    // ── 删除成员 ──
    removingMemberGroup?.let { group ->
        val selectedToRemove = remember { mutableStateListOf<String>() }
        AlertDialog(
            onDismissRequest = { removingMemberGroup = null; selectedToRemove.clear() },
            shape = AppShapes.dialog,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PersonRemove, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("删除成员")
                }
            },
            text = {
                if (group.botIds.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text("群聊中没有成员", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("选择要删除的智能体：", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            group.botIds.forEach { botId ->
                                val contact = contacts.find { it.id == botId }
                                val displayName = contact?.name ?: botId
                                val isChecked = selectedToRemove.contains(botId)
                                val botColor = BotColorScheme.forName(displayName)
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        if (isChecked) selectedToRemove.remove(botId)
                                        else selectedToRemove.add(botId)
                                    },
                                    shape = AppShapes.menu,
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isChecked) MaterialTheme.colorScheme.errorContainer
                                        else MaterialTheme.colorScheme.surface
                                    ),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AvatarView(name = displayName, size = 40.dp, avatarColor = botColor.avatarColor)
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(displayName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                            contact?.let {
                                                Text("ID: ${it.deviceId.takeLast(8)}", fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                            }
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        Icon(
                                            imageVector = if (isChecked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                            contentDescription = if (isChecked) "已选择" else null,
                                            tint = if (isChecked) MaterialTheme.colorScheme.error
                                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedToRemove.isNotEmpty()) {
                            coroutineScope.launch {
                                viewModel.removeBotsFromGroup(group.id, selectedToRemove.toList())
                                removingMemberGroup = null
                            }
                        }
                    },
                    enabled = selectedToRemove.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("删除（${selectedToRemove.size}）") }
            },
            dismissButton = {
                TextButton(onClick = { removingMemberGroup = null; selectedToRemove.clear() }) { Text("取消") }
            }
        )
    }

    // ── 清空群聊记录 ──
    clearingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { clearingGroup = null },
            shape = AppShapes.dialog,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("清空群聊记录")
                }
            },
            text = { Text("确定要清空「${group.name}」的所有聊天记录吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            viewModel.clearGroupMessages(group.id)
                            clearingGroup = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { clearingGroup = null }) { Text("取消") }
            }
        )
    }
}
