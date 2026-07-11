package com.promenar.nexara.data.backup

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCrypto(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    data class Parameters(val salt: ByteArray, val iv: ByteArray)

    fun newParameters(): Parameters = Parameters(
        salt = ByteArray(SALT_SIZE_BYTES).also(secureRandom::nextBytes),
        iv = ByteArray(IV_SIZE_BYTES).also(secureRandom::nextBytes),
    )

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray =
        encrypt(plaintext, password, newParameters())

    fun encrypt(plaintext: ByteArray, password: CharArray, parameters: Parameters): ByteArray {
        require(parameters.salt.size == SALT_SIZE_BYTES) { "无效的备份加密参数" }
        require(parameters.iv.size == IV_SIZE_BYTES) { "无效的备份加密参数" }
        val keyBytes = deriveKey(password, parameters.salt)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(TAG_SIZE_BITS, parameters.iv),
            )
            val ciphertext = cipher.doFinal(plaintext)
            ByteBuffer.allocate(HEADER_SIZE + ciphertext.size)
                .put(MAGIC)
                .put(ENVELOPE_VERSION)
                .put(parameters.salt)
                .put(parameters.iv)
                .put(ciphertext)
                .array()
        } finally {
            keyBytes.fill(0)
            password.fill('\u0000')
        }
    }

    @Throws(GeneralSecurityException::class)
    fun decrypt(envelope: ByteArray, password: CharArray): ByteArray {
        if (!isEncrypted(envelope) || envelope.size < HEADER_SIZE + TAG_SIZE_BYTES) {
            password.fill('\u0000')
            throw GeneralSecurityException("无效的加密备份包")
        }
        val buffer = ByteBuffer.wrap(envelope)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        val version = buffer.get()
        if (!magic.contentEquals(MAGIC) || version != ENVELOPE_VERSION) {
            password.fill('\u0000')
            throw GeneralSecurityException("不支持的加密备份包")
        }
        val salt = ByteArray(SALT_SIZE_BYTES).also(buffer::get)
        val iv = ByteArray(IV_SIZE_BYTES).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val keyBytes = deriveKey(password, salt)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_SIZE_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (error: GeneralSecurityException) {
            throw GeneralSecurityException("备份密码错误或备份包认证失败", error)
        } finally {
            keyBytes.fill(0)
            password.fill('\u0000')
            salt.fill(0)
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_SIZE_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
        }
    }

    companion object {
        const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        const val PBKDF2_ITERATIONS = 600_000
        const val KEY_SIZE_BITS = 256
        const val SALT_SIZE_BYTES = 16
        const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
        private const val TAG_SIZE_BYTES = TAG_SIZE_BITS / 8
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
        private const val ENVELOPE_VERSION: Byte = 1
        private const val HEADER_SIZE = 4 + 1 + SALT_SIZE_BYTES + IV_SIZE_BYTES
    }
}
