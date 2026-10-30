package com.dsmobile.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * 设计系统「纸墨」（Paper & Ink）。
 *
 * 这个 App 是深夜里一个人跟她说话的地方：一来一回的都是长句子，
 * 所以整套视觉只做一件事——把字托起来。
 *
 * 三条硬规则：
 * 1. 画布是暖纸色，正文是近黑墨色，对比度一律 4.5:1 以上；
 * 2. 除了「她」的紫，界面上不出现第二种彩色；状态色只在出错/成功时出现；
 * 3. 不靠投影分层，靠 1px 的头发丝分隔线和留白分层。
 */

// ── 色板 ────────────────────────────────────────────────────────────────

data class DsColors(
    /** 页面底色 */
    val canvas: Color,
    /** 卡片/气泡/输入区底色 */
    val surface: Color,
    /** 比 surface 略沉一档，用于输入框、未选中的胶囊 */
    val surfaceMuted: Color,
    /** 再沉一档，用于按下态、轨道 */
    val surfaceSunken: Color,
    /** 正文 */
    val ink: Color,
    /** 次要文字：时间、说明 */
    val inkSecondary: Color,
    /** 三级文字：占位符、极弱提示 */
    val inkTertiary: Color,
    /** 1px 分隔线 */
    val hairline: Color,
    /** 唯一的彩色 */
    val accent: Color,
    /** 彩色底上的字 */
    val accentInk: Color,
    /** 彩色底 8% 左右，用于选中态背景 */
    val accentSoft: Color,
    /** 她的气泡 */
    val aiBubble: Color,
    val aiBubbleText: Color,
    /** 我的气泡 */
    val userBubble: Color,
    val userBubbleText: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    val isDark: Boolean,
) {
    /** 气泡阴影：浅色下几乎看不见，只是让气泡离纸面半毫米 */
    val bubbleShadow: Color
        get() = if (isDark) Color(0x66000000) else Color(0x0F2B2450)
}

private val LightColors = DsColors(
    canvas = Color(0xFFF7F6F3),
    surface = Color(0xFFFFFFFF),
    surfaceMuted = Color(0xFFF1F0EB),
    surfaceSunken = Color(0xFFE9E7E1),
    ink = Color(0xFF17171A),
    inkSecondary = Color(0xFF5E5B54),
    inkTertiary = Color(0xFF706C63),
    hairline = Color(0xFFE6E3DC),
    accent = Color(0xFF4B3FD1),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFEDEAFB),
    aiBubble = Color(0xFFFFFFFF),
    aiBubbleText = Color(0xFF1B1B1F),
    userBubble = Color(0xFF4B3FD1),
    userBubbleText = Color(0xFFFFFFFF),
    danger = Color(0xFFB3261E),
    success = Color(0xFF1B7A4B),
    warning = Color(0xFF8A5A00),
    isDark = false,
)

private val DarkColors = DsColors(
    canvas = Color(0xFF0E0E10),
    surface = Color(0xFF17171A),
    surfaceMuted = Color(0xFF1F1F23),
    surfaceSunken = Color(0xFF28282D),
    ink = Color(0xFFF4F2ED),
    inkSecondary = Color(0xFFB2AFA7),
    inkTertiary = Color(0xFF7E7B74),
    hairline = Color(0xFF2A2A2F),
    accent = Color(0xFFA79BFF),
    accentInk = Color(0xFF14121F),
    accentSoft = Color(0xFF241F3D),
    aiBubble = Color(0xFF1A1A1E),
    aiBubbleText = Color(0xFFF0EEE9),
    userBubble = Color(0xFF5B4BE8),
    userBubbleText = Color(0xFFFFFFFF),
    danger = Color(0xFFFF6B5E),
    success = Color(0xFF57D68A),
    warning = Color(0xFFF0C05A),
    isDark = true,
)

val LocalDsColors = staticCompositionLocalOf { LightColors }

/** 取色入口：[Ds.colors]。 */
object Ds {
    val colors: DsColors
        @Composable get() = LocalDsColors.current
}

// ── 间距 ────────────────────────────────────────────────────────────────

object DsSpace {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val lg = 20.dp
    val xl = 24.dp
    val xxl = 32.dp
}

// ── 字体 ────────────────────────────────────────────────────────────────

private val Sans = FontFamily.SansSerif

private fun st(
    size: Int,
    line: Int,
    weight: FontWeight = FontWeight.Normal,
    spacing: Double = 0.0,
) = TextStyle(
    fontFamily = Sans,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = spacing.sp,
)

/**
 * 中文排版：行高比英文更松（1.6 倍左右），字距保持 0——
 * 中文加字距只会显得散，不会显得精致。
 */
private val DsTypography = Typography(
    displaySmall = st(30, 40, FontWeight.SemiBold, -0.4),
    headlineMedium = st(24, 34, FontWeight.SemiBold, -0.2),
    headlineSmall = st(20, 30, FontWeight.SemiBold),
    titleLarge = st(18, 27, FontWeight.SemiBold),
    titleMedium = st(16, 24, FontWeight.SemiBold),
    titleSmall = st(15, 22, FontWeight.Medium),
    bodyLarge = st(16, 26),
    bodyMedium = st(15, 24),
    bodySmall = st(13, 21),
    labelLarge = st(14, 20, FontWeight.Medium),
    labelMedium = st(12, 18, FontWeight.Medium, 0.2),
    labelSmall = st(11, 16, FontWeight.Medium, 0.2),
)

private val DsShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

// ── Material3 桥接 ─────────────────────────────────────────────────────

private fun dsScheme(c: DsColors) = (if (c.isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = c.accent,
    onPrimary = c.accentInk,
    primaryContainer = c.accentSoft,
    onPrimaryContainer = if (c.isDark) c.accent else c.accent,
    secondary = c.accent,
    onSecondary = c.accentInk,
    background = c.canvas,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    surfaceVariant = c.surfaceMuted,
    onSurfaceVariant = c.inkSecondary,
    surfaceContainer = c.surface,
    surfaceContainerHigh = c.surfaceMuted,
    surfaceContainerLow = c.surface,
    outline = c.hairline,
    outlineVariant = c.hairline,
    error = c.danger,
    onError = Color.White,
)

/** 把「跟随系统 / 浅色 / 深色」翻译成真正的 darkTheme 开关。 */
@Composable
fun rememberDarkTheme(mode: String): Boolean = when (mode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun DsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }
    CompositionLocalProvider(LocalDsColors provides colors) {
        MaterialTheme(
            colorScheme = dsScheme(colors),
            typography = DsTypography,
            shapes = DsShapes,
            content = content,
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
