package com.promenar.nexara.data.backup

import com.promenar.nexara.data.security.SecretId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

enum class BackupContent {
    DATABASE,
    PREFERENCES,
    FILES,
    SECRETS,
}

data class BackupOptions(
    val content: Set<BackupContent>,
    val includeSecrets: Boolean = false,
    val password: CharArray? = null,
)

data class BackupSnapshot(
    val database: ByteArray = ByteArray(0),
    val preferences: ByteArray = ByteArray(0),
    val files: Map<String, ByteArray> = emptyMap(),
    val secrets: Map<SecretId, ByteArray> = emptyMap(),
    val databaseSchemaVersion: Int,
    val appVersion: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class ValidatedBackup(
    val manifest: BackupManifest,
    val database: ByteArray = ByteArray(0),
    val preferences: ByteArray = ByteArray(0),
    val files: Map<String, ByteArray> = emptyMap(),
    val secrets: Map<SecretId, ByteArray> = emptyMap(),
) : AutoCloseable {
    private val consumed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    internal fun beginConsumption() {
        if (!consumed.compareAndSet(false, true)) throw BackupValidationException("ValidatedBackup 已被消费")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        database.fill(0)
        preferences.fill(0)
        files.values.forEach { it.fill(0) }
        secrets.values.forEach { it.fill(0) }
    }
}

@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val databaseSchemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val entries: List<BackupManifestEntry>,
    val encrypted: Boolean,
    val containsSecrets: Boolean,
    val kdf: BackupKdfMetadata? = null,
)

@Serializable
data class BackupManifestEntry(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
data class BackupKdfMetadata(
    val algorithm: String,
    val saltBase64: String,
    val iterations: Int,
    val keySizeBits: Int,
)

@Serializable
internal data class SecretBackupEntry(
    val id: String,
    val valueBase64: String,
)

object BackupPackageLimits {
    const val MAX_TOTAL_BYTES: Long = 512L * 1024 * 1024
    const val MAX_ENTRY_BYTES: Long = 128L * 1024 * 1024
    const val MAX_ENTRIES: Int = 10_000
    internal const val MAX_MANIFEST_BYTES: Long = 4L * 1024 * 1024
    // 当前 API 同时持有 JSON AST/String/UTF-8/文件/codec 副本；按最坏约 8 倍放大将峰值压在 32 MiB 内。
    // 512 MiB 只保留为归档协议上限，不代表 Android 物化 datasource 可接受该体积。
    const val MAX_IN_MEMORY_BYTES: Long = 4L * 1024 * 1024
}

class BackupValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

interface BackupDataSource {
    suspend fun snapshot(content: Set<BackupContent>): BackupSnapshot
    suspend fun restore(validated: ValidatedBackup)
    suspend fun recoverInterruptedRestore()
}

@Serializable
data class BackupPreferenceSnapshot(
    val entries: List<BackupPreferenceEntry>,
    val providerIds: Set<String>,
)

@Serializable
data class BackupPreferenceEntry(
    val namespace: String,
    val key: String,
    val value: String,
)

@Serializable
internal data class DatabaseBackupPayload(
    val schemaVersion: Int,
    val tables: Map<String, List<JsonObject>>,
)
