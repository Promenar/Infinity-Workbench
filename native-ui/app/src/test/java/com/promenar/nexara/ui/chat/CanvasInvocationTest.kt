package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ExecutionStep
import org.junit.Test

class CanvasInvocationTest {
    @Test fun `同轮同画布多次更新只保留最后一次预览避免堆积WebView`() {
        val references = canvasTimelineReferences(listOf(
            invocation("canvas_create", """{"canvasId":"a","title":"A","version":1}"""),
            invocation("canvas_create", """{"canvasId":"b","title":"B","version":1}"""),
            invocation("canvas_update", """{"canvasId":"a","title":"A","version":2}"""),
        ))
        assertThat(references.map { it.id }).containsExactly("b", "a").inOrder()
        assertThat(references.last().version).isEqualTo(2)
    }

    @Test fun `只从成功的创建更新结果提取画布身份和版本`() {
        val result = """{"canvasId":"a","title":"统计","version":3}"""
        assertThat(canvasInvocation(invocation("canvas_create", result))?.id).isEqualTo("a")
        assertThat(canvasInvocation(invocation("canvas_update", result))?.version).isEqualTo(3)
        assertThat(canvasInvocation(invocation("canvas_read", result))).isNull()
        assertThat(canvasInvocation(invocation("web_fetch", result))).isNull()
        assertThat(canvasInvocation(invocation("canvas_create", result, failed = true))).isNull()
        for (bad in listOf("broken", "{}", "{\"canvasId\":\"\",\"version\":3}", "{\"canvasId\":\"a\",\"version\":0}", "{\"canvasId\":\"a\",\"version\":\"3\"}")) {
            assertThat(canvasInvocation(invocation("canvas_create", bad))).isNull()
        }
    }

    private fun invocation(name: String, content: String, failed: Boolean = false) = ToolInvocationPresentation(listOf(
        ExecutionStep(id = "call", type = "tool_call", toolName = name),
        ExecutionStep(id = "result", type = if (failed) "error" else "tool_result", content = content),
    ))
}
