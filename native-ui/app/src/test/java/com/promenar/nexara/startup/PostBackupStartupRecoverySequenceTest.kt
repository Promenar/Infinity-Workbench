package com.promenar.nexara.startup

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PostBackupStartupRecoverySequenceTest {
    @Test
    fun `严格按journal ledger writer vector顺序恢复`() = runTest {
        val events = mutableListOf<String>()
        val sequence = PostBackupStartupRecoverySequence(
            recoverWorkspaceJournal = { events += "journal" },
            recoverToolLedger = { events += "ledger" },
            recoverWriterTombstones = { events += "writer-tombstone" },
            initializeWriters = { events += "writers" },
            resumeVectorQueue = { events += "vector" },
        )

        sequence.runOrThrow()

        assertThat(events).containsExactly(
            "journal", "ledger", "writer-tombstone", "writers", "vector",
        ).inOrder()
    }

    @Test
    fun `journal冲突时fail-close且不启动任何后续writer`() = runTest {
        val events = mutableListOf<String>()
        val conflict = IllegalStateException("journal conflict")
        val sequence = PostBackupStartupRecoverySequence(
            recoverWorkspaceJournal = { events += "journal"; throw conflict },
            recoverToolLedger = { events += "ledger" },
            recoverWriterTombstones = { events += "writer-tombstone" },
            initializeWriters = { events += "writers" },
            resumeVectorQueue = { events += "vector" },
        )

        val failure = runCatching { sequence.runOrThrow() }.exceptionOrNull()

        assertThat(failure).isSameInstanceAs(conflict)
        assertThat(events).containsExactly("journal")
    }
}
