package com.promenar.nexara.data.local.db.recovery

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.backup.RestoreJournalAuthenticator
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

class DualDatabaseRecoveryJournalTest {
    @Test
    fun `journal round trips and rejects unknown fields`() {
        val root = Files.createTempDirectory("dual-db-journal")
        try {
            val journal = FileDualDatabaseRecoveryJournal(root, DigestAuthenticator)
            journal.write(record())
            assertThat(journal.read()).isEqualTo(record())
            val file = root.resolve(".dual-database-recovery-v1.json")
            Files.write(
                file,
                (Files.readAllBytes(file).toString(Charsets.UTF_8).dropLast(1) + ",\"unknown\":true}")
                    .toByteArray(Charsets.UTF_8),
            )
            assertThat(runCatching { journal.read() }.isFailure).isTrue()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `journal rejects writer gate before complete and traversal paths`() {
        val root = Files.createTempDirectory("dual-db-journal")
        try {
            val journal = FileDualDatabaseRecoveryJournal(root, DigestAuthenticator)
            assertThat(runCatching { journal.write(record().copy(writerGateOpened = true)) }.isFailure).isTrue()
            val bad = source("legacy", LEGACY_DATABASE_NAME, 17, PUBLIC_V17_IDENTITY)
                .copy(files = listOf(RecoveryFileFingerprint("../escape", 1, "a".repeat(64))))
            assertThat(runCatching { journal.write(record().copy(legacy = bad)) }.isFailure).isTrue()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `staged journal requires an authenticated working attempt`() {
        val root = Files.createTempDirectory("dual-db-journal")
        try {
            val journal = FileDualDatabaseRecoveryJournal(root, DigestAuthenticator)
            val staged = record().copy(
                stage = DualDatabaseRecoveryStage.STAGED,
                workingLegacySha256 = "c".repeat(64),
                workingCurrentSha256 = "d".repeat(64),
            )
            assertThat(runCatching { journal.write(staged) }.isFailure).isTrue()
            journal.write(staged.copy(workingAttemptId = "attempt-fixed-test"))
            assertThat(journal.read()?.workingAttemptId).isEqualTo("attempt-fixed-test")
        } finally { root.toFile().deleteRecursively() }
    }

    private fun record() = DualDatabaseRecoveryRecord(
        transactionId = "tx-1",
        stage = DualDatabaseRecoveryStage.SNAPSHOTTED,
        legacy = source("legacy", LEGACY_DATABASE_NAME, 17, PUBLIC_V17_IDENTITY),
        current = source("current", CURRENT_DATABASE_NAME, 2, PUBLIC_V2_IDENTITY),
        snapshotManifestSha256 = "b".repeat(64),
    )

    private fun source(role: String, name: String, version: Int, identity: String): RecoveryDatabaseSource {
        val files = listOf(RecoveryFileFingerprint(name, 1, "a".repeat(64)))
        return RecoveryDatabaseSource(role, name, version, identity, files, recoveryManifestSha256(files))
    }

    private object DigestAuthenticator : RestoreJournalAuthenticator {
        override fun sign(payload: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(payload)
        override fun verify(payload: ByteArray, signature: ByteArray): Boolean =
            MessageDigest.isEqual(sign(payload), signature)
    }
}
