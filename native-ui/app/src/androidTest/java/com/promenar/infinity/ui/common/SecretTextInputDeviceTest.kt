package com.promenar.infinity.ui.common

import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

/** 真实 Compose 文本会话：密钥输入框向输入法提交的 EditorInfo 不得带任何密码变体，否则 ColorOS 强制安全键盘。 */
class SecretTextInputDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun secretFieldRequestsPlainTextWithoutLearning() {
        lateinit var view: View
        rule.setContent {
            view = LocalView.current
            InfinityTheme {
                SecretField(
                    value = "",
                    onValueChange = {},
                    hasStoredSecret = false,
                    onRevealRequest = { null },
                    onClear = {},
                    modifier = Modifier.testTag("secret"),
                )
            }
        }
        rule.onNodeWithTag("secret").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()

        val info = EditorInfo()
        rule.runOnUiThread { checkNotNull(view.onCreateInputConnection(info)) }
        assertThat(info.inputType and InputType.TYPE_MASK_VARIATION).isEqualTo(InputType.TYPE_TEXT_VARIATION_NORMAL)
        assertThat(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS).isNotEqualTo(0)
        assertThat(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).isNotEqualTo(0)
    }
}
