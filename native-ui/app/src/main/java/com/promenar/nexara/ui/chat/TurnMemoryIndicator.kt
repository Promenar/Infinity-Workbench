package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.model.TurnMemoryState
import com.promenar.nexara.data.rag.TurnGraphDetails
import com.promenar.nexara.data.rag.TurnGraphState
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.CancellationException

internal const val TURN_MEMORY_STATUS_TAG = "CHAT_TURN_MEMORY_STATUS"
internal const val TURN_MEMORY_SHEET_TAG = "CHAT_TURN_MEMORY_SHEET"
internal const val TURN_MEMORY_REMOVE_TAG = "CHAT_TURN_MEMORY_REMOVE"
internal const val TURN_MEMORY_REWRITE_TAG = "CHAT_TURN_MEMORY_REWRITE"
internal const val TURN_GRAPH_RETRY_TAG = "CHAT_TURN_GRAPH_RETRY"
internal const val TURN_GRAPH_OPEN_TAG = "CHAT_TURN_GRAPH_OPEN"

internal fun TurnMemoryState.Status.labelRes(): Int = when (this) {
    TurnMemoryState.Status.WRITING -> R.string.turn_memory_status_writing
    TurnMemoryState.Status.STORED -> R.string.turn_memory_status_stored
    TurnMemoryState.Status.FAILED -> R.string.turn_memory_status_failed
    TurnMemoryState.Status.REMOVED -> R.string.turn_memory_status_removed
}

@Composable
internal fun TurnGraphState.label(): String = when (status) {
    TurnGraphState.Status.QUEUED -> stringResource(R.string.turn_graph_status_queued)
    TurnGraphState.Status.RUNNING -> stringResource(R.string.turn_graph_status_running)
    TurnGraphState.Status.DONE -> stringResource(R.string.turn_graph_status_done, entities, relations)
    TurnGraphState.Status.PARTIAL -> stringResource(R.string.turn_graph_status_partial, entities)
    TurnGraphState.Status.FAILED -> stringResource(R.string.turn_graph_status_failed)
}

/** 页脚显示的阶段：写入中、记忆失败或已移除优先；记入之后进入图谱抽取的各阶段。 */
internal data class TurnFooterStatus(val memory: TurnMemoryState?, val graph: TurnGraphState?) {
    val showsGraph: Boolean
        get() = graph != null && memory?.status.let {
            it == null || it == TurnMemoryState.Status.STORED
        }
    val failed: Boolean
        get() = if (showsGraph) graph?.status == TurnGraphState.Status.FAILED else memory?.status == TurnMemoryState.Status.FAILED
    val dimmed: Boolean
        get() = if (showsGraph) {
            graph?.status == TurnGraphState.Status.QUEUED || graph?.status == TurnGraphState.Status.RUNNING
        } else memory?.status == TurnMemoryState.Status.WRITING || memory?.status == TurnMemoryState.Status.REMOVED
    val isEmpty: Boolean get() = memory == null && graph == null
}

@Composable
internal fun TurnFooterStatus.label(): String? =
    if (showsGraph) graph?.label() else memory?.let { stringResource(it.status.labelRes()) }

/** 回复底部模型名之前的记忆写入状态；点按打开“本轮记忆”面板。 */
@Composable
internal fun TurnMemoryStatusLabel(state: TurnMemoryState, onClick: () -> Unit, modifier: Modifier = Modifier, showText: Boolean = true) =
    TurnMemoryStatusLabel(TurnFooterStatus(state, null), onClick, modifier, showText)

/** 回复底部模型名之前的记忆与图谱状态；点按打开“本轮记忆”面板。 */
@Composable
internal fun TurnMemoryStatusLabel(state: TurnFooterStatus, onClick: () -> Unit, modifier: Modifier = Modifier, showText: Boolean = true) {
    val label = state.label() ?: return
    val failed = state.failed
    val tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val dimmed = state.dimmed
    Row(
        modifier = modifier
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.turn_memory_open), onClick = onClick)
            .padding(horizontal = 4.dp)
            .alpha(if (dimmed) 0.6f else 1f)
            .testTag(TURN_MEMORY_STATUS_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = when {
                failed -> Icons.Rounded.ErrorOutline
                state.showsGraph -> Icons.Rounded.Hub
                else -> Icons.Rounded.History
            },
            contentDescription = if (showText) null else label,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
        if (showText) {
            Text(
                text = label,
                style = NexaraTypography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * “本轮记忆”面板：写入状态、本轮记忆可被哪些会话检索、本轮写入的片段，以及移除或重新记入。
 * 移除只删除记忆片段，聊天记录保留。
 */
@Composable
internal fun TurnMemorySheet(
    state: TurnMemoryState?,
    loadDetails: suspend () -> TurnMemoryDetails,
    onRemove: () -> Unit,
    onRewrite: () -> Unit,
    onDismiss: () -> Unit,
    graph: TurnGraphState? = null,
    loadGraph: suspend () -> TurnGraphDetails = { TurnGraphDetails(emptyList(), emptyList()) },
    onRetryGraph: () -> Unit = {},
    onOpenGraph: ((String) -> Unit)? = null,
) {
    var details by remember { mutableStateOf<TurnMemoryDetails?>(null) }
    LaunchedEffect(state?.status, state?.updatedAt) {
        if (state == null) return@LaunchedEffect
        details = try {
            loadDetails()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TurnMemoryDetails(emptyList(), shareWithOtherAgents = true)
        }
    }
    var graphDetails by remember { mutableStateOf<TurnGraphDetails?>(null) }
    LaunchedEffect(graph) {
        if (graph == null) return@LaunchedEffect
        graphDetails = try {
            loadGraph()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TurnGraphDetails(emptyList(), emptyList())
        }
    }
    val title = stringResource(if (state == null) R.string.turn_graph_title else R.string.turn_memory_title)
    NexaraBottomSheet(show = true, onDismiss = onDismiss, title = title, scrollContent = true) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state != null) TurnMemorySheetContent(state, details, onRemove, onRewrite)
            if (graph != null) TurnGraphSheetContent(graph, graphDetails, onRetryGraph, onOpenGraph, showTitle = state != null)
        }
    }
}

/** 本轮抽取的实体与关系，抽取失败或未完成时提供重新抽取。 */
@Composable
internal fun TurnGraphSheetContent(
    state: TurnGraphState,
    details: TurnGraphDetails?,
    onRetry: () -> Unit,
    onOpenGraph: ((String) -> Unit)?,
    showTitle: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) {
            Text(
                text = stringResource(R.string.turn_graph_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val failed = state.status == TurnGraphState.Status.FAILED
        Text(
            text = if (failed) stringResource(R.string.turn_graph_failed_title) else state.label(),
            style = MaterialTheme.typography.titleSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        when (state.status) {
            TurnGraphState.Status.QUEUED, TurnGraphState.Status.RUNNING -> Hint(stringResource(R.string.turn_graph_pending_hint))
            TurnGraphState.Status.PARTIAL -> Hint(stringResource(R.string.turn_graph_partial_hint))
            TurnGraphState.Status.FAILED -> state.error?.takeIf { it.isNotBlank() }?.let { error ->
                Hint(stringResource(R.string.turn_graph_failed_hint, error.take(200)))
            }
            TurnGraphState.Status.DONE -> Unit
        }
        val entities = details?.entities.orEmpty()
        val relations = details?.relations.orEmpty()
        if (state.status == TurnGraphState.Status.DONE && details != null && entities.isEmpty() && relations.isEmpty()) {
            Hint(stringResource(R.string.turn_graph_empty))
        }
        if (entities.isNotEmpty()) {
            Text(
                text = stringResource(R.string.turn_graph_entities_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entities.joinToString(stringResource(R.string.chat_step_summary_separator)) { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (relations.isNotEmpty()) {
            Text(
                text = stringResource(R.string.turn_graph_relations_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                relations.forEach { relation ->
                    Text(
                        text = stringResource(R.string.turn_graph_relation, relation.source, relation.relation, relation.target),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val focusEntity = details?.focusEntity
        if (onOpenGraph != null && focusEntity != null) {
            NexaraSettingsItem(
                icon = Icons.Rounded.Hub,
                title = stringResource(R.string.turn_graph_open_graph),
                horizontalPadding = 0.dp,
                onClick = { onOpenGraph(focusEntity) },
                modifier = Modifier.testTag(TURN_GRAPH_OPEN_TAG),
            )
        }
        if (failed || state.status == TurnGraphState.Status.PARTIAL) {
            NexaraSettingsItem(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.turn_graph_retry),
                horizontalPadding = 0.dp,
                onClick = onRetry,
                modifier = Modifier.testTag(TURN_GRAPH_RETRY_TAG),
            )
        }
    }
}

@Composable
internal fun TurnMemorySheetContent(
    state: TurnMemoryState,
    details: TurnMemoryDetails?,
    onRemove: () -> Unit,
    onRewrite: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(TURN_MEMORY_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val statusLine = buildList {
            add(stringResource(state.status.labelRes()))
            if (state.status == TurnMemoryState.Status.STORED && state.chunkCount > 0) {
                add(pluralStringResource(R.plurals.turn_memory_chunk_count, state.chunkCount, state.chunkCount))
            }
        }.joinToString(stringResource(R.string.chat_step_summary_separator))
        Text(
            text = statusLine,
            style = MaterialTheme.typography.titleSmall,
            color = if (state.status == TurnMemoryState.Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        when (state.status) {
            TurnMemoryState.Status.WRITING -> Hint(stringResource(R.string.turn_memory_writing_hint))
            TurnMemoryState.Status.REMOVED -> Hint(stringResource(R.string.turn_memory_removed_hint))
            TurnMemoryState.Status.FAILED -> Hint(stringResource(R.string.turn_memory_failed_hint))
            TurnMemoryState.Status.STORED -> Unit
        }

        Text(
            text = stringResource(R.string.turn_memory_visibility_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Hint(stringResource(R.string.turn_memory_visibility_session))
            Hint(stringResource(R.string.turn_memory_visibility_agent))
            Hint(stringResource(if (details?.shareWithOtherAgents != false) R.string.turn_memory_visibility_shared else R.string.turn_memory_visibility_private))
        }

        val chunks = details?.chunks.orEmpty()
        if (state.status == TurnMemoryState.Status.STORED && chunks.isNotEmpty()) {
            Text(
                text = stringResource(R.string.turn_memory_chunks_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            chunks.forEach { chunk ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = chunk,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        when (state.status) {
            TurnMemoryState.Status.STORED -> NexaraSettingsItem(
                icon = Icons.Rounded.DeleteOutline,
                title = stringResource(R.string.turn_memory_remove),
                subtitle = stringResource(R.string.turn_memory_remove_hint),
                titleColor = MaterialTheme.colorScheme.error,
                horizontalPadding = 0.dp,
                onClick = onRemove,
                modifier = Modifier.testTag(TURN_MEMORY_REMOVE_TAG),
            )
            TurnMemoryState.Status.REMOVED, TurnMemoryState.Status.FAILED -> NexaraSettingsItem(
                icon = Icons.Rounded.Refresh,
                title = stringResource(if (state.status == TurnMemoryState.Status.FAILED) R.string.turn_memory_retry else R.string.turn_memory_readd),
                horizontalPadding = 0.dp,
                onClick = onRewrite,
                modifier = Modifier.testTag(TURN_MEMORY_REWRITE_TAG),
            )
            TurnMemoryState.Status.WRITING -> Unit
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
