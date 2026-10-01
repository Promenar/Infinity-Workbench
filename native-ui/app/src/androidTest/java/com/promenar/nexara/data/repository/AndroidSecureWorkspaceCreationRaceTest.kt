package com.promenar.nexara.data.repository

import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

/** Android FD 桥接的创建、竞态与回滚合同必须在真实应用私有目录执行。 */
class AndroidSecureWorkspaceCreationRaceTest {
    private val testDirectory: Path
        get() = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.toPath()

    @Test
    fun directoryDeletionResumesAfterMarkerRemovalWithoutRecursiveDelete() {
        val root = createTemporaryDirectory("workspace-creation-phase")
        verifySecureSupport(root)
        try {
            val ops = SecureWorkspaceFileOps { phase ->
                if (phase == WorkspaceFilePhase.CREATION_MARKER_REMOVED) throw IllegalStateException("模拟进程中断")
            }
            claim(ops, root)
            ops.createDirectory(root, listOf("owner"))
            ops.createDirectory(root, listOf("created"))
            val identity = ops.inspect(root, listOf("created"))
            val token = java.util.UUID.randomUUID().toString()
            ops.retainCreationProof(root, listOf("created"), listOf("owner"), token)
            assertThat(runCatching {
                ops.deleteCreatedNode(root, listOf("created"), listOf("owner"), token, identity)
            }.isFailure).isTrue()
            val quarantine = listOf("owner", CREATE_ROLLBACK_NODE)
            assertThat(Files.exists(root.resolve("owner/$CREATE_DIRECTORY_DELETE_READY"))).isTrue()
            assertThat(Files.exists(root.resolve("owner/$CREATE_ROLLBACK_NODE/$CREATE_DIRECTORY_MARKER"))).isFalse()
            SecureWorkspaceFileOps().deleteCreatedNode(root, quarantine, listOf("owner"), token, identity)
            assertThat(Files.exists(root.resolve("owner/$CREATE_ROLLBACK_NODE"))).isFalse()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun directoryRollbackKeepsAddedFiles() {
        val root = createTemporaryDirectory("workspace-creation-proof")
        verifySecureSupport(root)
        try {
            val ops = SecureWorkspaceFileOps()
            claim(ops, root)
            ops.createDirectory(root, listOf("owner"))
            ops.createDirectory(root, listOf("owner", "stage"))
            val identity = ops.inspect(root, listOf("owner", "stage"))
            val token = java.util.UUID.randomUUID().toString()
            ops.retainCreationProof(root, listOf("owner", "stage"), listOf("owner"), token)
            ops.move(root, listOf("owner", "stage"), listOf("created")).commit()
            ops.createFile(root, listOf("created", "keep.txt"), "keep".toByteArray())

            assertThat(runCatching {
                ops.deleteCreatedNode(root, listOf("created"), listOf("owner"), token, identity)
            }.isFailure).isTrue()
            assertThat(ops.read(root, listOf("created", "keep.txt")).toString(Charsets.UTF_8)).isEqualTo("keep")

            ops.delete(root, listOf("created", "keep.txt"))
            ops.deleteCreatedNode(root, listOf("created"), listOf("owner"), token, identity)
            assertThat(Files.exists(root.resolve("created"))).isFalse()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun creationRollbackRetainsNodeReplacedBeforeQuarantine() {
        val root = createTemporaryDirectory("workspace-creation-race")
        verifySecureSupport(root)
        try {
            var armed = false
            val ops = SecureWorkspaceFileOps { phase ->
                if (armed && phase == WorkspaceFilePhase.BEFORE_MUTATION) {
                    armed = false
                    Files.move(root.resolve("created"), root.resolve("original"))
                    Files.createDirectory(root.resolve("created"))
                    Files.write(root.resolve("created/keep.txt"), "keep".toByteArray())
                }
            }
            claim(ops, root)
            ops.createDirectory(root, listOf("owner"))
            ops.createDirectory(root, listOf("created"))
            val identity = ops.inspect(root, listOf("created"))
            val token = java.util.UUID.randomUUID().toString()
            ops.retainCreationProof(root, listOf("created"), listOf("owner"), token)
            armed = true

            assertThat(runCatching {
                ops.deleteCreatedNode(root, listOf("created"), listOf("owner"), token, identity)
            }.isFailure).isTrue()
            assertThat(ops.read(root, listOf("created", "keep.txt")).toString(Charsets.UTF_8)).isEqualTo("keep")
            assertThat(Files.isDirectory(root.resolve("original"))).isTrue()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun directoryCreationDoesNotWriteOutsideAfterRootSwap() {
        val base = createTemporaryDirectory(".workspace-secure-base")
        val root = Files.createDirectory(base.resolve("root"))
        val outside = Files.createDirectory(base.resolve("outside"))
        verifySecureSupport(root)
        var swapped = false
        val ops = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.DESCRIPTOR_OPENED && !swapped) {
                swapped = true
                Files.move(root, base.resolve("claimed-root"))
                Files.createSymbolicLink(root, outside)
            }
        }
        claim(ops, root)
        try {
            val failure = runCatching { ops.createDirectory(root, listOf("docs")) }.exceptionOrNull()
            // O_NOFOLLOW 在真实 Android 上以 ELOOP 拒绝已替换为符号链接的根。
            assertThat(swapped).isTrue()
            assertThat(failure).isInstanceOf(ErrnoException::class.java)
            assertThat((failure as ErrnoException).errno).isEqualTo(OsConstants.ELOOP)
            assertThat(Files.list(outside).use { it.count() }).isEqualTo(0L)
            assertThat(Files.exists(base.resolve("claimed-root/docs"))).isFalse()
        } finally {
            if (Files.isSymbolicLink(root)) Files.deleteIfExists(root)
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun directoryCreationCleansBoundTemporaryAfterPostCreateRootSwap() {
        val base = createTemporaryDirectory(".workspace-secure-base")
        val root = Files.createDirectory(base.resolve("root"))
        val outside = Files.createDirectory(base.resolve("outside"))
        verifySecureSupport(root)
        var swapped = false
        val ops = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.DIRECTORY_TEMP_CREATED && !swapped) {
                swapped = true
                Files.move(root, base.resolve("claimed-root"))
                Files.createSymbolicLink(root, outside)
            }
        }
        claim(ops, root)
        try {
            val failure = runCatching { ops.createDirectory(root, listOf("docs")) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SecurityException::class.java)
            assertThat(Files.list(outside).use { it.count() }).isEqualTo(0L)
            assertThat(Files.list(base.resolve("claimed-root")).use { stream ->
                stream.anyMatch { it.fileName.toString().startsWith(".mkdir-") || it.fileName.toString() == "docs" }
            }).isFalse()
        } finally {
            if (Files.isSymbolicLink(root)) Files.deleteIfExists(root)
            base.toFile().deleteRecursively()
        }
    }

    private val temporaryRoots = mutableListOf<Path>()

    private fun createTemporaryDirectory(prefix: String): Path =
        Files.createTempDirectory(testDirectory, prefix).also { temporaryRoots.add(it) }

    @After
    fun cleanTemporaryRoots() {
        temporaryRoots.forEach { it.toFile().deleteRecursively() }
    }

    private fun verifySecureSupport(root: Path) {
        assertThat(Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> }).isTrue()
    }

    private fun claim(ops: SecureWorkspaceFileOps, root: Path) {
        val identity = ops.ensureRoot(root)
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, identity)
    }
}
