package com.promenar.nexara.ui.rag

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test

class RagImportSafetyContractTest {
    @Test
    fun `RagViewModel不得自行无界读取或把二进制伪装成文本`() {
        val source = String(
            Files.readAllBytes(Path.of("app/src/main/java/com/promenar/nexara/ui/rag/RagViewModel.kt")),
            Charsets.UTF_8,
        )

        assertThat(source).doesNotContain(".readBytes()")
        assertThat(source).doesNotContain("Binary file:")
        assertThat(source).contains("SharedFileImporter")
        assertThat(source).contains("AndroidSafContentSource")
    }
}
