package com.xiaozhi.aiservice.presentation.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

@Composable
fun InputBar(
    isRecording: Boolean,
    inputMode: Int,
    onSendText: (String) -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onTryReconnect: (() -> Unit)? = null
) {
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(inputMode) {
        if (inputMode == 1) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(sending) {
        if (sending) {
            delay(500)
            sending = false
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recording_scale"
    )

    val buttonColor = if (isRecording) Color(0xFFF44336) else MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AnimatedContent(
                    targetState = inputMode,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { it } + fadeIn(animationSpec = tween(200))).togetherWith(
                                slideOutHorizontally { -it } + fadeOut(animationSpec = tween(200))
                            )
                        } else {
                            (slideInHorizontally { -it } + fadeIn(animationSpec = tween(200))).togetherWith(
                                slideOutHorizontally { it } + fadeOut(animationSpec = tween(200))
                            )
                        }
                    },
                    label = "input_mode",
                    modifier = Modifier.fillMaxWidth()
                ) { mode ->
                    when (mode) {
                        0 -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val micIcon = if (isRecording) Icons.Default.Stop else Icons.Default.Mic
                                val contentDesc = if (isRecording) "停止录音" else "开始录音"

                                IconButton(
                                    onClick = {
                                        if (isRecording) {
                                            onStopRecording()
                                        } else {
                                            onTryReconnect?.invoke()
                                            onStartRecording()
                                        }
                                    },
                                    modifier = Modifier
                                        .size(72.dp)
                                        .scale(if (isRecording) scale else 1f)
                                        .clip(CircleShape)
                                        .background(buttonColor)
                                ) {
                                    Icon(
                                        imageVector = micIcon,
                                        contentDescription = contentDesc,
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                        }
                        1 -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { text = it },
                                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                                    placeholder = { Text("输入消息...", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                        cursorColor = MaterialTheme.colorScheme.primary,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(
                                        onSend = {
                                            if (text.isNotBlank() && !sending) {
                                                sending = true
                                                onSendText(text.trim())
                                                text = ""
                                            }
                                        }
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                FilledIconButton(
                                    onClick = {
                                        if (text.isNotBlank() && !sending) {
                                            sending = true
                                            onSendText(text.trim())
                                            text = ""
                                        }
                                    },
                                    enabled = text.isNotBlank() && !sending,
                                    modifier = Modifier.size(52.dp),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = "发送",
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
