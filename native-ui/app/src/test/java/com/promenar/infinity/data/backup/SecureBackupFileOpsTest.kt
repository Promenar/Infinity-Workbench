package com.promenar.infinity.data.backup

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.repository.PersistentFileIdentity
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.UserDefinedFileAttributeView

class SecureBackupFileOpsTest {
    @Test
    fun `恢复根标记和身份在设备号变化后保持一致`() = withRoot { base ->
        assumeSecureDirectoryStream(base)
        val root = Files.createDirectory(base.resolve("root"))
        val nonce = "11111111-1111-4111-8111-111111111111"

        val identity = SecureBackupFileOps.initializeWorkspaceRootIdentity(base, listOf("root"), nonce)
        val inode = PersistentFileIdentity.inode(Files.readAttributes(root, BasicFileAttributes::class.java).fileKey())
        val afterUpdateKey = "(dev=999,$inode)"

        assertThat(identity).isEqualTo(PersistentFileIdentity.encode(nonce, afterUpdateKey))
        assertThat(Files.readAllBytes(root.resolve(".infinity_root_identity")).toString(Charsets.UTF_8))
            .isEqualTo(PersistentFileIdentity.marker(nonce, afterUpdateKey))
    }

    @Test
    fun `descriptor清理接受设备号变化后的持久身份`() = withRoot { base ->
        assumeSecureDirectoryStream(base)
        val root = Files.createDirectory(base.resolve("restore-tx"))
        val expected = identityWithChangedDevice(root)

        SecureBackupFileOps.deleteTree(base, "restore-tx", expected)

        assertThat(Files.exists(root)).isFalse()
    }

    @Test
    fun `descriptor清理拒绝复制nonce到另一个inode的目录`() = withRoot { base ->
        val original = Files.createDirectory(base.resolve("original"))
        val expected = PersistentFileIdentity.read(original)
        val replacement = Files.createDirectory(base.resolve("restore-tx"))
        copyAttributes(original, replacement)
        Files.write(replacement.resolve("sentinel"), "keep".toByteArray())

        val failure = runCatching { SecureBackupFileOps.deleteTree(base, "restore-tx", expected) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupValidationException::class.java)
        assertThat(Files.readAllBytes(replacement.resolve("sentinel")).toString(Charsets.UTF_8)).isEqualTo("keep")
    }

    @Test
    fun `descriptor清理拒绝同inode但nonce不同的身份`() = withRoot { base ->
        val root = Files.createDirectory(base.resolve("restore-tx"))
        PersistentFileIdentity.read(root)
        val key = Files.readAttributes(root, BasicFileAttributes::class.java).fileKey()
        val expected = PersistentFileIdentity.encode("22222222-2222-4222-8222-222222222222", key)

        val failure = runCatching { SecureBackupFileOps.deleteTree(base, "restore-tx", expected) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupValidationException::class.java)
        assertThat(Files.isDirectory(root)).isTrue()
    }

    @Test
    fun `descriptor-relative write refuses a symlink destination`() {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".secure-restore-test")
        try {
            val root = Files.createDirectory(base.resolve("root"))
            val directory = Files.createDirectory(root.resolve("docs"))
            val outside = Files.write(base.resolve("outside"), "unchanged".toByteArray())
            Files.createSymbolicLink(directory.resolve("a.txt"), outside)

            val failure = runCatching {
                SecureBackupFileOps.writeNew(root, listOf("docs", "a.txt"), "overwrite".toByteArray())
            }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(Files.readAllBytes(outside).toString(Charsets.UTF_8)).isEqualTo("unchanged")
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `descriptor-relative cleanup refuses a swapped symlink root`() {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".secure-restore-test")
        try {
            val root = Files.createDirectory(base.resolve("restore-tx"))
            val key = PersistentFileIdentity.read(root)
            Files.delete(root)
            val outside = Files.createDirectory(base.resolve("outside"))
            Files.write(outside.resolve("sentinel"), byteArrayOf(1))
            Files.createSymbolicLink(base.resolve("restore-tx"), outside)

            val failure = runCatching {
                SecureBackupFileOps.deleteTree(base, "restore-tx", key)
            }.exceptionOrNull()

            assertThat(failure).isNotNull()
            assertThat(Files.exists(outside.resolve("sentinel"))).isTrue()
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    private fun identityWithChangedDevice(root: Path): String {
        val identity = PersistentFileIdentity.read(root)
        val inode = PersistentFileIdentity.inode(Files.readAttributes(root, BasicFileAttributes::class.java).fileKey())
        return PersistentFileIdentity.encode(PersistentFileIdentity.nonce(identity), "(dev=999,$inode)")
    }

    private fun copyAttributes(source: Path, target: Path) {
        val from = Files.getFileAttributeView(source, UserDefinedFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        val to = Files.getFileAttributeView(target, UserDefinedFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        from.list().forEach { name ->
            val bytes = ByteBuffer.allocate(from.size(name))
            from.read(name, bytes)
            bytes.flip()
            to.write(name, bytes)
        }
    }

    private fun assumeSecureDirectoryStream(base: Path) {
        assumeTrue(Files.newDirectoryStream(base).use { it is SecureDirectoryStream<*> })
    }

    private fun withRoot(block: (Path) -> Unit) {
        val base = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".secure-restore-test")
        try { block(base) } finally { base.toFile().deleteRecursively() }
    }
}
