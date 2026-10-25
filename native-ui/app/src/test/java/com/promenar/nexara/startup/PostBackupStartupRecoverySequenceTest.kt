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
            onIsolatedFailure = { _, _ -> },
        )

        sequence.runOrThrow()

        assertThat(events).containsExactly(
            "journal", "ledger", "writer-tombstone", "writers", "vector",
        ).inOrder()
    }

    @Test
    fun `journal冲突隔离后仍初始化其余writer`() = runTest {
        val events = mutableListOf<String>()
        val conflict = IllegalStateException("journal conflict")
        val sequence = PostBackupStartupRecoverySequence(
            recoverWorkspaceJournal = { events += "journal"; throw conflict },
            recoverToolLedger = { events += "ledger" },
            recoverWriterTombstones = { events += "writer-tombstone" },
            initializeWriters = { events += "writers" },
            resumeVectorQueue = { events += "vector" },
            onIsolatedFailure = { _, _ -> },
        )

        val failure = runCatching { sequence.runOrThrow() }.exceptionOrNull()

        assertThat(failure).isNull()
        assertThat(events).containsExactly("journal", "ledger", "writer-tombstone", "writers", "vector").inOrder()
    }
    @Test fun `writer初始化失败保持全局阻断且不会恢复向量`() = runTest {
        var resumed = false
        val sequence = PostBackupStartupRecoverySequence({}, {}, {}, { error("writer失败") }, { resumed = true }, { _, _ -> })
        assertThat(runCatching { sequence.runOrThrow() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(resumed).isFalse()
    }

    @Test fun `只有数据库备份和writer初始化属于全局关键步骤`() {
        assertThat(StartupStep.entries.filter { it.critical }).containsExactly(
            StartupStep.DUAL_DATABASE, StartupStep.BACKUP_RESTORE, StartupStep.WRITER_INITIALIZATION)
    }
}
