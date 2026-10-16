package com.promenar.nexara.ui.ssh

import java.io.InputStream
import java.io.IOException

data class TerminalGrid(val columns: Int, val rows: Int)

object TerminalInput {
    fun encode(text: String, control: Boolean = false, alt: Boolean = false): String {
        if (text.isEmpty()) return ""
        val normalized = text.replace("\r\n", "\n").replace('\n', '\r')
        val input = if (control) buildString {
            normalized.forEach { character ->
                append(when (character) {
                    in 'a'..'z', in '@'..'_' -> (character.code and 0x1F).toChar()
                    ' ' -> '\u0000'
                    '?' -> '\u007F'
                    else -> character
                })
            }
        } else normalized
        return if (alt) "\u001B$input" else input
    }

    fun arrow(direction: Char, applicationMode: Boolean): String =
        "\u001B${if (applicationMode) 'O' else '['}$direction"

    fun grid(width: Int, height: Int, cellWidth: Float, cellHeight: Int): TerminalGrid {
        if (!cellWidth.isFinite() || cellWidth <= 0f || cellHeight <= 0) return TerminalGrid(2, 2)
        return TerminalGrid(
            columns = (width.coerceAtLeast(0) / cellWidth).toInt().coerceIn(2, 320),
            rows = (height.coerceAtLeast(0) / cellHeight).coerceIn(2, 200),
        )
    }
}

class SshInputException(code: String) : Exception(code)

object SshPrivateKeyInput {
    fun read(input: InputStream): ByteArray {
        val limit = 65_536
        val buffer = ByteArray(limit + 1)
        try {
            var size = 0
            while (size < buffer.size) {
                val count = input.read(buffer, size, buffer.size - size)
                if (count < 0) break
                if (count == 0) {
                    val byte = input.read()
                    if (byte < 0) break
                    buffer[size++] = byte.toByte()
                } else size += count
            }
            if (size > limit) throw SshInputException("SSH_KEY_TOO_LARGE")
            if (size == 0) throw SshInputException("SSH_KEY_EMPTY")
            return buffer.copyOf(size)
        } catch (_: IOException) {
            throw SshInputException("SSH_KEY_READ_FAILED")
        } finally {
            buffer.fill(0)
        }
    }
}
