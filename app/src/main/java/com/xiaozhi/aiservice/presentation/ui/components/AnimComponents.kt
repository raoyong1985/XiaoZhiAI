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
 *
 * @param pressedScale 按下时的缩放比例
 * @param stiffness Spring 刚度，值越大回弹越快
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
        label = "bounce_scale"
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
