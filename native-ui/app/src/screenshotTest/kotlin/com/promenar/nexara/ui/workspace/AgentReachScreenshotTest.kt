package com.promenar.nexara.ui.workspace

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.termux.TermuxAvailability
import com.promenar.nexara.domain.model.ExecutionMode
import com.promenar.nexara.ui.settings.previewNetworkSnapshot
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "可达范围 私有工作区 浅色", widthDp = 412, heightDp = 3200, locale = "en")
@Preview(name = "可达范围 私有工作区 浅色 大字号130", widthDp = 360, heightDp = 3000, locale = "en", fontScale = 1.3f)
@Composable
fun agentReachPrivateLightPreview() = AgentReachPreview(dark = false, shared = false)

@PreviewTest
@Preview(name = "可达范围 共享工作区 深色", widthDp = 412, heightDp = 3200, locale = "zh-rCN")
@Preview(name = "可达范围 共享工作区 深色 大字号130", widthDp = 360, heightDp = 3000, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun agentReachSharedDarkPreview() = AgentReachPreview(dark = true, shared = true)

/** 私有工作区：无挂载、无 Python、Termux 未启用；共享工作区：带挂载、全部后端可用、tailnet 已连接。 */
@Composable
private fun AgentReachPreview(dark: Boolean, shared: Boolean) {
    val state = if (shared) {
        AgentReachState(
            workspace = SessionWorkspaceUi.Directory("w1", "nexara-notes", WorkspaceEntity.KIND_SHARED, "/storage/emulated/0/Nexara/nexara-notes"),
            mounts = listOf(
                ReachMount("downloads", ReachMountSource.PATH, commandAccessible = true, unavailable = false),
                ReachMount("termux-home", ReachMountSource.SAF, commandAccessible = false, unavailable = false),
                ReachMount("old-photos", ReachMountSource.SAF, commandAccessible = false, unavailable = true),
            ),
            backends = reachBackends(true, true, TermuxAvailability(true, "0.119.1", true), 2),
            executionMode = ExecutionMode.FULL_ACCESS,
            network = previewNetworkSnapshot(configured = true),
        )
    } else {
        AgentReachState(
            workspace = SessionWorkspaceUi.Private,
            backends = reachBackends(false, false, null, 0),
            executionMode = ExecutionMode.WORKSPACE,
            network = previewNetworkSnapshot(configured = false),
        )
    }
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            AgentReachContent(
                state = state,
                // 从顶栏打开时工作区一行带切换；共享工作区预览为生成进行中，切换停用并提示。
                actions = AgentReachActions(onOpenTailscale = {}, workspaceSwitch = AgentReachWorkspaceSwitch(busy = shared, failed = false, onChoose = {})),
                modifier = Modifier.padding(horizontal = NexaraSpacing.ScreenHorizontal),
            )
        }
    }
}
