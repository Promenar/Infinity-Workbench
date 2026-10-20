package com.promenar.nexara.ui.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.promenar.nexara.domain.media.MediaKind
import com.promenar.nexara.domain.media.MediaResolution
import com.promenar.nexara.ui.renderer.ImageLightbox
import com.promenar.nexara.ui.renderer.ImageLightboxActions
import com.promenar.nexara.ui.theme.NexaraSpacing

/** 工作区图片查看器：沿用 [ImageLightbox]，保存、分享与用其它应用打开均经工作区来源，提示显示在查看器窗口内。 */
@Composable
fun WorkspaceImageViewer(
    available: MediaResolution.Available,
    model: WorkspaceMediaModel,
    onDismiss: () -> Unit,
) {
    val environment = LocalWorkspaceMedia.current ?: return
    val hostState = remember { SnackbarHostState() }
    val messenger = rememberSnackbarMediaMessenger(hostState)
    CompositionLocalProvider(LocalMediaMessenger provides messenger) {
        val runner = rememberMediaActionRunner(environment)
        ImageLightbox(
            imageUrl = model.toUri(),
            model = model,
            onDismiss = onDismiss,
            actions = ImageLightboxActions(
                onSave = { runner.save(available) },
                onShare = { runner.share(available) },
                onOpenWith = { runner.openWith(available) },
            ),
            overlay = {
                MediaSnackbarHost(
                    hostState = hostState,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
                )
            },
        )
    }
}

/** 按类别打开已解析媒体：图片进 Lightbox，视频进全屏播放，音频与其它文件进底部面板。 */
@Composable
fun WorkspaceMediaViewerFor(
    available: MediaResolution.Available,
    model: WorkspaceMediaModel,
    onDismiss: () -> Unit,
) {
    when (available.ref.kind) {
        MediaKind.IMAGE -> WorkspaceImageViewer(available = available, model = model, onDismiss = onDismiss)
        MediaKind.VIDEO -> {
            val session = rememberMediaPlayerSession(available)
            LaunchedEffect(session) { session.play() }
            FullscreenVideoDialog(session = session, onDismiss = onDismiss)
        }
        MediaKind.AUDIO -> MediaPanelSheet(onDismiss = onDismiss) {
            WorkspaceAudioCard(available = available, autoPlay = true)
            MediaPanelActions(available)
        }
        MediaKind.FILE -> MediaPanelSheet(onDismiss = onDismiss) {
            WorkspaceFileCard(available = available, model = model)
        }
    }
}

/** 按虚拟路径打开查看器（资源管理器、正文内链接）；解析完成前不显示，不可用时在面板中显示占位。 */
@Composable
fun WorkspaceMediaViewer(path: String, onDismiss: () -> Unit) {
    val environment = LocalWorkspaceMedia.current ?: return
    val resolved = rememberWorkspaceMediaResolution(environment, path) ?: return
    when (val resolution = resolved.resolution) {
        is MediaResolution.Available -> WorkspaceMediaViewerFor(
            available = resolution,
            model = WorkspaceMediaModel(environment.sessionId, path, resolved.identity),
            onDismiss = onDismiss,
        )
        is MediaResolution.Unavailable -> MediaPanelSheet(onDismiss = onDismiss) {
            WorkspaceMediaUnavailable(
                path = path,
                reason = resolution.reason,
                onLocate = environment.onLocate
                    ?.takeIf { canLocateInExplorer(path) }
                    ?.let { locate -> mediaParentPath(path)?.let { parent -> { onDismiss(); locate(parent) } } },
            )
        }
    }
}

@Composable
private fun MediaPanelActions(available: MediaResolution.Available) {
    val environment = LocalWorkspaceMedia.current ?: return
    val runner = rememberMediaActionRunner(environment)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        MediaActionButtons(available = available, runner = runner)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPanelSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val hostState = remember { SnackbarHostState() }
    val messenger = rememberSnackbarMediaMessenger(hostState)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CompositionLocalProvider(LocalMediaMessenger provides messenger) {
            Box {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NexaraSpacing.Large)
                        .padding(bottom = NexaraSpacing.Large)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                ) {
                    content()
                }
                MediaSnackbarHost(hostState = hostState, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}
