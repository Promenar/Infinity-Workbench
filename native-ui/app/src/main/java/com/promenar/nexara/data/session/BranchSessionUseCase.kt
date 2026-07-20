package com.promenar.nexara.data.session

import androidx.room.withTransaction
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.dao.MessageDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.model.ToolCall
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class BranchSessionPlan(
    val session: SessionEntity,
    val messages: List<MessageEntity>,
)

sealed interface BranchSessionPlanResult {
    data class Ready(val plan: BranchSessionPlan) : BranchSessionPlanResult
    data class Rejected(val reason: BranchSessionRejectReason) : BranchSessionPlanResult
}

enum class BranchSessionRejectReason {
    SourceNotFound,
    TargetNotFound,
    TargetNotStable,
}

object BranchSessionPlanner {
    fun plan(
        source: SessionEntity,
        sourceMessages: List<MessageEntity>,
        targetMessageId: String,
        newSessionId: String,
        now: Long,
        branchTitle: String = "${source.title} · Branch",
        newMessageId: (String) -> String,
    ): BranchSessionPlanResult {
        val targetIndex = sourceMessages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) {
            return BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotFound)
        }
        val selectedMessages = sourceMessages.take(targetIndex + 1)
        if (!selectedMessages.isStableBranchPrefix()) {
            return BranchSessionPlanResult.Rejected(BranchSessionRejectReason.TargetNotStable)
        }
        val target = selectedMessages.last()
        val idMap = selectedMessages.associate { message -> message.id to newMessageId(message.id) }
        val branchedMessages = selectedMessages.map { message ->
            message.copy(
                id = idMap.getValue(message.id),
                sessionId = newSessionId,
                parentMessageId = message.parentMessageId?.let(idMap::get),
                tokens = null,
                ragProgress = null,
                ragReferencesLoading = 0,
                pendingApprovalToolIds = null,
                planningTask = null,
                isArchived = 0,
                vectorizationStatus = null,
                layoutHeight = null,
            )
        }
        val branchedSession = source.copy(
            id = newSessionId,
            title = branchTitle,
            lastMessage = target.content.take(240).takeIf(String::isNotBlank),
            time = null,
            unread = 0,
            isPinned = 0,
            scrollOffset = null,
            draft = null,
            loopStatus = "idle",
            pendingIntervention = null,
            approvalRequest = null,
            activeTask = null,
            stats = null,
            workspacePath = null,
            workspaceRootUuid = null,
            activeTaskTreeId = null,
            createdAt = now,
            updatedAt = now,
        )
        return BranchSessionPlanResult.Ready(BranchSessionPlan(branchedSession, branchedMessages))
    }

    private fun MessageEntity.isStableBranchMessage(): Boolean {
        if (status?.lowercase() in UNSTABLE_STATUSES) return false
        if (ragReferencesLoading != 0) return false
        if (!pendingApprovalToolIds.isNullOrBlank()) return false
        if (role == "assistant" && content.isBlank() && toolCalls.isNullOrBlank()) return false
        return true
    }

    private fun List<MessageEntity>.isStableBranchPrefix(): Boolean {
        if (any { !it.isStableBranchMessage() }) return false
        val selectedIds = mapTo(mutableSetOf()) { it.id }
        if (any { it.parentMessageId != null && it.parentMessageId !in selectedIds }) return false

        val declaredToolCalls = mutableMapOf<String, Int>()
        forEachIndexed { index, message ->
            if (message.role != "assistant" || message.toolCalls.isNullOrBlank()) return@forEachIndexed
            val calls = runCatching {
                JSON.decodeFromString<List<ToolCall>>(message.toolCalls)
            }.getOrNull() ?: return false
            if (calls.any { it.id.isBlank() || declaredToolCalls.put(it.id, index) != null }) return false
        }
        val toolResults = filter { it.role == "tool" }
        if (toolResults.any { result ->
                val callId = result.toolCallId ?: return@any true
                val assistantIndex = declaredToolCalls[callId] ?: return@any true
                indexOf(result) <= assistantIndex
            }
        ) return false
        return declaredToolCalls.keys == toolResults.mapNotNullTo(mutableSetOf()) { it.toolCallId }
    }

    private val UNSTABLE_STATUSES = setOf(
        "pending",
        "streaming",
        "generating",
        "thinking",
        "waiting_for_approval",
    )

    private val JSON = Json { ignoreUnknownKeys = true }
}

sealed interface BranchSessionResult {
    data class Success(val sessionId: String) : BranchSessionResult
    data class Rejected(val reason: BranchSessionRejectReason) : BranchSessionResult
}

class BranchSessionUseCase(
    private val database: NexaraDatabase,
    private val sessionDao: SessionDao = database.sessionDao(),
    private val messageDao: MessageDao = database.messageDao(),
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val branchTitle: (String) -> String = { title -> "$title · Branch" },
) {
    suspend fun branch(sourceSessionId: String, targetMessageId: String): BranchSessionResult =
        database.withTransaction {
            val source = sessionDao.getById(sourceSessionId)
                ?: return@withTransaction BranchSessionResult.Rejected(
                    BranchSessionRejectReason.SourceNotFound,
                )
            val newSessionId = newId()
            when (
                val planned = BranchSessionPlanner.plan(
                    source = source,
                    sourceMessages = messageDao.getBySession(sourceSessionId),
                    targetMessageId = targetMessageId,
                    newSessionId = newSessionId,
                    now = now(),
                    branchTitle = branchTitle(source.title),
                    newMessageId = { newId() },
                )
            ) {
                is BranchSessionPlanResult.Rejected -> BranchSessionResult.Rejected(planned.reason)
                is BranchSessionPlanResult.Ready -> {
                    sessionDao.insert(planned.plan.session)
                    messageDao.insertAll(planned.plan.messages)
                    BranchSessionResult.Success(newSessionId)
                }
            }
        }
}
