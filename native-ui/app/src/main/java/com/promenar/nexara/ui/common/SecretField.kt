package com.promenar.nexara.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.SpaceGrotesk
import kotlinx.coroutines.launch

/**
 * 全站统一的短生命周期密钥输入框。
 *
 * 完整密钥只存在于当前组合的可清零 [CharArray] 中；该状态不使用 rememberSaveable，
 * 因而页面离开、配置变化或 Activity 重建后不会恢复明文。调用方的常驻 UiState 只应保存
 * `hasStoredSecret`，不得保存本组件临时显示的值。
 */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    hasStoredSecret: Boolean,
    onRevealRequest: suspend () -> CharArray?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.secret_field_placeholder),
) {
    val scope = rememberCoroutineScope()
    var transientSecret by remember(hasStoredSecret) { mutableStateOf<CharArray?>(null) }
    var visible by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }

    fun hide() {
        transientSecret?.fill('\u0000')
        transientSecret = null
        visible = false
    }

    DisposableEffect(Unit) {
        onDispose { transientSecret?.fill('\u0000') }
    }

    val displayValue = when {
        value.isNotEmpty() -> value
        visible && transientSecret != null -> transientSecret!!.concatToString()
        hasStoredSecret -> "****"
        else -> ""
    }
    val editable = value.isNotEmpty() || !hasStoredSecret || focused

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceContainer)
            .border(0.5.dp, NexaraColors.GlassBorder, NexaraShapes.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            BasicTextField(
                value = displayValue,
                onValueChange = { next ->
                    if (!editable && next == "****") return@BasicTextField
                    hide()
                    onValueChange(
                        if (value.isEmpty() && hasStoredSecret && next.startsWith("****")) {
                            next.removePrefix("****")
                        } else next,
                    )
                },
                singleLine = true,
                visualTransformation = if (visible || (hasStoredSecret && value.isEmpty())) {
                    VisualTransformation.None
                } else PasswordVisualTransformation(),
                textStyle = NexaraTypography.bodyMedium.copy(
                    color = NexaraColors.OnSurface,
                    fontFamily = SpaceGrotesk,
                ),
                cursorBrush = SolidColor(NexaraColors.Primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder }
                    .onFocusChanged {
                        focused = it.isFocused
                        if (!it.isFocused) hide()
                    },
            )
            if (displayValue.isEmpty()) {
                androidx.compose.material3.Text(
                    text = placeholder,
                    style = NexaraTypography.bodyMedium.copy(fontFamily = SpaceGrotesk),
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
        if (value.isNotEmpty() || hasStoredSecret) {
            IconButton(
                onClick = {
                    if (visible) hide() else if (value.isNotEmpty()) visible = true else scope.launch {
                        transientSecret?.fill('\u0000')
                        transientSecret = onRevealRequest()
                        visible = transientSecret != null
                    }
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(if (visible) R.string.secret_field_hide else R.string.secret_field_show),
                    tint = NexaraColors.OnSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    hide()
                    onValueChange("")
                    onClear()
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Clear,
                    contentDescription = stringResource(R.string.secret_field_clear),
                    tint = NexaraColors.OnSurfaceVariant,
                )
            }
        }
    }
}
