package com.promenar.nexara.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R

/** [close] 为 true 时显示关闭图标，用于侧滑面板的根层：点击收起面板而不是回到上一页。 */
@Composable
fun NexaraBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    close: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp)
    ) {
        Icon(
            imageVector = if (close) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(if (close) R.string.shared_btn_close else R.string.common_cd_back),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
    }
}
