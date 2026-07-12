package com.promenar.nexara.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalFocusManager
import com.promenar.nexara.ui.common.SecretField
import com.promenar.nexara.ui.theme.NexaraTheme

class SecretFieldTestActivity : ComponentActivity() {
    val hasStored = mutableStateOf(true)
    val showField = mutableStateOf(true)
    val edit = mutableStateOf("")
    var revealProvider: suspend () -> CharArray? = { "default-secret".toCharArray() }
    var clearAction: () -> Unit = {}

    fun resetHarness() {
        hasStored.value = true
        showField.value = true
        edit.value = ""
        revealProvider = { "default-secret".toCharArray() }
        clearAction = {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NexaraTheme {
                val focus = LocalFocusManager.current
                Column {
                    if (showField.value) {
                        SecretField(
                            value = edit.value,
                            onValueChange = { edit.value = it },
                            hasStoredSecret = hasStored.value,
                            onRevealRequest = { revealProvider() },
                            onClear = { clearAction() },
                        )
                    }
                    Button(onClick = { focus.clearFocus() }) { Text("outside") }
                }
            }
        }
    }
}
