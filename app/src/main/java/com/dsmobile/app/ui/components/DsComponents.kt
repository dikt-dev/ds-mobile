package com.dsmobile.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dsmobile.app.ui.theme.Ds
import com.dsmobile.app.ui.theme.DsSpace

// ── 触控 ───────────────────────────────────────────────────────────────

/** 44dp 触控目标 + 22dp 图标。所有顶栏/输入区的图标按钮都走这个。 */
@Composable
fun DsIconButton(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Ds.colors.inkSecondary,
    background: Color = Color.Transparent,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 小圆点：在线状态、未读提示。 */
@Composable
fun DsDot(color: Color, size: Dp = 7.dp, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(size).clip(CircleShape).background(color))
}

// ── 容器 ───────────────────────────────────────────────────────────────

/** 白卡片：圆角 + 1px 头发丝描边，不用投影。 */
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    color: Color = Ds.colors.surface,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(DsSpace.md),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color)
            .border(1.dp, Ds.colors.hairline, shape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun DsSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(start = DsSpace.xxs, bottom = DsSpace.xs),
        style = MaterialTheme.typography.labelMedium,
        color = Ds.colors.inkTertiary,
    )
}

@Composable
fun DsDivider(modifier: Modifier = Modifier, insetStart: Dp = 0.dp) {
    HorizontalDivider(
        modifier = modifier.padding(start = insetStart),
        thickness = 1.dp,
        color = Ds.colors.hairline,
    )
}

// ── 顶栏 ───────────────────────────────────────────────────────────────

/**
 * 统一顶栏：左「返回/关闭」，中间标题（可带副标题），右操作区。
 * 底边一条头发丝线代替阴影，滚动时不会糊成一片。
 */
@Composable
fun DsTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backIcon: ImageVector? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth().background(Ds.colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = if (onBack != null) 6.dp else DsSpace.md, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null && backIcon != null) {
                DsIconButton(backIcon, "返回", onClick = onBack)
            }
            Column(modifier = Modifier.weight(1f).padding(start = if (onBack != null) 2.dp else 0.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Ds.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Ds.colors.inkTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        DsDivider()
    }
}

// ── 按钮 ───────────────────────────────────────────────────────────────

@Composable
fun DsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    fillWidth: Boolean = true,
) {
    val active = enabled && !loading
    Row(
        modifier = modifier
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) Ds.colors.accent else Ds.colors.surfaceSunken)
            .clickable(enabled = active, onClick = onClick)
            .padding(horizontal = DsSpace.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = Ds.colors.accentInk,
            )
            Spacer(Modifier.width(DsSpace.xs))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (active) Ds.colors.accentInk else Ds.colors.inkTertiary,
        )
    }
}

@Composable
fun DsOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val fg = when {
        !enabled -> Ds.colors.inkTertiary
        danger -> Ds.colors.danger
        else -> Ds.colors.ink
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, if (danger) Ds.colors.danger.copy(alpha = 0.35f) else Ds.colors.hairline, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = DsSpace.lg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
fun DsTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = Ds.colors.accent,
) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = DsSpace.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = if (enabled) color else Ds.colors.inkTertiary,
        )
    }
}

// ── 设置行 ─────────────────────────────────────────────────────────────

/** 一行设置：标题 + 可选说明 + 右侧值/箭头。整行可点。 */
@Composable
fun DsSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = DsSpace.md, vertical = DsSpace.sm)
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(DsSpace.sm))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Ds.colors.ink)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Ds.colors.inkTertiary,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(DsSpace.sm))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = Ds.colors.inkSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(DsSpace.xs))
            trailing()
        }
        if (showChevron && trailing == null) {
            Spacer(Modifier.width(DsSpace.xxs))
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = Ds.colors.inkTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun DsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = DsSpace.md, vertical = DsSpace.sm)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Ds.colors.ink)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Ds.colors.inkTertiary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ds.colors.surface,
                checkedTrackColor = Ds.colors.accent,
                uncheckedThumbColor = Ds.colors.surface,
                uncheckedTrackColor = Ds.colors.surfaceSunken,
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

// ── 输入 ───────────────────────────────────────────────────────────────

/**
 * 表单输入：标签在上，输入框是浅底圆角、无边框。
 * 比 OutlinedTextField 的浮动标签更安静，一屏能放更多字段还不显乱。
 */
@Composable
fun DsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Ds.colors.inkSecondary,
            modifier = Modifier.padding(start = DsSpace.xxs, bottom = 6.dp),
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ds.colors.ink),
            placeholder = {
                if (placeholder.isNotEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ds.colors.inkTertiary,
                    )
                }
            },
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(14.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Ds.colors.surfaceMuted,
                unfocusedContainerColor = Ds.colors.surfaceMuted,
                disabledContainerColor = Ds.colors.surfaceMuted,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = Ds.colors.accent,
            ),
        )
    }
}

/** 分段选择：三选一（外观模式这类），选中项浮起一档。 */
@Composable
fun DsSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Ds.colors.surfaceMuted)
            .padding(3.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) Ds.colors.surface else Color.Transparent)
                    .then(
                        if (active) Modifier.border(1.dp, Ds.colors.hairline, RoundedCornerShape(10.dp))
                        else Modifier
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) Ds.colors.ink else Ds.colors.inkSecondary,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** 小胶囊：渠道标、标签。 */
@Composable
fun DsPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ds.colors.inkTertiary,
    background: Color = Ds.colors.surfaceMuted,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** 底部抽屉的顶部小横条。 */
@Composable
fun DsSheetHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(top = DsSpace.sm, bottom = DsSpace.xs)
            .size(width = 36.dp, height = 4.dp)
            .clip(CircleShape)
            .background(Ds.colors.surfaceSunken),
    )
}
