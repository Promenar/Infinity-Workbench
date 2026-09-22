package com.promenar.nexara.data.model.catalog

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelCatalogUpdateTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val verifier = CatalogEnvelopeVerifier(mapOf("test" to keys.public.encoded))
    private val catalog = """{"schemaVersion":3,"generatedAt":"2026-09-22T00:00:00Z","sources":[],"records":[{"canonicalModelId":"test/a","displayName":"A","source":"MODELS_DEV"}]}""".toByteArray()

    private fun envelope(version: Long = 10, data: ByteArray = catalog, file: String? = null): ByteArray {
        val hash = catalogSha256(data)
        val payload = Json.encodeToString(CatalogManifest(1, version, "2026-09-22T00:00:00Z", file ?: "catalog-$hash.json", data.size, hash, 1)).toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(keys.private); update(payload) }.sign()
        return Json.encodeToString(CatalogEnvelope("test", Base64.getEncoder().encodeToString(payload), Base64.getEncoder().encodeToString(signature))).toByteArray()
    }

    @Test fun `有效签名和哈希才接受目录`() {
        val verified = verifier.verify(envelope(), catalog)
        assertThat(verified.manifest.catalogVersion).isEqualTo(10)
        assertThat(verified.catalog.records).hasSize(1)
    }

    @Test fun `篡改签名未知公钥和内容哈希均拒绝`() {
        val original = Json.decodeFromString<CatalogEnvelope>(envelope().decodeToString())
        val bad = original.copy(signature = Base64.getEncoder().encodeToString(ByteArray(72)))
        assertThrows(Exception::class.java) { verifier.verify(Json.encodeToString(bad).toByteArray(), catalog) }
        assertThrows(Exception::class.java) { verifier.verify(Json.encodeToString(original.copy(keyId = "remote-key")).toByteArray(), catalog) }
        assertThrows(Exception::class.java) { verifier.verify(envelope(), catalog + 32) }
    }

    @Test fun `签名合法也不能指定路径逃逸或超大信封`() {
        assertThrows(Exception::class.java) { verifier.verify(envelope(file = "../catalog.json"), catalog) }
        assertThrows(Exception::class.java) { verifier.readManifest(ByteArray(65 * 1024)) }
    }

    @Test fun `缓存拒绝旧版本和同版本不同内容并保持有效内容`() {
        val root = Files.createTempDirectory("catalog-test").toFile()
        try {
            val cache = ModelCatalogCache(root, verifier)
            cache.install(verifier.verify(envelope(20), catalog))
            assertThrows(Exception::class.java) { cache.install(verifier.verify(envelope(19), catalog)) }
            val changed = catalog.decodeToString().replace("\"A\"", "\"B\"").toByteArray()
            assertThrows(Exception::class.java) { cache.install(verifier.verify(envelope(20, changed), changed)) }
            assertThat(ModelCatalogCache(root, verifier).load()?.manifest?.catalogVersion).isEqualTo(20)
            cache.install(verifier.verify(envelope(20), catalog))
        } finally { root.deleteRecursively() }
    }

    @Test fun `提交指针失败时重启保留上一有效版本`() {
        val root = Files.createTempDirectory("catalog-test").toFile()
        try {
            ModelCatalogCache(root, verifier).install(verifier.verify(envelope(20), catalog))
            val failing = ModelCatalogCache(root, verifier, beforePointerCommit = { error("模拟磁盘故障") })
            assertThrows(Exception::class.java) { failing.install(verifier.verify(envelope(21), catalog)) }
            assertThat(ModelCatalogCache(root, verifier).load()?.manifest?.catalogVersion).isEqualTo(20)
        } finally { root.deleteRecursively() }
    }

    @Test fun `丢失或损坏指针从验签目录恢复且拒绝回放`() {
        for (missing in listOf(true, false)) {
            val root = Files.createTempDirectory("catalog-test").toFile()
            try {
                val cache = ModelCatalogCache(root, verifier)
                cache.install(verifier.verify(envelope(20), catalog))
                cache.install(verifier.verify(envelope(21), catalog))
                if (missing) root.resolve("state.json").delete() else root.resolve("state.json").writeText("{")
                assertThat(cache.load()?.manifest?.catalogVersion).isEqualTo(21)
                assertThrows(Exception::class.java) { cache.install(verifier.verify(envelope(19), catalog)) }
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun `投影失败显示已提交版本且重试能够恢复`() = runTest {
        val root = Files.createTempDirectory("catalog-test").toFile()
        try {
            var failProjection = true
            var applied = false
            val updater = ModelCatalogUpdater(
                ModelCatalogCache(root, verifier), verifier,
                CatalogTransport { file, _ -> if (file == "manifest.json") envelope() else catalog },
                { 0 }, {}, { if (failProjection) error("投影失败") else applied = true },
            )
            updater.refresh()
            assertThat(updater.status.value.phase).isEqualTo(CatalogUpdatePhase.APPLY_FAILED)
            assertThat(updater.status.value.version).isEqualTo(10)
            assertThat(ModelCatalogCache(root, verifier).load()?.manifest?.catalogVersion).isEqualTo(10)
            failProjection = false
            updater.refresh(force = true)
            assertThat(applied).isTrue()
            assertThat(updater.status.value.phase).isEqualTo(CatalogUpdatePhase.CURRENT)
        } finally { root.deleteRecursively() }
    }

    @Test fun `当前缓存损坏回退上一版本但不降低版本高水位`() {
        val root = Files.createTempDirectory("catalog-test").toFile()
        try {
            val cache = ModelCatalogCache(root, verifier)
            cache.install(verifier.verify(envelope(20), catalog))
            cache.install(verifier.verify(envelope(21), catalog))
            root.resolve("v21-${catalogSha256(catalog)}/catalog.json").writeText("损坏")
            assertThat(cache.load()?.manifest?.catalogVersion).isEqualTo(20)
            assertThrows(Exception::class.java) { cache.install(verifier.verify(envelope(20), catalog)) }
        } finally { root.deleteRecursively() }
    }

    @Test fun `自动刷新一天一次且网络故障保留已加载目录`() = runTest {
        val root = Files.createTempDirectory("catalog-test").toFile()
        try {
            var now = 100_000L
            var checked = 0L
            var calls = 0
            var offline = false
            var installed = 0
            val updater = ModelCatalogUpdater(
                ModelCatalogCache(root, verifier), verifier,
                CatalogTransport { file, _ ->
                    calls++
                    if (offline) error("离线")
                    if (file == "manifest.json") envelope() else catalog
                },
                { checked }, { checked = it }, { installed++ }, { now },
            )
            updater.refresh()
            assertThat(calls).isEqualTo(2)
            assertThat(updater.status.value.phase).isEqualTo(CatalogUpdatePhase.CURRENT)
            updater.refresh()
            assertThat(calls).isEqualTo(2)
            offline = true
            now += 24 * 60 * 60 * 1000L
            updater.refresh()
            assertThat(updater.status.value.phase).isEqualTo(CatalogUpdatePhase.FAILED)
            assertThat(updater.status.value.version).isEqualTo(10)
            assertThat(installed).isEqualTo(1)
            updater.refresh()
            assertThat(calls).isEqualTo(3)
            updater.refresh(force = true)
            assertThat(calls).isEqualTo(4)
        } finally { root.deleteRecursively() }
    }

    @Test fun `重启在离线环境使用验签缓存`() = runTest {
        val root = Files.createTempDirectory("catalog-test").toFile()
        try {
            ModelCatalogCache(root, verifier).install(verifier.verify(envelope(), catalog))
            var installed = false
            val updater = ModelCatalogUpdater(
                ModelCatalogCache(root, verifier), verifier,
                CatalogTransport { _, _ -> error("离线") }, { 0 }, {}, { installed = true },
            )
            updater.refresh()
            assertThat(installed).isTrue()
            assertThat(updater.status.value.version).isEqualTo(10)
            assertThat(updater.status.value.phase).isEqualTo(CatalogUpdatePhase.FAILED)
        } finally { root.deleteRecursively() }
    }
}
