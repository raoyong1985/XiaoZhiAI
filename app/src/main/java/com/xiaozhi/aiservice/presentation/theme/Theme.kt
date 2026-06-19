package com.xiaozhi.aiservice.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ═══════════════════════════════════════════
// 统一圆角风格
// ═══════════════════════════════════════════
object AppShapes {
    /** 弹窗菜单、输入框 — 12dp */
    val menu = RoundedCornerShape(12.dp)
    /** 消息气泡内嵌图片 — 12dp */
    val image = RoundedCornerShape(12.dp)
    /** 消息气泡 — 12dp */
    val bubble = RoundedCornerShape(12.dp)
    /** 空状态图标底板 — 28dp */
    val iconBg = RoundedCornerShape(28.dp)
    /** 确认/编辑弹窗 — 16dp（介于 menu 和 iconBg 之间） */
    val dialog = RoundedCornerShape(16.dp)
}

/** 全局 Material3 Shapes，通过 MaterialTheme(shapes = ...) 传入 */
val XiaoZhiShapes = Shapes(
    small = AppShapes.menu,      // chips, 按钮
    medium = AppShapes.dialog,   // 弹窗、卡片
    large = AppShapes.iconBg     // 底部面板 (原 28dp)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF5B67CA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE0FF),
    onPrimaryContainer = Color(0xFF161B59),
    secondary = Color(0xFF5C5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E0F9),
    onSecondaryContainer = Color(0xFF191A2C),
    tertiary = Color(0xFF78536B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8ED),
    onTertiaryContainer = Color(0xFF2E1126),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8F8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC8C5D0)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFBBC3FF),
    onPrimary = Color(0xFF262B6F),
    primaryContainer = Color(0xFF404494),
    onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC5C4DD),
    onSecondary = Color(0xFF2E2F42),
    secondaryContainer = Color(0xFF444559),
    onSecondaryContainer = Color(0xFFE1E0F9),
    tertiary = Color(0xFFE5BAD4),
    onTertiary = Color(0xFF45253B),
    tertiaryContainer = Color(0xFF5E3B52),
    onTertiaryContainer = Color(0xFFFFD8ED),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE5E1E9),
    surface = Color(0xFF1B1B21),
    onSurface = Color(0xFFE5E1E9),
    surfaceVariant = Color(0xFF2F3038),
    onSurfaceVariant = Color(0xFFC8C5D0),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF47464F)
)

@Composable
fun XiaoZhiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val themeMode by ThemeManager.mode.collectAsState()
    val actualDarkTheme = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> darkTheme  // "system" → 跟随系统
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (actualDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        actualDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !actualDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = XiaoZhiShapes,
        content = content
    )
}
