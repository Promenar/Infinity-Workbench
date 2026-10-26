package com.promenar.nexara.data.linux

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LinuxExecTerminalTextTest {
    @Test fun `跨块颜色标题与回车覆盖只保留最终行且提示立即可读`() {
        val text = LinuxExecTerminalText(100)
        text.accept("\u001b[3")
        text.accept("1m正在下载 1%\u001b[0m\r")
        text.accept("完成\u001b]0;标题")
        text.accept("\u0007\r\n请输入：")
        assertThat(text.snapshot().stdout).isEqualTo("完成\n请输入：")
        assertThat(text.finish()).isEqualTo("请输入：")
    }
    @Test fun `无换行大输出受尾部上限约束并保留长度`() {
        val text = LinuxExecTerminalText(8)
        text.accept("123456789012")
        assertThat(text.snapshot().stdout).isEqualTo("56789012")
        assertThat(text.snapshot().stdoutOriginalLength).isEqualTo(12)
        assertThat(text.snapshot().stdoutTruncated).isTrue()
        text.accept("\rOK\n")
        assertThat(text.snapshot().stdout).isEqualTo("OK\n")
    }
}
