package com.promenar.nexara.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.R
import com.promenar.nexara.data.skills.DiscoveredSkill
import com.promenar.nexara.data.skills.SkillScope
import com.promenar.nexara.data.skills.SkillStatus
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.ui.chat.command.SlashCommandAction
import com.promenar.nexara.ui.chat.command.SlashCommandCatalog
import com.promenar.nexara.ui.chat.command.SlashCommandContext
import com.promenar.nexara.ui.chat.command.SlashCommandController
import com.promenar.nexara.ui.chat.command.rememberBuiltinSlashCommands
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

/** 输入框斜杠命令：输入 `/` 弹出候选、按前缀过滤、点选技能填入、点选内置命令执行、无匹配提示。 */
class SlashCommandPaletteTest {
    @get:Rule
    val rule = createComposeRule()

    private val session = Session(id = "session-slash", agentId = "agent", title = "Slash", modelId = "provider/model")
    private var input by mutableStateOf("")
    private val performed = mutableListOf<SlashCommandAction>()

    private fun skill(name: String, scope: SkillScope = SkillScope.USER) = DiscoveredSkill(
        id = "${scope.name}:$name", scope = scope, name = name, description = "整理 $name 相关的材料",
        displayName = null, shortDescription = null,
        virtualDir = if (scope == SkillScope.USER) "/skills/$name" else "/.agents/skills/$name",
        files = emptyList(), status = SkillStatus.ENABLED, modifiedSinceConfirmed = false,
        allowImplicitInvocation = true, brokenReason = null, metadata = null,
    )

    private fun render(skills: List<DiscoveredSkill> = listOf(skill("weekly-report"), skill("pdf-tools", SkillScope.PROJECT))) {
        rule.setContent {
            NexaraTheme {
                val scope = rememberCoroutineScope()
                val builtins = rememberBuiltinSlashCommands()
                val controller = remember {
                    SlashCommandController(
                        scope = scope,
                        scanSkills = { skills },
                        context = { SlashCommandContext(hasMessages = true) },
                        perform = { performed += it },
                        block = { _, _ -> },
                        setInput = { input = it },
                    )
                }
                ChatScreenContent(
                    state = ChatScreenState(
                        uiState = ChatUiState(session = session),
                        inputText = input,
                        slashCommands = SlashCommandCatalog.build(builtins, skills),
                    ),
                    actions = ChatScreenActions(
                        onTextChange = { input = it },
                        onSlashCommandPicked = { controller.pick(it, input) },
                    ),
                )
            }
        }
    }

    @Test
    fun typingSlash_showsBuiltinAndSkillCommands() {
        render()
        rule.onNodeWithTag(UiTags.CHAT_SLASH_PALETTE).assertDoesNotExist()
        typeInInput("/")
        rule.onNodeWithTag(UiTags.CHAT_SLASH_PALETTE).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_SLASH_ITEM_PREFIX + "new").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_SLASH_ITEM_PREFIX + "rename").assertIsDisplayed()
    }

    @Test
    fun typingPrefix_filtersCommands() {
        render()
        typeInInput("/PDF")
        rule.onNodeWithTag(UiTags.CHAT_SLASH_ITEM_PREFIX + "pdf-tools").assertIsDisplayed()
        rule.onNodeWithText(resource(R.string.slash_source_workspace)).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_SLASH_ITEM_PREFIX + "rename").assertDoesNotExist()
        // 中文别名同样可以过滤。
        rule.onNodeWithTag(UiTags.CHAT_INPUT).performTextClearance()
        typeInInput("/导出")
        rule.onNodeWithTag(UiTags.CHAT_SLASH_ITEM_PREFIX + "export").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_SLASH_ITEM_PREFIX + "pdf-tools").assertDoesNotExist()
    }

    @Test
    fun pickingSkill_fillsCommandAndHidesPalette() {
        render()
        typeInInput("/week")
        rule.onNodeWithTag(UiTags.CHAT_SLASH_ITEM_PREFIX + "weekly-report").performClick()
        rule.runOnIdle { assertThat(input).isEqualTo("/weekly-report ") }
        rule.onNodeWithTag(UiTags.CHAT_SLASH_PALETTE).assertDoesNotExist()
        rule.runOnIdle { assertThat(performed).isEmpty() }
    }

    @Test
    fun pickingBuiltin_runsAndClearsInput() {
        render()
        typeInInput("/ren")
        rule.onNodeWithTag(UiTags.CHAT_SLASH_ITEM_PREFIX + "rename").performClick()
        rule.runOnIdle {
            assertThat(performed).containsExactly(SlashCommandAction.OpenRenameDialog)
            assertThat(input).isEmpty()
        }
        rule.onNodeWithTag(UiTags.CHAT_SLASH_PALETTE).assertDoesNotExist()
    }

    @Test
    fun noMatch_showsEmptyHint_andSpaceHidesPalette() {
        render()
        typeInInput("/zzz")
        rule.onNodeWithTag(UiTags.CHAT_SLASH_EMPTY).assertIsDisplayed()
        rule.onNodeWithText(resource(R.string.slash_palette_empty)).assertIsDisplayed()
        typeInInput(" ")
        rule.onNodeWithTag(UiTags.CHAT_SLASH_PALETTE).assertDoesNotExist()
    }

    /** 输入后收起软键盘：测试宿主窗口按平移方式避让键盘，候选列表顶部会被推出屏幕。 */
    private fun typeInInput(text: String) {
        rule.onNodeWithTag(UiTags.CHAT_INPUT).performTextInput(text)
        Espresso.closeSoftKeyboard()
    }

    private fun resource(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
}
