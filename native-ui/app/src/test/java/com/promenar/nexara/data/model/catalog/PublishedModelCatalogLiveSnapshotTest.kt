package com.promenar.nexara.data.model.catalog

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 显式提供本机下载的公开目录时，复核真实产物与 Kotlin 消费端契约。 */
class PublishedModelCatalogLiveSnapshotTest {
    @Test fun `真实公开产物可解析并匹配网关目标型号`() {
        val path = System.getenv("NEXARA_CATALOG_TEST_FILE")
        assumeTrue(!path.isNullOrBlank())
        val catalog = PublishedModelCatalog.fromJson(File(requireNotNull(path)).readText())
        assertThat(catalog.records).isNotEmpty()
        val resolver = ModelMetadataResolver(catalog.records + NEXARA_EXACT_MODEL_OVERRIDES.values)
        listOf(
            "newapi/deepseek-v4-flash",
            "newapi/gemini-3.8-flash",
            "newapi/sensenova-6.8-flash-lite",
            "openai-chatgpt/gpt-5.6-luna",
            "newapi/MiniMax-M3",
        ).forEach { wireId ->
            val result = resolver.resolve(wireId, ownedBy = if (wireId.startsWith("newapi/")) "NEWAPI" else "OpenAI ChatGPT")
            assertThat(result.remoteModelId).isEqualTo(wireId)
            assertThat(result.canonicalModelId).isNotNull()
            assertThat(result.diagnostics).doesNotContain("ambiguous_exact_match")
        }
        val sense = resolver.resolve("newapi/sensenova-6.8-flash-lite", ownedBy = "NEWAPI")
        assertThat(sense.contextTokens).isNull()
        assertThat(sense.outputTokens).isNull()
    }
}
