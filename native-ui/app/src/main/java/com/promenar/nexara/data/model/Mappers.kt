package com.promenar.nexara.data.model

import com.promenar.nexara.data.local.db.entity.MessageEntity
import com.promenar.nexara.data.local.db.entity.SessionEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

private inline fun <reified T> encodeToJson(value: T): String =
    json.encodeToString(serializer<T>(), value)

private inline fun <reified T> decodeFromJson(text: String): T =
    json.decodeFromString(serializer<T>(), text)

fun SessionEntity.toDomain(): Session = Session(
    id = id,
    agentId = agentId,
    title = title,
    lastMessage = lastMessage,
    time = time,
    unread = unread,
    modelId = modelId,
    customPrompt = customPrompt,
    isPinned = isPinned == 1,
    scrollOffset = scrollOffset,
    draft = draft,
    executionMode = executionMode,
    loopStatus = LoopStatus.fromSerializedName(loopStatus),
    pendingIntervention = pendingIntervention,
    approvalRequest = approvalRequest?.let { decodeFromJson<ApprovalRequest>(it) },
    messages = emptyList(),
    ragOptions = ragOptions?.let { decodeFromJson<RagOptions>(it) },
    inferenceParams = inferenceParams?.let { decodeFromJson<InferenceParams>(it) },
    activeTask = activeTask?.let { decodeFromJson<TaskState>(it) },
    stats = stats?.let { decodeFromJson<SessionStats>(it) },
    options = options?.let { decodeFromJson<SessionOptions>(it) },
    activeMcpServerIds = activeMcpServerIds?.let { decodeFromJson<List<String>>(it) } ?: emptyList(),
    activeSkillIds = activeSkillIds?.let { decodeFromJson<List<String>>(it) } ?: emptyList(),
    workspacePath = workspacePath,
    continuationBudget = 0,
    autoLoopLimit = 5,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Session.toEntity(): SessionEntity = SessionEntity(
    id = id,
    agentId = agentId,
    title = title,
    lastMessage = lastMessage,
    time = time,
    unread = unread,
    modelId = modelId,
    customPrompt = customPrompt,
    isPinned = if (isPinned) 1 else 0,
    scrollOffset = scrollOffset,
    draft = draft,
    executionMode = executionMode,
    loopStatus = loopStatus.toSerializedName(),
    pendingIntervention = pendingIntervention,
    approvalRequest = approvalRequest?.let { encodeToJson(it) },
    ragOptions = ragOptions?.let { encodeToJson(it) },
    inferenceParams = inferenceParams?.let { encodeToJson(it) },
    activeTask = activeTask?.let { encodeToJson(it) },
    stats = stats?.let { encodeToJson(it) },
    options = options?.let { encodeToJson(it) },
    activeMcpServerIds = if (activeMcpServerIds.isNotEmpty()) encodeToJson(activeMcpServerIds) else null,
    activeSkillIds = if (activeSkillIds.isNotEmpty()) encodeToJson(activeSkillIds) else null,
    workspacePath = workspacePath,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun MessageEntity.toDomain(): Message = Message(
    id = id,
    role = MessageRole.fromSerializedName(role),
    content = content,
    modelId = modelId,
    status = status,
    reasoning = reasoning,
    thoughtSignature = thoughtSignature,
    images = images,
    files = files,
    tokens = tokens?.let { decodeFromJson<TokenUsage>(it) },
    citations = citations?.let { decodeFromJson<List<Citation>>(it) },
    ragReferences = ragReferences?.let { decodeFromJson<List<RagReference>>(it) },
    ragProgress = ragProgress?.let { decodeFromJson<RagProgress>(it) },
    ragMetadata = ragMetadata?.let { decodeFromJson<RagMetadata>(it) },
    ragReferencesLoading = ragReferencesLoading == 1,
    executionSteps = executionSteps?.let { decodeFromJson<List<ExecutionStep>>(it) },
    toolCalls = toolCalls?.let { decodeFromJson<List<ToolCall>>(it) },
    pendingApprovalToolIds = pendingApprovalToolIds?.let { decodeFromJson<List<String>>(it) },
    toolCallId = toolCallId,
    name = name,
    planningTask = planningTask?.let { decodeFromJson<TaskState>(it) },
    isArchived = isArchived == 1,
    vectorizationStatus = vectorizationStatus,
    layoutHeight = layoutHeight,
    toolResults = toolResults?.let { decodeFromJson<List<ToolResultArtifact>>(it) },
    isError = isError == 1,
    errorMessage = errorMessage,
    createdAt = createdAt
)

fun Message.toEntity(sessionId: String): MessageEntity = MessageEntity(
    id = id,
    sessionId = sessionId,
    role = role.toSerializedName(),
    content = content,
    modelId = modelId,
    status = status,
    reasoning = reasoning,
    thoughtSignature = thoughtSignature,
    images = images,
    files = files,
    tokens = tokens?.let { encodeToJson(it) },
    citations = citations?.let { encodeToJson(it) },
    ragReferences = ragReferences?.let { encodeToJson(it) },
    ragProgress = ragProgress?.let { encodeToJson(it) },
    ragMetadata = ragMetadata?.let { encodeToJson(it) },
    ragReferencesLoading = if (ragReferencesLoading) 1 else 0,
    executionSteps = executionSteps?.let { encodeToJson(it) },
    toolCalls = toolCalls?.let { encodeToJson(it) },
    pendingApprovalToolIds = pendingApprovalToolIds?.let { encodeToJson(it) },
    toolCallId = toolCallId,
    name = name,
    planningTask = planningTask?.let { encodeToJson(it) },
    isArchived = if (isArchived) 1 else 0,
    vectorizationStatus = vectorizationStatus,
    layoutHeight = layoutHeight,
    toolResults = toolResults?.let { encodeToJson(it) },
    isError = if (isError) 1 else 0,
    errorMessage = errorMessage,
    createdAt = createdAt
)
