@file:OptIn(coil3.annotation.ExperimentalCoilApi::class)

package com.promenar.nexara.ui.chat

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.model.*
import com.promenar.nexara.ui.theme.*

@PreviewTest
@Preview(name = "顶栏短标题 深中411", widthDp = 411, heightDp = 72, locale = "zh-rCN")
@Composable
fun chatTopBarShortBatch2Preview() = ChatBatch2Frame { Batch2TopBar("季度经营分析") }

@PreviewTest
@Preview(name = "顶栏长标题 深中411", widthDp = 411, heightDp = 72, locale = "zh-rCN")
@Composable
fun chatTopBarLongBatch2Preview() = ChatBatch2Frame { Batch2TopBar("关于2026年第四季度各项目经营情况与市场变化的完整分析") }

@Composable
private fun Batch2TopBar(title: String) = ChatTopBar(title, {}, {}, {}, onClearHistory = {}, onRename = {}, onDeleteSession = {})

@PreviewTest
@Preview(name = "输入空态 深中411", widthDp = 411, heightDp = 156, locale = "zh-rCN")
@Composable
fun chatComposerEmptyBatch2Preview() = ComposerFrame()

@PreviewTest
@Preview(name = "输入文字 深中411", widthDp = 411, heightDp = 156, locale = "zh-rCN")
@Composable
fun chatComposerTextBatch2Preview() = ComposerFrame("请总结这份报告的关键结论。")

@PreviewTest
@Preview(name = "输入超过六行 深中411", widthDp = 411, heightDp = 270, locale = "zh-rCN")
@Composable
fun chatComposerSixLinesBatch2Preview() = ComposerFrame((1..9).joinToString("\n") { "第${it}项：核对项目收入、成本与进度。" })

@PreviewTest
@Preview(name = "输入生成中 深中411", widthDp = 411, heightDp = 156, locale = "zh-rCN")
@Composable
fun chatComposerGeneratingBatch2Preview() = ComposerFrame("请分析这份材料。", generating = true)

@PreviewTest
@Preview(name = "输入图片文档 深中411", widthDp = 411, heightDp = 220, locale = "zh-rCN")
@Composable
fun chatComposerAttachmentsBatch2Preview() = ComposerFrame("请比较图片和附件中的数据。", attachments = true)

@PreviewTest
@Preview(name = "输入任务状态 深中411", widthDp = 411, heightDp = 202, locale = "zh-rCN")
@Composable
fun chatComposerTaskBatch2Preview() = ComposerFrame("继续核对报告。", task = true)

@PreviewTest
@Preview(name = "输入放大字号 深中411", widthDp = 411, heightDp = 180, locale = "zh-rCN", fontScale = 1.3f)
@Preview(name = "输入窄屏 深中360", widthDp = 360, heightDp = 180, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun chatComposerLargeFontBatch2Preview() = ComposerFrame("请比较这两份报告。", modelName = "超长模型名称 Gemini 2.5 Flash Preview")

@Composable
private fun ComposerFrame(text: String = "", generating: Boolean = false, attachments: Boolean = false, task: Boolean = false, modelName: String = "Gemini 2.5 Flash") {
    ChatBatch2Frame {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (task) ChatComposerStatusBar(
                taskPanel = taskPanelUiState(listOf(TaskStep("plan", "分析报告", status = "doing")), false),
                postProcessTasks = listOf(PostProcessTask("summary", PostProcessType.AUTO_SUMMARY, PostProcessStatus.RUNNING)),
            )
            ChatInputBar(
                text = text, placeholder = "向 Nexara 提问…", onTextChange = {}, onSend = {},
                status = if (generating) GenerationStatus.THINKING else GenerationStatus.IDLE,
                isGenerating = generating,
                modelName = modelName, modelId = "google::gemini-2.5-flash",
                tokenState = ChatViewModel.TokenIndicatorState(used = 44800, max = 128000),
                hasImages = attachments, hasDocuments = attachments,
                attachmentContent = {
                    if (attachments) CompositionLocalProvider(
                        LocalAsyncImagePreviewHandler provides AsyncImagePreviewHandler { ColorImage(0xFF426A85.toInt()) },
                    ) {
                        ChatComposerAttachments(
                            imageUris = listOf(Uri.parse("content://preview/chat-image")),
                            documents = listOf(batch2Document()), isImporting = false,
                        )
                    }
                },
            )
        }
    }
}

@PreviewTest
@Preview(name = "会话完整 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatPageDarkBatch2Preview() = Batch2Page()

@PreviewTest
@Preview(name = "会话完整 浅中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatPageLightBatch2Preview() = Batch2Page(dark = false)

@PreviewTest
@Preview(name = "会话完整 深英411", widthDp = 411, heightDp = 892, locale = "en")
@Composable
fun chatPageEnglishBatch2Preview() = Batch2Page(english = true)

@PreviewTest
@Preview(name = "会话空态 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatPageEmptyBatch2Preview() = Batch2Page(empty = true)

@PreviewTest
@Preview(name = "会话完整 深中360", widthDp = 360, heightDp = 892, locale = "zh-rCN")
@Preview(name = "会话完整 深中平板", widthDp = 1000, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatPageAdaptiveBatch2Preview() = Batch2Page()

@Composable
private fun Batch2Page(dark: Boolean = true, english: Boolean = false, empty: Boolean = false) {
    ChatBatch2Frame(dark) {
        ChatScreenContent(
            state = ChatScreenState(
                uiState = ChatUiState(
                    session = Session("batch2", "assistant", title = if (english) "Quarterly report" else "季度经营分析", modelId = "google::gemini-2.5-flash"),
                    agentName = "Nexara",
                    messages = if (empty) emptyList() else listOf(
                        Message("user", MessageRole.USER, if (english) "Summarize the quarterly report." else "请总结季度报告，并给出三个行动建议。"),
                        Message("assistant", MessageRole.ASSISTANT,
                            if (english) "## Quarterly performance\n\nRevenue grew steadily, while delivery costs remained stable.\n\n1. Focus on high-margin projects.\n2. Review cash collection weekly.\n3. Match capacity to confirmed demand."
                            else "## 季度表现\n\n收入保持增长，交付成本总体稳定。下一季度应优先关注回款、项目结构和产能安排。\n\n1. 优先推进利润较高的项目。\n2. 每周复核回款进度。\n3. 根据已确认需求安排产能。",
                            reasoning = if (english) "Compare revenue and delivery costs." else "先比较收入与成本，再检查回款安排。",
                            modelId = "google::gemini-2.5-flash", createdAt = 1700000000000L),
                    ),
                ),
                modelDisplayNames = mapOf("google::gemini-2.5-flash" to "Gemini 2.5 Flash"),
                tokenState = ChatViewModel.TokenIndicatorState(used = 44800, max = 128000),
            ),
            actions = ChatScreenActions(),
        )
    }
}

@Composable
private fun ChatBatch2Frame(dark: Boolean = true, content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT, colorSource = NexaraColorSource.NEXARA)) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

private fun batch2Document() = MessageDocumentAttachment(
    id = "report", name = "季度报告.pdf", mimeType = "application/pdf", content = "季度收入与成本", sizeBytes = 12000, sha256 = "preview", estimatedTokens = 24,
)

@PreviewTest
@Preview(name = "顶栏横屏 横向安全区", widthDp = 800, heightDp = 80, locale = "zh-rCN")
@Composable
fun chatTopBarLandscapeSafeInsetsBatch2Preview() = ChatBatch2Frame {
    ChatTopBar(
        title = "季度经营分析", onBack = {}, onWorkspace = {}, onSettings = {},
        onClearHistory = {}, onRename = {}, onDeleteSession = {},
        windowInsets = WindowInsets(left = 28.dp, right = 48.dp),
    )
}
