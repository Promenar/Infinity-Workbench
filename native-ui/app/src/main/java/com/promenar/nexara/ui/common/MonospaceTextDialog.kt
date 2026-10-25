package com.promenar.nexara.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraMonospace
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography

/** 只读等宽文本（日志、诊断记录），可选择复制；长文本在框内纵向滚动。 */
@Composable
fun MonospaceTextDialog(title: String, message: String, text: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        NexaraConfirmDialog(
            title = title,
            message = message,
            confirmText = stringResource(R.string.common_dismiss),
            cancelText = null,
            onConfirm = onDismiss,
            onCancel = onDismiss,
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = NexaraTypography.labelSmall.copy(fontFamily = NexaraMonospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(NexaraSpacing.Small),
                )
            }
        }
    }
}
