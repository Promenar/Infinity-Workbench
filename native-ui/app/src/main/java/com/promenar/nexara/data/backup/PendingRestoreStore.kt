package com.promenar.nexara.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class PendingRestorePhase { STAGED }

data class PendingRestoreMetadata(
    val txId: String,
    val packageSha256: ByteArray,
    val phase: PendingRestorePhase,
)

class PendingRestorePayload(
    val metadata: PendingRestoreMetadata,
    val packageBytes: ByteArray,
    val password: CharArray?,
) : AutoCloseable {
    override fun close() {
        packageBytes.fill(0)
        password?.fill('\u0000')
        metadata.packageSha256.fill(0)
    }
}

interface PendingRestoreStore {
    fun stage(packageBytes: ByteArray, password: CharArray?): PendingRestoreMetadata
    fun read(): PendingRestorePayload?
    fun clear(expectedTxId: String)
}

internal interface PendingRestoreCryptor {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray): ByteArray
}

class AndroidPendingRestoreStore internal constructor(
    private val atomicFile: AtomicFile,
    private val cryptor: PendingRestoreCryptor,
    private val txIdFactory: () -> String = { UUID.randomUUID().toString() },
) : PendingRestoreStore {
    constructor(context: Context) : this(
        AtomicFile(
            requireNotNull(ContextCompat.getNoBackupFilesDir(context.applicationContext))
                .resolve(FILE_NAME)
        ),
        AndroidKeystorePendingRestoreCryptor(),
    )

    override fun stage(packageBytes: ByteArray, password: CharArray?): PendingRestoreMetadata {
        requireBounded(packageBytes)
        val metadata = PendingRestoreMetadata(
            txId = txIdFactory().also(::requireTxId),
            packageSha256 = sha256(packageBytes),
            phase = PendingRestorePhase.STAGED,
        )
        val plain = encode(metadata, packageBytes, password)
        var encrypted = ByteArray(0)
        try {
            encrypted = cryptor.encrypt(plain)
            if (encrypted.size > MAX_FILE_BYTES) throw BackupValidationException("待恢复记录超过安全限制")
            val output = atomicFile.startWrite()
            try {
                output.write(encrypted)
                output.fd.sync()
                atomicFile.finishWrite(output)
            } catch (error: Throwable) {
                atomicFile.failWrite(output)
                throw error
            }
            return metadata.copy(packageSha256 = metadata.packageSha256.copyOf())
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Exception) {
            throw BackupValidationException("无法安全保存待恢复记录", error)
        } finally {
            plain.fill(0)
            encrypted.fill(0)
            metadata.packageSha256.fill(0)
        }
    }

    override fun read(): PendingRestorePayload? {
        if (!atomicFile.baseFile.exists()) return null
        val encrypted = try {
            atomicFile.openRead().use { input ->
                val declared = atomicFile.baseFile.length()
                if (declared <= 0 || declared > MAX_FILE_BYTES) throw BackupValidationException("待恢复记录大小无效")
                val output = WipeableByteArrayOutputStream(declared.toInt())
                val buffer = ByteArray(16 * 1024)
                val bytes = try {
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > MAX_FILE_BYTES) {
                            throw BackupValidationException("待恢复记录超过安全限制")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } finally {
                    buffer.fill(0)
                    output.wipe()
                }
                if (bytes.size.toLong() != declared) {
                    bytes.fill(0)
                    throw BackupValidationException("待恢复记录读取不完整")
                }
                bytes
            }
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Exception) {
            throw BackupValidationException("无法读取待恢复记录", error)
        }
        var plain = ByteArray(0)
        try {
            plain = cryptor.decrypt(encrypted)
            return decode(plain)
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Exception) {
            throw BackupValidationException("待恢复记录认证失败", error)
        } finally {
            encrypted.fill(0)
            plain.fill(0)
        }
    }

    override fun clear(expectedTxId: String) {
        requireTxId(expectedTxId)
        val existing = read() ?: return
        existing.use {
            if (it.metadata.txId != expectedTxId) throw BackupValidationException("待恢复事务身份不一致")
        }
        atomicFile.delete()
    }

    private fun encode(metadata: PendingRestoreMetadata, packageBytes: ByteArray, password: CharArray?): ByteArray {
        val tx = metadata.txId.toByteArray(Charsets.US_ASCII)
        val passwordBytes = encodePassword(password)
        return try {
            ByteBuffer.allocate(HEADER_BYTES + tx.size + packageBytes.size + passwordBytes.size)
                .putInt(MAGIC)
                .putInt(VERSION)
                .putInt(metadata.phase.ordinal)
                .putInt(tx.size)
                .putInt(packageBytes.size)
                .putInt(passwordBytes.size)
                .put(metadata.packageSha256)
                .put(tx)
                .put(packageBytes)
                .put(passwordBytes)
                .array()
        } finally {
            tx.fill(0)
            passwordBytes.fill(0)
        }
    }

    private fun decode(plain: ByteArray): PendingRestorePayload {
        try {
            val buffer = ByteBuffer.wrap(plain)
            if (buffer.remaining() < HEADER_BYTES || buffer.int != MAGIC || buffer.int != VERSION) {
                throw BackupValidationException("待恢复记录格式无效")
            }
            val phase = PendingRestorePhase.entries.getOrNull(buffer.int)
                ?: throw BackupValidationException("待恢复记录阶段无效")
            val txLength = buffer.int
            val packageLength = buffer.int
            val passwordLength = buffer.int
            val digest = ByteArray(SHA256_BYTES).also(buffer::get)
            if (txLength !in 1..64 || packageLength !in 1..BackupPackageLimits.MAX_IN_MEMORY_BYTES.toInt() ||
                passwordLength !in 0..MAX_PASSWORD_BYTES ||
                buffer.remaining() != txLength + packageLength + passwordLength
            ) {
                digest.fill(0)
                throw BackupValidationException("待恢复记录长度无效")
            }
            val txBytes = ByteArray(txLength).also(buffer::get)
            val packageBytes = ByteArray(packageLength).also(buffer::get)
            val passwordBytes = ByteArray(passwordLength).also(buffer::get)
            var decodedPassword: CharArray? = null
            var ownershipTransferred = false
            try {
                val txId = txBytes.toString(Charsets.US_ASCII).also(::requireTxId)
                val actualDigest = sha256(packageBytes)
                if (!MessageDigest.isEqual(digest, actualDigest)) {
                    actualDigest.fill(0)
                    packageBytes.fill(0)
                    throw BackupValidationException("待恢复包摘要不一致")
                }
                actualDigest.fill(0)
                decodedPassword = decodePassword(passwordBytes)
                return PendingRestorePayload(
                    PendingRestoreMetadata(txId, digest, phase),
                    packageBytes,
                    decodedPassword,
                ).also { ownershipTransferred = true }
            } finally {
                txBytes.fill(0)
                passwordBytes.fill(0)
                if (!ownershipTransferred) {
                    digest.fill(0)
                    packageBytes.fill(0)
                    decodedPassword?.fill('\u0000')
                }
            }
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Exception) {
            throw BackupValidationException("待恢复记录损坏", error)
        }
    }

    private fun encodePassword(password: CharArray?): ByteArray {
        if (password == null) return ByteArray(0)
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val byteBuffer = encoder.encode(CharBuffer.wrap(password))
        if (byteBuffer.remaining() > MAX_PASSWORD_BYTES) throw BackupValidationException("恢复密码过长")
        return try {
            ByteArray(byteBuffer.remaining()).also(byteBuffer::get)
        } finally {
            if (byteBuffer.hasArray()) byteBuffer.array().fill(0)
        }
    }

    private fun decodePassword(bytes: ByteArray): CharArray? {
        if (bytes.isEmpty()) return null
        val chars = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        return try {
            CharArray(chars.remaining()).also(chars::get)
        } finally {
            if (chars.hasArray()) chars.array().fill('\u0000')
        }
    }

    private fun requireBounded(bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size.toLong() > BackupPackageLimits.MAX_IN_MEMORY_BYTES) {
            throw BackupValidationException("恢复包大小无效")
        }
    }

    private fun requireTxId(value: String) {
        if (!TX_ID.matches(value)) throw BackupValidationException("待恢复事务 ID 无效")
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        const val FILE_NAME = ".pending-restore-v1.bin"
        private const val MAGIC = 0x4e585250
        private const val VERSION = 1
        private const val SHA256_BYTES = 32
        private const val HEADER_BYTES = 6 * Int.SIZE_BYTES + SHA256_BYTES
        private const val MAX_PASSWORD_BYTES = 4096
        private const val MAX_FILE_BYTES = BackupPackageLimits.MAX_IN_MEMORY_BYTES.toInt() + MAX_PASSWORD_BYTES + 4096
        private val TX_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}

internal class AndroidKeystorePendingRestoreCryptor(
    private val alias: String = KEY_ALIAS,
) : PendingRestoreCryptor {
    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(plain)
        return ByteBuffer.allocate(12 + ciphertext.size).put(cipher.iv).put(ciphertext).array().also {
            ciphertext.fill(0)
        }
    }

    override fun decrypt(encrypted: ByteArray): ByteArray {
        if (encrypted.size <= 12) throw BackupValidationException("待恢复密文无效")
        val iv = encrypted.copyOfRange(0, 12)
        val ciphertext = encrypted.copyOfRange(12, encrypted.size)
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                doFinal(ciphertext)
            }
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        const val KEY_ALIAS = "nexara.pending.restore.v1"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
