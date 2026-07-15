package com.promenar.nexara.data.generation

import com.promenar.nexara.data.model.PostProcessStatus
import com.promenar.nexara.data.model.PostProcessTask
import com.promenar.nexara.data.model.PostProcessType
import com.promenar.nexara.data.model.RagPhase
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationPhase
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class GenerationPresentationState(
    val taskId: String,
    val sessionId: String,
    val ragPhases: List<RagPhase> = emptyList(),
    val streamingContent: String = "",
    val error: GenerationFailure? = null,
    val providerFailure: ProviderResolution.Failure? = null,
    val generating: Boolean = false,
    val handledFailure: Boolean = false,
    val phase: GenerationPhase? = null,
    val postProcessTasks: List<PostProcessTask> = emptyList(),
) {
    override fun toString(): String =
        "GenerationPresentationState(taskId=$taskId, sessionId=$sessionId, " +
            "ragPhaseCount=${ragPhases.size}, streamingContentLength=${streamingContent.length}, " +
            "errorCode=${error?.code}, providerFailureReason=${providerFailure?.reason}, " +
            "generating=$generating, handledFailure=$handledFailure, phase=$phase, " +
            "postProcessTaskCount=${postProcessTasks.size})"
}

/** 应用级、按会话隔离的生成展示状态；导航不会改变正在生成任务的写入目标。 */
class GenerationPresentationStore {
    private val entries = ConcurrentHashMap<String, MutableStateFlow<GenerationPresentationState?>>()

    fun observe(sessionId: String): StateFlow<GenerationPresentationState?> = entry(sessionId)

    fun begin(sessionId: String, taskId: String) {
        entry(sessionId).value = GenerationPresentationState(taskId = taskId, sessionId = sessionId)
    }

    fun finish(sessionId: String, taskId: String) {
        val flow = entries[sessionId] ?: return
        if (flow.value?.taskId != taskId) return
        flow.value = null
        entries.remove(sessionId, flow)
    }

    fun release(sessionId: String) {
        val flow = entries[sessionId] ?: return
        if (flow.value == null) entries.remove(sessionId, flow)
    }

    fun port(sessionId: String, taskId: String): GenerationUiPort = SessionPort(sessionId, taskId)

    suspend fun accept(sessionId: String, taskId: String, event: GenerationEvent) {
        update(sessionId, taskId) { current ->
            when (event) {
                is GenerationEvent.PhaseChanged -> current.copy(
                    phase = event.phase,
                    generating = event.phase !in setOf(
                        GenerationPhase.COMPLETED,
                        GenerationPhase.FAILED,
                        GenerationPhase.CANCELLED,
                        GenerationPhase.PERSISTENCE_FAILED,
                        GenerationPhase.WAITING_APPROVAL,
                    ),
                )
                is GenerationEvent.SnapshotChanged -> current.copy(
                    streamingContent = event.snapshot.content,
                    error = event.snapshot.failure,
                )
                is GenerationEvent.TargetChanged -> current
                is GenerationEvent.Rejected -> current.copy(
                    error = event.failure,
                    generating = false,
                    handledFailure = true,
                    phase = GenerationPhase.FAILED,
                )
                is GenerationEvent.PersistenceFailed -> current.copy(
                    error = event.failure,
                    generating = false,
                    handledFailure = true,
                    phase = GenerationPhase.PERSISTENCE_FAILED,
                )
                is GenerationEvent.Failed -> current.copy(
                    error = event.failure,
                )
            }
        }
    }

    private fun entry(sessionId: String): MutableStateFlow<GenerationPresentationState?> =
        entries.getOrPut(sessionId) { MutableStateFlow(null) }

    internal fun retainedSessionStateCount(): Int = entries.size

    private fun update(
        sessionId: String,
        taskId: String,
        transform: (GenerationPresentationState) -> GenerationPresentationState,
    ) = entry(sessionId).update { current ->
        if (current?.taskId == taskId) transform(current) else current
    }

    private inner class SessionPort(
        private val sessionId: String,
        private val taskId: String,
    ) : GenerationUiPort {
        override fun replaceRagPhases(phases: List<RagPhase>) =
            update(sessionId, taskId) { it.copy(ragPhases = phases) }

        override fun updateRagPhases(transform: (List<RagPhase>) -> List<RagPhase>) =
            update(sessionId, taskId) { it.copy(ragPhases = transform(it.ragPhases)) }

        override fun setGenerating(value: Boolean) = update(sessionId, taskId) {
            it.copy(generating = value, handledFailure = if (value) false else it.handledFailure)
        }

        override fun setStreamingContent(value: String) =
            update(sessionId, taskId) { it.copy(streamingContent = value) }

        override fun setError(failure: GenerationFailure?) =
            update(sessionId, taskId) { it.copy(error = failure) }

        override fun setProviderFailure(failure: ProviderResolution.Failure?) =
            update(sessionId, taskId) { it.copy(providerFailure = failure) }

        override fun onHandledFailure() = update(sessionId, taskId) {
            it.copy(generating = false, handledFailure = true)
        }

        override fun addPostProcessTask(type: PostProcessType, detail: String): String {
            val id = UUID.randomUUID().toString()
            update(sessionId, taskId) {
                it.copy(
                    postProcessTasks = it.postProcessTasks + PostProcessTask(
                        id = id,
                        type = type,
                        status = PostProcessStatus.RUNNING,
                        progress = 0f,
                        detail = detail,
                    ),
                )
            }
            return id
        }

        override fun updatePostProcessTask(
            id: String,
            status: PostProcessStatus?,
            progress: Float?,
            detail: String?,
        ) = update(sessionId, taskId) { current ->
            current.copy(
                postProcessTasks = current.postProcessTasks.map { task ->
                    if (task.id == id) task.copy(
                        status = status ?: task.status,
                        progress = progress ?: task.progress,
                        detail = detail ?: task.detail,
                    ) else task
                },
            )
        }

        override fun removePostProcessTask(id: String) = update(sessionId, taskId) {
            it.copy(postProcessTasks = it.postProcessTasks.filterNot { task -> task.id == id })
        }
    }
}
