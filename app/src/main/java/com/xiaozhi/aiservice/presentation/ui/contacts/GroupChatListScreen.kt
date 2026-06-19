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
import com.xiaozhi.aiservice.presentation.viewmodel.GroupChatViewModel
import kotlinx.coroutines.launch

/**
 * 群聊列表界面
 *
 * 功能：
 * - 显示所有群聊（列表 / 空状态）
 * - 点击进入聊天
 * - 更多菜单：编辑名称、切换输入模式、添加/删除成员、
 *   清空记录、导出记录、解散群聊
 * - FAB 按钮创建新群聊
 *
 * 交互：
 * - 「编辑名称」弹出输入对话框
 * - 「添加/删除成员」弹出联系人选择列表
 * - 「清空记录」「解散群聊」弹出确认对话框
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

    // ── 各对话框的状态（null = 未展示） ──
    var editingGroup by remember { mutableStateOf<com.xiaozhi.aiservice.domain.model.GroupChat?>(null) }
    var deletingGroup by remember { mutableStateOf<com.xiaozhi.aiservice.domain.model.GroupChat?>(null) }
    var addingMemberGroup by remember { mutableStateOf<com.xiaozhi.aiservice.domain.model.GroupChat?>(null) }
    var removingMemberGroup by remember { mutableStateOf<com.xiaozhi.aiservice.domain.model.GroupChat?>(null) }
    var clearingGroup by remember { mutableStateOf<com.xiaozhi.aiservice.domain.model.GroupChat?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("群聊", fontSize = 20.sp, fontWeight = FontWeight.Medium) },
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
        // FAB 创建群聊
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
            // ── 空状态 ──
            com.xiaozhi.aiservice.presentation.ui.components.EmptyStateView(
                icon = Icons.Default.Forum,
                title = "暂无群聊",
                subtitle = "点击 + 创建群聊，选择多个智能体加入",
                modifier = Modifier.fillMaxSize().padding(padding)
            )
        } else {
            // ── 群聊列表 ──
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 2.dp)
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
                            com.xiaozhi.aiservice.presentation.ui.components.AvatarView(
                                name = group.name,
                                size = 48.dp
                            )
                        },
                        trailingContent = {
                            // ── 三点菜单 ──
                            Box(modifier = Modifier.padding(start = 8.dp)) {
                                var showMenu by remember { mutableStateOf(false) }
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
                                    // ── 基本设置 ──
                                    SectionHeader("基本设置")
                                    DropdownMenuItem(
                                        text = { Text("编辑名称") },
                                        onClick = { showMenu = false; editingGroup = group },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (group.inputMode == 0) "切换为文字输入" else "切换为语音输入"
                                            )
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
                                                tint = Color(0xFF4CAF50)
                                            )
                                        }
                                    )
                                    MenuDivider()

                                    // ── 成员管理 ──
                                    SectionHeader("成员管理")
                                    DropdownMenuItem(
                                        text = { Text("添加成员") },
                                        onClick = { showMenu = false; addingMemberGroup = group },
                                        leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null, tint = Color(0xFF4CAF50)) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("删除成员") },
                                        onClick = { showMenu = false; removingMemberGroup = group },
                                        leadingIcon = { Icon(Icons.Default.PersonRemove, contentDescription = null, tint = Color(0xFFFF9800)) }
                                    )
                                    MenuDivider()

                                    // ── 数据管理 ──
                                    SectionHeader("数据管理")
                                    DropdownMenuItem(
                                        text = { Text("清空群聊记录") },
                                        onClick = { showMenu = false; clearingGroup = group },
                                        leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color(0xFFFF9800)) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("导出群聊记录") },
                                        onClick = {
                                            showMenu = false
                                            viewModel.exportGroupMessages(group.id, context)
                                        },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                    )
                                    MenuDivider()

                                    // ── 危险操作 ──
                                    DropdownMenuItem(
                                        text = { Text("解散该群", color = MaterialTheme.colorScheme.error) },
                                        onClick = { showMenu = false; deletingGroup = group },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                    )
                                }
                            }
                        }
                    )
                    Divider(
                        modifier = Modifier.padding(start = 80.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════
    // 对话框
    // ═══════════════════════════════════════════

    // ── 编辑群聊名称 ──
    editingGroup?.let { group ->
        var newName by remember(group.id) { mutableStateOf(group.name) }
        AlertDialog(
            onDismissRequest = { editingGroup = null },
            shape = AppShapes.dialog,
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

    // ── 删除群聊确认 ──
    deletingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { deletingGroup = null },
            shape = AppShapes.dialog,
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

    // ── 添加成员 ──
    // 列出所有不在群内的联系人，点击即加入
    addingMemberGroup?.let { group ->
        val available = contacts.filter { c -> !group.botIds.contains(c.id) }
        AlertDialog(
            onDismissRequest = { addingMemberGroup = null },
            shape = AppShapes.dialog,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("添加成员", fontWeight = FontWeight.SemiBold)
                }
            },
            text = {
                if (available.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "没有可添加的智能体",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column {
                        Text(
                            "选择要添加的智能体：",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            available.forEachIndexed { index, contact ->
                                val botColor = BotColorScheme.forName(contact.name)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(AppShapes.menu)
                                        .clickable {
                                            coroutineScope.launch {
                                                viewModel.addBotToGroup(group.id, contact.id)
                                                addingMemberGroup = null
                                            }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .then(
                                            Modifier.padding(vertical = 6.dp)
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AvatarView(
                                        name = contact.name,
                                        size = 40.dp,
                                        avatarColor = botColor.avatarColor
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            contact.name,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            "ID: ${contact.deviceId.takeLast(8)}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    Icon(
                                        Icons.Default.AddCircle,
                                        contentDescription = "添加",
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                if (index < available.size - 1) {
                                    Divider(
                                        modifier = Modifier.padding(start = 52.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        thickness = 0.5.dp
                                    )
                                }
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

    // ── 删除成员 ──
    // 列出群内所有 bot，点击即移出
    removingMemberGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { removingMemberGroup = null },
            shape = AppShapes.dialog,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PersonRemove,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("删除成员", fontWeight = FontWeight.SemiBold)
                }
            },
            text = {
                if (group.botIds.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "群聊中没有成员",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column {
                        Text(
                            "选择要删除的智能体：",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            group.botIds.forEachIndexed { index, botId ->
                                val contact = contacts.find { it.id == botId }
                                val displayName = contact?.name ?: botId
                                val botColor = BotColorScheme.forName(displayName)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(AppShapes.menu)
                                        .clickable {
                                            coroutineScope.launch {
                                                viewModel.removeBotFromGroup(group.id, botId)
                                                removingMemberGroup = null
                                            }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                        .then(
                                            Modifier.padding(vertical = 6.dp)
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AvatarView(
                                        name = displayName,
                                        size = 40.dp,
                                        avatarColor = botColor.avatarColor
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            displayName,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        contact?.let {
                                            Text(
                                                "ID: ${it.deviceId.takeLast(8)}",
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                    }
                                    Icon(
                                        Icons.Default.RemoveCircle,
                                        contentDescription = "移除",
                                        tint = Color(0xFFFF5252),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                if (index < group.botIds.size - 1) {
                                    Divider(
                                        modifier = Modifier.padding(start = 52.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                        thickness = 0.5.dp
                                    )
                                }
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

    // ── 清空聊天记录确认 ──
    clearingGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { clearingGroup = null },
            shape = AppShapes.dialog,
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
