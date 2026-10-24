package com.promenar.nexara.data.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Path

data class SessionDeletionTarget(
    val sessionId: String,
    val workspaceRootUuid: String,
    val physicalRoot: Path,
    val rootIdentity: String,
    val fileUuids: List<String>,
)

sealed interface SessionDeletionResult {
    data object Deleted : SessionDeletionResult
    data class DeletedKeepingFiles(val workspaceId: String, val displayName: String) : SessionDeletionResult
    data object AlreadyDeleted : SessionDeletionResult
    data class Failed(val error: SessionDeletionError) : SessionDeletionResult
}

data class SessionDeletionError(
    val code: SessionDeletionErrorCode,
    val cause: Throwable,
)

enum class SessionDeletionErrorCode { INVALID_TARGET, GENERATION, EXECUTION, VECTOR, WORKSPACE, DATABASE }

interface SessionDeletionBarrier {
    suspend fun awaitReady()
    suspend fun commit()
    suspend fun abort()
}

object NoOpSessionDeletionBarrier : SessionDeletionBarrier {
    override suspend fun awaitReady() = Unit
    override suspend fun commit() = Unit
    override suspend fun abort() = Unit
}

data class StagedSessionWorkspaceMutation(
    val operationId: String,
    val target: SessionDeletionTarget,
)

interface SessionWorkspaceMutationJournal {
    suspend fun stage(target: SessionDeletionTarget): StagedSessionWorkspaceMutation
    suspend fun rollback(staged: StagedSessionWorkspaceMutation)
    suspend fun complete(staged: StagedSessionWorkspaceMutation)
}

/** 删除主事务的唯一编排器；物理树与 Room 的崩溃一致性由 journal 实现。 */
class SessionDeletionCoordinator(
    private val gate: SessionExecutionGate,
    private val recoverSessionDeletion: suspend (String) -> Unit = {},
    private val resolveTarget: suspend (String) -> SessionDeletionTarget?,
    private val isDatabaseOnlySession: suspend (String) -> Boolean = { false },
    private val deleteDatabaseOnly: suspend (String) -> Boolean = { false },
    private val cancelAndJoinGeneration: suspend (String) -> Unit,
    private val recoverFileMutations: suspend (SessionDeletionTarget) -> Unit = {},
    private val closePendingExecution: suspend (String) -> Unit,
    private val acquireVectorBarrier: suspend (SessionDeletionTarget) -> SessionDeletionBarrier,
    private val journal: SessionWorkspaceMutationJournal,
    private val deleteDatabase: suspend (SessionDeletionTarget, operationId: String) -> Unit,
    private val afterDatabaseCommit: suspend (SessionDeletionTarget) -> Unit = {},
    private val cleanupSessionData: suspend (String) -> Unit = {},
    private val retainWorkspace: suspend (SessionDeletionTarget) -> com.promenar.nexara.data.workspace.RetainedSessionWorkspace? = { null },
    private val deleteDatabaseKeepingFiles: suspend (SessionDeletionTarget, String, com.promenar.nexara.data.workspace.RetainedSessionWorkspace) -> Unit = { target, operationId, retained ->
        retained.register()
        deleteDatabase(target, operationId)
    },
) {
    suspend fun delete(sessionId: String, keepFiles: Boolean = false): SessionDeletionResult = try {
        gate.withSessionDeletionSequence(sessionId) {
            try {
                recoverSessionDeletion(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                return@withSessionDeletionSequence failed(SessionDeletionErrorCode.WORKSPACE, failure)
            }
            deleteAfterRecovery(sessionId, keepFiles)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        failed(SessionDeletionErrorCode.EXECUTION, failure)
    }

    private suspend fun deleteAfterRecovery(sessionId: String, keepFiles: Boolean): SessionDeletionResult {
        val databaseOnly = try {
            isDatabaseOnlySession(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return failed(SessionDeletionErrorCode.INVALID_TARGET, failure)
        }
        if (databaseOnly) return deleteDatabaseOnlySession(sessionId)

        val firstTarget = try {
            resolveTarget(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return failed(SessionDeletionErrorCode.INVALID_TARGET, failure)
        } ?: return alreadyDeleted(sessionId)

        return try {
            gate.withDeletion(
                sessionId = sessionId,
                workspaceRootUuid = firstTarget.workspaceRootUuid,
                beforeAdmissionsDrained = {
                    try {
                        cancelAndJoinGeneration(sessionId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        throw DeletionPreparationException(SessionDeletionErrorCode.GENERATION, failure)
                    }
                },
            ) {
            val target = try {
                resolveTarget(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                return@withDeletion failed(SessionDeletionErrorCode.INVALID_TARGET, failure)
            } ?: return@withDeletion alreadyDeleted(sessionId)
            if (!target.hasSameWorkspaceOwnership(firstTarget)) {
                return@withDeletion failed(
                    SessionDeletionErrorCode.INVALID_TARGET,
                    SecurityException("会话工作区认领在删除锁定前发生变化"),
                )
            }

            try {
                recoverFileMutations(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                return@withDeletion failed(SessionDeletionErrorCode.WORKSPACE, failure)
            }

            try {
                closePendingExecution(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                return@withDeletion failed(SessionDeletionErrorCode.EXECUTION, failure)
            }

            val barrier = try {
                acquireVectorBarrier(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                return@withDeletion failed(SessionDeletionErrorCode.VECTOR, failure)
            }
            var staged: StagedSessionWorkspaceMutation? = null
            var retained: com.promenar.nexara.data.workspace.RetainedSessionWorkspace? = null
            var databaseCommitted = false
            try {
                barrier.awaitReady()
                val prepareAndDelete: suspend () -> Unit = {
                    if (keepFiles) retained = retainWorkspace(target)
                    staged = journal.stage(target)
                    val kept = retained
                    if (kept == null) deleteDatabase(target, checkNotNull(staged).operationId)
                    else deleteDatabaseKeepingFiles(target, checkNotNull(staged).operationId, kept)
                    databaseCommitted = true
                }
                if (keepFiles) {
                    val failure = withContext(NonCancellable) { runCatching { prepareAndDelete() }.exceptionOrNull() }
                    if (failure != null) throw failure
                } else prepareAndDelete()
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    if (!databaseCommitted) {
                        var restored = staged == null
                        staged?.let { mutation ->
                            val rollback = runCatching { journal.rollback(mutation) }
                            restored = rollback.isSuccess
                            rollback.exceptionOrNull()?.let(failure::addSuppressed)
                        }
                        runCatching { barrier.abort() }.exceptionOrNull()?.let(failure::addSuppressed)
                        // journal 拒绝回滚时可能已经提交；保留副本避免恢复收尾后失去文件。
                        if (restored) retained?.let { copy -> runCatching { copy.rollback() }.exceptionOrNull()?.let(failure::addSuppressed) }
                    }
                }
                if (failure is CancellationException) throw failure
                return@withDeletion failed(
                    if (keepFiles || staged == null) SessionDeletionErrorCode.WORKSPACE else SessionDeletionErrorCode.DATABASE,
                    failure,
                )
            }

            val completionFailure = runCatching {
                withContext(NonCancellable) {
                    barrier.commit()
                    journal.complete(checkNotNull(staged))
                    afterDatabaseCommit(target)
                }
            }.exceptionOrNull()
            // 数据库提交后独立尝试私有数据清理，物理收尾失败不应遗留会话授权或输出。
            val cleanupFailure = cleanupPrivateDataFailure(sessionId)
            if (completionFailure != null) {
                if (cleanupFailure != null && cleanupFailure !== completionFailure) {
                    completionFailure.addSuppressed(cleanupFailure)
                }
                failed(SessionDeletionErrorCode.WORKSPACE, completionFailure)
            } else if (cleanupFailure != null) {
                failed(SessionDeletionErrorCode.WORKSPACE, cleanupFailure)
            } else {
                retained?.let { SessionDeletionResult.DeletedKeepingFiles(it.workspaceId, it.displayName) }
                    ?: SessionDeletionResult.Deleted
            }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DeletionPreparationException) {
            failed(failure.code, checkNotNull(failure.cause))
        } catch (failure: Throwable) {
            failed(SessionDeletionErrorCode.EXECUTION, failure)
        }
    }

    private suspend fun deleteDatabaseOnlySession(sessionId: String): SessionDeletionResult = try {
        gate.withDeletion(sessionId, workspaceRootUuid = null, beforeAdmissionsDrained = {
            cancelAndJoinGeneration(sessionId)
        }) {
            closePendingExecution(sessionId)
            val deleted = deleteDatabaseOnly(sessionId)
            val cleanupFailure = cleanupPrivateDataFailure(sessionId)
            if (cleanupFailure != null) failed(SessionDeletionErrorCode.WORKSPACE, cleanupFailure)
            else if (deleted) SessionDeletionResult.Deleted
            else SessionDeletionResult.AlreadyDeleted
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        failed(SessionDeletionErrorCode.DATABASE, failure)
    }

    private suspend fun alreadyDeleted(sessionId: String): SessionDeletionResult {
        val cleanupFailure = cleanupPrivateDataFailure(sessionId)
        return if (cleanupFailure == null) SessionDeletionResult.AlreadyDeleted
        else failed(SessionDeletionErrorCode.WORKSPACE, cleanupFailure)
    }

    private suspend fun cleanupPrivateDataFailure(sessionId: String): Throwable? = runCatching {
        withContext(NonCancellable) { cleanupSessionData(sessionId) }
    }.exceptionOrNull()

    private fun failed(code: SessionDeletionErrorCode, cause: Throwable) =
        SessionDeletionResult.Failed(SessionDeletionError(code, cause))

    private fun SessionDeletionTarget.hasSameWorkspaceOwnership(other: SessionDeletionTarget): Boolean =
        sessionId == other.sessionId &&
            workspaceRootUuid == other.workspaceRootUuid &&
            physicalRoot.toAbsolutePath().normalize() == other.physicalRoot.toAbsolutePath().normalize() &&
            rootIdentity == other.rootIdentity

    private class DeletionPreparationException(
        val code: SessionDeletionErrorCode,
        cause: Throwable,
    ) : IllegalStateException(cause)
}
