package com.promenar.nexara

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.AndroidJUnitRunner
import com.promenar.nexara.domain.generation.CancellationReason
import com.promenar.nexara.domain.generation.GenerationCoordinator
import com.promenar.nexara.domain.generation.GenerationError
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationTaskSnapshot
import com.promenar.nexara.domain.generation.StartGenerationResult
import com.promenar.nexara.ui.chat.ChatRouteDependencies
import com.promenar.nexara.ui.chat.ChatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MainActivityE2eRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application = super.newApplication(cl, MainActivityE2eApplication::class.java.name, context)
}

class MainActivityE2eApplication : NexaraApplication() {
    var viewModelCreateCount: Int = 0
        private set
    var createdChatViewModel: ChatViewModel? = null
        private set
    val recordingCoordinator = RecordingGenerationCoordinator(this)

    override fun createChatRouteDependencies(): ChatRouteDependencies {
        val delegate = ChatViewModel.factory(this, recordingCoordinator)
        val recordingFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                viewModelCreateCount += 1
                return delegate.create(modelClass).also { created ->
                    createdChatViewModel = created as? ChatViewModel
                }
            }
        }
        return ChatRouteDependencies(
            viewModelFactory = recordingFactory,
            taskRepository = taskRepository,
        )
    }

    fun resetE2eObservations() {
        recordingCoordinator.reset()
        viewModelCreateCount = 0
        createdChatViewModel = null
    }
}

class RecordingGenerationCoordinator(
    private val application: NexaraApplication,
) : GenerationCoordinator {
    private val mutableActive = MutableStateFlow<GenerationTaskSnapshot?>(null)
    override val active: StateFlow<GenerationTaskSnapshot?> = mutableActive
    val requests = mutableListOf<GenerationRequest>()
    val selectedModelsAtStart = mutableListOf<String?>()

    override fun observe(sessionId: String): StateFlow<GenerationTaskSnapshot?> = mutableActive

    override suspend fun start(request: GenerationRequest): StartGenerationResult {
        requests += request
        selectedModelsAtStart += application.chatStore.getSession(request.sessionId)?.modelId
        return StartGenerationResult.Rejected(GenerationError("e2e_recorded_without_network"))
    }

    override fun cancel(taskId: String, reason: CancellationReason): Boolean = false
    override fun acknowledgeTerminal(taskId: String): Boolean = false
    override fun release(sessionId: String, discardTerminal: Boolean) = Unit

    fun reset() {
        requests.clear()
        selectedModelsAtStart.clear()
        mutableActive.value = null
    }
}
