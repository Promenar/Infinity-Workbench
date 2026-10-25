package com.promenar.nexara.data.linux

import com.promenar.nexara.domain.linux.LinuxLineEnding
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

internal object LinuxFileText {
    data class Analysis(val isBinary: Boolean, val encoding: String?, val lineEnding: LinuxLineEnding?, val lineCount: Int?)

    /** UTF-8 无效字节超过 1% 视为二进制；截断末尾的不完整字符不计入无效比例。 */
    fun analyze(bytes: ByteArray, truncated: Boolean): Analysis {
        val (encoding, bom) = when {
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> "UTF-8" to 3
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> "UTF-16LE" to 2
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> "UTF-16BE" to 2
            else -> "UTF-8" to 0
        }
        val input = ByteBuffer.wrap(bytes, bom, bytes.size - bom)
        val output = CharBuffer.allocate(bytes.size + 1)
        val decoder = Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        var invalid = 0
        while (true) {
            val result = decoder.decode(input, output, !truncated)
            if (!result.isError) break
            invalid += result.length()
            input.position(input.position() + result.length())
            output.put('\ufffd')
        }
        if (input.hasRemaining()) output.put('\ufffd')
        output.flip()
        val text = output.toString()
        if ('\u0000' in text || (invalid > 0 && (encoding != "UTF-8" || invalid.toDouble() / (bytes.size - bom).coerceAtLeast(1) > 0.01))) {
            return Analysis(true, null, null, null)
        }
        var lf = 0
        var cr = 0
        var crlf = 0
        var index = 0
        while (index < text.length) {
            when (text[index]) {
                '\r' -> if (index + 1 < text.length && text[index + 1] == '\n') { crlf++; index++ } else cr++
                '\n' -> lf++
            }
            index++
        }
        val kinds = listOf(lf, cr, crlf).count { it > 0 }
        val ending = when {
            kinds > 1 -> LinuxLineEnding.MIXED
            crlf > 0 -> LinuxLineEnding.CRLF
            cr > 0 -> LinuxLineEnding.CR
            lf > 0 -> LinuxLineEnding.LF
            else -> null
        }
        val lines = lf + cr + crlf + if (text.isNotEmpty() && text.last() != '\r' && text.last() != '\n') 1 else 0
        return Analysis(false, encoding, ending, lines)
    }
}
