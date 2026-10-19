package com.promenar.nexara.data.git

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream

class NioGitWorkTreeReaderTest {
    private val work: File = Files.createTempDirectory("git-worktree").toFile().canonicalFile
    private val root = File(work, "repo").apply { mkdirs() }
    private val outside = File(work, "outside").apply { mkdirs() }
    /** 描述符相对访问在 Linux/Android 可用，macOS 的 JVM 不支持；支持时两种实现都跑同一组断言。 */
    private val secureSupported = Files.newDirectoryStream(root.toPath()).use { it is SecureDirectoryStream<*> }
    private val readers = listOfNotNull(
        NioGitWorkTreeReader(maxBytes = 64, pathFallback = true),
        NioGitWorkTreeReader(maxBytes = 64).takeIf { secureSupported },
    )

    private fun eachReader(block: (NioGitWorkTreeReader) -> Unit) = readers.forEach(block)

    @After fun tearDown() { work.deleteRecursively() }

    /** 每个实现各跑一遍，符号链接需可重复创建。 */
    private fun relink(link: java.nio.file.Path, target: java.nio.file.Path) {
        Files.deleteIfExists(link)
        Files.createSymbolicLink(link, target)
    }

    private fun write(path: String, bytes: ByteArray): File = File(root, path).apply {
        parentFile.mkdirs()
        writeBytes(bytes)
    }

    @Test fun `读取工作区内的文本文件`() {
        eachReader {
            write("src/中文.kt", "val a = 1\n".toByteArray())
            val content = it.readBlocking(root, "src/中文.kt")
            assertThat(content).isEqualTo(GitFileContent(exists = true, size = 10, text = "val a = 1\n"))
        }
    }

    @Test fun `不存在、目录与中间片段是文件时返回不存在`() {
        eachReader {
            write("a.txt", "a".toByteArray())
            File(root, "dir").mkdirs()
            assertThat(it.readBlocking(root, "missing.txt")).isEqualTo(GitFileContent.Absent)
            assertThat(it.readBlocking(root, "nested/missing.txt")).isEqualTo(GitFileContent.Absent)
            assertThat(it.readBlocking(root, "dir")).isEqualTo(GitFileContent.Absent)
            assertThat(it.readBlocking(root, "a.txt/child")).isEqualTo(GitFileContent.Absent)
        }
    }

    @Test fun `拒绝越界片段、绝对路径与 git 目录`() {
        eachReader {
            File(outside, "secret.txt").writeText("secret")
            for (path in listOf("../outside/secret.txt", "src/../../outside/secret.txt", "/etc/hosts", "", "a//b", "./a", "a\\b", ".git/config", "sub/.GIT/HEAD")) {
                assertThrows(path, GitUnsafePathException::class.java) { it.readBlocking(root, path) }
            }
        }
    }

    @Test fun `中间目录是符号链接时拒绝，不跟随到工作区之外`() {
        eachReader {
            File(outside, "secret.txt").writeText("secret")
            relink(File(root, "link").toPath(), outside.toPath())
            assertThrows(GitUnsafePathException::class.java) { it.readBlocking(root, "link/secret.txt") }
        }
    }

    @Test fun `末段符号链接返回链接目标文本而不读取目标内容`() {
        eachReader {
            File(outside, "secret.txt").writeText("secret")
            relink(File(root, "pointer").toPath(), java.nio.file.Paths.get("../outside/secret.txt"))
            val content = it.readBlocking(root, "pointer")
            assertThat(content.symlink).isTrue()
            assertThat(content.text).isEqualTo("../outside/secret.txt")
            assertThat(content.text).doesNotContain("secret\n")
        }
    }

    @Test fun `工作区根本身是符号链接时拒绝`() {
        eachReader {
            val linkedRoot = File(work, "linked-root")
            relink(linkedRoot.toPath(), root.toPath())
            write("a.txt", "a".toByteArray())
            assertThrows(GitUnsafePathException::class.java) { it.readBlocking(linkedRoot, "a.txt") }
        }
    }

    @Test fun `二进制按 NUL 判断，超限文件只返回大小与截断标志`() {
        eachReader {
            write("image.bin", byteArrayOf(1, 2, 0, 3))
            val binary = it.readBlocking(root, "image.bin")
            assertThat(binary.binary).isTrue()
            assertThat(binary.text).isNull()
            assertThat(binary.size).isEqualTo(4)

            write("edge.txt", ByteArray(64) { 'x'.code.toByte() })
            assertThat(it.readBlocking(root, "edge.txt").text).hasLength(64)

            write("large.txt", ByteArray(65) { 'y'.code.toByte() })
            val large = it.readBlocking(root, "large.txt")
            assertThat(large.truncated).isTrue()
            assertThat(large.text).isNull()
            assertThat(large.size).isEqualTo(65)
            assertThat(large.exists).isTrue()
        }
    }

    @Test fun `无效 UTF-8 字节替换为替换字符`() {
        eachReader {
            write("latin.txt", byteArrayOf('c'.code.toByte(), 0xE9.toByte(), '\n'.code.toByte()))
            assertThat(it.readBlocking(root, "latin.txt").text).isEqualTo("c�\n")
        }
    }

    @Test fun `默认不降级为按路径读取`() {
        File(root, "a.txt").writeText("a")
        val strict = NioGitWorkTreeReader()
        if (secureSupported) {
            assertThat(strict.readBlocking(root, "a.txt").text).isEqualTo("a")
        } else {
            assertThrows(IOException::class.java) { strict.readBlocking(root, "a.txt") }
        }
    }

    @Test fun `GitPaths 校验与 Python 端规则一致`() {
        assertThat(GitPaths.segments("a/b/c.txt")).containsExactly("a", "b", "c.txt").inOrder()
        assertThat(GitPaths.isSafe("a/.gitignore")).isTrue()
        assertThat(GitPaths.isSafe("a/")).isFalse()
        assertThat(GitPaths.isSafe("a/\u0000")).isFalse()
    }
}
