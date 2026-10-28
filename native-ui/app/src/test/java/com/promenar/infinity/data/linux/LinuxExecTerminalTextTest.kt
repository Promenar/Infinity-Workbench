package com.promenar.infinity.data.linux

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

    @Test fun `gh 启动时的背景色与光标位置查询得到答复且不进入正文`() {
        val text = LinuxExecTerminalText(200)
        text.accept("第一行\r\n\u001b]11;?\u001b")
        text.accept("\\\u001b[6n")
        assertThat(text.takeReplies()).isEqualTo("\u001b]11;rgb:0000/0000/0000\u001b\\\u001b[2;1R")
        assertThat(text.takeReplies()).isEmpty()
        assertThat(text.snapshot().stdout).isEqualTo("第一行\n")
    }
    @Test fun `光标列跟随未换行提示且行号限制在可见行内`() {
        val text = LinuxExecTerminalText(10_000, rows = 3, columns = 10)
        text.accept("a\nb\nc\nd\n? 继续\u001b[6n")
        assertThat(text.takeReplies()).isEqualTo("\u001b[3;5R")
        text.accept("\r\u001b[6n")
        assertThat(text.takeReplies()).isEqualTo("\u001b[3;1R")
    }
    @Test fun `设备状态属性尺寸与 BEL 结尾的颜色查询逐一答复，普通控制序列不答复`() {
        val text = LinuxExecTerminalText(100)
        text.accept("\u001b[5n\u001b[c\u001b[>c\u001b[18t\u001b]10;?\u0007\u001b[2K\u001b[?25l\u001b]0;标题\u0007")
        assertThat(text.takeReplies()).isEqualTo(
            "\u001b[0n\u001b[?1;2c\u001b[>0;10;0c\u001b[8;40;120t\u001b]10;rgb:ffff/ffff/ffff\u0007",
        )
    }
}
