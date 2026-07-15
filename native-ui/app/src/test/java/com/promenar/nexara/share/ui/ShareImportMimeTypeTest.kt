package com.promenar.nexara.share.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShareImportMimeTypeTest {
    @Test
    fun `已知MIME紧凑显示且未知值保持原样`() {
        assertThat(compactMimeType("APPLICATION/PDF")).isEqualTo("PDF")
        assertThat(
            compactMimeType(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ),
        ).isEqualTo("DOCX")
        assertThat(compactMimeType("text/plain")).isEqualTo("TXT")
        assertThat(compactMimeType("application/x-custom-format"))
            .isEqualTo("application/x-custom-format")
        assertThat(compactMimeType(null)).isNull()
    }
}
