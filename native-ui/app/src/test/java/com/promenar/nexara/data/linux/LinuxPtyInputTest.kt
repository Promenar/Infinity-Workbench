package com.promenar.nexara.data.linux

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxPtyInputTest {
    @Test fun `长输入完整保留UTF8内容并清除临时缓冲`() {
        val input = "中文😀".repeat(900)
        val output = ByteArrayOutputStream()
        var buffer: ByteArray? = null
        writeLinuxPtyInput(input, { true }) { bytes, offset, count ->
            buffer = bytes
            output.write(bytes, offset, count)
        }
        assertThat(output.toString(Charsets.UTF_8.name())).isEqualTo(input)
        assertThat(buffer!!.all { it == 0.toByte() }).isTrue()
    }

    @Test fun `写入前已退出不能把未提交输入报告为成功`() {
        assertThrows(IllegalStateException::class.java) {
            writeLinuxPtyInput("echo hello", { false }) { _, _, _ -> error("不能写入") }
        }
    }

    @Test fun `分块中途退出时拒绝剩余输入并清理缓冲`() {
        var running = true
        var count = 0
        var buffer: ByteArray? = null
        assertThrows(IllegalStateException::class.java) {
            writeLinuxPtyInput("x".repeat(9_000), { running }) { bytes, _, size ->
                buffer = bytes
                count += size
                running = false
            }
        }
        assertThat(count).isLessThan(9_000)
        assertThat(buffer!!.all { it == 0.toByte() }).isTrue()
    }

    @Test fun `完整提交后快速退出不误报失败`() {
        var running = true
        writeLinuxPtyInput("exit\r", { running }) { _, _, _ -> running = false }
        assertThat(running).isFalse()
    }

    @Test fun `写入器失败保持原异常并清除输入`() {
        val failure = IllegalStateException("write failed")
        var buffer: ByteArray? = null
        val actual = assertThrows(IllegalStateException::class.java) {
            writeLinuxPtyInput("echo hello", { true }) { bytes, _, _ -> buffer = bytes; throw failure }
        }
        assertThat(actual).isSameInstanceAs(failure)
        assertThat(buffer!!.all { it == 0.toByte() }).isTrue()
    }
}
