package com.xiaozhi.aiservice.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.xiaozhi.aiservice.di.AppContext
import com.xiaozhi.aiservice.presentation.theme.XiaoZhiTheme
import com.xiaozhi.aiservice.presentation.ui.chat.ChatScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.ContactsScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.CreateGroupScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.GroupChatListScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.GroupChatScreen
import com.xiaozhi.aiservice.presentation.viewmodel.ChatViewModel
import com.xiaozhi.aiservice.presentation.viewmodel.ContactsViewModel
import com.xiaozhi.aiservice.utils.Logger
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        Logger.i(TAG, "权限结果: RECORD_AUDIO=$audioGranted")
        // onCreate 中已 setContent，不需要重复调用
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContext.initDeviceIds(this)
        checkAndRequestPermissions()
        setContent {
            XiaoZhiTheme {
                MainNavigation()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // RECORD_AUDIO 是核心功能权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        // Android 13+ 通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Android 12+ 蓝牙权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        // 定位（WiFi 扫描 + MCP 定位工具）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // 相机（MCP 拍照、扫码）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }

        // 读取联系人（MCP 拨号、短信）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@Composable
private fun MainNavigation() {
    val chatViewModel: ChatViewModel = hiltViewModel()
    val contactsViewModel: ContactsViewModel = hiltViewModel()
    var selectedContactId by rememberSaveable { mutableStateOf<String?>(null) }
    var showGroupChatList by rememberSaveable { mutableStateOf(false) }
    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateGroup by rememberSaveable { mutableStateOf(false) }
    val contacts by contactsViewModel.contacts.collectAsState()

    BackHandler(enabled = selectedGroupId != null || showCreateGroup || showGroupChatList || selectedContactId != null) {
        when {
            selectedGroupId != null -> selectedGroupId = null
            showCreateGroup -> showCreateGroup = false
            showGroupChatList -> showGroupChatList = false
            selectedContactId != null -> selectedContactId = null
        }
    }

    val currentScreen = when {
        selectedGroupId != null -> "group_chat"
        showCreateGroup -> "create_group"
        showGroupChatList -> "group_chat_list"
        selectedContactId != null -> "chat"
        else -> "contacts"
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            if (targetState == "chat" || targetState == "group_chat") {
                slideInHorizontally(tween(300)) { it }
                    .togetherWith(slideOutHorizontally(tween(300)) { -it / 3 })
            } else if (initialState == "chat" || initialState == "group_chat") {
                slideInHorizontally(tween(300)) { -it / 3 }
                    .togetherWith(slideOutHorizontally(tween(300)) { it })
            } else {
                fadeIn(tween(250))
                    .togetherWith(fadeOut(tween(250)))
            }
        },
        label = "page_transition"
    ) { screen ->
        when (screen) {
            "group_chat" -> {
                selectedGroupId?.let { groupId ->
                    GroupChatScreen(
                        groupId = groupId,
                        onNavigateBack = { selectedGroupId = null }
                    )
                }
            }
            "create_group" -> {
                CreateGroupScreen(
                    onNavigateBack = { showCreateGroup = false },
                    onGroupCreated = { groupId ->
                        showCreateGroup = false
                        selectedGroupId = groupId
                    }
                )
            }
            "group_chat_list" -> {
                GroupChatListScreen(
                    onGroupClick = { groupId ->
                        selectedGroupId = groupId
                    },
                    onCreateGroupClick = { showCreateGroup = true },
                    onNavigateBack = { showGroupChatList = false }
                )
            }
            "chat" -> {
                val contact = contacts.find { it.id == selectedContactId }
                if (contact != null) {
                    // 设置唤醒词 = 联系人名 + "你好"
                    chatViewModel.setCurrentWakeWord(contact.name)
                    ChatScreen(
                        viewModel = chatViewModel,
                        contact = contact,
                        onNavigateBack = { selectedContactId = null }
                    )
                }
            }
            else -> {
                ContactsScreen(
                    viewModel = contactsViewModel,
                    onContactClick = { contact ->
                        selectedContactId = contact.id
                    },
                    onGroupChatClick = { showGroupChatList = true }
                )
            }
        }
    }
}
