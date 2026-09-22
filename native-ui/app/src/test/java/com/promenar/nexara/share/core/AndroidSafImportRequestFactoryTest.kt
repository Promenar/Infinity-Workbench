package com.promenar.nexara.share.core

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.rag.DocumentReferenceExtractor
import org.junit.Test

class AndroidSafImportRequestFactoryTest {
    @Test
    fun `HTML声明与扩展名统一解析为text html`() {
        assertThat(AndroidSafImportRequestFactory.resolveMime("text/html; charset=UTF-8", "page.bin"))
            .isEqualTo("text/html")
        assertThat(AndroidSafImportRequestFactory.resolveMime("application/octet-stream", "page.html"))
            .isEqualTo("text/html")
        assertThat(AndroidSafImportRequestFactory.resolveMime(null, "PAGE.HTM"))
            .isEqualTo("text/html")
    }

    @Test
    fun `Word仅承诺DOCX而旧DOC保持不支持`() {
        assertThat(AndroidSafImportRequestFactory.resolveMime("application/msword", "legacy.doc"))
            .isEqualTo("application/msword")
        assertThat(AndroidSafImportRequestFactory.resolveMime(null, "legacy.doc"))
            .isEqualTo("application/octet-stream")
        assertThat(AndroidSafImportRequestFactory.resolveMime(null, "modern.docx"))
            .isEqualTo(DocumentReferenceExtractor.DOCX_MIME)
    }
}
