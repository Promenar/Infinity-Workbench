package com.promenar.nexara

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ShareManifestContractTest {
    private val manifest = Files.readAllBytes(
        Path.of("app/src/main/AndroidManifest.xml")
    ).toString(Charsets.UTF_8)

    @Test
    fun `share filters expose only the importer whitelist for send and send multiple`() {
        val expected = listOf(
            "text/plain",
            "text/markdown",
            "text/csv",
            "application/json",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        )

        assertThat(manifest.contains("application/*")).isFalse()
        assertThat(manifest.contains("text/*")).isFalse()
        assertThat(Regex("android.intent.action.SEND\\\"").findAll(manifest).count()).isEqualTo(1)
        assertThat(Regex("android.intent.action.SEND_MULTIPLE\\\"").findAll(manifest).count()).isEqualTo(1)
        expected.forEach { mime ->
            assertThat(Regex("android:mimeType=\\\"${Regex.escape(mime)}\\\"").findAll(manifest).count())
                .isEqualTo(2)
        }
    }
}
