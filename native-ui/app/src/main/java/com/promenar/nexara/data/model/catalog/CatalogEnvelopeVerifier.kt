package com.promenar.nexara.data.model.catalog

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val MAX_CATALOG_BYTES = 16 * 1024 * 1024
internal const val MAX_MANIFEST_BYTES = 64 * 1024

@Serializable
internal data class CatalogEnvelope(val keyId: String, val payload: String, val signature: String)

@Serializable
internal data class CatalogManifest(
    val schemaVersion: Int,
    val catalogVersion: Long,
    val generatedAt: String,
    val catalogFile: String,
    val catalogBytes: Int,
    val catalogSha256: String,
    val recordCount: Int,
)

internal data class VerifiedCatalog(
    val manifest: CatalogManifest,
    val envelopeBytes: ByteArray,
    val catalogBytes: ByteArray,
    val catalog: PublishedModelCatalog,
)

internal class CatalogEnvelopeVerifier(publicKeys: Map<String, ByteArray>) {
    private val keys = publicKeys.mapValues { (_, der) ->
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der.copyOf()))
    }
    private val json = Json { ignoreUnknownKeys = false }

    fun readManifest(bytes: ByteArray): CatalogManifest {
        require(bytes.size in 1..MAX_MANIFEST_BYTES) { "目录信封大小异常" }
        val envelope = json.decodeFromString<CatalogEnvelope>(bytes.decodeToString(throwOnInvalidSequence = true))
        val key = requireNotNull(keys[envelope.keyId]) { "目录签名公钥未知" }
        val payload = Base64.getDecoder().decode(envelope.payload)
        val signature = Base64.getDecoder().decode(envelope.signature)
        require(payload.size in 1..MAX_MANIFEST_BYTES && signature.size in 8..80) { "目录签名格式异常" }
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(payload)
            verify(signature)
        }) { "目录签名无效" }
        return json.decodeFromString<CatalogManifest>(payload.decodeToString(throwOnInvalidSequence = true)).also {
            require(it.schemaVersion == 1 && it.catalogVersion > 0) { "目录协议版本无效" }
            require(it.catalogBytes in 1..MAX_CATALOG_BYTES && it.recordCount in 1..30_000) { "目录规模异常" }
            require(it.catalogSha256.matches(Regex("[a-f0-9]{64}"))) { "目录摘要无效" }
            require(it.catalogFile == "catalog-${it.catalogSha256}.json") { "目录文件名无效" }
            Instant.parse(it.generatedAt)
        }
    }

    fun verify(envelopeBytes: ByteArray, catalogBytes: ByteArray): VerifiedCatalog {
        val manifest = readManifest(envelopeBytes)
        require(catalogBytes.size == manifest.catalogBytes) { "目录长度不符" }
        require(catalogSha256(catalogBytes) == manifest.catalogSha256) { "目录摘要不符" }
        val parsed = PublishedModelCatalog.fromJson(catalogBytes.decodeToString(throwOnInvalidSequence = true))
        require(parsed.records.size == manifest.recordCount) { "目录记录数量不符" }
        return VerifiedCatalog(manifest, envelopeBytes.copyOf(), catalogBytes.copyOf(), parsed)
    }
}

internal fun catalogSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
