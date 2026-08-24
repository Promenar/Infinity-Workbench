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
    private val cancelAndJoinGeneration: suspend (String) -> Unit,
    private val recoverFileMutations: suspend (SessionDeletionTarget) -> Unit = {},
    private val closePendingExecution: suspend (String) -> Unit,
    private val acquireVectorBarrier: suspend (SessionDeletionTarget) -> SessionDeletionBarrier,
    private val journal: SessionWorkspaceMutationJournal,
    private val deleteDatabase: suspend (SessionDeletionTarget, operationId: String) -> Unit,
    private val afterDatabaseCommit: suspend (SessionDeletionTarget) -> Unit = {},
) {
    suspend fun delete(sessionId: String): SessionDeletionResult = try {
        gate.withSessionDeletionSequence(sessionId) {
            try {
                recoverSessionDeletion(sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                return@withSessionDeletionSequence failed(SessionDeletionErrorCode.WORKSPACE, failure)
            }
            deleteAfterRecovery(sessionId)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        failed(SessionDeletionErrorCode.EXECUTION, failure)
    }

    private suspend fun deleteAfterRecovery(sessionId: String): SessionDeletionResult {
        val firstTarget = try {
            resolveTarget(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            return failed(SessionDeletionErrorCode.INVALID_TARGET, failure)
        } ?: return SessionDeletionResult.AlreadyDeleted

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
            } ?: return@withDeletion SessionDeletionResult.AlreadyDeleted
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
            try {
                barrier.awaitReady()
                staged = journal.stage(target)
                deleteDatabase(target, staged.operationId)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    staged?.let { mutation ->
                        runCatching { journal.rollback(mutation) }
                            .exceptionOrNull()?.let(failure::addSuppressed)
                    }
                    runCatching { barrier.abort() }.exceptionOrNull()?.let(failure::addSuppressed)
                }
                if (failure is CancellationException) throw failure
                return@withDeletion failed(
                    if (staged == null) SessionDeletionErrorCode.WORKSPACE else SessionDeletionErrorCode.DATABASE,
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
            if (completionFailure != null) {
                failed(SessionDeletionErrorCode.WORKSPACE, completionFailure)
            } else {
                SessionDeletionResult.Deleted
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
