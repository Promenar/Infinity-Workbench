package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolErrorPresentationTest {
    @Test
    fun `结构化失败结果取错误码退出码作标签并分出输出细节`() {
        val error = toolErrorPresentation(
            """工具执行失败：{"error_code":"COMMAND_FAILED","guidance":"检查命令参数","exit_code":2,"timed_out":true,"stderr":"No such file"}""",
        )
        assertThat(error.tags).containsExactly("COMMAND_FAILED", "exit 2", "timed_out").inOrder()
        assertThat(error.message).isEqualTo("检查命令参数")
        assertThat(error.details).isEqualTo("No such file")
    }

    @Test
    fun `结构化结果只有输出时说明为空`() {
        val error = toolErrorPresentation("""工具执行失败：{"exit_code":1,"stdout":"a","stderr":"b"}""")
        assertThat(error.tags).containsExactly("exit 1")
        assertThat(error.message).isEmpty()
        assertThat(error.details).isEqualTo("b\na")
    }

    @Test
    fun `纯文本中嵌入的服务端错误体提取状态码错误码与说明`() {
        // 旧版生图失败记录：重复前缀且 JSON 被截断。
        val error = toolErrorPresentation(
            "工具执行失败：Image generation failed: Image generation failed (503): " +
                """{"error":{"code":"model_not_found","message":"No available channel for model default::sensenova-u1.5-lite (request id: 2026""",
        )
        assertThat(error.tags).containsExactly("HTTP 503", "model_not_found").inOrder()
        assertThat(error.message).isEqualTo("No available channel for model default::sensenova-u1.5-lite (request id: 2026")
        assertThat(error.details).isNull()
    }

    @Test
    fun `HTTP摘要格式取状态码与错误码作标签说明只保留服务端原文`() {
        val error = toolErrorPresentation("工具执行失败：Image generation failed (HTTP 503 · model_not_found): No available channel")
        assertThat(error.tags).containsExactly("HTTP 503", "model_not_found").inOrder()
        assertThat(error.message).isEqualTo("No available channel")
        assertThat(toolErrorPresentation("Image generation failed (HTTP 500)").tags).containsExactly("HTTP 500")
        assertThat(toolErrorPresentation("Image generation failed (HTTP 500)").message).isEqualTo("Image generation failed")
    }

    @Test
    fun `普通纯文本只去掉显示前缀`() {
        val error = toolErrorPresentation("工具执行失败：目标授权已失效，已安全终止。")
        assertThat(error.tags).isEmpty()
        assertThat(error.message).isEqualTo("目标授权已失效，已安全终止。")
        assertThat(toolErrorPresentation("连接被重置 (code 104)").message).isEqualTo("连接被重置 (code 104)")
    }

    @Test
    fun `不认识的JSON对象按原文显示`() {
        val raw = """{"foo":"bar"}"""
        assertThat(toolErrorPresentation(raw).message).isEqualTo(raw)
        assertThat(toolErrorPresentation(raw).tags).isEmpty()
    }
}
