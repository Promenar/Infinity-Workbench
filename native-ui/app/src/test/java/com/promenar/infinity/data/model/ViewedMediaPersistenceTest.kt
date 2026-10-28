package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.media.MediaViewMode
import com.promenar.infinity.domain.media.ViewedMedia
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ViewedMediaPersistenceTest {
    @Test
    fun `查看描述经消息 files 映射后保留`() {
        val payload = """{"schema":"infinity-full-context-documents-v1","documents":[],"viewedMedia":[{"ref":{"path":"/media/片段.mp4","mimeType":"video/mp4","kind":"VIDEO","sizeBytes":42,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","width":null,"height":null,"durationMs":null},"mode":"KEYFRAMES","frameTimestampsSeconds":[1.25,3.75]}]}"""
        val entity = MessageEntity(
            id = "tool-view",
            sessionId = "session-1",
            role = "tool",
            content = "已查看关键帧",
            files = payload,
            createdAt = 1L,
        )

        val restored = entity.toDomain().toEntity(entity.sessionId)

        assertThat(json.parseToJsonElement(restored.files!!).jsonObject["viewedMedia"])
            .isEqualTo(json.parseToJsonElement(payload).jsonObject["viewedMedia"])
    }

    @Test
    fun `查看描述经 Message JSON 读写后保留`() {
        val payload = """{"id":"tool-view","role":"TOOL","content":"已查看图片","createdAt":1,"viewedMedia":[{"ref":{"path":"/media/a.png","mimeType":"image/png","kind":"IMAGE","sizeBytes":42,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","width":null,"height":null,"durationMs":null},"mode":"ORIGINAL","frameTimestampsSeconds":[]}]}"""

        val restored = json.encodeToString(Message.serializer(), json.decodeFromString(Message.serializer(), payload))

        assertThat(json.parseToJsonElement(restored).jsonObject["viewedMedia"])
            .isEqualTo(json.parseToJsonElement(payload).jsonObject["viewedMedia"])
    }

    @Test
    fun `旧消息和工具结果缺省查看描述且不增加 JSON 字段`() {
        val message = json.decodeFromString(
            Message.serializer(),
            """{"id":"old-message","role":"TOOL","content":"旧消息","createdAt":1}""",
        )
        val result = json.decodeFromString(
            ToolResult.serializer(),
            """{"id":"old-result","content":"旧结果"}""",
        )

        assertThat(message.viewedMedia).isNull()
        assertThat(result.viewedMedia).isNull()
        assertThat(json.encodeToString(Message.serializer(), message)).doesNotContain("viewedMedia")
        assertThat(json.encodeToString(ToolResult.serializer(), result)).doesNotContain("viewedMedia")
        assertThat(message.toEntity("session-1").files).isNull()
    }

    @Test
    fun `工具结果查看描述序列化后保持路径内容身份和帧时间戳`() {
        val result = ToolResult("view-result", "已查看", viewedMedia = listOf(viewedVideo()))

        val restored = json.decodeFromString(
            ToolResult.serializer(),
            json.encodeToString(ToolResult.serializer(), result),
        )

        assertThat(restored.viewedMedia).containsExactly(viewedVideo())
    }

    @Test
    fun `仅查看媒体的消息往返不会生成文档附件或历史载荷`() {
        val message = Message(
            id = "view-only",
            role = MessageRole.TOOL,
            content = "已查看",
            createdAt = 1L,
            viewedMedia = listOf(viewedVideo()),
        )

        val entity = message.toEntity("session-1")
        val restored = entity.toDomain()

        assertThat(restored).isEqualTo(message)
        assertThat(entity.files).doesNotContain("base64")
        assertThat(restored.userDocuments).isNull()
        assertThat(restored.legacyFilesPayload).isNull()
    }

    @Test
    fun `查看描述与完整文档和历史附件同时存在时全部保留`() {
        val document = MessageDocumentAttachment(
            id = "doc-1",
            name = "说明.md",
            mimeType = "text/markdown",
            content = "# 媒体说明\n正文",
            sizeBytes = 23L,
            sha256 = "document-hash",
            estimatedTokens = 5,
        )
        val message = Message(
            id = "mixed",
            role = MessageRole.TOOL,
            content = "已查看",
            userDocuments = listOf(document),
            legacyFilesPayload = """[{"uri":"legacy.pdf"}]""",
            legacyAttachmentsPayload = """[{"uri":"content://legacy"}]""",
            createdAt = 1L,
            viewedMedia = listOf(viewedVideo()),
        )

        val entity = message.toEntity("session-1")
        val restored = entity.toDomain()

        assertThat(restored).isEqualTo(message)
        assertThat(decodeTextDocumentAttachmentsOrNull(entity.files!!)).containsExactly(document)
        assertThat(restored.toEntity("session-1").files).isEqualTo(entity.files)
    }

    @Test
    fun `空查看列表与缺省列表保持区别`() {
        val message = Message(
            id = "empty-view",
            role = MessageRole.TOOL,
            content = "没有可查看条目",
            createdAt = 1L,
            viewedMedia = emptyList(),
        )

        assertThat(message.toEntity("session-1").toDomain()).isEqualTo(message)
    }

    @Test
    fun `旧 documents 格式保持原编码且没有查看描述`() {
        val payload = """{"schema":"infinity-full-context-documents-v1","documents":[]}"""
        val entity = legacyEntity(payload)

        val restored = entity.toDomain()

        assertThat(restored.userDocuments).isEmpty()
        assertThat(restored.viewedMedia).isNull()
        assertThat(restored.legacyFilesPayload).isNull()
        assertThat(restored.toEntity("session-1").files).isEqualTo(payload)
    }

    @Test
    fun `未知或损坏的历史 files 载荷原样保留`() {
        val legacyPayloads = listOf(
            """[{"uri":"legacy.pdf"}]""",
            """{"schema":"future-files-v2","documents":[]}""",
            """{"schema":"infinity-full-context-documents-v1"}""",
            """{"schema":"infinity-full-context-documents-v1","documents":[],"viewedMedia":"invalid"}""",
            "not-json",
        )

        legacyPayloads.forEach { payload ->
            val restored = legacyEntity(payload).toDomain()

            assertThat(restored.userDocuments).isNull()
            assertThat(restored.viewedMedia).isNull()
            assertThat(restored.legacyFilesPayload).isEqualTo(payload)
            assertThat(restored.toEntity("session-1").files).isEqualTo(payload)
        }
    }

    @Test
    fun `给历史载荷增加查看描述后原文不丢失`() {
        val payload = """[{"uri":"legacy.pdf"}]"""
        val message = legacyEntity(payload).toDomain().copy(viewedMedia = listOf(viewedVideo()))

        val restored = message.toEntity("session-1").toDomain()

        assertThat(restored).isEqualTo(message)
        assertThat(restored.legacyFilesPayload).isEqualTo(payload)
    }

    private fun legacyEntity(payload: String) = MessageEntity(
        id = "legacy",
        sessionId = "session-1",
        role = "tool",
        content = "旧消息",
        files = payload,
        createdAt = 1L,
    )

    private fun viewedVideo() = ViewedMedia(
        ref = MediaRef(
            path = "/media/片段.mp4",
            mimeType = "video/mp4",
            kind = MediaKind.VIDEO,
            sizeBytes = 42L,
            sha256 = "a".repeat(64),
            durationMs = 5_000L,
        ),
        mode = MediaViewMode.KEYFRAMES,
        frameTimestampsSeconds = listOf(1.25, 3.75),
    )
}
