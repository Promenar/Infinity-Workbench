package com.promenar.nexara.data.script

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class ScriptOutputForwarderTest {
    @Test
    fun `一个窗口内的输出合并为一条消息，空窗口不发送`() {
        val sent = mutableListOf<String>()
        val forwarder = ScriptOutputForwarder(windowChars = 100) { sent.add(it) }

        forwarder.flush()
        assertThat(sent).isEmpty()

        forwarder.add(1, "a\n")
        forwarder.add(2, "err\n")
        forwarder.flush()
        val frames = Json.parseToJsonElement(sent.single()).jsonArray.map { it.jsonObject }
        assertThat(frames.map { it.getValue("fd").jsonPrimitive.int to it.getValue("d").jsonPrimitive.content })
            .containsExactly(1 to "a\n", 2 to "err\n").inOrder()
    }

    @Test
    fun `输出过快时只保留窗口尾部`() {
        val sent = mutableListOf<String>()
        val forwarder = ScriptOutputForwarder(windowChars = 4) { sent.add(it) }

        forwarder.add(1, "11")
        forwarder.add(1, "22")
        forwarder.add(1, "33")
        forwarder.add(1, "abcdefgh")
        forwarder.flush()

        val texts = Json.parseToJsonElement(sent.single()).jsonArray.map { it.jsonObject.getValue("d").jsonPrimitive.content }
        assertThat(texts).containsExactly("efgh")
    }

    @Test
    fun `相邻同一路输出合并成一段，交替输出的片段数有上限`() {
        val sent = mutableListOf<String>()
        val forwarder = ScriptOutputForwarder(windowChars = 10_000) { sent.add(it) }
        forwarder.add(1, "a"); forwarder.add(1, "b"); forwarder.add(2, "c")
        forwarder.flush()
        assertThat(Json.parseToJsonElement(sent.last()).jsonArray.map { it.jsonObject.getValue("d").jsonPrimitive.content })
            .containsExactly("ab", "c").inOrder()

        repeat(ScriptOutputForwarder.MAX_PIECES * 2) { forwarder.add(1 + it % 2, "x") }
        forwarder.flush()
        assertThat(Json.parseToJsonElement(sent.last()).jsonArray).hasSize(ScriptOutputForwarder.MAX_PIECES)
    }

    @Test
    fun `单段超出窗口时取尾部且不切开代理对`() {
        val sent = mutableListOf<String>()
        val forwarder = ScriptOutputForwarder(windowChars = 3) { sent.add(it) }
        forwarder.add(1, "a😀😀")
        forwarder.flush()
        assertThat(Json.parseToJsonElement(sent.single()).jsonArray.single().jsonObject.getValue("d").jsonPrimitive.content).isEqualTo("😀")
    }
}
