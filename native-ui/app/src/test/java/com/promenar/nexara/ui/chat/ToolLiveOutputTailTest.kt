package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolLiveOutputTailTest {
    @Test
    fun `只显示最后若干行，去掉末尾空行`() {
        val text = (1..20).joinToString("\n") { "line $it" } + "\n\n"
        assertThat(liveOutputTail(text, maxLines = 3)).isEqualTo("line 18\nline 19\nline 20")
    }

    @Test
    fun `回车刷新的进度行只留最后一次，CRLF 与 ANSI 颜色不影响显示`() {
        val text = "Downloading 10%\rDownloading 50%\rDownloading 100%\r\n\u001B[32mdone\u001B[0m\r\n"
        assertThat(liveOutputTail(text)).isEqualTo("Downloading 100%\ndone")
    }

    @Test
    fun `空输出为空`() {
        assertThat(liveOutputTail("")).isEmpty()
        assertThat(liveOutputTail("\n\n")).isEmpty()
    }
}
