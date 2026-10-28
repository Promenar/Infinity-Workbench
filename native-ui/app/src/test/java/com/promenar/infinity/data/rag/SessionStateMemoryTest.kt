package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.VectorEntity
import org.junit.Test

class SessionStateMemoryTest {
    @Test fun `键按兼容规范化和所有空白折叠且不受语言环境影响`() {
        assertThat(normalizeStateKey("  ＵＳＥＲ\u00a0 Name\t", "  IDE\n Theme "))
            .isEqualTo("user name\u001fide theme")
    }

    @Test fun `状态行解析保护转义空值并拒绝损坏及非状态数据`() {
        val fact = parseStateRow(row("""{"type":"state","key":"user\u001ftheme","subject":"user","attribute":"theme","value":"dark\n\"blue\""}"""))!!
        assertThat(fact.value).isEqualTo("dark\n\"blue\"")
        assertThat(fact.occurredAt).isEqualTo(7)
        assertThat(parseStateRow(row("""{"type":"state","key":"user\u001ftheme","subject":"user","attribute":"theme","value":null}"""))!!.value).isNull()
        listOf("broken", "{}", "{\"type\":\"memory\"}", """{"type":"state","key":"k","subject":"u","attribute":"a","value":3}""")
            .forEach { assertThat(parseStateRow(row(it))).isNull() }
    }

    @Test fun `最新状态按消息时间覆盖并撤销且并列按行号决定后按时间排序`() {
        fun fact(id: String, key: String, time: Long, value: String?) = SessionStateFact(id, "s", key, "u", key, value, "text", "u", "a", time)
        val values = listOf(fact("a", "theme", 3, "light"), fact("b", "theme", 3, "dark"), fact("c", "theme", 2, "old"),
            fact("d", "plan", 1, "yes"), fact("e", "plan", 4, null), fact("f", "editor", 2, "vim"))
        assertThat(latestStates(values).map { it.rowId }).containsExactly("f", "b").inOrder()
    }

    @Test fun `模型输出容忍代码块和前后文字且逐项丢弃坏状态`() {
        val output = parseSessionStateOutput("""说明
            ```json
            {"event":"  chose a theme  ","states":[{"subject":"user","attribute":"theme","value":"dark"},{"subject":"user","attribute":"plan","value":null},{"subject":" ","attribute":"a","value":"x"},{"subject":"u","value":"x"},{"subject":3,"attribute":"a","value":"x"},{"subject":"u","attribute":"a","value":false}]}
            ```
            结束""")
        assertThat(output.event).isEqualTo("chose a theme")
        assertThat(output.states).hasSize(2)
        assertThat(output.states.last().value).isNull()
        assertThat(parseSessionStateOutput("{\"event\":\"  \",\"states\":[]}").event).isNull()
        assertThat(parseSessionStateOutput("{\"event\":null,\"states\":[]}").states).isEmpty()
    }

    @Test fun `非结果JSON不能当空结果清空旧记忆且状态最多八条`() {
        listOf("not json", "{}", "[]", "{\"event\":null,\"states\":{}}")
            .forEach { assertThat(runCatching { parseSessionStateOutput(it) }.isFailure).isTrue() }
        val states = (1..12).joinToString(",") { """{"subject":"user","attribute":"a$it","value":"v"}""" }
        assertThat(parseSessionStateOutput("""{"event":null,"states":[$states]}""").states).hasSize(8)
    }

    private fun row(metadata: String) = VectorEntity("row", sessionId = "s", content = "text", embedding = byteArrayOf(), metadata = metadata,
        startMessageId = "u", endMessageId = "a", createdAt = 7)
}
