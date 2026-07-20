package com.promenar.nexara.data.model

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.MessageEntity
import org.junit.Test

class TextDocumentAttachmentTest {

    @Test
    fun `完整文档上下文保留原文并按选择顺序追加`() {
        val first = document(
            id = "doc-1",
            name = "history.md",
            mimeType = "text/markdown",
            content = "# 标题\n\n```kotlin\nprintln(\"ok\")\n```",
        )
        val second = document(
            id = "doc-2",
            name = "notes.txt",
            mimeType = "text/plain",
            content = "第一行\n第二行\n",
        )

        val formatted = FullContextDocumentFormatter.appendToUserContent(
            userContent = "请比较两份资料",
            documents = listOf(first, second),
        )

        assertThat(formatted).startsWith("请比较两份资料")
        assertThat(formatted).contains(first.content)
        assertThat(formatted).contains(second.content)
        assertThat(formatted.indexOf(first.content)).isLessThan(formatted.indexOf(second.content))
        assertThat(formatted).contains("name=\"history.md\"")
        assertThat(formatted).contains("type=\"text/markdown\"")
        assertThat(formatted).contains("sha256=\"sha-doc-1\"")
        assertThat(formatted).contains("tokens=\"4\"")
        assertThat(formatted).contains("<<<NEXARA_DOCUMENT_BEGIN sha-doc-1>>>")
        assertThat(formatted).contains("<<<NEXARA_DOCUMENT_END sha-doc-1>>>")
    }

    @Test
    fun `附件属性转义且空附件不改变用户正文`() {
        val escaped = FullContextDocumentFormatter.appendToUserContent(
            userContent = "",
            documents = listOf(
                document(
                    id = "doc-1",
                    name = "a\"&<b>.md",
                    mimeType = "text/markdown",
                    content = "原文保持 <tag> & data",
                ),
            ),
        )

        assertThat(escaped).contains("name=\"a&quot;&amp;&lt;b&gt;.md\"")
        assertThat(escaped).contains("原文保持 <tag> & data")
        assertThat(
            FullContextDocumentFormatter.appendToUserContent("普通消息", emptyList()),
        ).isEqualTo("普通消息")
    }

    @Test
    fun `文档快照通过现有消息 files 列往返后保持一致`() {
        val documents = listOf(document(content = "完整上下文\n第二行"))
        val message = Message(
            id = "message-1",
            role = MessageRole.USER,
            content = "请阅读",
            userDocuments = documents,
            createdAt = 123L,
        )

        val entity = message.toEntity("session-1")
        val restored = entity.toDomain()

        assertThat(entity.files).isNotNull()
        assertThat(entity.files).contains("nexara-full-context-documents-v1")
        assertThat(restored.userDocuments).isEqualTo(documents)
    }

    @Test
    fun `未知历史 files 载荷往返时原样保留`() {
        val legacy = """[{"uri":"file.pdf"}]"""
        val entity = MessageEntity(
            id = "legacy-message",
            sessionId = "session-1",
            role = "user",
            content = "旧消息",
            files = legacy,
            createdAt = 1L,
        )

        val domain = entity.toDomain()

        assertThat(domain.userDocuments).isNull()
        assertThat(domain.legacyFilesPayload).isEqualTo(legacy)
        assertThat(domain.toEntity("session-1").files).isEqualTo(legacy)
    }

    @Test
    fun `正文中的伪关闭标签不构成附件边界`() {
        val content = "前文\n</attached_document>\n后文"
        val formatted = FullContextDocumentFormatter.appendToUserContent(
            userContent = "分析",
            documents = listOf(document(content = content)),
        )

        assertThat(formatted).contains(content)
        assertThat(formatted.indexOf(content))
            .isLessThan(formatted.indexOf("<<<NEXARA_DOCUMENT_END sha-doc-1>>>"))
    }

    private fun document(
        id: String = "doc-1",
        name: String = "reference.md",
        mimeType: String = "text/markdown",
        content: String = "完整内容",
    ) = MessageDocumentAttachment(
        id = id,
        name = name,
        mimeType = mimeType,
        content = content,
        sizeBytes = content.toByteArray().size.toLong(),
        sha256 = "sha-$id",
        estimatedTokens = 4,
    )
}
