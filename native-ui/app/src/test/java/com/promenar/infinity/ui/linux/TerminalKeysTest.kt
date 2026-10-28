package com.promenar.infinity.ui.linux

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.linux.terminal.TerminalExtraKey
import com.promenar.infinity.ui.linux.terminal.TerminalKeyEncoder
import com.promenar.infinity.ui.linux.terminal.TerminalModifiers
import org.junit.Test

class TerminalKeysTest {
    private val esc = "\u001B"
    private val none = TerminalModifiers.NONE
    private val ctrl = TerminalModifiers(ctrl = true)
    private val alt = TerminalModifiers(alt = true)

    @Test
    fun `修饰键再按一次取消，可同时生效`() {
        val both = none.toggleCtrl().toggleAlt()

        assertThat(both).isEqualTo(TerminalModifiers(ctrl = true, alt = true))
        assertThat(both.toggleCtrl()).isEqualTo(alt)
        assertThat(none.any).isFalse()
        assertThat(alt.any).isTrue()
    }

    @Test
    fun `方向键遵循光标键应用模式`() {
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.UP, none, false)).isEqualTo("$esc[A")
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.LEFT, none, true)).isEqualTo("${esc}OD")
    }

    @Test
    fun `带修饰键的方向键使用 xterm 修饰参数`() {
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.RIGHT, ctrl, true)).isEqualTo("$esc[1;5C")
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.DOWN, alt, false)).isEqualTo("$esc[1;3B")
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.UP, TerminalModifiers(ctrl = true, alt = true), false)).isEqualTo("$esc[1;7A")
    }

    @Test
    fun `Esc 与 Tab`() {
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.ESC, none, false)).isEqualTo(esc)
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.ESC, alt, false)).isEqualTo(esc + esc)
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.TAB, none, false)).isEqualTo("\t")
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.TAB, alt, false)).isEqualTo("$esc\t")
    }

    @Test
    fun `符号键与修饰键`() {
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.SLASH, none, false)).isEqualTo("/")
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.SLASH, ctrl, false)).isEqualTo("\u001F")
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.SLASH, TerminalModifiers(ctrl = true, alt = true), false)).isEqualTo("$esc\u001F")
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.PIPE, none, false)).isEqualTo("|")
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.DASH, alt, false)).isEqualTo("$esc-")
        assertThat(TerminalKeyEncoder.encode(TerminalExtraKey.TILDE, none, false)).isEqualTo("~")
    }
}
