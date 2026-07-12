package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption

class WorkspaceReconcilePerformanceTest {
    @Test
    fun `production clean reconcile does not open sparse file content`() {
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".workspace-reconcile")
        var contentReads = 0
        val ops = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.RECONCILE_CONTENT_READ) contentReads += 1
        }
        try {
            val secure = Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> }
            if (!secure) {
                assertThat(runCatching { ops.ensureRoot(root) }.exceptionOrNull())
                    .isInstanceOf(SecurityException::class.java)
                return
            }
            val identity = ops.ensureRoot(root)
            WorkspaceMutationCoordinator.bindIdentityForTesting(root, identity)
            val sparse = root.resolve("large.bin")
            java.nio.channels.FileChannel.open(sparse, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                it.position(512L * 1024 * 1024 - 1)
                it.write(ByteBuffer.wrap(byteArrayOf(0)))
            }

            ops.reconcileFile(root, listOf("large.bin"), "unused-clean-path-hash")

            assertThat(contentReads).isEqualTo(0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
