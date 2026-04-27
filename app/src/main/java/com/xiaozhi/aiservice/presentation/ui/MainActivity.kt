package com.xiaozhi.aiservice.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.xiaozhi.aiservice.di.AppContainer
import com.xiaozhi.aiservice.presentation.theme.XiaoZhiTheme
import com.xiaozhi.aiservice.presentation.ui.chat.ChatScreen
import com.xiaozhi.aiservice.presentation.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    private lateinit var chatViewModel: ChatViewModel
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        val networkGranted = permissions[Manifest.permission.INTERNET] == true
        if (audioGranted && networkGranted) {
            initializeViewModel()
        } else {
            // 权限被拒绝，显示提示
            val denied = permissions.filterValues { !it }.keys.joinToString(", ")
            Toast.makeText(this, "权限不足，无法运行: $denied", Toast.LENGTH_LONG).show()
            Log.e(TAG, "权限被拒绝: $denied")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        checkAndRequestPermissions()
    }
    
    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        
        // Base permissions (always needed)
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
        
        // Android 13+ Notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // Android 12+ Bluetooth permissions
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
            initializeViewModel()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
    
    private fun initializeViewModel() {
        chatViewModel = ViewModelProvider(
            this,
            ChatViewModel.Factory(AppContainer.gson)
        )[ChatViewModel::class.java]
        
        setContent {
            XiaoZhiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatScreen(
                        viewModel = chatViewModel,
                        onNavigateToSettings = { }
                    )
                }
            }
        }
    }
}
