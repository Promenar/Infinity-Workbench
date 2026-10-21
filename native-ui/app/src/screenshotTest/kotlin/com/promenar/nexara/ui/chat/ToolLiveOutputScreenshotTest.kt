package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.exec.ToolLiveOutputStore
import com.promenar.nexara.data.model.ExecutionStep
import com.promenar.nexara.domain.tool.ToolOutputStream
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "实时输出 运行中 深色", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Composable
fun toolLiveOutputRunningPreview() = LiveOutputFrame {
    val store = ToolLiveOutputStore().apply {
        open(RUNNING_STEP).apply {
            onStop {}
            emit(ToolOutputStream.STDOUT, buildOutput)
            emit(ToolOutputStream.STDERR, "warning: 1 deprecated API\n")
        }
    }
    CompositionLocalProvider(LocalToolLiveOutput provides store) {
        InlineToolRow(steps = runningSteps, isExecuting = true, fontSize = 16)
    }
}

@PreviewTest
@Preview(name = "实时输出 停止中 等待 不支持 浅色", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Composable
fun toolLiveOutputStatesLightPreview() = LiveOutputFrame(dark = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ToolLiveOutputPanel(snapshot("Downloading 100%\nInstalling…\n", stoppable = true, stopRequested = true), onStop = {})
        ToolLiveOutputPanel(snapshot("", stoppable = true), onStop = {})
        ToolLiveOutputPanel(snapshot("", unsupported = true), onStop = {})
    }
}

@PreviewTest
@Preview(name = "Live output English 360dp font130", widthDp = 360, heightDp = 460, locale = "en", fontScale = 1.3f)
@Composable
fun toolLiveOutputNarrowEnglishPreview() = LiveOutputFrame {
    ToolLiveOutputPanel(
        snapshot("collecting packages from https://example.test/very/long/index/path/that/does/not/fit\n" + buildOutput, stoppable = true),
        onStop = {},
    )
}

private const val RUNNING_STEP = "step_preview_call_1"

private val runningSteps = listOf(
    ExecutionStep(
        id = "ai_preview::$RUNNING_STEP",
        type = "tool_call",
        toolName = "shell_exec",
        toolArgs = """{"command":"./gradlew assemble"}""",
        toolCallId = "call_1",
        timestamp = 1L,
    ),
)

private val buildOutput = (1..16).joinToString("\n", postfix = "\n") { "> Task :app:step$it UP-TO-DATE" }

private fun snapshot(text: String, stoppable: Boolean = false, stopRequested: Boolean = false, unsupported: Boolean = false) =
    ToolLiveOutputStore.Snapshot(text, truncated = false, stoppable = stoppable, stopRequested = stopRequested, unsupported = unsupported)

@Composable
private fun LiveOutputFrame(dark: Boolean = true, content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                content()
            }
        }
    }
}
