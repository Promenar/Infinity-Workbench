package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

/** 触摸锁入口：手机在运行坞，宽窗口没有运行坞时在检视器卡片顶栏与边缘条。 */
class TouchLockEntryContractTest {
    private val screen = Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt")).toString(Charsets.UTF_8)

    @Test
    fun `宽窗口检视器的卡片与边缘条在生成中都提供触摸锁`() {
        val entry = "onLockTouch = actions.onLockTouch.takeIf { uiState.isGenerating }"
        val card = screen.substringAfter("ChatInspectorCard(").substringBefore(") {")
        val strip = screen.substringAfter("ChatInspectorStrip(").substringBefore("\n                        )")
        assertThat(card).contains(entry)
        assertThat(strip).contains(entry)
        assertThat(screen).contains("onLockTouch = actions.onLockTouch.takeIf { uiState.isGenerating }")
    }
}
