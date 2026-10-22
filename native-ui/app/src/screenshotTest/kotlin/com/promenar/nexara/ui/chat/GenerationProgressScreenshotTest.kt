package com.promenar.nexara.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.model.*
import com.promenar.nexara.domain.generation.GenerationProgress
import com.promenar.nexara.ui.theme.*

// 生成中：输入框浮岛顶部展开活动栏，浮岛描边亮起流光；输入框仍可编辑，占位文字提示可以先写下一条。
// 有计划时活动统计并入计划面板的收起行，不再单独占一行。
@PreviewTest
@Preview(name = "生成浮岛 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatGenerationProgressDarkPreview() = GenerationProgressPage(english = false)

@PreviewTest
@Preview(name = "生成浮岛 深英360 大字号", widthDp = 360, heightDp = 892, locale = "en", fontScale = 1.3f)
@Composable
fun chatGenerationProgressEnglishLargeFontPreview() = GenerationProgressPage(english = true)

@PreviewTest
@Preview(name = "生成浮岛 浅中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatGenerationProgressLightPreview() = GenerationProgressPage(english = false, mode = NexaraThemeMode.LIGHT)

@PreviewTest
@Preview(name = "生成浮岛 计划 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatGenerationProgressPlanDarkPreview() = GenerationProgressPage(english = false, withPlan = true)

@PreviewTest
@Preview(name = "生成浮岛 计划 浅英360 大字号", widthDp = 360, heightDp = 892, locale = "en", fontScale = 1.3f)
@Composable
fun chatGenerationProgressPlanLightPreview() = GenerationProgressPage(english = true, mode = NexaraThemeMode.LIGHT, withPlan = true)

private fun progressPlan(english: Boolean): TaskPanelUiState = requireNotNull(
    taskPanelUiState(
        listOf(
            TaskStep(id = "probe-root", title = if (english) "List the workspace root" else "列出工作区根目录", status = "done", sortOrder = 0),
            TaskStep(id = "probe-md", title = if (english) "Find Markdown files" else "查找 Markdown 文件", status = "doing", sortOrder = 1),
            TaskStep(id = "summary", title = if (english) "Summarize both results" else "总结两个结果", status = "todo", sortOrder = 2),
        ),
        isGenerating = true,
    ),
)

@Composable
private fun GenerationProgressPage(english: Boolean, mode: NexaraThemeMode = NexaraThemeMode.DARK, withPlan: Boolean = false) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = mode, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ChatScreenContent(
                state = ChatScreenState(
                    uiState = ChatUiState(
                        session = Session("progress", "assistant", title = if (english) "Media test" else "媒体测试", modelId = "deepseek::v4-flash"),
                        agentName = "Nexara",
                        messages = progressMessages(english),
                        isGenerating = true,
                        status = GenerationStatus.RECEIVING,
                    ),
                    modelDisplayNames = mapOf("deepseek::v4-flash" to "DeepSeek V4 Flash"),
                    tokenState = ChatViewModel.TokenIndicatorState(used = 21800, max = 128000),
                    generationProgress = GenerationProgress(rounds = 3, calls = 7, tokens = 21_846, estimated = true),
                    taskPanel = if (withPlan) progressPlan(english) else null,
                ),
                actions = ChatScreenActions(),
            )
        }
    }
}

private fun progressMessages(english: Boolean): List<Message> {
    val answer = if (english) {
        "Both probes are back. The workspace root only holds the media and skills folders. The media test folder contains four files: two MP4 clips, one chart image and one tone recording, and the two MP4 clips are identical copies. There are no Markdown files anywhere in the workspace."
    } else {
        "两个探子都回来了：工作区根目录下只有 media 和 skills 两个目录，media/test 里有 4 个媒体文件（两个 mp4、一张图表和一段音频），其中两个 mp4 是同一份副本，而且整个工作区一个 .md 文件都没有。"
    }
    val question = if (english) {
        "Task 1: list the workspace root. Task 2: find any Markdown files in the workspace. Then summarize both results in one sentence."
    } else {
        "任务一：列出工作区根目录。任务二：查找工作区内所有 Markdown 文件。最后用一句话总结两个结果。"
    }
    return (1..3).flatMap { round ->
        listOf(
            Message("user-$round", MessageRole.USER, question, createdAt = 1700000000000L + round * 60_000),
            Message("assistant-$round", MessageRole.ASSISTANT, answer, modelId = "deepseek::v4-flash", createdAt = 1700000030000L + round * 60_000),
        )
    }
}

// 活动栏的典型状态：刚开始（只有运行指示）、普通对话（只有 Token）、工具循环中、估算 Token 的长任务；
// 文字、数字与图标须在同一条视觉中线上。
@Composable
private fun ActivityRowStates(mode: NexaraThemeMode) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = mode, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(vertical = 8.dp)) {
                GenerationActivityRow(GenerationProgress())
                GenerationActivityRow(GenerationProgress(tokens = 1_536))
                GenerationActivityRow(GenerationProgress(rounds = 1, calls = 3, tokens = 4_812))
                GenerationActivityRow(GenerationProgress(rounds = 12, calls = 48, tokens = 1_284_000, estimated = true))
            }
        }
    }
}

@PreviewTest
@Preview(name = "活动栏 深中411", widthDp = 411, locale = "zh-rCN")
@Preview(name = "活动栏 深中360 大字", widthDp = 360, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun generationProgressCapsuleDarkPreview() = ActivityRowStates(NexaraThemeMode.DARK)

@PreviewTest
@Preview(name = "活动栏 浅英360 大字", widthDp = 360, locale = "en", fontScale = 1.3f)
@Composable
fun generationProgressCapsuleLightPreview() = ActivityRowStates(NexaraThemeMode.LIGHT)

// 空会话：粒子字标停在笔画上（预览中不运行动画），下方是提示语，输入框浮岛为静止质感。
@Composable
private fun EmptyChatPage(mode: NexaraThemeMode) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = mode, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ChatScreenContent(
                state = ChatScreenState(
                    uiState = ChatUiState(
                        session = Session("empty", "assistant", title = "", modelId = "deepseek::v4-flash"),
                        agentName = "Nexara",
                    ),
                    modelDisplayNames = mapOf("deepseek::v4-flash" to "DeepSeek V4 Flash"),
                ),
                actions = ChatScreenActions(),
            )
        }
    }
}

@PreviewTest
@Preview(name = "空会话字标 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun emptyChatWordmarkDarkPreview() = EmptyChatPage(NexaraThemeMode.DARK)

@PreviewTest
@Preview(name = "空会话字标 浅英360", widthDp = 360, heightDp = 780, locale = "en")
@Composable
fun emptyChatWordmarkLightPreview() = EmptyChatPage(NexaraThemeMode.LIGHT)
