package com.promenar.nexara.data.backup

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import java.util.UUID

class AndroidRestoreJournalAuthenticatorTest {
    private val alias = "nexara.test.restore.journal.${UUID.randomUUID()}"

    @After
    fun tearDown() {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun hmacKeyIsNonExportableAndSignatureSurvivesAuthenticatorRecreation() {
        val payload = "restore-journal-payload".toByteArray()
        val signature = AndroidRestoreJournalAuthenticator(alias).sign(payload)

        assertTrue(AndroidRestoreJournalAuthenticator(alias).verify(payload, signature))
        val key = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }.getKey(alias, null)
        assertNull(key.encoded)
        assertArrayEquals(signature, AndroidRestoreJournalAuthenticator(alias).sign(payload))
    }

    @Test
    fun verifyRejectsPayloadAndSignatureTampering() {
        val authenticator = AndroidRestoreJournalAuthenticator(alias)
        val payload = "restore-journal-payload".toByteArray()
        val signature = authenticator.sign(payload)

        val changedPayload = payload.copyOf().apply { this[lastIndex] = (last() + 1).toByte() }
        val changedSignature = signature.copyOf().apply { this[lastIndex] = (last() + 1).toByte() }

        assertFalse(authenticator.verify(changedPayload, signature))
        assertFalse(authenticator.verify(payload, changedSignature))
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
