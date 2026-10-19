package com.promenar.nexara.ui.common

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.forInput
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val SECRET_REVEAL_TIMEOUT_MILLIS = 15_000L
private const val STORED_SECRET_MASK = "****"

/**
 * 密钥与口令输入的键盘选项。不使用 [KeyboardType.Password]：多数输入法与 ColorOS 会对
 * 密码类型强制切换安全键盘并禁用剪贴板，API 密钥这类长随机串无法粘贴，实际不可用。
 * 屏幕上仍由 [PasswordVisualTransformation] 掩码，输入法侧配合 [SecretTextInput] 关闭联想与学习。
 */
val SecretKeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false)

/**
 * 密钥输入向输入法声明的类型：普通文本并关闭候选词。不得带任何密码变体——ColorOS 对
 * 可见密码变体同样强制切换系统安全键盘（实测 com.oplus.securitykeyboard）。
 */
internal const val SECRET_INPUT_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

internal fun secretImeOptions(imeOptions: Int): Int = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING

/** 包裹密钥输入框：改写输入法请求，保证普通键盘可粘贴，同时要求输入法不记录输入内容。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SecretTextInput(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, nextHandler ->
            val secretRequest = PlatformTextInputMethodRequest { outAttributes ->
                request.createInputConnection(outAttributes).also {
                    outAttributes.inputType = SECRET_INPUT_TYPE
                    outAttributes.imeOptions = secretImeOptions(outAttributes.imeOptions)
                }
            }
            nextHandler.startInputMethod(secretRequest)
        },
        content = content,
    )
}

/**
 * 全站统一的短生命周期密钥输入框。
 *
 * 持久 UiState 只保存密钥存在性；临时 reveal 使用可清零 [CharArray]，不进入 StateFlow 或
 * rememberSaveable。Compose 文本输入和渲染必须使用 String，因此显示期间会产生平台无法原地
 * 擦除的短生命周期 String；隐藏、失焦、离页、重建或超时会立即删除其 Compose 状态引用并清零数组。
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
    label: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    revealTimeoutMillis: Long = SECRET_REVEAL_TIMEOUT_MILLIS,
) {
    val scope = rememberCoroutineScope()
    val fieldFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var transientSecret by remember { mutableStateOf<CharArray?>(null) }
    var visible by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var focusGeneration by remember { mutableStateOf(0L) }
    var revealRequested by remember { mutableStateOf(false) }
    var revealGeneration by remember { mutableStateOf(0L) }
    var revealJob by remember { mutableStateOf<Job?>(null) }
    var revealTimeoutJob by remember { mutableStateOf<Job?>(null) }
    var pageActive by remember { mutableStateOf(true) }

    fun hide() {
        revealGeneration += 1
        revealRequested = false
        revealJob?.cancel()
        revealJob = null
        revealTimeoutJob?.cancel()
        revealTimeoutJob = null
        transientSecret?.fill('\u0000')
        transientSecret = null
        visible = false
    }

    fun scheduleRevealTimeout() {
        revealTimeoutJob?.cancel()
        revealTimeoutJob = scope.launch {
            delay(revealTimeoutMillis)
            if (pageActive && visible) hide()
        }
    }

    DisposableEffect(hasStoredSecret, lifecycleOwner) {
        pageActive = true
        hide()
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) hide()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            pageActive = false
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            hide()
        }
    }

    val displayValue = when {
        value.isNotEmpty() -> value
        visible && transientSecret != null -> transientSecret!!.concatToString()
        hasStoredSecret -> STORED_SECRET_MASK
        else -> ""
    }
    SecretTextInput {
        OutlinedTextField(
            value = displayValue,
            onValueChange = { next ->
                hide()
                onValueChange(next)
            },
            modifier = modifier
                .fillMaxWidth()
                .focusRequester(fieldFocusRequester)
                .semantics { contentDescription = label ?: placeholder }
                .onFocusChanged {
                    if (focused && !it.isFocused) focusGeneration += 1
                    focused = it.isFocused
                    if (!it.isFocused) hide()
                },
            singleLine = true,
            readOnly = hasStoredSecret && value.isEmpty(),
            placeholder = { androidx.compose.material3.Text(placeholder) },
            label = label?.let { value -> { androidx.compose.material3.Text(value) } },
            supportingText = supportingText?.let { value -> { androidx.compose.material3.Text(value) } },
            isError = isError,
            visualTransformation = if (visible || (hasStoredSecret && value.isEmpty())) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            textStyle = MaterialTheme.typography.bodyLarge.forInput(),
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = SecretKeyboardOptions,
            trailingIcon = {
                if (value.isNotEmpty() || hasStoredSecret) {
                    Row {
                        IconButton(
                            onClick = {
                                if (visible) {
                                    hide()
                                } else {
                                    fieldFocusRequester.requestFocus()
                                    keyboardController?.hide()
                                    if (value.isNotEmpty()) {
                                        visible = true
                                        scheduleRevealTimeout()
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
                                                    scheduleRevealTimeout()
                                                }
                                            } finally {
                                                if (!accepted) loaded?.fill('\u0000')
                                            }
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = stringResource(
                                    if (visible) R.string.secret_field_hide else R.string.secret_field_show,
                                ),
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
                            )
                        }
                    }
                }
            },
        )
    }
}
