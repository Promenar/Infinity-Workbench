package com.promenar.infinity.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput

/**
 * 分组卡片内的无框输入行：标签在上、输入在下，没有描边，和值行、开关行同处一张卡片。
 * - 聚焦时标签转为主色，底部出现 2dp 指示线；
 * - [error] 不为空时整行转为错误色，并在下方写出原因（读屏作为错误播报）；否则显示 [help]；
 * - [trailing] 放尾部动作（如显示 / 清除），每个动作自带 48dp 触控区；
 * - 最小高度 64dp，读屏把标签、输入值与错误合并朗读。
 */
@Composable
fun InfinityFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    help: String? = null,
    error: String? = null,
    monospace: Boolean = false,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    fieldModifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    InfinityFieldRowTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = fieldModifier,
        rowModifier = modifier,
        label = label,
        placeholder = placeholder,
        supportingText = error ?: help,
        isError = error != null,
        monospace = monospace,
        singleLine = singleLine,
        readOnly = readOnly,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        leading = leading,
        trailing = trailing,
    )
}

/**
 * [InfinityFieldRow] 的底层输入框，参数与 OutlinedTextField 中常用的一组对齐，
 * 便于 [SecretField] 等已有输入控件在无框形态下复用同一套交互逻辑。
 * [modifier] 作用在输入框本身（焦点、读屏标签），[rowModifier] 作用在整行。
 */
@Composable
fun InfinityFieldRowTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    rowModifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    monospace: Boolean = false,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val labelColor by animateColorAsState(
        targetValue = when {
            isError -> scheme.error
            focused -> scheme.primary
            else -> scheme.onSurfaceVariant
        },
        animationSpec = tween(InfinityMotion.DurationShort),
        label = "field_row_label",
    )
    val indicator by animateFloatAsState(
        targetValue = if (focused || isError) 1f else 0f,
        animationSpec = tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate),
        label = "field_row_indicator",
    )
    val baseStyle = textStyle ?: MaterialTheme.typography.bodyLarge.forInput()
    val inputStyle = baseStyle.copy(
        color = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.38f),
        fontFamily = if (monospace) InfinityMonospace else baseStyle.fontFamily,
    )
    Column(rowModifier.fillMaxWidth()) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = FieldRowMinHeight)
                    .padding(start = InfinitySpacing.RowHorizontal, end = FieldRowEndPadding, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                leading?.invoke()
                // 标签放进输入框的装饰区：点按标签也能聚焦，读屏把标签与输入值作为同一个输入框朗读。
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = modifier
                        .weight(1f)
                        .semantics {
                            if (isError && supportingText != null) error(supportingText)
                        },
                    enabled = enabled,
                    readOnly = readOnly,
                    textStyle = inputStyle.forInput(),
                    singleLine = singleLine,
                    maxLines = if (singleLine) 1 else 6,
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    cursorBrush = SolidColor(if (isError) scheme.error else scheme.primary),
                    decorationBox = { inner ->
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            if (label != null) {
                                Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
                            }
                            Box(Modifier.padding(vertical = 2.dp)) {
                                if (value.isEmpty() && placeholder != null) {
                                    Text(
                                        text = placeholder,
                                        style = inputStyle,
                                        color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        maxLines = 1,
                                    )
                                }
                                inner()
                            }
                        }
                    },
                )
                if (trailing != null) {
                    CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) { trailing() }
                }
            }
            // 聚焦指示线：从中间向两侧展开。
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = InfinitySpacing.RowHorizontal)
                    .fillMaxWidth()
                    .height(2.dp)
                    .graphicsLayer { scaleX = indicator; alpha = if (indicator > 0f) 1f else 0f }
                    .background(if (isError) scheme.error else scheme.primary, FieldIndicatorShape),
            )
        }
        if (!supportingText.isNullOrBlank()) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) scheme.error else scheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = InfinitySpacing.RowHorizontal, end = InfinitySpacing.RowHorizontal, top = 6.dp, bottom = 10.dp)
                    .then(if (isError) Modifier.semantics { liveRegion = LiveRegionMode.Assertive } else Modifier),
            )
        }
    }
}

/** 分组卡片内两行之间的细分隔线；[startInset] 与上一行文字起点对齐。 */
@Composable
fun InfinityGroupDivider(modifier: Modifier = Modifier, startInset: androidx.compose.ui.unit.Dp = InfinitySpacing.RowHorizontal) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startInset)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    )
}

private val FieldRowMinHeight = 64.dp
private val FieldRowEndPadding = 8.dp
private val FieldIndicatorShape = androidx.compose.foundation.shape.RoundedCornerShape(1.dp)
