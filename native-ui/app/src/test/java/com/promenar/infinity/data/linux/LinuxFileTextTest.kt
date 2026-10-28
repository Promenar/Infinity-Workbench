package com.promenar.infinity.data.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.linux.LinuxLineEnding
import java.nio.charset.Charset
import org.junit.Test

class LinuxFileTextTest {
    @Test fun `识别UTF8与BOM并统计换行`() {
        val plain = LinuxFileText.analyze("中文\r\n第二行\r\n".toByteArray(), false)
        assertThat(plain.encoding).isEqualTo("UTF-8")
        assertThat(plain.lineEnding).isEqualTo(LinuxLineEnding.CRLF)
        assertThat(plain.lineCount).isEqualTo(2)
        assertThat(plain.isBinary).isFalse()
        val bom = LinuxFileText.analyze(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "a\nb".toByteArray(), false)
        assertThat(bom.encoding).isEqualTo("UTF-8")
        assertThat(bom.lineCount).isEqualTo(2)
    }

    @Test fun `UTF16两种BOM不会把编码内NUL误判二进制`() {
        listOf("UTF-16LE" to byteArrayOf(0xff.toByte(), 0xfe.toByte()), "UTF-16BE" to byteArrayOf(0xfe.toByte(), 0xff.toByte())).forEach { (encoding, bom) ->
            val result = LinuxFileText.analyze(bom + "a\rb".toByteArray(Charset.forName(encoding)), false)
            assertThat(result.encoding).isEqualTo(encoding)
            assertThat(result.isBinary).isFalse()
            assertThat(result.lineEnding).isEqualTo(LinuxLineEnding.CR)
            assertThat(result.lineCount).isEqualTo(2)
        }
    }

    @Test fun `空文本混合换行与结尾换行行数`() {
        assertThat(LinuxFileText.analyze(byteArrayOf(), false).lineCount).isEqualTo(0)
        assertThat(LinuxFileText.analyze("one".toByteArray(), false).lineEnding).isNull()
        val mixed = LinuxFileText.analyze("a\nb\rc\r\n".toByteArray(), false)
        assertThat(mixed.lineEnding).isEqualTo(LinuxLineEnding.MIXED)
        assertThat(mixed.lineCount).isEqualTo(3)
    }

    @Test fun `二进制未知编码与截断多字节字符`() {
        listOf(byteArrayOf(65, 0, 66), byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0), byteArrayOf(0x81.toByte(), 0xff.toByte())).forEach {
            val result = LinuxFileText.analyze(it, false)
            assertThat(result.isBinary).isTrue()
            assertThat(result.encoding).isNull()
            assertThat(result.lineCount).isNull()
        }
        val truncated = "a\n中".toByteArray().dropLast(1).toByteArray()
        val result = LinuxFileText.analyze(truncated, true)
        assertThat(result.isBinary).isFalse()
        assertThat(result.lineCount).isEqualTo(2)
        assertThat(LinuxFileText.analyze(truncated, false).isBinary).isTrue()
    }
}
