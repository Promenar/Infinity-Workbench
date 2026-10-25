package com.promenar.nexara.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 真机回归：目录FD身份与跨根rename不能由宿主路径夹具替代。 */
class AndroidSecureWorkspacePrivateStorageTest {
    private fun fixture(block: (Path, WorkspaceInternalStorage, PrivateWorkspaceFileOps) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createDirectory(context.filesDir.toPath().resolve("private-storage-${UUID.randomUUID()}"))
        val privateBase = context.noBackupFilesDir.canonicalFile.toPath().resolve("private-storage-${UUID.randomUUID()}")
        val storage = WorkspaceInternalStorage(privateBase)
        try { block(root, storage, PrivateWorkspaceFileOps(storage)) }
        finally { root.toFile().deleteRecursively(); privateBase.toFile().deleteRecursively() }
    }

    @Test
    fun rootIdentityIsInvisibleAndExistingSecureReadersAcceptFdIdentity() = fixture { root, _, ops ->
        val identity = ops.ensureRoot(root)
        runBlocking { WorkspaceMutationCoordinator.withBoundRoot(root, identity) {
            ops.createFile(root, listOf("note.txt"), "内容".toByteArray())
            assertThat(Files.exists(root.resolve(".nexara_root_identity"))).isFalse()
            assertThat(SecureWorkspaceFileOps().read(root, listOf("note.txt")).toString(Charsets.UTF_8)).isEqualTo("内容")
            SecureWorkspaceFileOps().ensureRoot(root, initializeIdentity = false, expectedIdentity = identity)
            assertThat(Files.exists(root.resolve(".nexara_root_identity"))).isFalse()
            assertThat(Files.list(root).use { it.map { node -> node.fileName.toString() }.toList() })
                .containsExactly("note.txt", ".nexara")
        } }
    }

    @Test
    fun rootFdNonceCannotBeRebuiltFromPrivateIndexAndGuardRestoresOnlySameDirectory() = fixture { root, _, ops ->
        val identity = ops.ensureRoot(root)
        runBlocking { WorkspaceMutationCoordinator.withBoundRoot(root, identity) {
            val guard = WorkspaceRootIdentityGuard.capture(root, ops.captureRootIdentityRestorer(root))
            val key = Files.readAttributes(root, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey().toString()
            AndroidWorkspaceCreationProof.withRootDirectory(root, key) { fd ->
                AndroidWorkspaceFileIdentity.restoreRoot(fd.fd, UUID.randomUUID().toString())
            }
            assertThat(runCatching { ops.ensureRoot(root, initializeIdentity = false, expectedIdentity = identity) }.exceptionOrNull())
                .isInstanceOf(SecurityException::class.java)
            assertThat(guard.restoreIfChanged()).isTrue()
            assertThat(ops.ensureRoot(root, initializeIdentity = false, expectedIdentity = identity)).isEqualTo(identity)
            val retained = root.resolveSibling("${root.fileName}-retained")
            Files.move(root, retained)
            try {
                Files.createDirectory(root)
                assertThat(runCatching { guard.restoreIfChanged() }.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
                assertThat(Files.list(root).use { it.count() }).isEqualTo(0L)
            } finally { root.toFile().deleteRecursively(); Files.move(retained, root) }
        } }
    }

    @Test
    fun directoryCreationProofDoesNotLeaveMarkerAndLegacyMarkerRemainsRecoverable() = fixture { root, _, ops ->
        val identity = ops.ensureRoot(root)
        runBlocking { WorkspaceMutationCoordinator.withBoundRoot(root, identity) {
            ops.ensureDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
            val owner = listOf(CREATE_OWNERSHIP_DIRECTORY, "operation")
            ops.createDirectory(root, owner)
            ops.createDirectory(root, listOf("directory"))
            val token = UUID.randomUUID().toString()
            val node = ops.inspect(root, listOf("directory"))
            ops.retainCreationProof(root, listOf("directory"), owner, token)
            assertThat(ops.listChildren(root, listOf("directory"))).isEmpty()
            ops.verifyCreationProof(root, listOf("directory"), owner, token, node)
            ops.deleteCreatedNode(root, listOf("directory"), owner, token, node)
            assertThat(ops.exists(root, listOf("directory"))).isFalse()

            ops.createDirectory(root, listOf("legacy"))
            val legacy = ops.inspect(root, listOf("legacy"))
            ops.createFile(root, listOf("legacy", CREATE_DIRECTORY_MARKER), token.toByteArray())
            ops.verifyCreationProof(root, listOf("legacy"), owner, token, legacy)
            AndroidWorkspaceCreationProof.withDirectory(root, listOf("legacy"), { _, _, _ -> }) { file ->
                AndroidWorkspaceFileIdentity.create(file.fd, UUID.randomUUID().toString())
            }
            assertThat(runCatching { ops.verifyCreationProof(root, listOf("legacy"), owner, token, legacy) }.exceptionOrNull())
                .isInstanceOf(SecurityException::class.java)
        } }
    }

    @Test
    fun recyclingAcrossPrivateRootsKeepsBytesAndRollbackRestoresOriginal() = fixture { root, storage, ops ->
        val identity = ops.ensureRoot(root)
        runBlocking { WorkspaceMutationCoordinator.withBoundRoot(root, identity) {
            ops.createFile(root, listOf("note.txt"), "原文".toByteArray())
            ops.ensureDirectory(root, listOf(".recycle_bin"))
            val moved = ops.move(root, listOf("note.txt"), listOf(".recycle_bin", "note"))
            assertThat(ops.read(root, listOf(".recycle_bin", "note")).toString(Charsets.UTF_8)).isEqualTo("原文")
            assertThat(Files.exists(root.resolve(".recycle_bin"))).isFalse()
            assertThat(Files.exists(storage.area(identity).resolve("recycle/note"))).isTrue()
            moved.rollback()
            assertThat(ops.read(root, listOf("note.txt")).toString(Charsets.UTF_8)).isEqualTo("原文")
        } }
    }
}
