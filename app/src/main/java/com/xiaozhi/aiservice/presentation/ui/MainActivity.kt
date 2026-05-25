package com.xiaozhi.aiservice.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import com.xiaozhi.aiservice.utils.Logger
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.xiaozhi.aiservice.BuildConfig
import com.xiaozhi.aiservice.di.AppContext
import com.xiaozhi.aiservice.presentation.theme.XiaoZhiTheme
import com.xiaozhi.aiservice.presentation.ui.chat.ChatScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.ContactsScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.CreateGroupScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.GroupChatListScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.GroupChatScreen
import com.xiaozhi.aiservice.presentation.viewmodel.ChatViewModel
import com.xiaozhi.aiservice.presentation.viewmodel.ContactsViewModel
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
        setContent {
            XiaoZhiTheme {
                MainNavigation()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        val basePermissions = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )

        for (permission in basePermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

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

        // 定位权限（MCP: get_weather/get_location/get_network_info 需要）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // 日历读取权限（MCP: get_calendar 需要）
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CALENDAR)
        }

        if (permissionsToRequest.isEmpty()) {
            // 权限已有，onCreate 中已调用 setContent
        } else {
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

    // 处理返回键
    BackHandler(enabled = selectedGroupId != null || showCreateGroup || showGroupChatList || selectedContactId != null) {
        when {
            selectedGroupId != null -> selectedGroupId = null
            showCreateGroup -> showCreateGroup = false
            showGroupChatList -> showGroupChatList = false
            selectedContactId != null -> selectedContactId = null
        }
    }

    when {
        selectedGroupId != null -> {
            GroupChatScreen(
                groupId = selectedGroupId!!,
                onNavigateBack = { selectedGroupId = null }
            )
        }
        showCreateGroup -> {
            CreateGroupScreen(
                onNavigateBack = { showCreateGroup = false },
                onGroupCreated = { groupId ->
                    showCreateGroup = false
                    selectedGroupId = groupId
                }
            )
        }
        showGroupChatList -> {
            GroupChatListScreen(
                onGroupClick = { groupId ->
                    selectedGroupId = groupId
                },
                onCreateGroupClick = { showCreateGroup = true },
                onNavigateBack = { showGroupChatList = false }
            )
        }
        selectedContactId != null -> {
            val contact = contacts.find { it.id == selectedContactId }
            if (contact != null) {
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
