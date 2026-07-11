package com.promenar.nexara.data.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

class SecureBackupFileOpsTest {
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
            val key = Files.readAttributes(
                root, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS
            ).fileKey().toString()
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
}
