package com.promenar.nexara.domain.repository

interface IFileOperationRepository {
    suspend fun writeFileAtomic(
        uuid: String,
        newContent: String,
        sessionId: String,
        expectedHash: String
    ): WriteResult

    suspend fun readFileRange(
        uuid: String,
        startLine: Int? = null,
        endLine: Int? = null
    ): ReadResult

    suspend fun diffFile(
        uuid: String,
        basisHash: String? = null
    ): DiffResult

    suspend fun patchFile(
        uuid: String,
        operations: List<PatchOperation>,
        expectedHash: String
    ): PatchResult
}

sealed class WriteResult {
    data class Success(val newHash: String) : WriteResult()
    data class Conflict(val currentHash: String, val expectedHash: String, val message: String) :
        WriteResult()

    data object NotFound : WriteResult()
}

data class ReadResult(
    val uuid: String,
    val name: String,
    val totalLines: Int,
    val startLine: Int,
    val endLine: Int,
    val content: String,
    val hash: String,
    val lastModified: Long
)

data class DiffResult(
    val uuid: String,
    val basisHash: String,
    val currentHash: String,
    val hunks: List<DiffHunk>
)

data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<DiffLine>
)

data class DiffLine(val type: String, val content: String)

data class PatchOperation(
    val action: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val afterLine: Int? = null,
    val newContent: String? = null
)

sealed class PatchResult {
    data class Success(val newHash: String, val appliedOperations: Int) : PatchResult()
    data class Failure(val error: PatchError) : PatchResult()
}

data class PatchError(
    val code: String,
    val message: String,
    val operationIndex: Int,
    val fileUuid: String,
    val totalLines: Int? = null,
    val suggestion: String? = null
)
