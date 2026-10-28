package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ProductRenameWorkspaceCompatibilityTest {
    @Test
    fun `旧版本目录迁移中断时可读但禁止新写入旧目录`() = runBlocking {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".rename-read-fallback-")
        try {
            val root = Files.createDirectory(base.resolve("workspace"))
            Files.createDirectory(root.resolve(".nexara_versions"))
            Files.write(root.resolve(".nexara_versions/original"), "旧版本".toByteArray())
            val disk = TestWorkspaceFileOps()
            val identity = disk.ensureRoot(root)
            val failing = object : WorkspaceFileOps by disk {
                override fun moveAcrossRoots(sourceRoot: Path, source: List<String>, targetRoot: Path, target: List<String>): WorkspaceFileRollback =
                    throw java.io.IOException("迁移中断")
            }
            val ops = PrivateWorkspaceFileOps(WorkspaceInternalStorage(base.resolve("private")), failing)
            WorkspaceMutationCoordinator.withBoundRoot(root, identity) {
                assertThat(ops.read(root, listOf(".infinity_versions", "original")).toString(Charsets.UTF_8)).isEqualTo("旧版本")
                assertThat(runCatching { ops.createFile(root, listOf(".infinity_versions", "new"), "新内容".toByteArray()) }.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
                assertThat(runCatching { ops.reconcileFile(root, listOf(".infinity_versions", "original"), "0".repeat(64)) }.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
                assertThat(runCatching { ops.retainCreationProof(root, listOf(".infinity_versions", "original"), listOf(".infinity_versions"), UUID.randomUUID().toString()) }.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
            }
            assertThat(Files.exists(root.resolve(".nexara_versions/new"))).isFalse()
            assertThat(Files.readAllBytes(root.resolve(".nexara_versions/original")).toString(Charsets.UTF_8)).isEqualTo("旧版本")
        } finally { base.toFile().deleteRecursively() }
    }

    @Test
    fun `旧创建归属容器迁至当前目录且保留暂存内容`() = runBlocking {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".rename-owner-")
        try {
            val root = Files.createDirectory(base.resolve("workspace"))
            val delegate = TestWorkspaceFileOps()
            val identity = delegate.ensureRoot(root)
            Files.createDirectories(root.resolve(".nexara/create-operations/op"))
            Files.write(root.resolve(".nexara/create-operations/op/staged"), "未发布内容".toByteArray())
            Files.write(root.resolve(".nexara/create-operations/op.cleanup-v2"), "清理回执".toByteArray())
            val ops = PrivateWorkspaceFileOps(WorkspaceInternalStorage(base.resolve("private")), delegate)
            WorkspaceMutationCoordinator.withBoundRoot(root, identity) {
                assertThat(ops.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op", "staged"))).isTrue()
                assertThat(ops.read(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op", "staged")).toString(Charsets.UTF_8)).isEqualTo("未发布内容")
                assertThat(ops.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY, "op.cleanup-v2"))).isTrue()
            }
            assertThat(Files.exists(root.resolve(".nexara/create-operations"))).isFalse()
            assertThat(Files.exists(root.resolve(".infinity/create-operations/op/staged"))).isTrue()
        } finally { base.toFile().deleteRecursively() }
    }

    @Test
    fun `新旧创建归属容器并存时保留双方并拒绝猜测`() = runBlocking {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".rename-owner-conflict-")
        try {
            val root = Files.createDirectory(base.resolve("workspace"))
            val delegate = TestWorkspaceFileOps()
            val identity = delegate.ensureRoot(root)
            Files.createDirectories(root.resolve(".nexara/create-operations"))
            Files.createDirectories(root.resolve(".infinity/create-operations"))
            val ops = PrivateWorkspaceFileOps(WorkspaceInternalStorage(base.resolve("private")), delegate)
            WorkspaceMutationCoordinator.withBoundRoot(root, identity) {
                assertThat(runCatching { ops.exists(root, listOf(CREATE_OWNERSHIP_DIRECTORY)) }.exceptionOrNull()).isInstanceOf(SecurityException::class.java)
            }
            assertThat(Files.exists(root.resolve(".nexara/create-operations"))).isTrue()
            assertThat(Files.exists(root.resolve(".infinity/create-operations"))).isTrue()
        } finally { base.toFile().deleteRecursively() }
    }

    @Test
    fun `命令后恢复单独旧根标记以及双根标记`() {
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".rename-guard-")
        try {
            val old = root.resolve(".nexara_root_identity")
            val current = root.resolve(".infinity_root_identity")
            Files.write(old, "身份快照".toByteArray())
            val oldOnly = WorkspaceRootIdentityGuard.capture(root)
            Files.delete(old)
            assertThat(oldOnly.restoreIfChanged()).isTrue()
            assertThat(Files.readAllBytes(old).toString(Charsets.UTF_8)).isEqualTo("身份快照")
            assertThat(Files.exists(current)).isFalse()
            Files.write(current, "身份快照".toByteArray())
            val both = WorkspaceRootIdentityGuard.capture(root)
            Files.write(old, "被修改".toByteArray())
            Files.delete(current)
            assertThat(both.restoreIfChanged()).isTrue()
            assertThat(Files.readAllBytes(old)).isEqualTo(Files.readAllBytes(current))
            assertThat(both.restoreIfChanged()).isFalse()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun `新旧临时文件严格按类别和完整 UUID 接管清理`() {
        val token = "11111111-2222-4333-8444-555555555555"
        listOf(".nexara", ".infinity").forEach { prefix ->
            assertThat(com.promenar.infinity.data.storage.ExternalFolderLegacyTemporaries.matches("$prefix-write-$token")).isTrue()
            assertThat(com.promenar.infinity.data.storage.ExternalFolderLegacyTemporaries.matches("$prefix-unknown-$token")).isFalse()
            assertThat(com.promenar.infinity.data.storage.ExternalFolderLegacyTemporaries.matches("$prefix-write-orphan")).isFalse()
        }
    }

    @Test
    fun `新旧保留名都拒绝作为用户文件路径`() {
        listOf(".nexara", ".NEXARA-old", ".nexara_root_identity", ".infinity", ".INFINITY-old").forEach {
            assertThat(WorkspaceInternalPaths.isReservedName(it)).isTrue()
            assertThat(WorkspaceInternalPaths.containsReservedPath("docs/$it/file")).isTrue()
        }
    }

    @Test
    fun `旧根标记及旧回收目录可读取恢复`() = runBlocking {
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".rename-workspace-")
        try {
            assumeTrue(Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> })
            val key = Files.readAttributes(root, BasicFileAttributes::class.java).fileKey()
            Files.write(root.resolve(".nexara_root_identity"), PersistentFileIdentity.marker(UUID.randomUUID().toString(), key).toByteArray())
            val ops = SecureWorkspaceFileOps()
            val identity = ops.ensureRoot(root, initializeIdentity = false)
            WorkspaceMutationCoordinator.withBoundRoot(root, identity) {
                val tombstones = Files.createDirectories(root.resolve(".nexara/tombstones"))
                val token = "a".repeat(24)
                Files.write(tombstones.resolve(token), "旧文件".toByteArray())
                val result = ops.recoverTombstones(root) { if (it == token) listOf("restored.txt") else null }
                assertThat(result.attentionTokens).isEmpty()
                assertThat(Files.readAllBytes(root.resolve("restored.txt")).toString(Charsets.UTF_8)).isEqualTo("旧文件")
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
