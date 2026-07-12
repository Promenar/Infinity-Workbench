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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 全站统一的短生命周期密钥输入框。
 *
 * 持久 UiState 只保存密钥存在性；临时 reveal 使用可清零 [CharArray]，不进入 StateFlow 或
 * rememberSaveable。Compose 文本输入和渲染必须使用 String，因此显示期间会产生平台无法原地
 * 擦除的短生命周期 String；隐藏、失焦、离页与重建会立即删除其 Compose 状态引用并清零数组。
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
    var transientSecret by remember { mutableStateOf<CharArray?>(null) }
    var visible by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var focusGeneration by remember { mutableStateOf(0L) }
    var revealRequested by remember { mutableStateOf(false) }
    var revealGeneration by remember { mutableStateOf(0L) }
    var revealJob by remember { mutableStateOf<Job?>(null) }
    var pageActive by remember { mutableStateOf(true) }

    fun hide() {
        revealGeneration += 1
        revealRequested = false
        revealJob?.cancel()
        revealJob = null
        transientSecret?.fill('\u0000')
        transientSecret = null
        visible = false
    }

    DisposableEffect(hasStoredSecret) {
        pageActive = true
        hide()
        onDispose {
            pageActive = false
            hide()
        }
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
                        if (focused && !it.isFocused) focusGeneration += 1
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
                    if (visible) {
                        hide()
                    } else if (value.isNotEmpty()) {
                        visible = true
                    } else {
                        revealJob?.cancel()
                        revealGeneration += 1
                        val token = revealGeneration
                        val focusToken = focusGeneration
                        revealRequested = true
                        revealJob = scope.launch {
                            var loaded: CharArray? = null
                            var accepted = false
                            try {
                                loaded = withContext(NonCancellable) { onRevealRequest() }
                                if (token == revealGeneration && focusToken == focusGeneration &&
                                    pageActive && revealRequested && loaded != null
                                ) {
                                    transientSecret?.fill('\u0000')
                                    transientSecret = loaded
                                    loaded = null
                                    visible = true
                                    accepted = true
                                }
                            } finally {
                                if (!accepted) loaded?.fill('\u0000')
                            }
                        }
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
