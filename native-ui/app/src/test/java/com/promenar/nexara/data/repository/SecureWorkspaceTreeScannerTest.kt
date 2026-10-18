package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream

class SecureWorkspaceTreeScannerTest {
    private lateinit var root: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("scan-root").toFile()
        outside = Files.createTempDirectory("scan-outside").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        outside.deleteRecursively()
    }

    /** 宿主 JDK 不支持安全目录流时必须拒绝遍历，而不是降级为按路径访问。 */
    private fun secureSupported(): Boolean {
        val supported = Files.newDirectoryStream(root.toPath()).use { it is SecureDirectoryStream<*> }
        if (!supported) {
            val failure = runCatching { SecureWorkspaceTreeScanner.scan(root.toPath()) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(SecurityException::class.java)
        }
        return supported
    }

    @Test
    fun `普通条目、保留路径与不支持条目分开记录且不跟随符号链接`() {
        if (!secureSupported()) return
        File(root, "docs/sub").mkdirs()
        File(root, "docs/a.txt").writeText("abc")
        File(root, ".recycle_bin/x").mkdirs()
        File(root, ".nexara_versions/r/f").mkdirs()
        File(root, ".nexara_versions/r/f/v.snapshot").writeText("old")
        File(root, "docs/.nexara-write-tmp").writeText("t")
        File(root, "bad\\name").writeText("b")
        File(outside, "secret.txt").writeText("s")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())

        val snapshot = SecureWorkspaceTreeScanner.scan(root.toPath())

        assertThat(snapshot.nodes.keys).containsExactly("/bad\\name", "/docs", "/docs/a.txt", "/docs/sub", "/link")
        assertThat(snapshot.nodes.getValue("/docs/a.txt").kind).isEqualTo(WorkspaceScanKind.FILE)
        assertThat(snapshot.nodes.getValue("/docs/a.txt").sizeBytes).isEqualTo(3)
        assertThat(snapshot.nodes.getValue("/docs/a.txt").fileKey).isNotNull()
        assertThat(snapshot.nodes.getValue("/link").kind).isEqualTo(WorkspaceScanKind.UNSUPPORTED)
        assertThat(snapshot.nodes.getValue("/bad\\name").kind).isEqualTo(WorkspaceScanKind.UNSUPPORTED)
        assertThat(snapshot.reserved.keys).containsExactly(
            "/.nexara_versions", "/.nexara_versions/r", "/.nexara_versions/r/f", "/.nexara_versions/r/f/v.snapshot",
            "/.recycle_bin", "/.recycle_bin/x", "/docs/.nexara-write-tmp",
        )
    }

    @Test(expected = SecurityException::class)
    fun `根目录是符号链接时拒绝遍历`() {
        val link = File(outside, "root-link")
        Files.createSymbolicLink(link.toPath(), root.toPath())
        SecureWorkspaceTreeScanner.scan(link.toPath())
    }
}
