package com.xiaozhi.aiservice.presentation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 统一Bot色彩方案（淡雅系）
 *
 * 每个bot一个颜色族：
 * - bubbleColor = 淡色（气泡背景）
 * - avatarColor = 饱和色（头像背景）
 * - borderColor = 深色（气泡边框）
 *
 * 共8个色系，单聊固定用第一个色系。
 */
object BotColorScheme {

    data class BotColor(
        val bubbleColor: Color,   // 气泡背景（淡）
        val avatarColor: Color,   // 头像背景（饱和）
        val nameColor: Color,      // 名字文字色（适中）
        val borderColor: Color    // 气泡边框色（比 bubbleColor 深）
    )

    // 浅色模式（16色淡雅系）— 边框只比气泡深一点点
    private val lightColors = listOf(
        BotColor(Color(0xFFE8F5E9), Color(0xFF66BB6A), Color(0xFF388E3C), Color(0xFFD7EDD8)),
        BotColor(Color(0xFFE3F2FD), Color(0xFF42A5F5), Color(0xFF1976D2), Color(0xFFD3E9FC)),
        BotColor(Color(0xFFFFF3E0), Color(0xFFFFB74D), Color(0xFFF57C00), Color(0xFFF5E6D0)),
        BotColor(Color(0xFFF3E5F5), Color(0xFFAB47BC), Color(0xFF7B1FA2), Color(0xFFEFE2F1)),
        BotColor(Color(0xFFE0F7FA), Color(0xFF26C6DA), Color(0xFF00838F), Color(0xFFDCF4F7)),
        BotColor(Color(0xFFFFEBEE), Color(0xFFEC407A), Color(0xFFC62828), Color(0xFFFBE8EB)),
        BotColor(Color(0xFFE8EAF6), Color(0xFF5C6BC0), Color(0xFF283593), Color(0xFFE4E7F2)),
        BotColor(Color(0xFFFBE9E7), Color(0xFFFF7043), Color(0xFFD84315), Color(0xFFF7E6E4)),
        BotColor(Color(0xFFE0F2F1), Color(0xFF26A69A), Color(0xFF00695C), Color(0xFFDCEFEE)),
        BotColor(Color(0xFFFFF8E1), Color(0xFFFFCA28), Color(0xFFF57F17), Color(0xFFFBF5DE)),
        BotColor(Color(0xFFEDE7F6), Color(0xFF7E57C2), Color(0xFF4527A0), Color(0xFFE9E4F2)),
        BotColor(Color(0xFFE0F2F1), Color(0xFF00897B), Color(0xFF004D40), Color(0xFFDCEFEE)),
        BotColor(Color(0xFFECEFF1), Color(0xFF78909C), Color(0xFF37474F), Color(0xFFE8ECEE)),
        BotColor(Color(0xFFFCE4EC), Color(0xFFF48FB1), Color(0xFFC2185B), Color(0xFFF8E1E9)),
        BotColor(Color(0xFFF1F8E9), Color(0xFF8BC34A), Color(0xFF558B2F), Color(0xFFEDF5E6)),
        BotColor(Color(0xFFE1F5FE), Color(0xFF4FC3F7), Color(0xFF0277BD), Color(0xFFDDF2FB)),
    )

    // 深色模式 — 边框只比气泡稍亮，不突兀
    private val darkColors = listOf(
        BotColor(Color(0xFF2E7D32), Color(0xFF66BB6A), Color(0xFFFFFFFF), Color(0xFF357D38)),
        BotColor(Color(0xFF1565C0), Color(0xFF42A5F5), Color(0xFFFFFFFF), Color(0xFF1D65BC)),
        BotColor(Color(0xFFE65100), Color(0xFFFFB74D), Color(0xFFFFFFFF), Color(0xFFE65408)),
        BotColor(Color(0xFF6A1B9A), Color(0xFFAB47BC), Color(0xFFFFFFFF), Color(0xFF6E1E9A)),
        BotColor(Color(0xFF00695C), Color(0xFF26C6DA), Color(0xFFFFFFFF), Color(0xFF086A5D)),
        BotColor(Color(0xFFC62828), Color(0xFFEC407A), Color(0xFFFFFFFF), Color(0xFFC82C2C)),
        BotColor(Color(0xFF283593), Color(0xFF5C6BC0), Color(0xFFFFFFFF), Color(0xFF2C3893)),
        BotColor(Color(0xFFBF360C), Color(0xFFFF7043), Color(0xFFFFFFFF), Color(0xFFC03912)),
        BotColor(Color(0xFF00695C), Color(0xFF26A69A), Color(0xFFFFFFFF), Color(0xFF086A5D)),
        BotColor(Color(0xFFF57F17), Color(0xFFFFCA28), Color(0xFFFFFFFF), Color(0xFFF0821D)),
        BotColor(Color(0xFF4527A0), Color(0xFF7E57C2), Color(0xFFFFFFFF), Color(0xFF482BA0)),
        BotColor(Color(0xFF004D40), Color(0xFF00897B), Color(0xFFFFFFFF), Color(0xFF064E42)),
        BotColor(Color(0xFF37474F), Color(0xFF78909C), Color(0xFFFFFFFF), Color(0xFF3A4A52)),
        BotColor(Color(0xFFAD1457), Color(0xFFF48FB1), Color(0xFFFFFFFF), Color(0xFFB0185A)),
        BotColor(Color(0xFF558B2F), Color(0xFF8BC34A), Color(0xFFFFFFFF), Color(0xFF588C33)),
        BotColor(Color(0xFF0277BD), Color(0xFF4FC3F7), Color(0xFFFFFFFF), Color(0xFF0879BD)),
    )

    /** 用户自己的主题色 — 仅头像色 */
    fun selfAvatarColor(isDark: Boolean): Color =
        if (isDark) Color(0xFF42A5F5) else Color(0xFF42A5F5)

    /** 根据索引取色 */
    fun get(index: Int, isDark: Boolean = false): BotColor {
        val palette = if (isDark) darkColors else lightColors
        val idx = (if (index < 0) -index else index) % palette.size
        return palette[idx]
    }

    /** 根据名字哈希取色（同名单次会话内同色） */
    fun forName(name: String, isDark: Boolean = false): BotColor {
        val idx = name.hashCode().let { if (it < 0) -it else it } % lightColors.size
        return get(idx, isDark)
    }

    /** 调色板大小 */
    const val SIZE = 16
}

/**
 * 淡雅风格圆形头像
 *
 * 默认用 BotColorScheme 选色（名字哈希决定），
 * 也可传自定义 avatarColor 覆盖（用于群聊中与气泡统一）。
 */
@Composable
fun AvatarView(
    name: String,
    size: Dp = 40.dp,
    isSelf: Boolean = false,
    avatarColor: Color? = null,     // 覆盖自定义颜色（群聊传气泡对应色）
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = remember(name, isDark, isSelf, avatarColor) {
        when {
            avatarColor != null -> avatarColor
            isSelf -> BotColorScheme.selfAvatarColor(isDark)
            else -> BotColorScheme.forName(name, isDark).avatarColor
        }
    }
    val initial = remember(name) {
        name.firstOrNull()?.toString()?.uppercase() ?: "?"
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            color = Color.White,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.Medium
        )
    }
}
