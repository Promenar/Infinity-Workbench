package com.promenar.nexara.data.backup

import com.promenar.nexara.data.security.SecretId
import kotlinx.serialization.Serializable

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
)

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
}

data class BackupLimits(
    val maxTotalBytes: Long = BackupPackageLimits.MAX_TOTAL_BYTES,
    val maxEntryBytes: Long = BackupPackageLimits.MAX_ENTRY_BYTES,
    val maxEntries: Int = BackupPackageLimits.MAX_ENTRIES,
    val maxManifestBytes: Long = BackupPackageLimits.MAX_MANIFEST_BYTES,
) {
    init {
        require(maxTotalBytes >= 0 && maxEntryBytes >= 0 && maxEntries >= 0 && maxManifestBytes > 0)
        require(maxManifestBytes <= Int.MAX_VALUE)
    }
}

class BackupValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
