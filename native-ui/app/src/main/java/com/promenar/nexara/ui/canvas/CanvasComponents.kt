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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
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
import com.promenar.nexara.data.artifact.ArtifactKind
import com.promenar.nexara.data.canvas.CanvasKind
import androidx.compose.ui.res.pluralStringResource
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
import com.promenar.nexara.ui.theme.NexaraMonospace
import com.promenar.nexara.ui.theme.NexaraTypography
import com.promenar.nexara.ui.common.NexaraMenuItem
import com.promenar.nexara.ui.common.rememberNexaraMenuTouch
import com.promenar.nexara.ui.common.nexaraMenuTouch

/**
 * 本轮产物区的画布卡：上方灰底预览区里放一张按手机宽度排版后缩小的缩略图，下方一行标题与版本路径。
 * 轻点整卡打开该消息对应的版本，长按展开更多操作；预览始终显示最新内容，旧消息用标签提示已有新版本。
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
    previewFailed: Boolean = false,
    workspacePath: String? = null,
    workspaceWarning: Boolean = false,
    onOpenLatest: (() -> Unit)? = null,
    onCopySource: (() -> Unit)? = null,
    onRevealInWorkspace: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val menuTouch = rememberNexaraMenuTouch()
    val hasMenu = onOpenLatest != null || onCopySource != null || onRevealInWorkspace != null
    val shape = RoundedCornerShape(16.dp)
    val newer = metadata.currentVersion > version
    Box(modifier = modifier.widthIn(max = 420.dp).fillMaxWidth()) {
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .nexaraMenuTouch(menuTouch)
                .combinedClickable(
                    onClickLabel = stringResource(R.string.canvas_open),
                    onLongClickLabel = if (hasMenu) stringResource(R.string.canvas_card_more_actions) else null,
                    onLongClick = if (hasMenu) ({ menuExpanded = true }) else null,
                    onClick = onOpen,
                )
                .testTag(UiTags.CANVAS_CARD),
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        // 预览内容不进入无障碍树；标签必须在清除语义的同一节点上设置。
                        .clearAndSetSemantics { testTag = UiTags.CANVAS_PREVIEW },
                    contentAlignment = Alignment.Center,
                ) {
                    CanvasThumbnailFrame(
                        html = html,
                        previewContent = previewContent,
                        previewFailed = previewFailed,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .padding(start = NexaraSpacing.Medium, end = NexaraSpacing.Small, top = 10.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (metadata.kind == CanvasKind.PROTOTYPE) ArtifactKind.PROTOTYPE.icon else Icons.Rounded.Dashboard,
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
                                text = prototypeCardMeta(metadata, version) ?: when {
                                    workspaceWarning -> stringResource(R.string.canvas_card_not_synced_version, version)
                                    workspacePath != null -> stringResource(R.string.canvas_card_version_path, version, workspacePath)
                                    else -> stringResource(R.string.canvas_version, version)
                                },
                                style = NexaraTypography.labelSmall.copy(fontFamily = if (workspacePath != null && !workspaceWarning) NexaraMonospace else null),
                                color = if (workspaceWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                // 路径两端都有信息（目录与扩展名），从中间省略；警告文字从末尾省略。
                                overflow = if (workspaceWarning) TextOverflow.Ellipsis else TextOverflow.MiddleEllipsis,
                            )
                        }
                    }
                    if (newer) CanvasNewerTag(metadata.currentVersion)
                }
            }
        }
        NexaraDropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }, touch = menuTouch, modifier = Modifier.testTag(UiTags.CANVAS_CARD_MENU)) {
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

/**
 * 生成中锚定在创建或更新步骤下方的紧凑画布卡：左侧小缩略图，右侧标题与“画布 · v1”。
 * 轻点打开该步骤产生的版本；新版本提示与更多操作留给回合结束后的本轮产物卡，窄屏把宽度让给标题。
 */
@Composable
fun CanvasInlineCard(
    metadata: CanvasMetadata,
    version: Int,
    html: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    previewContent: (@Composable () -> Unit)? = null,
    previewFailed: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClickLabel = stringResource(R.string.canvas_open), onClick = onOpen)
            .testTag(UiTags.CANVAS_CARD_INLINE),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CanvasThumbnailFrame(
                html = html,
                previewContent = previewContent,
                previewFailed = previewFailed,
                shape = RoundedCornerShape(8.dp),
                compact = true,
                modifier = Modifier
                    .size(width = 76.dp, height = 52.dp)
                    .clearAndSetSemantics { testTag = UiTags.CANVAS_PREVIEW },
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = metadata.title,
                    style = NexaraTypography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = prototypeCardMeta(metadata, version) ?: stringResource(R.string.canvas_card_inline_version, version),
                    style = NexaraTypography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 缩略图外框：有内容时按虚拟宽度缩放绘制，加载中、失败与预览环境的占位按原尺寸居中显示。 */
@Composable
private fun CanvasThumbnailFrame(
    html: String?,
    previewContent: (@Composable () -> Unit)?,
    previewFailed: Boolean,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (compact) 0.dp else 2.dp,
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            when {
                previewContent != null -> ScaledCanvasThumbnail(Modifier.fillMaxSize()) { previewContent() }
                previewFailed -> if (compact) CanvasThumbnailIcon() else Text(
                    text = stringResource(R.string.canvas_load_error),
                    style = NexaraTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(NexaraSpacing.Medium),
                )
                LocalInspectionMode.current -> if (compact) CanvasThumbnailIcon() else CanvasPreviewPlaceholder()
                html == null -> if (compact) CanvasThumbnailIcon() else CanvasLoading()
                else -> ScaledCanvasThumbnail(Modifier.fillMaxSize()) {
                    CanvasWebView(html = html, modifier = Modifier.fillMaxSize(), interactive = false, readOnly = true)
                }
            }
        }
    }
}

@Composable
private fun CanvasThumbnailIcon() {
    Icon(
        imageVector = Icons.Rounded.Dashboard,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun CanvasNewerTag(currentVersion: Int) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = stringResource(R.string.canvas_card_newer, currentVersion),
            style = NexaraTypography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = NexaraSpacing.Small, vertical = 3.dp),
        )
    }
}

/** 原型卡片写明类别与屏数，与产物页一致；页面返回 null，沿用原有版本文案。 */
@Composable
private fun prototypeCardMeta(metadata: CanvasMetadata, version: Int): String? {
    if (metadata.kind != CanvasKind.PROTOTYPE) return null
    return listOf(
        stringResource(R.string.artifact_kind_prototype),
        pluralStringResource(R.plurals.artifact_screen_count, metadata.screens.size, metadata.screens.size),
        stringResource(R.string.artifact_meta_version, version),
    ).joinToString(" · ")
}

@Composable
private fun CanvasCardMenuItem(label: Int, icon: ImageVector, onClick: () -> Unit) {
    NexaraMenuItem(
        text = { Text(stringResource(label), style = NexaraTypography.labelLarge) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(NexaraSpacing.RowIconSize)) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
    )
}

/** 列表自身管理纵向滚动，宿主只需提供可用高度。 */
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
        Column(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    // 顶栏底色延伸到状态栏下方，和系统栏连成一体。
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
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
internal fun CanvasVersionSelector(
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
                NexaraMenuItem(
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
                    NexaraMenuItem(
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
internal fun CanvasLoading() {
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
