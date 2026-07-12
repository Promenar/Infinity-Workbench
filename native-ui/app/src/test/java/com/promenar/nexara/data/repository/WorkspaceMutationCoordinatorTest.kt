package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceMutationCoordinatorTest {
    @Test
    fun `active root identity is pinned while bounded cache trims`() = runBlocking<Unit> {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".workspace-coordinator")
        val holder = Files.createDirectory(base.resolve("holder"))
        val blockerEntered = CompletableDeferred<Unit>()
        val releaseBlocker = CompletableDeferred<Unit>()
        try {
            val blocker = async(Dispatchers.Default) {
                WorkspaceMutationCoordinator.withRoot(holder) {
                    blockerEntered.complete(Unit)
                    releaseBlocker.await()
                }
            }
            blockerEntered.await()
            val waiter = async(Dispatchers.Default) {
                WorkspaceMutationCoordinator.withBoundRoot(holder, "holder-identity") { }
            }
            kotlinx.coroutines.delay(100)
            repeat(4_097) { index ->
                WorkspaceMutationCoordinator.bindIdentityForTesting(base.resolve("root-$index"), "identity-$index")
            }

            assertThat(WorkspaceMutationCoordinator.expectedIdentity(holder)).isEqualTo("holder-identity")
            releaseBlocker.complete(Unit)
            blocker.await()
            waiter.await()
        } finally {
            releaseBlocker.complete(Unit)
            base.toFile().deleteRecursively()
        }
    }
}
