package com.promenar.infinity.data.repository

import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream

class AndroidPortedSecureWorkspaceFileOpsTest {
    private val testDirectory get() = InstrumentationRegistry.getInstrumentation().targetContext.filesDir.toPath()

    @Test
    fun `parent symlink swap after descriptor open fails closed without outside write`() {
        val projectRoot = testDirectory
        val root = Files.createTempDirectory(projectRoot, ".workspace-secure-root")
        val outside = Files.createTempDirectory(projectRoot, ".workspace-secure-outside")
        val safe = Files.createDirectory(root.resolve("safe"))
        if (!verifySecureSupport(root)) {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
            return
        }
        var swapped = false
        val ops = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.BEFORE_MUTATION && !swapped) {
                swapped = true
                Files.move(safe, root.resolve("safe-original"))
                Files.createSymbolicLink(root.resolve("safe"), outside)
            }
        }
        claim(ops, root)

        var rejected = false
        var outsideWritten = false
        try {
            ops.createFile(root, listOf("safe", "new.txt"), "secret".toByteArray())
        } catch (_: SecurityException) {
            rejected = true
        } finally {
            outsideWritten = Files.exists(outside.resolve("new.txt"))
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }

        assertThat(rejected).isTrue()
        assertThat(outsideWritten).isFalse()
    }

    @Test
    fun `move requires existing descriptor target parent`() {
        val root = Files.createTempDirectory(testDirectory, ".workspace-secure-root")
        if (!verifySecureSupport(root)) {
            root.toFile().deleteRecursively()
            return
        }
        try {
            Files.write(root.resolve("source.txt"), "x".toByteArray())
            Files.createDirectory(root.resolve("recycle"))
            claim(SecureWorkspaceFileOps(), root)

            val failure = runCatching {
                SecureWorkspaceFileOps().move(
                    root,
                    listOf("source.txt"),
                    listOf("recycle", "missing", "source.txt"),
                )
            }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(Files.readAllBytes(root.resolve("source.txt")).toString(Charsets.UTF_8)).isEqualTo("x")
            assertThat(Files.exists(root.resolve("recycle/missing"))).isFalse()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `directory creation rejects root symlink before any mutation`() {
        val base = Files.createTempDirectory(testDirectory, ".workspace-secure-base")
        val outside = Files.createDirectory(base.resolve("outside"))
        val root = Files.createDirectory(base.resolve("root"))
        if (!verifySecureSupport(root)) {
            base.toFile().deleteRecursively()
            return
        }
        claim(SecureWorkspaceFileOps(), root)
        Files.move(root, base.resolve("claimed-root"))
        Files.createSymbolicLink(root, outside)
        try {
            val failure = runCatching {
                SecureWorkspaceFileOps().createDirectory(root, listOf("docs"))
            }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SecurityException::class.java)
            assertThat(Files.list(outside).use { it.count() }).isEqualTo(0L)
        } finally {
            Files.deleteIfExists(root)
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed rollback restore keeps new file and remains retryable`() {
        val root = Files.createTempDirectory(testDirectory, ".workspace-secure-root")
        if (!verifySecureSupport(root)) {
            root.toFile().deleteRecursively()
            return
        }
        var failOnce = true
        val ops = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.ROLLBACK_BEFORE_RESTORE && failOnce) {
                failOnce = false
                throw IllegalStateException("injected restore failure")
            }
        }
        try {
            claim(ops, root)
            Files.write(root.resolve("file.txt"), "old".toByteArray())
            val rollback = ops.replaceFile(root, listOf("file.txt"), "new".toByteArray())

            assertThat(runCatching { rollback.rollback() }.exceptionOrNull()).isNotNull()
            assertThat(Files.readAllBytes(root.resolve("file.txt")).toString(Charsets.UTF_8)).isEqualTo("new")
            rollback.rollback()
            assertThat(Files.readAllBytes(root.resolve("file.txt")).toString(Charsets.UTF_8)).isEqualTo("old")
            assertThat(Files.list(root).use { stream ->
                stream.anyMatch { it.fileName.toString().startsWith(".infinity-rollback-") }
            }).isFalse()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replace failure before mutation cleans temporary file`() {
        val root = Files.createTempDirectory(testDirectory, ".workspace-secure-root")
        if (!verifySecureSupport(root)) {
            root.toFile().deleteRecursively()
            return
        }
        val ops = SecureWorkspaceFileOps { phase ->
            if (phase == WorkspaceFilePhase.BEFORE_MUTATION) throw IllegalStateException("injected failure")
        }
        try {
            claim(ops, root)
            Files.write(root.resolve("file.txt"), "old".toByteArray())

            assertThat(runCatching {
                ops.replaceFile(root, listOf("file.txt"), "new".toByteArray())
            }.exceptionOrNull()).isNotNull()
            assertThat(Files.readAllBytes(root.resolve("file.txt")).toString(Charsets.UTF_8)).isEqualTo("old")
            assertThat(Files.list(root).use { stream ->
                stream.anyMatch { it.fileName.toString().startsWith(".infinity-write-") }
            }).isFalse()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `directory creation rejects a plain replacement root without claimed identity`() {
        val base = Files.createTempDirectory(testDirectory, ".workspace-secure-base")
        val root = Files.createDirectory(base.resolve("root"))
        if (!verifySecureSupport(root)) {
            base.toFile().deleteRecursively()
            return
        }
        val ops = SecureWorkspaceFileOps()
        claim(ops, root)
        Files.move(root, base.resolve("claimed-root"))
        val copiedNonce = Files.readAllBytes(base.resolve("claimed-root/.infinity_root_identity"))
            .toString(Charsets.UTF_8).substringBefore('\n')
        Files.createDirectory(root)
        val replacementKey = Files.readAttributes(
            root,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).fileKey().toString()
        Files.write(root.resolve(".infinity_root_identity"), "$copiedNonce\n$replacementKey".toByteArray())
        try {
            val failure = runCatching { ops.createDirectory(root, listOf("docs")) }.exceptionOrNull()

            assertThat(failure).isInstanceOf(SecurityException::class.java)
            assertThat(Files.exists(root.resolve("docs"))).isFalse()
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun verifySecureSupport(root: java.nio.file.Path): Boolean {
        val supported = Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> }
        assertThat(supported).isTrue()
        return supported
    }

    private fun claim(ops: SecureWorkspaceFileOps, root: java.nio.file.Path) {
        val identity = ops.ensureRoot(root)
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, identity)
    }

    @Test
    fun `旧根标记及旧回收目录可读取恢复`() = runBlocking {
        val root = Files.createTempDirectory(testDirectory, ".rename-workspace-")
        try {
            assertThat(Files.newDirectoryStream(root).use { it is SecureDirectoryStream<*> }).isTrue()
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
