package com.promenar.infinity.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageQuote
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.toEntity
import com.promenar.infinity.data.repository.MessageRepository
import com.promenar.infinity.domain.model.Session
import com.promenar.infinity.domain.repository.ISessionRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class MessageQuoteExportTest {
    private val message = Message("u", MessageRole.USER, "正文", quotes = listOf(MessageQuote("a", "引文", "批注")))

    @Test
    fun `仓库到导出的文本和Markdown保留引用并放在正文前`() = runBlocking {
        val dao = mockk<MessageDao> {
            every { observeBySession("s") } returns flowOf(listOf(message.toEntity("s")))
        }
        val sessions = mockk<ISessionRepository> {
            every { observeById("s") } returns flowOf(Session("s", "agent", "引用导出", "model", createdAt = 1))
        }
        val exporter = ExportSessionUseCase(MessageRepository(dao), sessions)

        ExportSessionUseCase.Format.entries.forEach { format ->
            assertThat(exporter.export("s", format).content).contains("> 引文\n\n批注\n\n正文")
        }
    }

    @Test
    fun `结构化消息序列化以字段保存引用来源引文和批注`() {
        val serialized = Json.parseToJsonElement(Json.encodeToString(message)).jsonObject
        val quote = serialized.getValue("quotes").jsonArray.single().jsonObject
        assertThat(quote.getValue("sourceMessageId").jsonPrimitive.content).isEqualTo("a")
        assertThat(quote.getValue("text").jsonPrimitive.content).isEqualTo("引文")
        assertThat(quote.getValue("annotation").jsonPrimitive.content).isEqualTo("批注")
        assertThat(serialized.getValue("content").jsonPrimitive.content).isEqualTo("正文")
    }
}
