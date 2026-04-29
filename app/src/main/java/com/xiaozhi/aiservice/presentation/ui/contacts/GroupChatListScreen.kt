package com.xiaozhi.aiservice.presentation.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xiaozhi.aiservice.presentation.viewmodel.GroupChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupChatListScreen(
    viewModel: GroupChatViewModel = viewModel(factory = GroupChatViewModel.Factory()),
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
                title = { Text("群聊", fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateGroupClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "创建群聊")
            }
        }
    ) { padding ->
        if (groups.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Group,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "暂无群聊",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "点击 + 创建群聊，选择多个智能体加入",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(groups) { group ->
                    ListItem(
                        modifier = Modifier.clickable { onGroupClick(group.id) },
                        headlineContent = {
                            Text(
                                text = group.name,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp
                            )
                        },
                        supportingContent = {
                            Text(
                                text = "${group.botIds.size} 个成员",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Default.Group,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
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
                                    DropdownMenuItem(
                                        text = { Text("编辑名称") },
                                        onClick = { showMenu = false; editingGroup = group },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("添加成员") },
                                        onClick = { showMenu = false; addingMemberGroup = group },
                                        leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除成员") },
                                        onClick = { showMenu = false; removingMemberGroup = group },
                                        leadingIcon = { Icon(Icons.Default.PersonRemove, contentDescription = null) }
                                    )
                                    Divider()
                                    DropdownMenuItem(
                                        text = { Text("清空群聊记录") },
                                        onClick = { showMenu = false; clearingGroup = group },
                                        leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("导出群聊记录") },
                                        onClick = {
                                            showMenu = false
                                            viewModel.exportGroupMessages(group.id, context)
                                        },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                                    )
                                    Divider()
                                    DropdownMenuItem(
                                        text = { Text("解散该群", color = MaterialTheme.colorScheme.error) },
                                        onClick = { showMenu = false; deletingGroup = group },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    // 编辑群聊名称对话框
    editingGroup?.let { group ->
        var newName by remember(group.id) { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { editingGroup = null },
            title = { Text("编辑群聊名称") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("群聊名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isNotBlank()) {
                        coroutineScope.launch {
                            viewModel.updateGroupName(group.id, newName.trim())
                            editingGroup = null
                        }
                    }
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingGroup = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 删除群聊确认对话框
    deletingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deletingGroup = null },
            title = { Text("删除群聊") },
            text = { Text("确定要删除 \"${group.name}\" 吗？") },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        viewModel.deleteGroup(group.id)
                        deletingGroup = null
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingGroup = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 添加成员对话框
    addingMemberGroup?.let { group ->
        val available = contacts.filter { c -> !group.botIds.contains(c.id) }
        AlertDialog(
            onDismissRequest = { addingMemberGroup = null },
            title = { Text("添加成员到 ${group.name}") },
            text = {
                if (available.isEmpty()) {
                    Text("没有可添加的智能体")
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text("选择要添加的智能体：")
                        Spacer(modifier = Modifier.height(8.dp))
                        available.forEach { contact ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        coroutineScope.launch {
                                            viewModel.addBotToGroup(group.id, contact.id)
                                            addingMemberGroup = null
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(contact.name, fontSize = 16.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { addingMemberGroup = null }) { Text("取消") }
            }
        )
    }

    // 删除成员对话框
    removingMemberGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { removingMemberGroup = null },
            title = { Text("从 ${group.name} 删除成员") },
            text = {
                if (group.botIds.isEmpty()) {
                    Text("群聊中没有成员")
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text("选择要删除的智能体：")
                        Spacer(modifier = Modifier.height(8.dp))
                        group.botIds.forEach { botId ->
                            val contact = contacts.find { it.id == botId }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        coroutineScope.launch {
                                            viewModel.removeBotFromGroup(group.id, botId)
                                            removingMemberGroup = null
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(contact?.name ?: botId, fontSize = 16.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { removingMemberGroup = null }) { Text("取消") }
            }
        )
    }

    // 清空聊天记录确认对话框
    clearingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { clearingGroup = null },
            title = { Text("清空聊天记录") },
            text = { Text("确定要清空 \"${group.name}\" 的所有聊天记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch {
                        viewModel.clearGroupMessages(group.id)
                        clearingGroup = null
                    }
                }) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearingGroup = null }) { Text("取消") }
            }
        )
    }
}
