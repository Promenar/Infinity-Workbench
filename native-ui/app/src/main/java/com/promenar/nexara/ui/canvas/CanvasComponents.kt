package com.promenar.nexara.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
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

/**
 * 消息中的画布附件卡：缩略图铺满卡片、底部渐隐，标题与版本叠在渐隐层上。轻点整卡打开该消息对应的版本，
 * 长按展开更多操作；预览始终显示最新内容，旧消息用右上角标签提示已有新版本。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CanvasCard(
    metadata: CanvasMetadata,
    version: Int,
    html: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    previewContent: (@Composable () -> Unit)? = null,
    workspacePath: String? = null,
    workspaceWarning: Boolean = false,
    onOpenLatest: (() -> Unit)? = null,
    onCopySource: (() -> Unit)? = null,
    onRevealInWorkspace: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val hasMenu = onOpenLatest != null || onCopySource != null || onRevealInWorkspace != null
    val shape = RoundedCornerShape(16.dp)
    val container = MaterialTheme.colorScheme.surfaceContainerLow
    val newer = metadata.currentVersion > version
    Box(modifier = modifier.widthIn(max = 360.dp).fillMaxWidth()) {
        Surface(
            shape = shape,
            color = container,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    onClickLabel = stringResource(R.string.canvas_open),
                    onLongClickLabel = if (hasMenu) stringResource(R.string.canvas_card_more_actions) else null,
                    onLongClick = if (hasMenu) ({ menuExpanded = true }) else null,
                    onClick = onOpen,
                )
                .testTag(UiTags.CANVAS_CARD),
        ) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .clearAndSetSemantics {}
                        .testTag(UiTags.CANVAS_PREVIEW),
                    contentAlignment = Alignment.TopCenter,
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
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(88.dp)
                        .background(Brush.verticalGradient(listOf(container.copy(alpha = 0f), container.copy(alpha = 0.92f), container))),
                )
                // 文字全部叠在底部渐隐层上，缩略图上方不放任何遮挡，保持画布内容本身干净。
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = NexaraSpacing.Medium, end = NexaraSpacing.Small, bottom = NexaraSpacing.Small),
                    horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Dashboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = metadata.title,
                            style = NexaraTypography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (workspaceWarning) {
                                Icon(
                                    imageVector = Icons.Rounded.SyncProblem,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(end = 4.dp).size(12.dp),
                                )
                            }
                            Text(
                                text = when {
                                    workspaceWarning -> stringResource(R.string.canvas_card_not_synced_version, version)
                                    workspacePath != null -> stringResource(R.string.canvas_card_version_path, version, workspacePath)
                                    else -> stringResource(R.string.canvas_version, version)
                                },
                                style = NexaraTypography.labelSmall,
                                color = if (workspaceWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                // 路径两端都有信息（目录与扩展名），从中间省略；警告文字从末尾省略。
                                overflow = if (workspaceWarning) TextOverflow.Ellipsis else TextOverflow.MiddleEllipsis,
                            )
                        }
                    }
                    if (newer) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Text(
                                text = stringResource(R.string.canvas_card_newer, metadata.currentVersion),
                                style = NexaraTypography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = NexaraSpacing.Small, vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
        NexaraDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, modifier = Modifier.testTag(UiTags.CANVAS_CARD_MENU)) {
            if (newer && onOpenLatest != null) {
                CanvasCardMenuItem(R.string.canvas_card_open_latest, Icons.Rounded.OpenInFull) { menuExpanded = false; onOpenLatest() }
            }
            onCopySource?.let { copy ->
                CanvasCardMenuItem(R.string.canvas_copy_source, Icons.Rounded.ContentCopy) { menuExpanded = false; copy() }
            }
            onRevealInWorkspace?.let { reveal ->
                CanvasCardMenuItem(R.string.canvas_card_reveal, Icons.Rounded.FolderOpen) { menuExpanded = false; reveal() }
            }
        }
    }
}

@Composable
private fun CanvasCardMenuItem(label: Int, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label), style = NexaraTypography.labelLarge) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(NexaraSpacing.RowIconSize)) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
    )
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
