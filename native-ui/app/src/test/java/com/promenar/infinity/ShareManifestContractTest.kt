package com.promenar.infinity

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ShareManifestContractTest {
    @Test
    fun `分享过滤器各以通配类型接收任意格式`() {
        val manifest = Files.readAllBytes(Path.of("app/src/main/AndroidManifest.xml")).toString(Charsets.UTF_8)
        val filters = Regex("<intent-filter>[\\s\\S]*?</intent-filter>").findAll(manifest)
            .map { it.value }.filter { it.contains("android.intent.action.SEND") }.toList()
        assertThat(filters).hasSize(2)
        filters.forEach { filter ->
            assertThat(Regex("android:mimeType=\"([^\"]+)\"").findAll(filter).map { it.groupValues[1] }.toList())
                .containsExactly("*/*")
        }
    }
}
