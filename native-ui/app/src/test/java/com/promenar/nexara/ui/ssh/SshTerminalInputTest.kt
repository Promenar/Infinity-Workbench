package com.promenar.nexara.ui.ssh

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class SshTerminalInputTest {
    @Test
    fun `Ctrl组合转换ASCII字符但保留中文输入`() {
        assertThat(TerminalInput.encode("c", control = true)).isEqualTo("\u0003")
        assertThat(TerminalInput.encode("C", control = true)).isEqualTo("\u0003")
        assertThat(TerminalInput.encode("[", control = true)).isEqualTo("\u001B")
        assertThat(TerminalInput.encode(" ", control = true)).isEqualTo("\u0000")
        assertThat(TerminalInput.encode("?", control = true)).isEqualTo("\u007F")
        assertThat(TerminalInput.encode("中", control = true)).isEqualTo("中")
    }

    @Test
    fun `软键盘换行与Alt前缀按终端协议编码`() {
        assertThat(TerminalInput.encode("a\nb\r\nc")).isEqualTo("a\rb\rc")
        assertThat(TerminalInput.encode("x", alt = true)).isEqualTo("\u001Bx")
        assertThat(TerminalInput.encode("d", control = true, alt = true)).isEqualTo("\u001B\u0004")
    }

    @Test
    fun `方向键遵循远端应用光标模式`() {
        assertThat(TerminalInput.arrow('A', false)).isEqualTo("\u001B[A")
        assertThat(TerminalInput.arrow('D', true)).isEqualTo("\u001BOD")
    }

    @Test
    fun `终端尺寸按可用像素计算并限制最小和最大分配`() {
        assertThat(TerminalInput.grid(800, 480, 10f, 20)).isEqualTo(TerminalGrid(80, 24))
        assertThat(TerminalInput.grid(0, 0, 10f, 20)).isEqualTo(TerminalGrid(2, 2))
        assertThat(TerminalInput.grid(Int.MAX_VALUE, Int.MAX_VALUE, 1f, 1))
            .isEqualTo(TerminalGrid(320, 200))
    }

    @Test
    fun `私钥文档读取允许边界大小并完整保留字节`() {
        val bytes = ByteArray(65_536) { (it % 251).toByte() }
        assertThat(SshPrivateKeyInput.read(ByteArrayInputStream(bytes))).isEqualTo(bytes)
    }

    @Test
    fun `私钥文档超限或空文件只返回安全错误代码`() {
        val tooLarge = assertThrows(SshInputException::class.java) {
            SshPrivateKeyInput.read(ByteArrayInputStream(ByteArray(65_537)))
        }
        val empty = assertThrows(SshInputException::class.java) {
            SshPrivateKeyInput.read(ByteArrayInputStream(byteArrayOf()))
        }

        assertThat(tooLarge.message).isEqualTo("SSH_KEY_TOO_LARGE")
        assertThat(empty.message).isEqualTo("SSH_KEY_EMPTY")
    }
}
