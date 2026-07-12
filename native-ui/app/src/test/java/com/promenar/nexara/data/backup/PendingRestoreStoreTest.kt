package com.promenar.nexara.data.backup

import android.util.AtomicFile
import com.google.common.truth.Truth.assertThat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.assertThrows
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PendingRestoreStoreTest {
    @Test
    fun `encrypted pending survives store recreation and contains no package or password plaintext`() {
        val file = Files.createTempFile("pending-restore", ".bin").toFile().also { it.delete() }
        val key = ByteArray(32) { it.toByte() }
        val packageBytes = "package-private-marker".toByteArray()
        val password = "password-private-marker".toCharArray()

        val first = AndroidPendingRestoreStore(AtomicFile(file), TestCryptor(key)) { TX_ID }
        val metadata = first.stage(packageBytes, password)
        val disk = file.readBytes().toString(Charsets.ISO_8859_1)
        val recreated = AndroidPendingRestoreStore(AtomicFile(file), TestCryptor(key)) { error("unused") }

        assertThat(disk).doesNotContain("package-private-marker")
        assertThat(disk).doesNotContain("password-private-marker")
        recreated.read()!!.use { payload ->
            assertThat(payload.metadata.txId).isEqualTo(metadata.txId)
            assertThat(payload.packageBytes).isEqualTo(packageBytes)
            assertThat(payload.password).isEqualTo(password)
        }
        assertThat(packageBytes.toString(Charsets.UTF_8)).isEqualTo("package-private-marker")
        assertThat(password.concatToString()).isEqualTo("password-private-marker")
    }

    @Test
    fun `tamper wrong key and truncated atomic file fail closed`() {
        val file = Files.createTempFile("pending-restore", ".bin").toFile().also { it.delete() }
        val key = ByteArray(32) { 1 }
        val store = AndroidPendingRestoreStore(AtomicFile(file), TestCryptor(key)) { TX_ID }
        store.stage(byteArrayOf(1, 2, 3), "pw".toCharArray())

        val tampered = file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        file.writeBytes(tampered)
        assertThrows(BackupValidationException::class.java) { store.read() }

        store.stage(byteArrayOf(1, 2, 3), "pw".toCharArray())
        assertThrows(BackupValidationException::class.java) {
            AndroidPendingRestoreStore(AtomicFile(file), TestCryptor(ByteArray(32) { 2 })).read()
        }

        file.writeBytes(file.readBytes().copyOf(8))
        assertThrows(BackupValidationException::class.java) { store.read() }
    }

    private class TestCryptor(key: ByteArray) : PendingRestoreCryptor {
        private val key = SecretKeySpec(key.copyOf(), "AES")
        override fun encrypt(plain: ByteArray): ByteArray {
            val iv = ByteArray(12) { (it + 9).toByte() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + cipher.doFinal(plain)
        }

        override fun decrypt(encrypted: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
        }
    }

    private companion object {
        const val TX_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}
