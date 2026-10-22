package com.promenar.nexara.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.promenar.nexara.ui.common.bettboxListGroup
import com.promenar.nexara.ui.theme.NexaraMotion
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.theme.forReading

/**
 * 文件文本预览的分组面：加载、失败、不可预览与正文之间交叉淡入。
 * 各入口只提供自己的提示文案与测试标签，外观与动效保持一致。
 */
@Composable
internal fun FileTextPreviewPanel(
    preview: ExternalTextPreview,
    failedText: String,
    binaryText: String,
    tooLargeText: String,
    modifier: Modifier = Modifier,
    previewTag: String? = null,
) {
    Box(modifier.fillMaxWidth().bettboxListGroup()) {
        AnimatedContent(
            targetState = preview,
            contentKey = { it::class },
            transitionSpec = { NexaraMotion.fadeThrough() },
            label = "file_text_preview",
        ) { current ->
            when (current) {
                ExternalTextPreview.Loading -> Box(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
                }
                ExternalTextPreview.Failed -> FilePreviewMessage(failedText, error = true)
                ExternalTextPreview.Binary -> FilePreviewMessage(binaryText)
                ExternalTextPreview.TooLarge -> FilePreviewMessage(tooLargeText)
                is ExternalTextPreview.Text -> SelectionContainer(Modifier.fillMaxSize()) {
                    Text(
                        current.content,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(NexaraSpacing.Large)
                            .then(if (previewTag != null) Modifier.testTag(previewTag) else Modifier),
                        style = NexaraTypography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun FilePreviewMessage(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.forReading(),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(NexaraSpacing.Large)
            .semantics { if (error) liveRegion = LiveRegionMode.Polite },
    )
}
