package com.promenar.nexara.startup

/** 备份恢复成功后的单一启动门禁；任一步抛错都会阻止后续 writer 进入可写态。 */
class PostBackupStartupRecoverySequence(
    private val recoverWorkspaceJournal: suspend () -> Unit,
    private val recoverToolLedger: suspend () -> Unit,
    private val recoverWriterTombstones: suspend () -> Unit,
    private val initializeWriters: suspend () -> Unit,
    private val resumeVectorQueue: suspend () -> Unit,
) {
    suspend fun runOrThrow() {
        recoverWorkspaceJournal()
        recoverToolLedger()
        recoverWriterTombstones()
        initializeWriters()
        resumeVectorQueue()
    }
}
