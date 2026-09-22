package com.promenar.nexara.data.model.catalog

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Test

class ModelCatalogDeviceTest {
    @Test
    fun platformSignatureAndAtomicCacheSurviveReopen() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = java.io.File(app.cacheDir, "catalog-device-${java.util.UUID.randomUUID()}")
        try {
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val bytes = """{"schemaVersion":3,"generatedAt":"2026-09-22T00:00:00Z","sources":[],"records":[{"canonicalModelId":"test/中文模型","displayName":"中文模型","source":"MODELS_DEV"}]}""".toByteArray()
            val hash = catalogSha256(bytes)
            val manifest = CatalogManifest(1, 20, "2026-09-22T00:00:00Z", "catalog-$hash.json", bytes.size, hash, 1)
            val payload = Json.encodeToString(manifest).toByteArray()
            val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(payload) }.sign()
            val envelope = Json.encodeToString(CatalogEnvelope("test", Base64.getEncoder().encodeToString(payload), Base64.getEncoder().encodeToString(sig))).toByteArray()
            val verifier = CatalogEnvelopeVerifier(mapOf("test" to keys.public.encoded))
            ModelCatalogCache(root, verifier).install(verifier.verify(envelope, bytes))
            val reopened = ModelCatalogCache(root, verifier).load()
            assertThat(reopened?.catalog?.records?.single()?.displayName).isEqualTo("中文模型")
            assertThat(reopened?.manifest?.catalogVersion).isEqualTo(20)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun livePagesUpdateVerifiesPinnedKeyAndReopensOffline() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nexaraCatalogLive") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        var installed = 0
        val updater = ModelCatalogUpdater.create(app) { installed = it.records.size }
        updater.refresh(force = true)
        assertThat(updater.status.value.phase).isEqualTo(CatalogUpdatePhase.CURRENT)
        assertThat(installed).isGreaterThan(0)
        assertThat(updater.status.value.cached).isTrue()
        // 新实例首先从磁盘恢复同一有效目录，日间检查节流避免再次请求。
        var reopened = 0
        val fresh = ModelCatalogUpdater.create(app) { reopened = it.records.size }
        fresh.refresh()
        assertThat(reopened).isEqualTo(installed)
        assertThat(fresh.status.value.version).isEqualTo(updater.status.value.version)
    }

    @Test
    fun gatewayModelListRetainsWireIdsAndResolvesOfficialMetadata() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nexaraCatalogGateway") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        var catalog: PublishedModelCatalog? = null
        val updater = ModelCatalogUpdater.create(app) { catalog = it }
        updater.refresh(force = true)
        assertThat(updater.status.value.phase).isEqualTo(CatalogUpdatePhase.CURRENT)
        val provider = com.promenar.nexara.data.remote.provider.LlmProvider.builder()
            .protocolType(com.promenar.nexara.data.remote.protocol.ProtocolType.Generic_OpenAI_Compat)
            .baseUrl("http://127.0.0.1:1337/v1/chat/completions")
            .apiKey("").model("newapi/deepseek-v4-flash").build()
        val descriptors = provider.listModelDescriptors()
        val resolver = ModelMetadataResolver(requireNotNull(catalog).records + NEXARA_EXACT_MODEL_OVERRIDES.values)
        for (id in listOf("newapi/deepseek-v4-flash", "newapi/gemini-3.8-flash", "newapi/sensenova-6.8-flash-lite",
            "openai-chatgpt/gpt-5.6-luna", "newapi/MiniMax-M3")) {
            val descriptor = descriptors.single { it.id == id }
            val resolved = resolver.resolve(id, sourceProviderId = descriptor.sourceProviderId,
                ownedBy = descriptor.ownedBy, providerMetadata = descriptor.metadata)
            assertThat(resolved.remoteModelId).isEqualTo(id)
            assertThat(resolved.canonicalModelId).isNotNull()
            assertThat(resolved.diagnostics).doesNotContain("ambiguous_exact_match")
        }
    }
}
