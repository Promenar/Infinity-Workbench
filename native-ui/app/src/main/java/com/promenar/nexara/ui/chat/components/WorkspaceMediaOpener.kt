package com.promenar.nexara.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.domain.media.MediaKind
import com.promenar.nexara.ui.media.LocalWorkspaceMedia
import com.promenar.nexara.ui.media.WorkspaceMediaEnvironment
import com.promenar.nexara.ui.media.WorkspaceMediaViewer
import com.promenar.nexara.ui.media.mediaKindForFileName

/** 按文件名判断能否在会话内直接查看或播放：图片、视频、音频。 */
internal fun isPreviewableMediaName(name: String): Boolean = mediaKindForFileName(name) != MediaKind.FILE

/**
 * 资源浏览入口共享的媒体查看：返回按虚拟路径打开会话内查看器的函数，并在打开期间承载查看器。
 * 虚拟路径与 Agent 看到的一致：工作区内为 `/a/b.png`（目录工作区同样以目录根为 `/`），挂载为 `/mnt/<别名>/…`。
 * 没有会话或媒体环境时返回 null，调用方保持原有的文件预览。
 */
@Composable
internal fun rememberWorkspaceMediaOpener(sessionId: String?, stateKey: String?): ((String) -> Unit)? {
    // 键变化时会得到新的状态对象；打开函数以它为键重建，始终写入当前状态。
    val openedPath = rememberSaveable(sessionId, stateKey) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val environment = LocalWorkspaceMedia.current
        ?: remember(sessionId, context) {
            val application = context.applicationContext as? NexaraApplication
            if (sessionId == null || application == null) null
            else WorkspaceMediaEnvironment(sessionId, application.workspaceMediaResolver)
        }
    openedPath.value?.let { path ->
        if (environment != null) {
            CompositionLocalProvider(LocalWorkspaceMedia provides environment) {
                WorkspaceMediaViewer(path = path, onDismiss = { openedPath.value = null })
            }
        }
    }
    return environment?.let { remember(it, openedPath) { { path: String -> openedPath.value = path } } }
}
