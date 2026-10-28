package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GenerationTurnAdmissionTest {
    @Test fun `子协程不能继承同会话重入租约且父体退出后可以进入`() = runTest {
        val admission = GenerationTurnAdmission()
        val parentBody = CompletableDeferred<Unit>()
        var childEntered = false
        val parent = async {
            admission.withLock("a") {
                CoroutineScope(currentCoroutineContext()).launch {
                    admission.withLock("a") { childEntered = true }
                }
                parentBody.await()
            }
        }
        runCurrent()
        try { assertThat(childEntered).isFalse() }
        finally { parentBody.complete(Unit) }
        parent.await()
        assertThat(childEntered).isTrue()
    }

    @Test fun `全局租约的子协程必须重新等待而其它会话子协程可独立准入`() = runTest {
        val admission = GenerationTurnAdmission()
        val parentBody = CompletableDeferred<Unit>()
        var childEntered = false
        val parent = async {
            admission.withLock {
                CoroutineScope(currentCoroutineContext()).launch {
                    admission.withLock("a") { childEntered = true }
                }
                parentBody.await()
            }
        }
        runCurrent()
        try { assertThat(childEntered).isFalse() }
        finally { parentBody.complete(Unit) }
        parent.await()
        assertThat(childEntered).isTrue()
        admission.withLock("a") {
            val child = CoroutineScope(currentCoroutineContext()).async { admission.withLock("b") { "b" } }
            assertThat(child.await()).isEqualTo("b")
        }
    }

    @Test fun `不同会话可并行且同一会话保持排他`() = runTest {
        val admission = GenerationTurnAdmission()
        val gate = CompletableDeferred<Unit>()
        val first = async { admission.withLock("a") { gate.await() } }
        runCurrent()
        val same = async { admission.withLock("a") { "same" } }
        val other = async { admission.withLock("b") { "other" } }
        runCurrent()
        assertThat(other.await()).isEqualTo("other")
        assertThat(same.isCompleted).isFalse()
        gate.complete(Unit); first.await()
        assertThat(same.await()).isEqualTo("same")
    }

    @Test fun `旧全局入口排他且等待被取消后不会阻塞其它会话`() = runTest {
        val admission = GenerationTurnAdmission()
        val gate = CompletableDeferred<Unit>()
        val first = async { admission.withLock("a") { gate.await() } }
        runCurrent()
        val global = async { admission.withLock { "global" } }
        runCurrent()
        val other = async { admission.withLock("b") { "other" } }
        runCurrent()
        assertThat(global.isCompleted).isFalse()
        assertThat(other.isCompleted).isFalse()
        global.cancelAndJoin(); runCurrent()
        assertThat(other.await()).isEqualTo("other")
        gate.complete(Unit); first.await()
    }

    @Test fun `全局入口与会话入口可以按同协程的持有范围重入`() = runTest {
        val admission = GenerationTurnAdmission()
        assertThat(admission.withLock { admission.withLock("a") { "global" } }).isEqualTo("global")
        assertThat(admission.withLock("a") { admission.withLock("a") { "session" } }).isEqualTo("session")
    }

    @Test fun `审批决定到接续回调之间不能插入另一轮且同协程可重入`() = runTest {
        val admission = GenerationTurnAdmission()
        val approved = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val approval = async {
            admission.withLock {
                events += "decision"
                approved.complete(Unit)
                resume.await()
                admission.withLock { events += "resume" }
            }
        }
        approved.await()
        val trigger = async { admission.withLock { events += "trigger" } }
        runCurrent()
        assertThat(trigger.isCompleted).isFalse()
        resume.complete(Unit); approval.await(); trigger.await()
        assertThat(events).containsExactly("decision", "resume", "trigger").inOrder()
    }
}
