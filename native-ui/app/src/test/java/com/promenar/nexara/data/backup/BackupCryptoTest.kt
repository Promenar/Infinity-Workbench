package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.security.GeneralSecurityException

class BackupCryptoTest {
    @Test
    fun `PBKDF2 AES-GCM round trip uses an authenticated envelope`() {
        val crypto = BackupCrypto()
        val plaintext = "backup payload".toByteArray()
        val password = "correct horse battery staple".toCharArray()

        val encrypted = crypto.encrypt(plaintext, password)
        val decrypted = crypto.decrypt(encrypted, "correct horse battery staple".toCharArray())

        assertThat(encrypted).isNotEqualTo(plaintext)
        assertThat(decrypted).isEqualTo(plaintext)
    }

    @Test
    fun `wrong password is rejected before plaintext is returned`() {
        val crypto = BackupCrypto()
        val encrypted = crypto.encrypt("private".toByteArray(), "right".toCharArray())

        assertThrows<GeneralSecurityException> {
            crypto.decrypt(encrypted, "wrong".toCharArray())
        }
    }

    @Test
    fun `tampered authentication tag is rejected`() {
        val crypto = BackupCrypto()
        val encrypted = crypto.encrypt("private".toByteArray(), "right".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()

        assertThrows<GeneralSecurityException> {
            crypto.decrypt(encrypted, "right".toCharArray())
        }
    }

    @Test
    fun `authenticated envelope rejects every tampered header field`() {
        val crypto = BackupCrypto()
        val password = "right".toCharArray()
        val encrypted = crypto.encrypt("private".toByteArray(), password)
        val metadata = crypto.inspectEnvelope(encrypted)
        val mutations = listOf(
            encrypted.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
            encrypted.copyOf().also { it[4] = (it[4].toInt() xor 1).toByte() },
            mutateFirst(encrypted, metadata.algorithm.toByteArray()),
            mutateFirst(encrypted, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(metadata.iterations).array()),
            mutateFirst(encrypted, ByteBuffer.allocate(Int.SIZE_BYTES).putInt(metadata.keySizeBits).array()),
            mutateFirst(encrypted, metadata.salt),
            mutateFirst(encrypted, metadata.iv),
        )

        mutations.forEach { tampered ->
            assertThrows<GeneralSecurityException> {
                crypto.decrypt(tampered, "right".toCharArray())
            }
        }
    }

    @Test
    fun `crypto never modifies caller password on success or failure`() {
        val crypto = BackupCrypto()
        val encryptPassword = "right".toCharArray()
        val encrypted = crypto.encrypt("private".toByteArray(), encryptPassword)
        assertThat(encryptPassword.concatToString()).isEqualTo("right")

        val decryptPassword = "right".toCharArray()
        crypto.decrypt(encrypted, decryptPassword)
        assertThat(decryptPassword.concatToString()).isEqualTo("right")

        val wrongPassword = "wrong".toCharArray()
        assertThrows<GeneralSecurityException> { crypto.decrypt(encrypted, wrongPassword) }
        assertThat(wrongPassword.concatToString()).isEqualTo("wrong")
    }

    private fun mutateFirst(source: ByteArray, needle: ByteArray): ByteArray {
        val result = source.copyOf()
        val index = (0..source.size - needle.size).first {
            source.copyOfRange(it, it + needle.size).contentEquals(needle)
        }
        result[index] = (result[index].toInt() xor 1).toByte()
        return result
    }
}
