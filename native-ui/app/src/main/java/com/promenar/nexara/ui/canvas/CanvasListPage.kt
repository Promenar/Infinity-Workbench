package com.promenar.nexara.ui.canvas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraBackButton
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing

/**
 * 窄窗口的产物整页：与文件、浏览器同为会话的二级页面，返回回到会话。
 * 点开某个产物仍进入全屏查看层，返回回到列表。
 */
@Composable
internal fun CanvasListPage(onBack: (() -> Unit)?, modifier: Modifier = Modifier) {
    val session = LocalCanvasSession.current
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(UiTags.CANVAS_PAGE),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .padding(horizontal = NexaraSpacing.ScreenHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                NexaraBackButton(onClick = onBack)
                Spacer(Modifier.width(NexaraSpacing.XSmall))
            }
            Text(
                text = stringResource(R.string.canvas_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
        }
        when {
            session == null -> Unit
            session.listFailed -> Text(
                text = stringResource(R.string.canvas_load_error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(NexaraSpacing.Large),
            )
            else -> CanvasListContent(
                canvases = session.canvases,
                onOpen = session.open,
                showHeading = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
