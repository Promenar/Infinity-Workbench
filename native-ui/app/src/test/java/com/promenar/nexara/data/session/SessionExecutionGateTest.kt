package com.promenar.nexara.data.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionExecutionGateTest {
    @Test
    fun `删除租约同时阻止会话与工作区入口并在完成后释放`() = runTest {
        val gate = SessionExecutionGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val deletion = async {
            gate.withDeletion("session-1", "root-1") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        assertThat(runCatching { gate.requireSessionWritable("session-1") }.exceptionOrNull())
            .isInstanceOf(SessionDeletingException::class.java)
        assertThat(runCatching { gate.requireWorkspaceWritable("root-1") }.exceptionOrNull())
            .isInstanceOf(SessionDeletingException::class.java)

        release.complete(Unit)
        deletion.await()
        gate.requireSessionWritable("session-1")
        gate.requireWorkspaceWritable("root-1")
    }

    @Test
    fun `并发双删按会话串行且不会共享删除态`() = runTest {
        val gate = SessionExecutionGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val first = async {
            gate.withDeletion("session-1", "root-1") {
                order += "first"
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            gate.withDeletion("session-1", "root-1") { order += "second" }
        }

        assertThat(order).containsExactly("first")
        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertThat(order).containsExactly("first", "second").inOrder()
    }
}
