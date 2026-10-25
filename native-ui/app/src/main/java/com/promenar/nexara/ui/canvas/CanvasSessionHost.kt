package com.promenar.nexara.ui.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.nexara.R
import com.promenar.nexara.data.canvas.CanvasDocument
import com.promenar.nexara.data.canvas.CanvasMetadata
import com.promenar.nexara.data.canvas.CanvasStore
import com.promenar.nexara.data.canvas.CanvasWorkspaceSync
import com.promenar.nexara.data.workspace.WorkspaceDefaultFileDestination
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSheetHeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

internal data class CanvasSessionAccess(
    val sessionId: String,
    val store: CanvasStore,
    val canvases: List<CanvasMetadata>,
    val loaded: Boolean,
    val open: (String) -> Unit,
    val openList: () -> Unit,
    /** 打开指定版本；不早于当前版本时按跟随最新打开。 */
    val openVersion: (String, Int) -> Unit = { id, _ -> open(id) },
    val copySource: (String, Int) -> Unit = { _, _ -> },
    val revealInWorkspace: (String) -> Unit = {},
    /** 当前主工作区登记 ID；为空表示主工作区就是会话私有空间。 */
    val primaryWorkspaceId: String? = null,
)

/**
 * 资源管理器只浏览当前主工作区：画布镜像的绑定与当前主工作区一致时才返回可定位路径。
 * 无绑定的旧画布按主工作区处理；落在会话私有空间而主工作区是登记项目时不提供定位。
 */
internal fun canvasRevealPath(metadata: CanvasMetadata, primaryWorkspaceId: String?): String? {
    if (metadata.workspaceSync == CanvasWorkspaceSync.MISSING) return null
    val path = metadata.workspacePath ?: return null
    val relative = listOf("/home/session/", "/workspace/", "/session/", "/").firstOrNull(path::startsWith)
        ?.let(path::removePrefix) ?: path
    val binding = metadata.workspaceDestination ?: return "/$relative"
    val inPrimary = when (binding.destination) {
        WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE -> primaryWorkspaceId != null && binding.workspaceId == primaryWorkspaceId
        WorkspaceDefaultFileDestination.SESSION_PRIVATE -> primaryWorkspaceId == null
    }
    return "/$relative".takeIf { inPrimary }
}

internal val LocalCanvasSession = staticCompositionLocalOf<CanvasSessionAccess?> { null }

/** 会话切换会销毁所有页面；仅跟随最新时订阅当前版本，历史查看保持固定。 */
@Composable
fun CanvasSessionHost(
    sessionId: String,
    store: CanvasStore,
    onInsertText: (String) -> Unit,
    onRevealInWorkspace: (String) -> Unit = {},
    primaryWorkspaceId: String? = null,
    content: @Composable () -> Unit,
) = key(sessionId, store) {
    var canvases by remember { mutableStateOf<List<CanvasMetadata>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var listFailed by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var openedId by remember { mutableStateOf<String?>(null) }
    var selectedVersion by remember { mutableStateOf<Int?>(null) }
    val currentInsert by rememberUpdatedState(onInsertText)
    val loadError = stringResource(R.string.canvas_load_error)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(sessionId, store) {
        try {
            store.observe(sessionId).collect { canvases = it; loaded = true; listFailed = false }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            listFailed = true
            loaded = true
        }
    }
    val currentReveal by rememberUpdatedState(onRevealInWorkspace)
    val access = CanvasSessionAccess(sessionId, store, canvases, loaded,
        open = { id -> selectedVersion = null; openedId = id; showList = false },
        openList = { showList = true },
        openVersion = { id, version ->
            val current = canvases.find { it.id == id }?.currentVersion
            selectedVersion = version.takeIf { current != null && it < current }
            openedId = id
            showList = false
        },
        copySource = { id, version ->
            scope.launch {
                val snapshot = try { store.read(sessionId, id, version) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                snapshot?.let { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(it.metadata.title, it.html))) }
            }
        },
        revealInWorkspace = { path -> currentReveal(path) },
        primaryWorkspaceId = primaryWorkspaceId,
    )
    CompositionLocalProvider(LocalCanvasSession provides access) { content() }
    NexaraBottomSheet(show = showList, onDismiss = { showList = false }, title = null, height = NexaraSheetHeight.Full, dragGesturesEnabled = false) {
        if (listFailed) Text(loadError) else CanvasListContent(canvases, access.open, Modifier.fillMaxSize())
    }
    openedId?.let { id ->
        val metadata = canvases.find { it.id == id }
        val latestRevisionKey = metadata?.currentVersion.takeIf { selectedVersion == null }
        var document by remember(id, selectedVersion, latestRevisionKey) { mutableStateOf<CanvasDocument?>(null) }
        var error by remember(id, selectedVersion, latestRevisionKey) { mutableStateOf<String?>(null) }
        LaunchedEffect(id, selectedVersion, latestRevisionKey, loaded) {
            if (!loaded) return@LaunchedEffect
            try {
                // 跟随最新时先导入工作区里的外部修改；查看历史版本只读私有快照。
                document = if (selectedVersion == null) store.syncFromWorkspace(sessionId, id) else store.read(sessionId, id, selectedVersion)
                if (document == null) error = loadError
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = loadError
            }
        }
        Dialog(onDismissRequest = { openedId = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            CanvasFullscreenContent(
                document = document,
                metadata = metadata,
                selectedVersion = selectedVersion,
                onSelectVersion = { selectedVersion = it },
                onCopy = { document?.let { snapshot -> scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(snapshot.metadata.title, snapshot.html))) } } },
                onClose = { openedId = null },
                onInsertText = { text -> if (selectedVersion == null && openedId == id) currentInsert(text) },
                modifier = Modifier.fillMaxSize(),
                error = error,
            )
        }
    }
}
