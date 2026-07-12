package com.promenar.nexara.data.backup

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class AndroidPendingRestoreStoreTest {
    private val alias = "nexara.test.pending.${UUID.randomUUID()}"
    private val wrongAlias = "$alias.wrong"
    private val file = InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir
        .resolve("pending-test-${UUID.randomUUID()}.bin")

    @After
    fun tearDown() {
        AtomicFile(file).delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(wrongAlias)
    }

    @Test
    fun keystoreEncryptedPendingSurvivesRecreationAndRejectsTampering() {
        val txId = "123e4567-e89b-12d3-a456-426614174000"
        val packageBytes = "android-package-private".toByteArray()
        val password = "android-password-private".toCharArray()
        val first = store()
        first.begin(txId)
        first.stage(txId, packageBytes, password)
        first.authorize(txId)

        val disk = file.readBytes().toString(Charsets.ISO_8859_1)
        assertThat(disk).doesNotContain("android-package-private")
        assertThat(disk).doesNotContain("android-password-private")
        store().read()!!.use {
            assertThat(it.metadata.txId).isEqualTo(txId)
            assertThat(it.packageBytes).isEqualTo(packageBytes)
            assertThat(it.password).isEqualTo(password)
        }
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
        assertThat(key.encoded).isNull()

        assertThrows(BackupValidationException::class.java) {
            AndroidPendingRestoreStore(
                AtomicFile(file),
                AndroidKeystorePendingRestoreCryptor(wrongAlias),
            ).read()
        }

        file.writeBytes(file.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() })
        assertThrows(BackupValidationException::class.java) { store().read() }
    }

    private fun store() = AndroidPendingRestoreStore(
        AtomicFile(file),
        AndroidKeystorePendingRestoreCryptor(alias),
    )
}
