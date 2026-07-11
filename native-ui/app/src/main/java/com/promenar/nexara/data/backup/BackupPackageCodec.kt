package com.promenar.nexara.data.backup

import com.promenar.nexara.data.security.SecretCatalog
import com.promenar.nexara.data.security.SecretId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

interface BackupPackageCodec {
    fun encode(snapshot: BackupSnapshot, options: BackupOptions): ByteArray
    fun decode(bytes: ByteArray, password: CharArray?): ValidatedBackup
}

class DefaultBackupPackageCodec(
    private val crypto: BackupCrypto = BackupCrypto(),
) : BackupPackageCodec {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    override fun encode(snapshot: BackupSnapshot, options: BackupOptions): ByteArray {
        val includeSecrets = options.includeSecrets
        return try {
            if (includeSecrets && BackupContent.SECRETS !in options.content) {
                throw BackupValidationException("包含密钥时必须选择密钥内容")
            }
            if (includeSecrets && (options.password == null || options.password.isEmpty())) {
                throw BackupValidationException("包含密钥的备份必须设置非空密码")
            }

            val parameters = if (includeSecrets) crypto.newParameters() else null
            try {
                val entries = buildContentEntries(snapshot, options)
                try {
                    validateEncodeEntries(entries)
                    val manifest = BackupManifest(
                        formatVersion = FORMAT_VERSION,
                        databaseSchemaVersion = snapshot.databaseSchemaVersion,
                        appVersion = snapshot.appVersion,
                        createdAt = snapshot.createdAt,
                        entries = entries.map { (path, content) ->
                            BackupManifestEntry(path, content.size.toLong(), sha256(content))
                        },
                        encrypted = includeSecrets,
                        containsSecrets = includeSecrets && entries.any { it.first == SECRETS_PATH },
                        kdf = parameters?.let {
                            BackupKdfMetadata(
                                algorithm = BackupCrypto.KDF_ALGORITHM,
                                saltBase64 = Base64.getEncoder().encodeToString(it.salt),
                                iterations = BackupCrypto.PBKDF2_ITERATIONS,
                                keySizeBits = BackupCrypto.KEY_SIZE_BITS,
                            )
                        },
                    )
                    val zip = createZip(json.encodeToString(manifest).toByteArray(), entries)
                    if (!includeSecrets) return zip
                    try {
                        crypto.encrypt(zip, options.password!!, parameters!!)
                    } finally {
                        zip.fill(0)
                    }
                } finally {
                    entries.forEach { (_, content) -> content.fill(0) }
                }
            } finally {
                parameters?.salt?.fill(0)
                parameters?.iv?.fill(0)
            }
        } finally {
            if (includeSecrets) options.password?.fill('\u0000')
        }
    }

    override fun decode(bytes: ByteArray, password: CharArray?): ValidatedBackup {
        if (bytes.size.toLong() > MAX_INPUT_BYTES) {
            password?.fill('\u0000')
            throw BackupValidationException("备份包超过允许大小")
        }
        val encrypted = crypto.isEncrypted(bytes)
        if (encrypted && (password == null || password.isEmpty())) {
            password?.fill('\u0000')
            throw BackupValidationException("加密备份包需要密码")
        }
        val zipBytes = if (encrypted) {
            try {
                crypto.decrypt(bytes, password!!)
            } catch (error: GeneralSecurityException) {
                throw BackupValidationException("备份密码错误或备份包认证失败", error)
            }
        } else {
            password?.fill('\u0000')
            bytes
        }

        return try {
            val archive = readZip(zipBytes)
            try {
                validateArchive(archive, encrypted)
            } finally {
                archive.values.forEach { it.fill(0) }
            }
        } finally {
            if (encrypted) zipBytes.fill(0)
        }
    }

    private fun buildContentEntries(
        snapshot: BackupSnapshot,
        options: BackupOptions,
    ): List<Pair<String, ByteArray>> = buildList {
        if (BackupContent.DATABASE in options.content) add(DATABASE_PATH to snapshot.database.copyOf())
        if (BackupContent.PREFERENCES in options.content) add(PREFERENCES_PATH to snapshot.preferences.copyOf())
        if (BackupContent.FILES in options.content) {
            snapshot.files.toSortedMap().forEach { (id, content) ->
                requireSafeFileId(id)
                add("$FILES_PREFIX$id" to content.copyOf())
            }
        }
        if (options.includeSecrets && BackupContent.SECRETS in options.content) {
            val secrets = snapshot.secrets
                .filterKeys { it != SecretCatalog.automaticBackupPassword }
                .toSortedMap(compareBy(SecretId::value))
                .map { (id, value) -> SecretBackupEntry(id.value, Base64.getEncoder().encodeToString(value)) }
            add(SECRETS_PATH to json.encodeToString(secrets).toByteArray())
        }
    }

    private fun validateEncodeEntries(entries: List<Pair<String, ByteArray>>) {
        if (entries.size > BackupPackageLimits.MAX_ENTRIES) throw BackupValidationException("备份内容项过多")
        var total = 0L
        entries.forEach { (_, content) ->
            if (content.size.toLong() > BackupPackageLimits.MAX_ENTRY_BYTES) {
                throw BackupValidationException("备份内容项超过允许大小")
            }
            total += content.size
            if (total > BackupPackageLimits.MAX_TOTAL_BYTES) throw BackupValidationException("备份内容超过允许大小")
        }
    }

    private fun createZip(manifest: ByteArray, entries: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                writeZipEntry(zip, MANIFEST_PATH, manifest)
                entries.forEach { (path, content) -> writeZipEntry(zip, path, content) }
            }
        }.toByteArray()

    private fun writeZipEntry(zip: ZipOutputStream, path: String, content: ByteArray) {
        val entry = ZipEntry(path).apply { time = FIXED_ZIP_TIME }
        zip.putNextEntry(entry)
        zip.write(content)
        zip.closeEntry()
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) throw BackupValidationException("备份包不允许目录项")
                    validateArchivePath(entry.name)
                    if (entries.containsKey(entry.name)) throw BackupValidationException("备份包包含重复路径")
                    if (entries.size >= BackupPackageLimits.MAX_ENTRIES + 1) {
                        throw BackupValidationException("备份包内容项过多")
                    }
                    val entryLimit = if (entry.name == MANIFEST_PATH) {
                        BackupPackageLimits.MAX_MANIFEST_BYTES
                    } else BackupPackageLimits.MAX_ENTRY_BYTES
                    val content = readLimited(zip, entryLimit)
                    total += content.size
                    if (total > BackupPackageLimits.MAX_TOTAL_BYTES) {
                        throw BackupValidationException("备份包解压后超过允许大小")
                    }
                    entries[entry.name] = content
                    zip.closeEntry()
                }
            }
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: ZipException) {
            throw BackupValidationException("无效的 ZIP 备份包")
        } catch (error: IOException) {
            throw BackupValidationException("无法读取 ZIP 备份包")
        }
        if (entries.isEmpty()) throw BackupValidationException("空备份包")
        return entries
    }

    private fun validateArchive(archive: Map<String, ByteArray>, encryptedEnvelope: Boolean): ValidatedBackup {
        val manifestBytes = archive[MANIFEST_PATH] ?: throw BackupValidationException("备份包缺少 manifest")
        val manifest = try {
            json.decodeFromString<BackupManifest>(manifestBytes.toString(Charsets.UTF_8))
        } catch (error: SerializationException) {
            throw BackupValidationException("备份 manifest 无效")
        } catch (error: IllegalArgumentException) {
            throw BackupValidationException("备份 manifest 无效")
        }
        if (manifest.formatVersion != FORMAT_VERSION) throw BackupValidationException("不支持的备份格式版本")
        if (manifest.encrypted != encryptedEnvelope) throw BackupValidationException("备份加密元数据不一致")
        validateKdf(manifest, encryptedEnvelope)
        if (manifest.entries.size > BackupPackageLimits.MAX_ENTRIES) throw BackupValidationException("manifest 内容项过多")
        val declared = linkedMapOf<String, BackupManifestEntry>()
        var declaredTotal = 0L
        manifest.entries.forEach { entry ->
            validateArchivePath(entry.path)
            validateContentPath(entry.path)
            if (entry.path == MANIFEST_PATH || declared.put(entry.path, entry) != null) {
                throw BackupValidationException("manifest 包含重复或保留路径")
            }
            if (entry.sizeBytes < 0 || entry.sizeBytes > BackupPackageLimits.MAX_ENTRY_BYTES) {
                throw BackupValidationException("manifest 声明大小无效")
            }
            declaredTotal += entry.sizeBytes
            if (declaredTotal > BackupPackageLimits.MAX_TOTAL_BYTES) throw BackupValidationException("manifest 总大小无效")
            if (!SHA256_PATTERN.matches(entry.sha256)) throw BackupValidationException("manifest 摘要无效")
        }
        val actualPaths = archive.keys - MANIFEST_PATH
        if (actualPaths != declared.keys) throw BackupValidationException("备份内容与 manifest 不一致")
        declared.forEach { (path, metadata) ->
            val content = archive.getValue(path)
            if (content.size.toLong() != metadata.sizeBytes || sha256(content) != metadata.sha256) {
                throw BackupValidationException("备份内容完整性校验失败")
            }
        }

        val secrets = parseSecrets(archive[SECRETS_PATH])
        if (manifest.containsSecrets != (SECRETS_PATH in archive) || manifest.containsSecrets && !manifest.encrypted) {
            throw BackupValidationException("备份密钥元数据不一致")
        }
        return ValidatedBackup(
            manifest = manifest,
            database = archive[DATABASE_PATH]?.copyOf() ?: ByteArray(0),
            preferences = archive[PREFERENCES_PATH]?.copyOf() ?: ByteArray(0),
            files = archive.filterKeys { it.startsWith(FILES_PREFIX) }
                .mapKeys { (path, _) -> path.removePrefix(FILES_PREFIX) }
                .mapValues { (_, content) -> content.copyOf() },
            secrets = secrets,
        )
    }

    private fun parseSecrets(bytes: ByteArray?): Map<SecretId, ByteArray> {
        if (bytes == null) return emptyMap()
        val entries = try {
            json.decodeFromString<List<SecretBackupEntry>>(bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            throw BackupValidationException("备份密钥内容无效")
        }
        val result = linkedMapOf<SecretId, ByteArray>()
        entries.forEach { entry ->
            val id = SecretId(entry.id)
            if (id == SecretCatalog.automaticBackupPassword || result.containsKey(id)) {
                throw BackupValidationException("备份密钥目录无效")
            }
            result[id] = try {
                Base64.getDecoder().decode(entry.valueBase64)
            } catch (error: IllegalArgumentException) {
                throw BackupValidationException("备份密钥内容无效")
            }
        }
        return result
    }

    private fun validateKdf(manifest: BackupManifest, encrypted: Boolean) {
        if (!encrypted) {
            if (manifest.kdf != null) throw BackupValidationException("未加密备份不应包含 KDF 元数据")
            return
        }
        val kdf = manifest.kdf ?: throw BackupValidationException("加密备份缺少 KDF 元数据")
        if (kdf.algorithm != BackupCrypto.KDF_ALGORITHM ||
            kdf.iterations != BackupCrypto.PBKDF2_ITERATIONS ||
            kdf.keySizeBits != BackupCrypto.KEY_SIZE_BITS
        ) throw BackupValidationException("不支持的 KDF 参数")
        val salt = try { Base64.getDecoder().decode(kdf.saltBase64) } catch (_: IllegalArgumentException) { null }
        if (salt?.size != BackupCrypto.SALT_SIZE_BYTES) throw BackupValidationException("无效的 KDF salt")
        salt.fill(0)
    }

    private fun readLimited(input: ZipInputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw BackupValidationException("备份内容项解压后超过允许大小")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun validateArchivePath(path: String) {
        if (path.isBlank() || path.startsWith('/') || '\\' in path || ':' in path ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) throw BackupValidationException("备份包包含不安全路径")
    }

    private fun requireSafeFileId(id: String) {
        if (id.isBlank() || '/' in id || '\\' in id || id == "." || id == ".." || ':' in id) {
            throw BackupValidationException("文件标识不是安全的相对路径")
        }
    }

    private fun validateContentPath(path: String) {
        if (path == DATABASE_PATH || path == PREFERENCES_PATH || path == SECRETS_PATH) return
        if (path.startsWith(FILES_PREFIX)) {
            requireSafeFileId(path.removePrefix(FILES_PREFIX))
            return
        }
        throw BackupValidationException("备份包包含未知内容路径")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val FORMAT_VERSION = 1
        private const val MANIFEST_PATH = "manifest.json"
        private const val DATABASE_PATH = "database.json"
        private const val PREFERENCES_PATH = "preferences.json"
        private const val SECRETS_PATH = "secrets.json"
        private const val FILES_PREFIX = "files/"
        private const val FIXED_ZIP_TIME = 0L
        private const val MAX_INPUT_BYTES = BackupPackageLimits.MAX_TOTAL_BYTES + 64L * 1024 * 1024
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
