package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class RagDetailsDesignContractTest {
    private val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }

    @Test
    fun `RAG 详情不再使用玻璃卡片微字号与硬编码状态色`() {
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/components/RagDetailsSheet.kt",
        ).readText()

        assertThat(source).doesNotContain("NexaraGlassCard")
        assertThat(source).doesNotContain("fontSize = 9.sp")
        assertThat(source).doesNotContain("fontSize = 10.sp")
        assertThat(source).doesNotContain("Color(0xFF4CAF50)")
        assertThat(source).doesNotContain("Color(0xFFF44336)")
    }

    @Test
    fun `RAG 会话入口不再使用霓虹轨道与装饰性无限动画`() {
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/ChatInlineComponents.kt",
        ).readText()
        val ragSource = source
            .substringAfter("fun RagProgressCard(")
            .substringBefore("\n@Composable\nfun ToolExecutionTimeline(")

        assertThat(ragSource).doesNotContain("NeonMicroRail")
        assertThat(ragSource).doesNotContain("rememberInfiniteTransition")
        assertThat(ragSource).doesNotContain("Brush.sweepGradient")
        assertThat(ragSource).doesNotContain("Color(0xFF00FF66)")
        assertThat(ragSource).doesNotContain("Color(0xFFB026FF)")
    }

    @Test
    fun `RAG 链接失败提示不声明未渲染的关闭动作`() {
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/components/RagDetailsSheet.kt",
        ).readText()

        assertThat(source).doesNotContain("withDismissAction = true")
    }

    @Test
    fun `RAG 链接打开保留协程取消语义`() {
        val source = moduleRoot.resolve(
            "src/main/java/com/promenar/nexara/ui/chat/components/RagDetailsSheet.kt",
        ).readText()

        assertThat(source).contains("catch (cancelled: CancellationException)")
        assertThat(source).contains("throw cancelled")
        assertThat(source).doesNotContain("runCatching { linkOpener(url) }")
    }
}
