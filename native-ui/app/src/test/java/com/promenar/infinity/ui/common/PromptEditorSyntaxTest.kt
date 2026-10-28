package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PromptEditorSyntaxTest {
    private fun spans(text: String) = promptSyntaxSpans(text).map { Triple(it.kind, text.substring(it.start, it.end), it.start) }

    private fun PromptTextEdit.applyTo(text: String): Triple<String, Int, Int> =
        Triple(text.replaceRange(start, end, replacement), selectionStart, selectionEnd)

    @Test
    fun `标题整行高亮且井号标记单独弱化`() {
        val text = "# 角色\n正文\n### 输出格式"
        assertThat(spans(text)).containsExactly(
            Triple(PromptSyntaxKind.HEADING, "# 角色", 0),
            Triple(PromptSyntaxKind.MARKER, "# ", 0),
            Triple(PromptSyntaxKind.HEADING, "### 输出格式", 8),
            Triple(PromptSyntaxKind.MARKER, "### ", 8),
        ).inOrder()
    }

    @Test
    fun `没有空格的井号与七个井号不是标题`() {
        assertThat(spans("#标签\n####### 太深")).isEmpty()
    }

    @Test
    fun `无序有序列表与引用只标记符号本身`() {
        val text = "- 先给结论\n  * 嵌套\n12. 第十二条\n> 引用"
        assertThat(spans(text)).containsExactly(
            Triple(PromptSyntaxKind.MARKER, "-", 0),
            Triple(PromptSyntaxKind.MARKER, "*", 9),
            Triple(PromptSyntaxKind.MARKER, "12.", 14),
            Triple(PromptSyntaxKind.MARKER, ">", 23),
        ).inOrder()
    }

    @Test
    fun `行内代码与加粗分别标记，加粗两侧星号弱化，代码内的星号不算加粗`() {
        val text = "用 **结论先行**，命令写 `ls **/*.kt`"
        assertThat(spans(text)).containsExactly(
            Triple(PromptSyntaxKind.MARKER, "**", 2),
            Triple(PromptSyntaxKind.STRONG, "**结论先行**", 2),
            Triple(PromptSyntaxKind.MARKER, "**", 8),
            Triple(PromptSyntaxKind.CODE, "`ls **/*.kt`", 15),
        ).inOrder()
    }

    @Test
    fun `围栏代码块整段按代码处理且内部不再识别标题与列表`() {
        val text = "```bash\n# 注释\n- 不是列表\n```\n# 标题"
        val result = spans(text)
        assertThat(result.filter { it.first == PromptSyntaxKind.CODE }.map { it.second })
            .containsExactly("```bash", "# 注释", "- 不是列表", "```").inOrder()
        assertThat(result.filter { it.first == PromptSyntaxKind.HEADING }.map { it.second })
            .containsExactly("# 标题")
    }

    @Test
    fun `未闭合的星号不产生加粗`() {
        assertThat(spans("2 ** 3 等于 8")).isEmpty()
    }

    @Test
    fun `标题按钮在无标题到三级之间循环`() {
        var state = Triple("前文\n规则", 4, 4)
        val seen = mutableListOf<String>()
        repeat(4) {
            state = cycleHeading(state.first, state.second, state.third).applyTo(state.first)
            seen += state.first.substringAfter('\n')
        }
        assertThat(seen).containsExactly("# 规则", "## 规则", "### 规则", "规则").inOrder()
        assertThat(state.second).isEqualTo(4)
    }

    @Test
    fun `标题切换时光标跟随正文位置`() {
        val (text, start, end) = cycleHeading("规则说明", 2, 2).applyTo("规则说明")
        assertThat(text).isEqualTo("# 规则说明")
        assertThat(start to end).isEqualTo(4 to 4)
        val (back, caret, _) = cycleHeading("### 规则", 1, 1).applyTo("### 规则")
        assertThat(back).isEqualTo("规则")
        assertThat(caret).isEqualTo(0)
    }

    @Test
    fun `列表按钮给选中各行加符号并跳过空行`() {
        val text = "前文\n甲\n\n乙\n后文"
        val (result, start, end) = toggleListItems(text, 3, 7).applyTo(text)
        assertThat(result).isEqualTo("前文\n- 甲\n\n- 乙\n后文")
        assertThat(result.substring(start, end)).isEqualTo("- 甲\n\n- 乙")
    }

    @Test
    fun `所选各行都是列表时列表按钮移除符号`() {
        val text = "- 甲\n  - 乙"
        val (result, _, _) = toggleListItems(text, 0, text.length).applyTo(text)
        assertThat(result).isEqualTo("甲\n  乙")
    }

    @Test
    fun `空行上点列表按钮插入符号并把光标放到符号后`() {
        val (result, start, end) = toggleListItems("甲\n", 2, 2).applyTo("甲\n")
        assertThat(result).isEqualTo("甲\n- ")
        assertThat(start to end).isEqualTo(4 to 4)
    }

    @Test
    fun `加粗包裹选区并保持选中原文`() {
        val text = "先给结论再展开"
        val (result, start, end) = toggleInlineWrap(text, 2, 4, "**").applyTo(text)
        assertThat(result).isEqualTo("先给**结论**再展开")
        assertThat(result.substring(start, end)).isEqualTo("结论")
    }

    @Test
    fun `已加粗的选区再点一次取消加粗`() {
        val inner = toggleInlineWrap("先给**结论**再展开", 4, 6, "**").applyTo("先给**结论**再展开")
        assertThat(inner.first).isEqualTo("先给结论再展开")
        assertThat(inner.first.substring(inner.second, inner.third)).isEqualTo("结论")

        val outer = toggleInlineWrap("先给**结论**再展开", 2, 8, "**").applyTo("先给**结论**再展开")
        assertThat(outer.first).isEqualTo("先给结论再展开")
        assertThat(outer.first.substring(outer.second, outer.third)).isEqualTo("结论")
    }

    @Test
    fun `无选区时插入成对标记并把光标放在中间`() {
        val (result, start, end) = toggleInlineWrap("命令", 2, 2, "`").applyTo("命令")
        assertThat(result).isEqualTo("命令``")
        assertThat(start to end).isEqualTo(3 to 3)
        val (removed, caret, _) = toggleInlineWrap(result, 3, 3, "`").applyTo(result)
        assertThat(removed).isEqualTo("命令")
        assertThat(caret).isEqualTo(2)
    }

    @Test
    fun `宽窗口默认分栏，窄窗口只有编辑与预览，不预览时只剩编辑`() {
        assertThat(promptEditorViews(wide = true, previewAvailable = true))
            .containsExactly(PromptEditorView.EDIT, PromptEditorView.SPLIT, PromptEditorView.PREVIEW).inOrder()
        assertThat(promptEditorViews(wide = false, previewAvailable = true))
            .containsExactly(PromptEditorView.EDIT, PromptEditorView.PREVIEW).inOrder()
        assertThat(promptEditorViews(wide = true, previewAvailable = false)).containsExactly(PromptEditorView.EDIT)
        assertThat(defaultPromptEditorView(wide = true, previewAvailable = true)).isEqualTo(PromptEditorView.SPLIT)
        assertThat(defaultPromptEditorView(wide = false, previewAvailable = true)).isEqualTo(PromptEditorView.EDIT)
    }

    @Test
    fun `窗口变窄时分栏退回编辑，其余视图保持`() {
        assertThat(PromptEditorView.SPLIT.coerceTo(promptEditorViews(wide = false, previewAvailable = true)))
            .isEqualTo(PromptEditorView.EDIT)
        assertThat(PromptEditorView.PREVIEW.coerceTo(promptEditorViews(wide = false, previewAvailable = true)))
            .isEqualTo(PromptEditorView.PREVIEW)
    }
}
