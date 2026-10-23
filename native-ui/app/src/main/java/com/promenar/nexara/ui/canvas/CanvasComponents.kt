package com.promenar.nexara.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.canvas.CanvasDocument
import com.promenar.nexara.data.canvas.CanvasMetadata
import com.promenar.nexara.ui.common.NexaraDropdownMenu
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography

/** 卡片保留工具调用时的版本标记，预览与打开入口由宿主提供最新内容。 */
@Composable
fun CanvasCard(
    metadata: CanvasMetadata,
    version: Int,
    html: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    previewContent: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(UiTags.CANVAS_CARD),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(NexaraSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
        ) {
            Text(
                text = metadata.title,
                style = NexaraTypography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.canvas_version, version),
                style = NexaraTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (metadata.currentVersion > version) {
                Text(
                    text = stringResource(R.string.canvas_newer_version),
                    style = NexaraTypography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(MaterialTheme.colorScheme.surface)
                .testTag(UiTags.CANVAS_PREVIEW),
            contentAlignment = Alignment.Center,
        ) {
            when {
                previewContent != null -> previewContent()
                LocalInspectionMode.current -> CanvasPreviewPlaceholder()
                html == null -> CanvasLoading()
                else -> CanvasWebView(
                    html = html,
                    modifier = Modifier.fillMaxSize(),
                    interactive = false,
                    readOnly = true,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = NexaraSpacing.Small),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onOpen,
                modifier = Modifier
                    .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                    .testTag(UiTags.CANVAS_OPEN),
            ) {
                Icon(
                    imageVector = Icons.Rounded.OpenInFull,
                    contentDescription = null,
                    modifier = Modifier.size(NexaraSpacing.RowIconSize),
                )
                Text(
                    text = stringResource(R.string.canvas_open),
                    modifier = Modifier.padding(start = NexaraSpacing.Small),
                    style = NexaraTypography.labelLarge,
                )
            }
        }
    }
}

/** 列表自身管理纵向滚动，宿主只需提供可用高度。 */
@Composable
fun CanvasListContent(
    canvases: List<CanvasMetadata>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag(UiTags.CANVAS_LIST),
        contentPadding = PaddingValues(vertical = NexaraSpacing.Small),
    ) {
        item(key = "heading") {
            Text(
                text = stringResource(R.string.canvas_title),
                modifier = Modifier
                    .padding(horizontal = NexaraSpacing.Large, vertical = NexaraSpacing.Small)
                    .semantics { heading() },
                style = NexaraTypography.titleLarge,
            )
        }
        if (canvases.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(NexaraSpacing.XLarge),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Dashboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = stringResource(R.string.canvas_empty_title),
                        style = NexaraTypography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.canvas_empty_description),
                        style = NexaraTypography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(canvases, key = { it.id }) { canvas ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = canvas.title,
                            style = NexaraTypography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = stringResource(R.string.canvas_current_version, canvas.currentVersion),
                            style = NexaraTypography.bodySmall,
                        )
                    },
                    leadingContent = {
                        Icon(imageVector = Icons.Rounded.Dashboard, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .clickable { onOpen(canvas.id) }
                        .testTag(UiTags.CANVAS_LIST_ITEM),
                )
            }
        }
    }
}

/** 全屏窗口由宿主管理；固定历史版本只提供查看和复制能力。 */
@Composable
fun CanvasFullscreenContent(
    document: CanvasDocument?,
    metadata: CanvasMetadata?,
    selectedVersion: Int?,
    onSelectVersion: (Int?) -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
    onInsertText: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    val currentMetadata = metadata ?: document?.metadata
    val isHistory = selectedVersion != null
    Surface(
        modifier = modifier.fillMaxSize().testTag(UiTags.CANVAS_FULLSCREEN),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = NexaraSpacing.Small, vertical = NexaraSpacing.XSmall),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentMetadata?.title ?: stringResource(R.string.canvas_title),
                        modifier = Modifier.weight(1f).padding(horizontal = NexaraSpacing.Small),
                        style = NexaraTypography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = onCopy,
                        enabled = document != null && error == null,
                        modifier = Modifier
                            .size(NexaraSpacing.MinimumTouchTarget)
                            .testTag(UiTags.CANVAS_COPY),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.canvas_copy_source),
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(NexaraSpacing.MinimumTouchTarget)
                            .testTag(UiTags.CANVAS_CLOSE),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.canvas_close),
                        )
                    }
                }
                CanvasVersionSelector(
                    metadata = currentMetadata,
                    selectedVersion = selectedVersion,
                    onSelectVersion = onSelectVersion,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = NexaraSpacing.Small),
                )
                if (isHistory) {
                    Text(
                        text = stringResource(R.string.canvas_history_notice),
                        modifier = Modifier.padding(NexaraSpacing.Small),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = NexaraTypography.bodySmall,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    error != null -> Column(
                        modifier = Modifier.padding(NexaraSpacing.XLarge),
                        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.canvas_load_error),
                            color = MaterialTheme.colorScheme.error,
                            style = NexaraTypography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = NexaraTypography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    document == null -> CanvasLoading()
                    else -> CanvasWebView(
                        html = document.html,
                        modifier = Modifier.fillMaxSize(),
                        interactive = true,
                        readOnly = isHistory,
                        onInsertText = if (isHistory) ({ _: String -> }) else onInsertText,
                    )
                }
            }
        }
    }
}

@Composable
private fun CanvasVersionSelector(
    metadata: CanvasMetadata?,
    selectedVersion: Int?,
    onSelectVersion: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(metadata?.id) { mutableStateOf(false) }
    val label = when {
        selectedVersion != null -> stringResource(R.string.canvas_history_version, selectedVersion)
        metadata != null -> stringResource(R.string.canvas_follow_latest, metadata.currentVersion)
        else -> stringResource(R.string.canvas_loading)
    }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = metadata != null,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .testTag(UiTags.CANVAS_VERSION_SELECTOR),
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = NexaraTypography.labelLarge,
                textAlign = TextAlign.Start,
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.padding(start = NexaraSpacing.Small),
            )
        }
        NexaraDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (metadata != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(R.string.canvas_follow_latest, metadata.currentVersion),
                            style = NexaraTypography.labelLarge,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelectVersion(null)
                    },
                    modifier = Modifier
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .semantics { selected = selectedVersion == null },
                )
                metadata.versions.distinct().sortedDescending().forEach { version ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.canvas_history_version, version),
                                style = NexaraTypography.labelLarge,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelectVersion(version)
                        },
                        modifier = Modifier
                            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                            .semantics { selected = selectedVersion == version },
                    )
                }
            }
        }
    }
}

@Composable
private fun CanvasLoading() {
    Column(
        modifier = Modifier.padding(NexaraSpacing.Large),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
        Text(
            text = stringResource(R.string.canvas_loading),
            style = NexaraTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CanvasPreviewPlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        Icon(
            imageVector = Icons.Rounded.Dashboard,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.canvas_preview),
            style = NexaraTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
