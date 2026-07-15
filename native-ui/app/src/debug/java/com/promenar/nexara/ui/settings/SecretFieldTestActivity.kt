package com.promenar.nexara.ui.settings

import android.os.Bundle
import android.view.WindowInsets as AndroidWindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.Modifier
import com.promenar.nexara.ui.common.SecretField
import com.promenar.nexara.ui.theme.NexaraTheme

class SecretFieldTestActivity : ComponentActivity() {
    val hasStored = mutableStateOf(true)
    val showField = mutableStateOf(true)
    val edit = mutableStateOf("")
    val label = mutableStateOf<String?>(null)
    val revealTimeoutMillis = mutableStateOf(15_000L)
    var revealProvider: suspend () -> CharArray? = { "default-secret".toCharArray() }
    var clearAction: () -> Unit = {}

    fun resetHarness() {
        hasStored.value = true
        showField.value = true
        edit.value = ""
        label.value = null
        revealTimeoutMillis.value = 15_000L
        revealProvider = { "default-secret".toCharArray() }
        clearAction = {}
    }

    fun isImeVisible(): Boolean =
        window.decorView.rootWindowInsets?.isVisible(AndroidWindowInsets.Type.ime()) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexaraTheme {
                val focus = LocalFocusManager.current
                Column(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    if (showField.value) {
                        SecretField(
                            value = edit.value,
                            onValueChange = { edit.value = it },
                            hasStoredSecret = hasStored.value,
                            onRevealRequest = { revealProvider() },
                            onClear = { clearAction() },
                            label = label.value,
                            revealTimeoutMillis = revealTimeoutMillis.value,
                        )
                    }
                    Button(onClick = { focus.clearFocus() }) { Text("outside") }
                }
            }
        }
    }
}
