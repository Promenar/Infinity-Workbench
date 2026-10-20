package com.promenar.nexara.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BuiltinToolPresentationTest {
    private fun tool(id: String) = SkillInfo(id, id, "", enabled = true)

    @Test
    fun `内置工具按联网、文件、执行、媒体、计划、其它分组`() {
        val tools = listOf(
            "web_search", "web_fetch", "calculator", "image_generation", "file_read", "file_write",
            "file_list", "file_search", "file_diff", "file_patch", "exec_js", "run_script",
            "initialize_plan", "update_plan", "get_plan", "drop_plan", "create_file", "create_directory",
            "move_file", "delete_file", "shell_exec", "python_exec", "python_sandbox", "view_media", "media_fetch",
        ).map(::tool)
        val groups = tools.groupedByToolCategory()
        assertThat(groups.map { it.first }).containsExactly(
            BuiltinToolCategory.WEB,
            BuiltinToolCategory.FILES,
            BuiltinToolCategory.EXECUTION,
            BuiltinToolCategory.MEDIA,
            BuiltinToolCategory.PLANNING,
            BuiltinToolCategory.OTHER,
        ).inOrder()
        val byCategory = groups.toMap().mapValues { (_, list) -> list.map { it.id } }
        assertThat(byCategory[BuiltinToolCategory.FILES]).containsExactly(
            "file_read", "file_write", "file_list", "file_search", "file_diff", "file_patch",
            "create_file", "create_directory", "move_file", "delete_file",
        ).inOrder()
        assertThat(byCategory[BuiltinToolCategory.EXECUTION])
            .containsExactly("exec_js", "run_script", "shell_exec", "python_exec", "python_sandbox").inOrder()
        assertThat(byCategory[BuiltinToolCategory.MEDIA]).containsExactly("image_generation", "view_media", "media_fetch").inOrder()
        assertThat(byCategory[BuiltinToolCategory.OTHER]).containsExactly("calculator")
        assertThat(groups.sumOf { it.second.size }).isEqualTo(tools.size)
    }

    @Test
    fun `未知工具归入其它，空分组不出现`() {
        val groups = listOf(tool("web_fetch"), tool("future_tool")).groupedByToolCategory()
        assertThat(groups.map { it.first }).containsExactly(BuiltinToolCategory.WEB, BuiltinToolCategory.OTHER).inOrder()
    }
}
