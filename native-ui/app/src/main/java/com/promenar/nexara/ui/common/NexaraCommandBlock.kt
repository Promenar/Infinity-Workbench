package com.promenar.nexara.ui.common

import com.promenar.nexara.ui.theme.NexaraMonospace
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraSpacing
import kotlinx.coroutines.launch

/** 命令以原文复制；横向滚动仅作用于自有文本容器，不进入第三方渲染回调。 */
@Composable
fun NexaraCommandBlock(
    command: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCopy: ((String) -> Unit)? = null,
) {
    val haptics = LocalNexaraHaptics.current
    val clipboard = LocalClipboardManager.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(R.string.settings_command_copied)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = NexaraSpacing.Large),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_command_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        enabled = enabled,
                        onClick = {
                            haptics.perform(NexaraHapticEvent.CONFIRM)
                            if (onCopy != null) onCopy(command)
                            else {
                                clipboard.setText(AnnotatedString(command))
                                scope.launch {
                                    snackbarHost.currentSnackbarData?.dismiss()
                                    snackbarHost.showSnackbar(copiedMessage)
                                }
                            }
                        },
                        modifier = Modifier.size(NexaraSpacing.MinimumTouchTarget),
                    ) {
                        Icon(Icons.Rounded.ContentCopy, stringResource(R.string.settings_copy_command))
                    }
                }
                SelectionContainer {
                    Text(
                        command,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = NexaraMonospace),
                        softWrap = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(
                                start = NexaraSpacing.Large,
                                end = NexaraSpacing.Large,
                                bottom = NexaraSpacing.Large,
                            ),
                    )
                }
            }
            SnackbarHost(
                hostState = snackbarHost,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { data -> NexaraSnackbar(NexaraSnackbarData(data.visuals.message, SnackbarType.SUCCESS)) }
        }
    }
}
