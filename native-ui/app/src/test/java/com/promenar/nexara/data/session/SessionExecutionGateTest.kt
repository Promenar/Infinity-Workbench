package com.promenar.nexara.data.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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

    @Test
    fun `删除关闭admission后等待既有写租约且嵌套同资源只计最外层`() = runTest {
        val gate = SessionExecutionGate()
        val writeEntered = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var deletionEntered = false
        val write = launch {
            gate.withSessionAdmission("session-1") {
                gate.withSessionAdmission("session-1") {
                    gate.withWorkspaceAdmission("root-1") {
                        writeEntered.complete(Unit)
                        releaseWrite.await()
                    }
                }
            }
        }
        writeEntered.await()
        val deletion = async {
            gate.withDeletion("session-1", "root-1") { deletionEntered = true }
        }
        while (!gate.isDeleting("session-1")) yield()

        assertThat(deletionEntered).isFalse()
        assertThat(runCatching {
            gate.withWorkspaceAdmission("root-1") { Unit }
        }.exceptionOrNull()).isInstanceOf(SessionDeletingException::class.java)

        releaseWrite.complete(Unit)
        write.join()
        deletion.await()
        assertThat(deletionEntered).isTrue()
    }

    @Test
    fun `root claim冲突不会泄漏失败会话的deleting tombstone`() = runTest {
        val gate = SessionExecutionGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = async {
            gate.withDeletion("session-a", "shared-root") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        assertThat(runCatching {
            gate.withDeletion("session-b", "shared-root") { Unit }
        }.exceptionOrNull()).isNotNull()
        assertThat(gate.isDeleting("session-b")).isFalse()
        gate.withSessionAdmission("session-b") { Unit }

        release.complete(Unit)
        owner.await()
    }

    @Test
    fun `删除关闭后嵌套既有写可收尾但新的生成admission必须拒绝`() = runTest {
        val gate = SessionExecutionGate()
        val deletionEntered = CompletableDeferred<Unit>()
        val releaseDeletion = CompletableDeferred<Unit>()
        gate.withSessionAdmission("session-1") {
            val deletion = backgroundScope.async {
                gate.withDeletion("session-1", "root-1") {
                    deletionEntered.complete(Unit)
                    releaseDeletion.await()
                }
            }
            while (!gate.isDeleting("session-1")) yield()

            gate.withSessionAdmission("session-1") { Unit }
            assertThat(runCatching {
                gate.withNewSessionAdmission("session-1") { Unit }
            }.exceptionOrNull()).isInstanceOf(SessionDeletingException::class.java)
            releaseDeletion.complete(Unit)
        }
        deletionEntered.await()
    }
}
