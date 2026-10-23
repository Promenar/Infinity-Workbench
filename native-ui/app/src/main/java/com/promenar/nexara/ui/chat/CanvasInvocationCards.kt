package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
import com.promenar.nexara.R
import com.promenar.nexara.data.canvas.CanvasMetadata
import com.promenar.nexara.data.canvas.CanvasWorkspaceSync
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.ui.canvas.CanvasCard
import com.promenar.nexara.ui.canvas.LocalCanvasSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal data class CanvasInvocation(val id: String, val title: String, val version: Int)

internal fun canvasTimelineReferences(invocations: List<ToolInvocationPresentation>): List<CanvasInvocation> =
    invocations.mapNotNull(::canvasInvocation).asReversed().distinctBy { it.id }.asReversed()

internal fun canvasInvocation(invocation: ToolInvocationPresentation): CanvasInvocation? {
    if (invocation.name !in setOf("canvas_create", "canvas_update") || invocation.failed) return null
    return invocation.results.filter { it.type == "tool_result" }.firstNotNullOfOrNull { result ->
        val value = runCatching { Json.parseToJsonElement(result.content.orEmpty()) as? JsonObject }.getOrNull() ?: return@firstNotNullOfOrNull null
        val id = (value["canvasId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank) ?: return@firstNotNullOfOrNull null
        val version = (value["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 } ?: return@firstNotNullOfOrNull null
        val title = (value["title"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        CanvasInvocation(id, title, version)
    }
}

/** 卡片独立于执行过程折叠展示，避免已完成轮次把画布入口一同隐藏。 */
@Composable
internal fun CanvasInvocationCards(steps: List<ExecutionStep>) {
    val access = LocalCanvasSession.current ?: return
    val references = remember(steps) { canvasTimelineReferences(toolInvocations(steps)) }
    if (references.isEmpty()) return
    // 卡片跟在正文之后，与上方文字留出段落级间隔，避免顶边贴住最后一行。
    Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        references.forEachIndexed { index, reference -> key(index, reference.id) {
            val metadata = access.canvases.find { it.id == reference.id }
            var html by remember(access.sessionId, reference.id, metadata?.currentVersion) { mutableStateOf<String?>(null) }
            var failed by remember(access.sessionId, reference.id, metadata?.currentVersion) { mutableStateOf(false) }
            LaunchedEffect(access.sessionId, reference.id, metadata?.currentVersion, access.loaded) {
                if (!access.loaded) return@LaunchedEffect
                try {
                    html = access.store.read(access.sessionId, reference.id)?.html
                    failed = html == null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed = true }
            }
            val fallback = CanvasMetadata(reference.id, reference.title, 0, 0, reference.version, "", listOf(reference.version))
            CanvasCard(
                metadata = metadata ?: fallback,
                version = reference.version,
                html = html,
                onOpen = { access.openVersion(reference.id, reference.version) },
                previewContent = if (failed) { { Text(stringResource(R.string.canvas_load_error)) } } else null,
                workspacePath = metadata?.workspacePath,
                workspaceWarning = metadata?.workspaceSync == CanvasWorkspaceSync.FAILED,
                onOpenLatest = { access.open(reference.id) },
                onCopySource = { access.copySource(reference.id, reference.version) },
                onRevealInWorkspace = metadata?.workspacePath
                    ?.takeIf { metadata.workspaceSync != CanvasWorkspaceSync.MISSING }
                    ?.let { path -> { access.revealInWorkspace(path) } },
            )
        } }
    }
}
