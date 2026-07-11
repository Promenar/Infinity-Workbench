package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class RestoreJournalTest {
    @Test
    fun `journal is durable across store recreation and contains metadata only`() {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".restore-journal-test")
        try {
            val record = RestoreJournalRecord(
                txId = "tx-1",
                expectedDatabaseFingerprint = "a".repeat(64),
                oldRootIdentity = "old-root",
                newRootIdentity = "restore-tx-1",
                newRootFileKey = null,
                phase = RestoreJournalPhase.PREPARED,
            )

            FileRestoreJournal(base).write(record)

            assertThat(FileRestoreJournal(base).read()).isEqualTo(record)
            val raw = Files.readAllBytes(base.resolve(FileRestoreJournal.FILE_NAME)).toString(Charsets.UTF_8)
            assertThat(raw).doesNotContain("secret")
            assertThat(raw).doesNotContain("user content")
        } finally {
            base.toFile().deleteRecursively()
        }
    }
}
