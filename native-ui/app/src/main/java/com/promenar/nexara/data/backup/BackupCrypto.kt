package com.promenar.nexara.data.backup

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCrypto private constructor(
    private val secureRandom: SecureRandom,
) {
    constructor() : this(SecureRandom())

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray =
        encryptInternal(plaintext, password, Parameters.random(secureRandom))

    fun encryptPackage(
        password: CharArray,
        plaintextFactory: (BackupKdfMetadata) -> ByteArray,
    ): ByteArray {
        val parameters = Parameters.random(secureRandom)
        var plaintext: ByteArray? = null
        return try {
            plaintext = plaintextFactory(parameters.toKdfMetadata())
            encryptInternal(plaintext, password, parameters)
        } finally {
            plaintext?.fill(0)
            parameters.wipe()
        }
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(envelope: ByteArray, password: CharArray): ByteArray {
        val metadata = parseEnvelope(envelope)
        val ownedPassword = password.copyOf()
        var keyBytes: ByteArray? = null
        return try {
            keyBytes = deriveKey(ownedPassword, metadata.salt)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(TAG_SIZE_BITS, metadata.iv),
            )
            cipher.updateAAD(envelope, 0, metadata.headerSize)
            cipher.doFinal(envelope, metadata.headerSize, envelope.size - metadata.headerSize)
        } catch (error: GeneralSecurityException) {
            throw GeneralSecurityException("备份密码错误或备份包认证失败", error)
        } finally {
            keyBytes?.fill(0)
            ownedPassword.fill('\u0000')
            metadata.wipe()
        }
    }

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun encryptInternal(plaintext: ByteArray, password: CharArray, parameters: Parameters): ByteArray {
        val ownedPassword = password.copyOf()
        val header = encodeHeader(parameters)
        var keyBytes: ByteArray? = null
        return try {
            keyBytes = deriveKey(ownedPassword, parameters.salt)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(TAG_SIZE_BITS, parameters.iv),
            )
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plaintext)
            try {
                header + ciphertext
            } finally {
                ciphertext.fill(0)
            }
        } finally {
            keyBytes?.fill(0)
            ownedPassword.fill('\u0000')
            header.fill(0)
            parameters.wipe()
        }
    }

    private fun encodeHeader(parameters: Parameters): ByteArray {
        val algorithmBytes = KDF_ALGORITHM.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(
            MAGIC.size + 1 + Short.SIZE_BYTES + algorithmBytes.size +
                Int.SIZE_BYTES + Int.SIZE_BYTES + 1 + parameters.salt.size + 1 + parameters.iv.size,
        )
            .put(MAGIC)
            .put(ENVELOPE_VERSION.toByte())
            .putShort(algorithmBytes.size.toShort())
            .put(algorithmBytes)
            .putInt(PBKDF2_ITERATIONS)
            .putInt(KEY_SIZE_BITS)
            .put(parameters.salt.size.toByte())
            .put(parameters.salt)
            .put(parameters.iv.size.toByte())
            .put(parameters.iv)
            .array()
    }

    private fun parseEnvelope(envelope: ByteArray): EnvelopeMetadata {
        try {
            val buffer = ByteBuffer.wrap(envelope)
            val magic = ByteArray(MAGIC.size).also(buffer::get)
            val version = buffer.get().toInt() and 0xff
            val algorithmSize = buffer.short.toInt() and 0xffff
            if (!magic.contentEquals(MAGIC) || version != ENVELOPE_VERSION || algorithmSize !in 1..MAX_ALGORITHM_BYTES) {
                throw GeneralSecurityException("不支持的加密备份包 header")
            }
            val algorithm = ByteArray(algorithmSize).also(buffer::get).toString(StandardCharsets.UTF_8)
            val iterations = buffer.int
            val keySizeBits = buffer.int
            val saltSize = buffer.get().toInt() and 0xff
            if (saltSize != SALT_SIZE_BYTES) throw GeneralSecurityException("不支持的加密备份包 header")
            val salt = ByteArray(saltSize).also(buffer::get)
            val ivSize = buffer.get().toInt() and 0xff
            if (ivSize != IV_SIZE_BYTES) throw GeneralSecurityException("不支持的加密备份包 header")
            val iv = ByteArray(ivSize).also(buffer::get)
            val headerSize = buffer.position()
            if (algorithm != KDF_ALGORITHM || iterations != PBKDF2_ITERATIONS || keySizeBits != KEY_SIZE_BITS ||
                envelope.size - headerSize < TAG_SIZE_BYTES
            ) throw GeneralSecurityException("不支持的加密备份包 header")
            return EnvelopeMetadata(salt, iv, headerSize)
        } catch (error: GeneralSecurityException) {
            throw error
        } catch (error: RuntimeException) {
            throw GeneralSecurityException("无效的加密备份包 header")
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private data class Parameters(val salt: ByteArray, val iv: ByteArray) {
        fun toKdfMetadata() = BackupKdfMetadata(
            algorithm = KDF_ALGORITHM,
            saltBase64 = Base64.getEncoder().encodeToString(salt),
            iterations = PBKDF2_ITERATIONS,
            keySizeBits = KEY_SIZE_BITS,
        )

        fun wipe() {
            salt.fill(0)
            iv.fill(0)
        }

        companion object {
            fun random(secureRandom: SecureRandom) = Parameters(
                ByteArray(SALT_SIZE_BYTES).also(secureRandom::nextBytes),
                ByteArray(IV_SIZE_BYTES).also(secureRandom::nextBytes),
            )
        }
    }

    private data class EnvelopeMetadata(
        val salt: ByteArray,
        val iv: ByteArray,
        val headerSize: Int,
    ) {
        fun wipe() {
            salt.fill(0)
            iv.fill(0)
        }
    }

    companion object {
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val PBKDF2_ITERATIONS = 600_000
        const val KEY_SIZE_BITS = 256
        const val SALT_SIZE_BYTES = 16
        const val IV_SIZE_BYTES = 12
        private const val ENVELOPE_VERSION = 1
        private const val MAX_ALGORITHM_BYTES = 64
        private const val TAG_SIZE_BITS = 128
        private const val TAG_SIZE_BYTES = TAG_SIZE_BITS / 8
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    }
}
