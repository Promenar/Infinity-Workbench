package com.promenar.nexara.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.LocalNexaraHaptics
import com.promenar.nexara.ui.common.NexaraDropdownMenu
import com.promenar.nexara.ui.common.NexaraHapticEvent
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraMotion

/** 收起时最多显示的排队条数，其余折叠为“展开全部”。 */
private const val CollapsedQueueItems = 3

/**
 * 输入框浮岛中的排队托盘：位于活动栏与输入区之间，自下而上依次是“正在写的、排着的、正在运行的”。
 * 暂停时顶部一行说明原因并提供“继续”；每条排队消息可插队，更多操作里可编辑、移到最前与删除。
 * [canEdit] 为假（输入框已有内容）时编辑不可用，避免覆盖正在写的草稿。
 */
@Composable
internal fun ComposerQueueTray(
    queue: ComposerQueueUiState,
    canEdit: Boolean,
    onSteer: (String) -> Unit,
    onEdit: (String) -> Unit,
    onMoveToFront: (String) -> Unit,
    onRemove: (String) -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val items = queue.items
    val shown = if (expanded || items.size <= CollapsedQueueItems) items else items.take(CollapsedQueueItems)
    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize(tween(NexaraMotion.DurationMedium, easing = NexaraMotion.EmphasizedDecelerate))
            .testTag(UiTags.CHAT_QUEUE_TRAY),
    ) {
        queue.pauseReason?.let { reason -> QueuePausedHeader(reason, onResume) }
        shown.forEachIndexed { index, item ->
            QueuedMessageRow(
                item = item,
                position = index + 1,
                canEdit = canEdit,
                canMoveToFront = index > 0,
                onSteer = { onSteer(item.id) },
                onEdit = { onEdit(item.id) },
                onMoveToFront = { onMoveToFront(item.id) },
                onRemove = { onRemove(item.id) },
            )
        }
        if (items.size > CollapsedQueueItems) {
            Text(
                text = if (expanded) {
                    stringResource(R.string.chat_queue_collapse)
                } else {
                    stringResource(R.string.chat_queue_show_all, items.size)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(start = 42.dp, top = 12.dp, bottom = 12.dp)
                    .testTag(UiTags.CHAT_QUEUE_EXPAND),
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

@Composable
private fun QueuePausedHeader(reason: ComposerQueuePauseReason, onResume: () -> Unit) {
    val haptics = LocalNexaraHaptics.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.PauseCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(reason.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                haptics.perform(NexaraHapticEvent.CONFIRM)
                onResume()
            },
            modifier = Modifier.testTag(UiTags.CHAT_QUEUE_RESUME),
        ) { Text(stringResource(R.string.chat_queue_resume), style = MaterialTheme.typography.labelLarge) }
    }
}

private fun ComposerQueuePauseReason.labelRes(): Int = when (this) {
    ComposerQueuePauseReason.STOPPED -> R.string.chat_queue_paused_stopped
    ComposerQueuePauseReason.FAILED -> R.string.chat_queue_paused_failed
    ComposerQueuePauseReason.RESTORED -> R.string.chat_queue_paused_restored
    ComposerQueuePauseReason.INTERRUPTED -> R.string.chat_queue_paused_interrupted
    ComposerQueuePauseReason.BUSY -> R.string.chat_queue_paused_busy
}

@Composable
private fun QueuedMessageRow(
    item: QueuedMessageUi,
    position: Int,
    canEdit: Boolean,
    canMoveToFront: Boolean,
    onSteer: () -> Unit,
    onEdit: () -> Unit,
    onMoveToFront: () -> Unit,
    onRemove: () -> Unit,
) {
    val haptics = LocalNexaraHaptics.current
    val summary = item.text.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }
        ?: stringResource(R.string.chat_queue_attachment_only)
    val description = stringResource(R.string.chat_queue_item_cd, position, summary)
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 16.dp, end = 4.dp)
            .testTag(UiTags.CHAT_QUEUE_ITEM_PREFIX + item.id),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (item.steering) Icons.Rounded.Bolt else Icons.Rounded.Schedule,
            contentDescription = null,
            tint = if (item.steering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Row(
            modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (item.imageCount > 0) AttachmentCount(Icons.Rounded.Image, item.imageCount)
            if (item.documentNames.isNotEmpty()) AttachmentCount(Icons.Rounded.Description, item.documentNames.size)
        }
        // 插队后改为提示“当前步骤后发送”，不再重复触发。
        AnimatedContent(
            targetState = item.steering,
            transitionSpec = { fadeIn(tween(NexaraMotion.DurationShort)) togetherWith fadeOut(tween(NexaraMotion.DurationShort)) },
            contentAlignment = Alignment.CenterEnd,
            label = "queue_steer_state",
        ) { steering ->
            if (steering) {
                Text(
                    text = stringResource(R.string.chat_queue_steering),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            } else {
                val steerDescription = stringResource(R.string.chat_queue_steer_cd)
                TextButton(
                    onClick = {
                        haptics.perform(NexaraHapticEvent.CONFIRM)
                        onSteer()
                    },
                    modifier = Modifier
                        .semantics { contentDescription = steerDescription }
                        .testTag(UiTags.CHAT_QUEUE_STEER),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) { Text(stringResource(R.string.chat_queue_steer), style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            }
        }
        Box {
            ChatCompactIconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.testTag(UiTags.CHAT_QUEUE_MORE),
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.chat_queue_more_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            NexaraDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                QueueMenuItem(Icons.Rounded.Edit, stringResource(R.string.chat_queue_edit), enabled = canEdit) {
                    menuOpen = false
                    onEdit()
                }
                if (canMoveToFront) {
                    QueueMenuItem(Icons.Rounded.VerticalAlignTop, stringResource(R.string.chat_queue_move_front)) {
                        menuOpen = false
                        onMoveToFront()
                    }
                }
                QueueMenuItem(Icons.Rounded.Delete, stringResource(R.string.chat_queue_remove)) {
                    menuOpen = false
                    onRemove()
                }
            }
        }
    }
}

@Composable
private fun AttachmentCount(icon: ImageVector, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QueueMenuItem(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        enabled = enabled,
        onClick = onClick,
    )
}
