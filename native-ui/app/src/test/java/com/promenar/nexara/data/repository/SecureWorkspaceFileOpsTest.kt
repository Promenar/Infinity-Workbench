package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream

class SecureWorkspaceFileOpsTest {
    @Test
    fun `parent symlink swap after descriptor open fails closed without outside write`() {
        val projectRoot = java.nio.file.Path.of(System.getProperty("user.dir"))
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
        val root = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("user.dir")), ".workspace-secure-root")
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
    fun `directory creation cleans temporary outside root after root swap`() {
        val base = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("user.dir")), ".workspace-secure-base")
        val root = Files.createDirectory(base.resolve("root"))
        val outside = Files.createDirectory(base.resolve("outside"))
        if (!verifySecureSupport(root)) {
            base.toFile().deleteRecursively()
            return
        }
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
            assertThat(failure).isInstanceOf(SecurityException::class.java)
            assertThat(Files.list(outside).use { it.count() }).isEqualTo(0L)
            assertThat(Files.exists(base.resolve("claimed-root/docs"))).isFalse()
        } finally {
            Files.deleteIfExists(root)
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `directory creation rejects root symlink before any mutation`() {
        val base = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("user.dir")), ".workspace-secure-base")
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
    fun `directory creation cleans bound temporary after post-create root swap`() {
        val base = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("user.dir")), ".workspace-secure-base")
        val root = Files.createDirectory(base.resolve("root"))
        val outside = Files.createDirectory(base.resolve("outside"))
        if (!verifySecureSupport(root)) {
            base.toFile().deleteRecursively()
            return
        }
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
            Files.deleteIfExists(root)
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed rollback restore keeps new file and remains retryable`() {
        val root = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("user.dir")), ".workspace-secure-root")
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
                stream.anyMatch { it.fileName.toString().startsWith(".nexara-rollback-") }
            }).isFalse()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `replace failure before mutation cleans temporary file`() {
        val root = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("user.dir")), ".workspace-secure-root")
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
                stream.anyMatch { it.fileName.toString().startsWith(".nexara-write-") }
            }).isFalse()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `directory creation rejects a plain replacement root without claimed identity`() {
        val base = Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("user.dir")), ".workspace-secure-base")
        val root = Files.createDirectory(base.resolve("root"))
        if (!verifySecureSupport(root)) {
            base.toFile().deleteRecursively()
            return
        }
        val ops = SecureWorkspaceFileOps()
        claim(ops, root)
        Files.move(root, base.resolve("claimed-root"))
        val copiedNonce = Files.readAllBytes(base.resolve("claimed-root/.nexara_root_identity"))
            .toString(Charsets.UTF_8).substringBefore('\n')
        Files.createDirectory(root)
        val replacementKey = Files.readAttributes(
            root,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).fileKey().toString()
        Files.write(root.resolve(".nexara_root_identity"), "$copiedNonce\n$replacementKey".toByteArray())
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
        if (!supported) {
            val failure = runCatching { SecureWorkspaceFileOps().ensureRoot(root) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SecurityException::class.java)
        }
        return supported
    }

    private fun claim(ops: SecureWorkspaceFileOps, root: java.nio.file.Path) {
        val identity = ops.ensureRoot(root)
        WorkspaceMutationCoordinator.bindIdentityForTesting(root, identity)
    }
}
