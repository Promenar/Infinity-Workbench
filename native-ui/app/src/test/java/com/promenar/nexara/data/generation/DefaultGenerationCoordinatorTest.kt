package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.generation.CancellationReason
import com.promenar.nexara.domain.generation.GenerationEvent
import com.promenar.nexara.domain.generation.GenerationFailure
import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationRequest
import com.promenar.nexara.domain.generation.GenerationRunner
import com.promenar.nexara.domain.generation.GenerationRuntimePolicy
import com.promenar.nexara.domain.generation.StartGenerationResult
import com.promenar.nexara.data.remote.ProviderResolution
import com.promenar.nexara.data.remote.ProviderResolutionError
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Test

class DefaultGenerationCoordinatorTest {
    @Test
    fun `同请求Existing不同会话Busy且只启动一个runner`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val coordinator = coordinator { _, emit ->
            emit(GenerationEvent.PhaseChanged(GenerationPhase.CONNECTING))
            entered.complete(Unit)
            awaitCancellation()
        }

        val first = coordinator.start(request("s1", "a1"))
        entered.await()
        val same = coordinator.start(request("s1", "a1"))
        val other = coordinator.start(request("s2", "a2"))

        assertThat(first).isInstanceOf(StartGenerationResult.Started::class.java)
        assertThat(same).isInstanceOf(StartGenerationResult.Existing::class.java)
        assertThat(other).isEqualTo(StartGenerationResult.Busy("s1"))
        assertThat(coordinator.active.value?.sessionId).isEqualTo("s1")
        assertThat(coordinator.observe("s2").value).isNull()
    }

    @Test
    fun `相同assistant但不同requestId必须Busy不能误判Existing`() = runTest {
        val coordinator = coordinator { _, _ -> awaitCancellation() }
        coordinator.start(request("s1", "same-assistant").copy(requestId = "request-1"))
        runCurrent()

        val second = coordinator.start(
            request("s1", "same-assistant").copy(requestId = "request-2"),
        )

        assertThat(second).isEqualTo(StartGenerationResult.Busy("s1"))
    }

    @Test
    fun `并发start通过Mutex只创建一次任务`() = runTest {
        val starts = AtomicInteger()
        val coordinator = coordinator { _, _ ->
            starts.incrementAndGet()
            awaitCancellation()
        }

        val results = List(8) { async { coordinator.start(request("s1", "a1")) } }.map { it.await() }
        runCurrent()

        assertThat(results.count { it is StartGenerationResult.Started }).isEqualTo(1)
        assertThat(results.count { it is StartGenerationResult.Existing }).isEqualTo(7)
        assertThat(starts.get()).isEqualTo(1)
    }

    @Test
    fun `陈旧taskId不能取消新任务且当前taskId可取消同一job`() = runTest {
        val coordinator = coordinator { _, _ -> awaitCancellation() }
        val started = coordinator.start(request("s1", "a1")) as StartGenerationResult.Started
        runCurrent()

        assertThat(coordinator.cancel("stale", CancellationReason.USER)).isFalse()
        assertThat(coordinator.cancel(started.taskId, CancellationReason.USER)).isTrue()
        runCurrent()
        assertThat(coordinator.active.value).isNull()
    }

    @Test
    fun `VM观察者消失不取消应用级任务且新观察者恢复同一task`() = runTest {
        val coordinator = coordinator { _, emit ->
            emit(GenerationEvent.PhaseChanged(GenerationPhase.STREAMING))
            awaitCancellation()
        }
        val started = coordinator.start(request("s1", "a1")) as StartGenerationResult.Started
        runCurrent()
        val observer = launch { coordinator.observe("s1").first { it?.phase == GenerationPhase.STREAMING } }
        observer.join()

        val restored = coordinator.observe("s1").value

        assertThat(restored?.taskId).isEqualTo(started.taskId)
        assertThat(restored?.phase).isEqualTo(GenerationPhase.STREAMING)
        assertThat(coordinator.active.value?.taskId).isEqualTo(started.taskId)
    }

    @Test
    fun `当前观察者收到完成终态并确认后新观察者只看到空闲`() = runTest {
        val completed = coordinator { _, emit ->
            emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
        }
        val terminal = async {
            completed.observe("ok").first { it?.phase == GenerationPhase.COMPLETED }!!
        }
        completed.start(request("ok", "a1"))
        runCurrent()

        val snapshot = terminal.await()
        assertThat(completed.active.value).isNull()
        assertThat(snapshot.phase).isEqualTo(GenerationPhase.COMPLETED)
        assertThat(completed.acknowledgeTerminal(snapshot.taskId)).isTrue()
        assertThat(completed.observe("ok").value).isNull()
        completed.release("ok")
        assertThat(completed.retainedSessionStateCount()).isEqualTo(0)
    }

    @Test
    fun `异常终态在确认前可恢复且确认后不污染新VM`() = runTest {
        val failed = coordinator { _, _ -> throw IllegalStateException("boom") }
        failed.start(request("bad", "a2"))
        runCurrent()

        assertThat(failed.active.value).isNull()
        val terminal = failed.observe("bad").value!!
        assertThat(terminal.phase).isEqualTo(GenerationPhase.FAILED)
        assertThat(terminal.error?.code).isEqualTo(GenerationFailureCode.UNKNOWN)
        assertThat(terminal.error?.technical).isEqualTo("boom")
        assertThat(terminal.error?.cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(failed.acknowledgeTerminal(terminal.taskId)).isTrue()
        assertThat(failed.observe("bad").value).isNull()
    }

    @Test
    fun `未确认终态有界保留且释放会话后map不增长`() = runTest {
        val coordinator = coordinator(terminalRetentionLimit = 3) { _, emit ->
            emit(GenerationEvent.PhaseChanged(GenerationPhase.COMPLETED))
        }

        repeat(20) { index ->
            coordinator.start(request("session-$index", "assistant-$index"))
            runCurrent()
        }

        assertThat(coordinator.retainedSessionStateCount()).isAtMost(3)
        repeat(20) { index -> coordinator.release("session-$index", discardTerminal = true) }
        assertThat(coordinator.retainedSessionStateCount()).isEqualTo(0)
    }

    @Test
    fun `路由handled失败携带设置入口状态直至显式确认且不重复终态写入`() = runTest {
        val presentationStore = GenerationPresentationStore()
        val handledFailure = GenerationFailure.unknown(technical = "请配置 API Key")
        val failure = ProviderResolution.Failure(
            reason = ProviderResolutionError.API_KEY_MISSING,
            modelId = "provider::model",
            providerId = "provider",
        )
        val coordinator = DefaultGenerationCoordinator(
            applicationScope = backgroundScope,
            presentationStore = presentationStore,
            runnerFactory = GenerationRunnerFactory { request, taskId ->
                GenerationRunner { _, emit ->
                    presentationStore.port(request.sessionId, taskId).setProviderFailure(failure)
                    presentationStore.port(request.sessionId, taskId).setError(handledFailure)
                    emit(GenerationEvent.Rejected(handledFailure))
                    emit(GenerationEvent.PhaseChanged(GenerationPhase.FAILED))
                }
            },
            taskIdFactory = { "route-task" },
        )

        coordinator.start(request("route-session", "assistant"))
        runCurrent()

        val terminal = coordinator.observe("route-session").value!!
        assertThat(terminal.phase).isEqualTo(GenerationPhase.FAILED)
        assertThat(terminal.error?.code).isEqualTo(GenerationFailureCode.UNKNOWN)
        assertThat(terminal.error?.technical).isEqualTo("请配置 API Key")
        assertThat(presentationStore.observe("route-session").value?.providerFailure).isEqualTo(failure)
        assertThat(coordinator.acknowledgeTerminal(terminal.taskId)).isTrue()
        assertThat(coordinator.observe("route-session").value).isNull()
        assertThat(presentationStore.observe("route-session").value).isNull()
    }

    @Test
    fun `持久化失败不得被runner后续原业务异常覆盖成普通FAILED`() = runTest {
        val original = IllegalStateException("network")
        val persistence = IllegalStateException("missing row")
        val persistenceFailure = GenerationFailure.persistence(
            technical = "missing row",
            cause = persistence,
        )
        val coordinator = coordinator { _, emit ->
            emit(GenerationEvent.PersistenceFailed(persistence, original, persistenceFailure))
            throw original
        }

        coordinator.start(request("durability", "assistant"))
        runCurrent()

        val terminal = coordinator.observe("durability").value!!
        assertThat(terminal.phase).isEqualTo(GenerationPhase.PERSISTENCE_FAILED)
        assertThat(terminal.error?.code).isEqualTo(GenerationFailureCode.PERSISTENCE)
        assertThat(terminal.error?.cause).isSameInstanceAs(persistence)
    }

    private fun TestScope.coordinator(
        terminalRetentionLimit: Int = 32,
        run: suspend (GenerationRequest, suspend (GenerationEvent) -> Unit) -> Unit,
    ) = DefaultGenerationCoordinator(
        applicationScope = backgroundScope,
        runnerFactory = GenerationRunnerFactory { _, _ -> GenerationRunner { request, emit -> run(request, emit) } },
        taskIdFactory = { "task-${nextTaskId.incrementAndGet()}" },
        terminalRetentionLimit = terminalRetentionLimit,
    )

    private fun request(sessionId: String, assistantId: String) = GenerationRequest(
        sessionId = sessionId,
        assistantMessageId = assistantId,
        userMessageId = "user",
        userContent = "hello",
        imageDataUrls = emptyList(),
        runtimePolicy = GenerationRuntimePolicy.BACKGROUND_ALLOWED,
    )

    private companion object {
        val nextTaskId = AtomicInteger()
    }
}
