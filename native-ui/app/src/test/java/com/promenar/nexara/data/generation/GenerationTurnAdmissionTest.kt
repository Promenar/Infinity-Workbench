package com.promenar.nexara.data.generation

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GenerationTurnAdmissionTest {
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
