package com.xiaozhi.aiservice.presentation.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 弹性点击形变修饰符 — 按下时缩小弹缩，松手时 spring 回弹。
 * 所有可点击元素建议加上此效果，让交互更有物理感。
 */
fun Modifier.bounceClick(
    pressedScale: Float = 0.94f,
    stiffness: Float = Spring.StiffnessLow
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = stiffness
        ),
        label = "bounce_scale",
        finishedListener = { if (!isPressed) { /* 回弹完成 */ } }
    )
    Modifier
        .graphicsLayer(scaleX = scale, scaleY = scale)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    tryAwaitRelease()
                    isPressed = false
                }
            )
        }
}

/**
 * 轻量级点击弹性 — 不拦截点击事件，仅视觉反馈。
 * 配合 [Modifier.clickable] 使用，先弹再执行点击。
 */
fun Modifier.lightBounce(
    pressedScale: Float = 0.96f
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "light_bounce",
        finishedListener = { }
    )
    Modifier
        .graphicsLayer(scaleX = scale, scaleY = scale)
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    tryAwaitRelease()
                    isPressed = false
                }
            )
        }
}
