package com.xiaozhi.aiservice.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.xiaozhi.aiservice.BuildConfig
import com.xiaozhi.aiservice.di.AppContainer
import com.xiaozhi.aiservice.domain.model.BotContact
import com.xiaozhi.aiservice.presentation.theme.XiaoZhiTheme
import com.xiaozhi.aiservice.presentation.ui.chat.ChatScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.ContactsScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.CreateGroupScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.GroupChatListScreen
import com.xiaozhi.aiservice.presentation.ui.contacts.GroupChatScreen
import com.xiaozhi.aiservice.presentation.viewmodel.ChatViewModel
import com.xiaozhi.aiservice.presentation.viewmodel.ContactsViewModel

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private fun debugLogMainActivity(msg: String) {
        if (BuildConfig.ENABLE_DEBUG_LOG) Log.i(TAG, msg)
    }
    private lateinit var chatViewModel: ChatViewModel
    private lateinit var contactsViewModel: ContactsViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        debugLogMainActivity("权限结果: RECORD_AUDIO=$audioGranted")
        // 网络类权限安装时已授权，只要有 RECORD_AUDIO 结果就继续
        initializeViewModels()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppContainer.initDeviceIds(this)
        checkAndRequestPermissions()
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

        if (permissionsToRequest.isEmpty()) {
            initializeViewModels()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun initializeViewModels() {
        chatViewModel = ViewModelProvider(
            this,
            ChatViewModel.Factory(AppContainer.gson)
        )[ChatViewModel::class.java]

        contactsViewModel = ViewModelProvider(
            this,
            ContactsViewModel.Factory()
        )[ContactsViewModel::class.java]

        setContent {
            XiaoZhiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation(
                        chatViewModel = chatViewModel,
                        contactsViewModel = contactsViewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun MainNavigation(
    chatViewModel: ChatViewModel,
    contactsViewModel: ContactsViewModel
) {
    var selectedContact by remember { mutableStateOf<BotContact?>(null) }
    var showGroupChatList by remember { mutableStateOf(false) }
    var selectedGroupId by remember { mutableStateOf<String?>(null) }
    var showCreateGroup by remember { mutableStateOf(false) }

    // 处理返回键
    BackHandler(enabled = selectedGroupId != null || showCreateGroup || showGroupChatList || selectedContact != null) {
        when {
            selectedGroupId != null -> selectedGroupId = null
            showCreateGroup -> showCreateGroup = false
            showGroupChatList -> showGroupChatList = false
            selectedContact != null -> selectedContact = null
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
        selectedContact != null -> {
            ChatScreen(
                viewModel = chatViewModel,
                contact = selectedContact as BotContact,
                onNavigateBack = { selectedContact = null }
            )
        }
        else -> {
            ContactsScreen(
                viewModel = contactsViewModel,
                onContactClick = { contact ->
                    selectedContact = contact
                },
                onGroupChatClick = { showGroupChatList = true }
            )
        }
    }
}
