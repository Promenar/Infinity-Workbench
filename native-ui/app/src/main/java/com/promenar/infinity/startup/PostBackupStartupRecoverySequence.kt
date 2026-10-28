package com.promenar.infinity.startup

import kotlinx.coroutines.CancellationException

/** 局部恢复失败先建立隔离；只有 writer 初始化失败继续向上传播。 */
class PostBackupStartupRecoverySequence(
    private val recoverWorkspaceJournal: suspend () -> Unit,
    private val recoverToolLedger: suspend () -> Unit,
    private val recoverWriterTombstones: suspend () -> Unit,
    private val initializeWriters: suspend () -> Unit,
    private val resumeVectorQueue: suspend () -> Unit,
    private val onIsolatedFailure: suspend (StartupStep, Exception) -> Unit = { _, error -> throw error },
) {
    suspend fun runOrThrow() {
        isolated(StartupStep.WORKSPACE_JOURNAL, recoverWorkspaceJournal)
        isolated(StartupStep.TOOL_LEDGER, recoverToolLedger)
        isolated(StartupStep.TOMBSTONES, recoverWriterTombstones)
        initializeWriters()
        isolated(StartupStep.VECTOR_QUEUE, resumeVectorQueue)
    }
    private suspend fun isolated(step: StartupStep, recover: suspend () -> Unit) {
        try { recover() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { onIsolatedFailure(step, error) }
    }
}
