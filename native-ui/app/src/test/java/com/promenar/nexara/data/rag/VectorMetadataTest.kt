package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VectorMetadataTest {
    @Test
    fun `新metadata安全往返真实文件名`() {
        val metadata = documentVectorMetadata(
            fileUuid = "file-id",
            chunkIndex = 2,
            documentTitle = "用户的 \"发布\" 资料.txt",
        )

        assertThat(metadata).startsWith("{\"type\":\"document\"")
        assertThat(vectorMetadataType(metadata)).isEqualTo("document")
        assertThat(documentTitleFromVectorMetadata(metadata)).isEqualTo("用户的 \"发布\" 资料.txt")
    }

    @Test
    fun `旧metadata保持类型可读并回退无标题`() {
        val legacy = "{\"type\":\"document\",\"fileUuid\":\"legacy-id\",\"chunkIndex\":0}"

        assertThat(vectorMetadataType(legacy)).isEqualTo("document")
        assertThat(documentTitleFromVectorMetadata(legacy)).isNull()
        assertThat(documentReferenceSource(legacy, "fallback-document"))
            .isEqualTo("文档: legacy-i")
    }
}
