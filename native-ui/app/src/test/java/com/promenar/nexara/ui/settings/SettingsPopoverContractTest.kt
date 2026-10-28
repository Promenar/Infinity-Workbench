package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** 设置页的短单选在宽窗口贴着所在行弹出；编辑表单与组合选择仍为居中对话框。 */
class SettingsPopoverContractTest {
    private val sourceRoot = Path.of("app/src/main/java/com/promenar/nexara/ui")

    private fun source(relativePath: String): String = Files.readAllBytes(
        sourceRoot.resolve(relativePath),
    ).toString(Charsets.UTF_8)

    @Test
    fun `短单选面板带锚点并在点外部关闭后不被同一次点按重新打开`() {
        mapOf(
            "settings/ThemeScreen.kt" to "modesAnchor",
            "hub/UserSettingsHomeScreen.kt" to "languageAnchor",
            "settings/ProviderFormScreen.kt" to "anchor",
            "settings/ModelEditorSheet.kt" to "typePickerAnchor",
            "settings/ProviderApiSourceSection.kt" to "anchor",
            "settings/SkillsScreen.kt" to "depthAnchor",
            "rag/RetrievalSettingControls.kt" to "anchor",
            "rag/KnowledgeModelPicker.kt" to "anchor",
        ).forEach { (file, anchor) ->
            val text = source(file)
            assertWithMessage(file).that(text).contains("nexaraPopoverAnchor($anchor)")
            assertWithMessage(file).that(text).contains("$anchor.consumeRecentDismiss()")
            assertWithMessage(file).that(text).contains("NexaraPopoverSpec($anchor")
        }
    }

    @Test
    fun `模型类型弹出层与编辑器同处一个窗口`() {
        // 弹出层挂在调用处的窗口上：放在编辑器对话框之外会被对话框盖住，锚点坐标也不在同一窗口。
        val editor = source("settings/ModelEditorSheet.kt")
        val open = editor.indexOf("val editorContent: @Composable () -> Unit = {") +
            "val editorContent: @Composable () -> Unit = ".length
        val close = matchingBrace(editor, open)
        val picker = editor.indexOf("popover = NexaraPopoverSpec(typePickerAnchor)")
        assertWithMessage("类型面板应在 editorContent 内").that(picker).isGreaterThan(open)
        assertWithMessage("类型面板应在 editorContent 内").that(picker).isLessThan(close)
    }

    /** 与 [open] 处 `{` 配对的 `}` 位置（字符串模板中的花括号同样成对）。 */
    private fun matchingBrace(text: String, open: Int): Int {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        error("unbalanced")
    }

    @Test
    fun `组合选择与编辑表单不改为弹出层`() {
        listOf("hub/AgentMemoryPolicySection.kt", "settings/SshSettingsScreen.kt", "settings/BackupSettingsScreen.kt")
            .forEach { file -> assertWithMessage(file).that(source(file)).doesNotContain("NexaraPopoverSpec(") }
    }
}
