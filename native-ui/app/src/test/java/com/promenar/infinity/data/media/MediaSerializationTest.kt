package com.promenar.infinity.data.media

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.media.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test

class MediaSerializationTest {
    private val media = listOf(MediaRef("/media/a.png", "image/png", MediaKind.IMAGE, 123, "a".repeat(64), 20, 30, 45))

    @Test fun `空媒体兼容启用默认字段的编码器和旧JSON`() {
        for (codec in listOf(Json, json)) {
            assertThat(codec.parseToJsonElement(codec.encodeToString(ToolResult.serializer(), ToolResult("id", "正文"))).jsonObject).doesNotContainKey("media")
            assertThat(codec.parseToJsonElement(codec.encodeToString(ExecutionStep.serializer(), ExecutionStep("id", "tool"))).jsonObject).doesNotContainKey("media")
            assertThat(codec.decodeFromString(ToolResult.serializer(), """{"id":"id","content":"正文"}""").media).isNull()
            assertThat(codec.decodeFromString(ExecutionStep.serializer(), """{"id":"id","type":"tool"}""").media).isNull()
        }
    }

    @Test fun `媒体字段完整往返且执行步骤经实际消息持久化转换器保存`() {
        val result = ToolResult("id", "正文", media = media)
        val step = ExecutionStep("step", "tool", timestamp = 1, media = media)
        assertThat(json.decodeFromString(ToolResult.serializer(), json.encodeToString(ToolResult.serializer(), result))).isEqualTo(result)
        assertThat(json.decodeFromString(ExecutionStep.serializer(), json.encodeToString(ExecutionStep.serializer(), step))).isEqualTo(step)
        val message = Message(id = "message", role = MessageRole.ASSISTANT, content = "正文", executionSteps = listOf(step))
        val entity = message.toEntity("session")
        assertThat(entity.executionSteps).contains("\"media\"")
        assertThat(entity.toDomain().executionSteps).containsExactly(step)
        val legacy = entity.copy(executionSteps = """[{"id":"old","type":"tool","timestamp":1}]""")
        assertThat(legacy.toDomain().executionSteps!!.single().media).isNull()
    }
}
