package com.promenar.nexara.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Agent(
    val id: String,
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val model: String = "gpt-4o",
    val icon: String = "✨",
    val color: String = "#C0C1FF",
    val isPinned: Boolean = false,
    val createdAt: Long = 0L
)

@Serializable
data class TokenUsage(
    val input: Int = 0,
    val output: Int = 0,
    val total: Int = 0
)

@Serializable
data class GeneratedImageData(
    val thumbnail: String,
    val original: String,
    val mime: String
)

@Serializable
data class Citation(
    val title: String,
    val url: String,
    val source: String? = null
)

@Serializable
data class InferenceParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val thinkingLevel: String? = null
)

@Serializable
data class TokenMetric(
    val count: Int = 0,
    val isEstimated: Boolean = false
)

@Serializable
data class BillingUsage(
    val chatInput: TokenMetric = TokenMetric(),
    val chatOutput: TokenMetric = TokenMetric(),
    val ragSystem: TokenMetric = TokenMetric(),
    val total: Int = 0,
    val costUSD: Double = 0.0
)

@Serializable
data class RagReference(
    val id: String = "",
    val content: String = "",
    val source: String = "",
    val score: Float = 0f,
    val documentId: String? = null,
    val chunkIndex: Int = 0
)

@Serializable
data class RagProgress(
    val stage: String = "",
    val percentage: Int = 0,
    val subStage: String? = null,
    val networkStats: String? = null
)

@Serializable
data class RagMetadata(
    val chunkCount: Int = 0,
    val totalTokens: Int = 0,
    val retrievalTimeMs: Long = 0
)

@Serializable
data class RagUsage(
    val ragSystem: Int = 0,
    val isEstimated: Boolean = false
)

@Serializable
data class TaskStep(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val status: String = "pending"
)

@Serializable
data class TaskState(
    val id: String = "",
    val title: String = "",
    val status: String = "idle",
    val progress: Int = 0,
    val steps: List<TaskStep> = emptyList()
)

@Serializable
data class ExecutionStep(
    val id: String,
    val type: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolCallId: String? = null,
    val content: String? = null,
    val data: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val throttledUntil: Long? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

@Serializable
data class ToolResult(
    val id: String,
    val content: String,
    val status: String = "success",
    val data: String? = null
)

@Serializable
data class ToolResultArtifact(
    val type: String,
    val content: String,
    val name: String? = null
)

@Serializable
data class UpdateMessageOptions(
    val tokens: TokenUsage? = null,
    val reasoning: String? = null,
    val citations: List<Citation>? = null,
    val ragReferences: List<RagReference>? = null,
    val ragReferencesLoading: Boolean? = null,
    val ragMetadata: RagMetadata? = null,
    val thoughtSignature: String? = null,
    val planningTask: TaskState? = null,
    val toolCalls: List<ToolCall>? = null,
    val executionSteps: List<ExecutionStep>? = null,
    val pendingApprovalToolIds: List<String>? = null,
    val toolResults: List<ToolResultArtifact>? = null,
    val isError: Boolean? = null,
    val errorMessage: String? = null,
    val isLongWait: Boolean? = null,
    val loopCount: Int? = null
)

@Serializable
data class ApprovalRequest(
    val toolName: String? = null,
    val args: String? = null,
    val reason: String? = null,
    val type: String? = null
)

@Serializable
data class RagOptions(
    val enableMemory: Boolean = true,
    val enableDocs: Boolean = false,
    val activeDocIds: List<String> = emptyList(),
    val activeFolderIds: List<String> = emptyList(),
    val isGlobal: Boolean = false,
    val enableKnowledgeGraph: Boolean? = null
)

@Serializable
data class SessionOptions(
    val toolsEnabled: Boolean = true,
    val enableTimeInjection: Boolean = true,
    val webSearch: Boolean? = null,
    val ragOptions: RagOptions? = null
)

@Serializable
data class SessionStats(
    val totalTokens: Int = 0,
    val billing: BillingUsage = BillingUsage()
)

@Serializable
enum class LoopStatus {
    IDLE, RUNNING, PAUSED, WAITING_FOR_APPROVAL, COMPLETED;

    fun toSerializedName(): String = name.lowercase()

    companion object {
        fun fromSerializedName(name: String): LoopStatus =
            entries.find { it.name.lowercase() == name } ?: IDLE
    }
}

@Serializable
enum class MessageRole {
    USER, ASSISTANT, SYSTEM, TOOL;

    fun toSerializedName(): String = name.lowercase()

    companion object {
        fun fromSerializedName(name: String): MessageRole =
            entries.find { it.name.lowercase() == name } ?: USER
    }
}

@Serializable
data class Message(
    val id: String,
    val role: MessageRole,
    val content: String,
    val modelId: String? = null,
    val status: String? = null,
    val reasoning: String? = null,
    val thoughtSignature: String? = null,
    val images: String? = null,
    val files: String? = null,
    val tokens: TokenUsage? = null,
    val citations: List<Citation>? = null,
    val ragReferences: List<RagReference>? = null,
    val ragProgress: RagProgress? = null,
    val ragMetadata: RagMetadata? = null,
    val ragReferencesLoading: Boolean = false,
    val executionSteps: List<ExecutionStep>? = null,
    val toolCalls: List<ToolCall>? = null,
    val pendingApprovalToolIds: List<String>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    val planningTask: TaskState? = null,
    val isArchived: Boolean = false,
    val vectorizationStatus: String? = null,
    val layoutHeight: Double? = null,
    val toolResults: List<ToolResultArtifact>? = null,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val isLongWait: Boolean = false,
    val loopCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Session(
    val id: String,
    val agentId: String,
    val title: String = "New Chat",
    val lastMessage: String? = null,
    val time: String? = null,
    val unread: Int = 0,
    val modelId: String? = null,
    val customPrompt: String? = null,
    val isPinned: Boolean = false,
    val scrollOffset: Double? = null,
    val draft: String? = null,
    val executionMode: String = "auto",
    val loopStatus: LoopStatus = LoopStatus.IDLE,
    val pendingIntervention: String? = null,
    val approvalRequest: ApprovalRequest? = null,
    val messages: List<Message> = emptyList(),
    val ragOptions: RagOptions? = null,
    val inferenceParams: InferenceParams? = null,
    val activeTask: TaskState? = null,
    val stats: SessionStats? = null,
    val options: SessionOptions? = null,
    val activeMcpServerIds: List<String> = emptyList(),
    val activeSkillIds: List<String> = emptyList(),
    val workspacePath: String? = null,
    val continuationBudget: Int = 0,
    val autoLoopLimit: Int = 5,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
