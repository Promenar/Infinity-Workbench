package com.promenar.nexara.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.model.*
import com.promenar.nexara.domain.generation.GenerationProgress
import com.promenar.nexara.ui.theme.*

// 生成中的轮次进度行位于标题栏下方，正文较长时须与消息列表分区显示、互不重叠。
@PreviewTest
@Preview(name = "生成进度 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatGenerationProgressDarkPreview() = GenerationProgressPage(english = false)

@PreviewTest
@Preview(name = "生成进度 深英360 大字号", widthDp = 360, heightDp = 892, locale = "en", fontScale = 1.3f)
@Composable
fun chatGenerationProgressEnglishLargeFontPreview() = GenerationProgressPage(english = true)

@Composable
private fun GenerationProgressPage(english: Boolean) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = NexaraThemeMode.DARK, colorSource = NexaraColorSource.NEXARA)) {
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
