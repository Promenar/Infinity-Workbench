package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageQuoteMapperTest {
    @Test
    fun `空引用写成SQL空值且读取空数组也视为无引用`() {
        val entity = Message("u", MessageRole.USER, "正文", quotes = emptyList()).toEntity("s")
        assertThat(entity.quotes).isNull()
        assertThat(entity.copy(quotes = "[]").toDomain().quotes).isNull()
    }

    @Test
    fun `损坏或不兼容的引用JSON不影响其余消息字段`() {
        listOf("{", "{}", "[null]", "[{}]", "null").forEach { broken ->
            val entity = Message("u", MessageRole.USER, "正文", reasoning = "保留字段").toEntity("s")
            val restored = entity.copy(quotes = broken).toDomain()
            assertThat(restored.quotes).isNull()
            assertThat(restored.id).isEqualTo("u")
            assertThat(restored.content).isEqualTo("正文")
            assertThat(restored.reasoning).isEqualTo("保留字段")
        }
    }

    @Test
    fun `消息落库再读取保留多段引用与正文`() {
        val message = Message("u", MessageRole.USER, "正文", quotes = listOf(
            MessageQuote("a1", "第一行\n\n第三行", "批注"),
            MessageQuote("a2", "第二段", "另一个批注"),
        ))

        val restored = message.toEntity("s").toDomain()

        assertThat(restored.quotes).isEqualTo(message.quotes)
        assertThat(restored.content).isEqualTo("正文")
    }
}
