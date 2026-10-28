package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.ask.AskUserAnswer
import com.promenar.infinity.domain.ask.AskUserOption
import com.promenar.infinity.domain.ask.AskUserQuestion
import com.promenar.infinity.domain.ask.AskUserRequest
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private val TwoQuestions = AskUserRequest(
    listOf(
        AskUserQuestion(
            header = "测试框架",
            question = "新用例用哪个测试框架？",
            options = listOf(
                AskUserOption("JUnit 5（推荐）", "与模块里的新代码一致；旧用例靠 vintage 引擎继续跑"),
                AskUserOption("JUnit 4", "与旧用例一致，不改依赖"),
                AskUserOption("跟随各文件现有写法", "改动最小，但两套写法会继续并存"),
            ),
        ),
        AskUserQuestion(
            header = "处理范围",
            question = "发现业务缺陷时，分支可以做哪些事？",
            multiSelect = true,
            options = listOf(
                AskUserOption("写失败用例并注明原因", "保留红灯，方便主会话定位"),
                AskUserOption("在 tests/ 下加临时桩", "让其余用例先跑通"),
            ),
        ),
    ),
)

private val EnglishQuestion = AskUserRequest(
    listOf(
        AskUserQuestion(
            header = "Framework",
            question = "Which test framework should new cases use?",
            options = listOf(
                AskUserOption("JUnit 5 (Recommended)", "Matches the newer code in this module"),
                AskUserOption("JUnit 4", "Matches the old cases; no dependency changes"),
            ),
        ),
    ),
)

@Composable
private fun AskFrame(dark: Boolean = true, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

@PreviewTest
@Preview(name = "提问卡 两题 待回答与输入栏 深中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Composable
fun askUserCardDarkPreview() = AskFrame {
    AskUserCard(TwoQuestions, onSubmit = {}, onSkip = {}, stateKey = "preview")
    ChatInputBar(text = "", onTextChange = {}, onSend = {}, answeringQuestion = true, modelName = "Opus 5.5")
}

@PreviewTest
@Preview(name = "提问卡 已答与跳过摘要 浅中411", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Composable
fun askUserAnsweredLightPreview() = AskFrame(dark = false) {
    AskUserAnsweredCard(AskUserAnswered(TwoQuestions, AskUserAnswer(mapOf("测试框架" to listOf("JUnit 5（推荐）"), "处理范围" to listOf("写失败用例并注明原因", "在 tests/ 下加临时桩")))))
    AskUserAnsweredCard(AskUserAnswered(TwoQuestions, AskUserAnswer(other = "先别写测试，等我把接口定下来")))
    AskUserAnsweredCard(AskUserAnswered(TwoQuestions, AskUserAnswer(skipped = true)))
}

@PreviewTest
@Preview(name = "Ask card English 360dp font130", widthDp = 360, heightDp = 700, locale = "en", fontScale = 1.3f)
@Composable
fun askUserCardEnglishLargeFontPreview() = AskFrame {
    AskUserCard(EnglishQuestion, onSubmit = {}, onSkip = {}, stateKey = "preview-en")
}
