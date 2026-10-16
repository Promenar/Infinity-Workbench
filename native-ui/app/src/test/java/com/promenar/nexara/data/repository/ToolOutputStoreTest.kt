package com.promenar.nexara.data.repository

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ToolOutputStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val root: File get() = temporaryDirectory.resolve("private-output").toFile()

    @Test
    fun `小结果和阈值边界原样返回且不创建存储`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 8)

        assertThat(store.offload("session", "")).isEmpty()
        assertThat(store.offload("session", "12345678")).isEqualTo("12345678")
        assertThat(root.exists()).isFalse()
    }

    @Test
    fun `默认阈值卸载保留头尾与完整长度且完整内容可分段恢复`() = runTest {
        val content = "HEAD-" + "0123456789\n".repeat(2_000) + "-TAIL"
        val store = ToolOutputStore(root)

        val preview = store.offload("session", content)
        val id = outputId(preview)

        assertThat(preview).contains("HEAD-")
        assertThat(preview).contains("-TAIL")
        assertThat(preview).contains("total_chars=${content.length}")
        assertThat(preview).contains("UTF-16")
        assertThat(preview).contains("read_tool_output")
        assertThat(preview.length).isLessThan(content.length)
        val recovered = buildString {
            var offset = 0
            while (offset < content.length) {
                val page = store.read("session", id, offset, 4_000)
                append(page)
                offset += page.length
            }
        }
        assertThat(recovered).isEqualTo(content)
    }

    @Test
    fun `分段偏移精确使用UTF16字符单元`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("session", "A😀B界\nEND"))

        assertThat(store.read("session", id, 1, 2)).isEqualTo("😀")
        assertThat(store.read("session", id, 2, 1)).isEqualTo("\uDE00")
        assertThat(store.read("session", id, 3, 3)).isEqualTo("B界\n")
        assertThat(store.read("session", id, 6, 8_000)).isEqualTo("END")
        assertThat(store.read("session", id, 9, 1)).isEmpty()
    }

    @Test
    fun `读取默认四千字符且支持八千字符上限`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("session", "x".repeat(10_000)))

        assertThat(store.read("session", id)).isEqualTo("x".repeat(4_000))
        assertThat(store.read("session", id, 0, 8_000)).isEqualTo("x".repeat(8_000))
    }

    @Test
    fun `无效分页和整型溢出偏移被拒绝`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("session", "0123456789"))

        listOf(-1 to 1, 11 to 1, Int.MAX_VALUE to 8_000, 0 to 0, 0 to -1, 0 to 8_001)
            .forEach { (offset, limit) ->
                assertThat(runCatching { store.read("session", id, offset, limit) }.exceptionOrNull())
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
        assertThat(store.read("session", id, 8, 8_000)).isEqualTo("89")
    }

    @Test
    fun `路径穿越与伪造输出ID无法读取外部文件`() = runTest {
        val sentinel = temporaryDirectory.resolve("sentinel.txt").toFile().apply { writeText("private") }
        val store = ToolOutputStore(root, thresholdChars = 1)
        store.offload("session", "stored content")

        listOf("", "../sentinel.txt", sentinel.absolutePath, "out_../sentinel", "out_" + "0".repeat(64))
            .forEach { id ->
                val failure = runCatching { store.read("session", id, 0, 100) }.exceptionOrNull()
                assertThat(failure).isNotNull()
                assertThat(failure?.message.orEmpty()).doesNotContain("private")
            }
        assertThat(sentinel.readText()).isEqualTo("private")
    }

    @Test
    fun `输出ID与会话绑定且相同内容不会跨会话授权`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val firstId = outputId(store.offload("first", "same content"))
        val secondId = outputId(store.offload("second", "same content"))

        assertThat(firstId).isNotEqualTo(secondId)
        assertThat(runCatching { store.read("second", firstId, 0, 100) }.isFailure).isTrue()
        assertThat(runCatching { store.read("first", secondId, 0, 100) }.isFailure).isTrue()
        assertThat(store.read("first", firstId, 0, 100)).isEqualTo("same content")
        assertThat(store.read("second", secondId, 0, 100)).isEqualTo("same content")
    }

    @Test
    fun `会话名称包含路径字符时仍只写专属存储根`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("../../outside/session", "session content"))

        assertThat(store.read("../../outside/session", id, 0, 100)).isEqualTo("session content")
        assertThat(temporaryDirectory.resolve("outside").toFile().exists()).isFalse()
        assertThat(storedFiles().all { it.canonicalPath.startsWith(root.canonicalPath + File.separator) })
            .isTrue()
    }

    @Test
    fun `相同会话内容在重复构建与重启后复用稳定ID及同一份文件`() = runTest {
        val content = "stable output content"
        val store = ToolOutputStore(root, thresholdChars = 1)
        val firstId = outputId(store.offload("session", content))
        val initialPaths = storedFiles().map { it.relativeTo(root).path }.sorted()
        val initialBytes = storedBytes()

        repeat(4) {
            assertThat(outputId(store.offload("session", content))).isEqualTo(firstId)
        }
        val restarted = ToolOutputStore(root, thresholdChars = 1)

        assertThat(outputId(restarted.offload("session", content))).isEqualTo(firstId)
        assertThat(storedFiles().map { it.relativeTo(root).path }.sorted()).isEqualTo(initialPaths)
        assertThat(storedBytes()).isEqualTo(initialBytes)
        assertThat(restarted.read("session", firstId, 0, 100)).isEqualTo(content)
    }

    @Test
    fun `重启无需重新卸载即可读取完整内容`() = runTest {
        val id = outputId(ToolOutputStore(root, thresholdChars = 1).offload("session", "持久化😀内容"))

        val restarted = ToolOutputStore(root, thresholdChars = 1)

        assertThat(restarted.read("session", id, 0, 100)).isEqualTo("持久化😀内容")
    }

    @Test
    fun `删除会话仅清除该会话的输出且可重复调用`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val firstId = outputId(store.offload("first", "first output"))
        val secondId = outputId(store.offload("second", "second output"))
        val before = storedBytes()

        store.deleteSession("first")
        store.deleteSession("first")

        assertThat(runCatching { store.read("first", firstId, 0, 100) }.isFailure).isTrue()
        assertThat(store.read("second", secondId, 0, 100)).isEqualTo("second output")
        assertThat(storedBytes()).isLessThan(before)
        assertThat(ToolOutputStore(root, thresholdChars = 1).read("second", secondId, 0, 100))
            .isEqualTo("second output")
    }

    @Test
    fun `总量超过容量时按FIFO淘汰且重启保持顺序`() = runTest {
        val store = ToolOutputStore(root, maxBytes = 80, thresholdChars = 1)
        val firstId = outputId(store.offload("first", "a".repeat(32)))
        storedFiles().forEach { check(it.setLastModified(1_000)) }
        val secondId = outputId(store.offload("second", "b".repeat(32)))
        storedFiles().filter { it.lastModified() != 1_000L }.forEach { check(it.setLastModified(2_000)) }
        val restarted = ToolOutputStore(root, maxBytes = 80, thresholdChars = 1)

        val thirdId = outputId(restarted.offload("third", "c".repeat(32)))

        assertThat(storedBytes()).isAtMost(80)
        assertThat(runCatching { restarted.read("first", firstId, 0, 100) }.isFailure).isTrue()
        assertThat(restarted.read("second", secondId, 0, 100)).isEqualTo("b".repeat(32))
        assertThat(restarted.read("third", thirdId, 0, 100)).isEqualTo("c".repeat(32))
    }

    @Test
    fun `容量使用实际字节且超大单结果不能伪装为完整存储或驱逐旧结果`() = runTest {
        val store = ToolOutputStore(root, maxBytes = 24, thresholdChars = 1)
        val existingId = outputId(store.offload("session", "kept output"))
        val initialBytes = storedBytes()

        val failure = runCatching { store.offload("session", "界".repeat(10)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(store.read("session", existingId, 0, 100)).isEqualTo("kept output")
        assertThat(storedBytes()).isEqualTo(initialBytes)
        assertThat(storedBytes()).isAtMost(24)
    }

    @Test
    fun `存储根无法创建时返回受控IO异常且不覆盖现有文件`() = runTest {
        root.writeText("existing root file")
        val store = ToolOutputStore(root, thresholdChars = 1)

        assertThat(runCatching { store.offload("session", "new output") }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(root.readText()).isEqualTo("existing root file")
    }

    @Test
    fun `无法无损编码的UTF16内容拒绝存储且保留已有输出`() = runTest {
        val store = ToolOutputStore(root, maxBytes = 64, thresholdChars = 1)
        val id = outputId(store.offload("session", "valid output"))
        val initialBytes = storedBytes()

        val failure = runCatching { store.offload("session", "unpaired-\uD800-surrogate") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(storedBytes()).isEqualTo(initialBytes)
        assertThat(store.read("session", id, 0, 100)).isEqualTo("valid output")
    }

    @Test
    fun `输出文件链接不能读取或覆盖外部文件且会话清理只移除链接`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("session", "stored content"))
        val outputFile = storedFiles().single()
        val sentinel = temporaryDirectory.resolve("external.txt").toFile().apply { writeText("outside secret") }
        check(outputFile.delete())
        Files.createSymbolicLink(outputFile.toPath(), sentinel.toPath())

        assertThat(runCatching { store.read("session", id, 0, 100) }.isFailure).isTrue()
        assertThat(runCatching { store.offload("session", "stored content") }.isFailure).isTrue()
        store.deleteSession("session")

        assertThat(sentinel.readText()).isEqualTo("outside secret")
        assertThat(Files.exists(outputFile.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse()
    }

    @Test
    fun `会话目录被符号链接替换后拒绝读取写入与外部清理`() = runTest {
        val store = ToolOutputStore(root, thresholdChars = 1)
        val id = outputId(store.offload("session", "stored content"))
        val sessionDirectory = checkNotNull(storedFiles().single().parentFile)
        val outside = temporaryDirectory.resolve("outside").toFile().apply { mkdirs() }
        val sentinel = File(outside, "sentinel.txt").apply { writeText("outside private") }
        check(sessionDirectory.deleteRecursively())
        Files.createSymbolicLink(sessionDirectory.toPath(), outside.toPath())

        assertThat(runCatching { store.offload("session", "new output") }.isFailure).isTrue()
        assertThat(runCatching { store.read("session", id, 0, 100) }.isFailure).isTrue()
        runCatching { store.deleteSession("session") }

        assertThat(sentinel.readText()).isEqualTo("outside private")
        assertThat(outside.listFiles().orEmpty().map { it.name }).containsExactly("sentinel.txt")
    }

    @Test
    fun `并发重复卸载不会留下重复文件且多实例共同遵守总量上限`() = runTest {
        val stores = List(3) { ToolOutputStore(root, maxBytes = 80, thresholdChars = 1) }
        val sameIds = List(12) { index ->
            async { outputId(stores[index % stores.size].offload("session", "a".repeat(32))) }
        }.awaitAll()

        assertThat(sameIds.toSet()).hasSize(1)
        assertThat(storedFiles()).hasSize(1)
        val outputs = List(6) { index ->
            async { stores[index % stores.size].offload("other-$index", "$index".repeat(32)) }
        }.awaitAll()

        assertThat(outputs).hasSize(6)
        assertThat(storedBytes()).isAtMost(80)
        assertThat(storedFiles()).hasSize(2)
    }

    private fun outputId(preview: String): String =
        checkNotNull(Regex("output_id=(out_[a-f0-9]{64})").find(preview)?.groupValues?.get(1)) {
            "卸载结果必须包含可读取的输出 ID"
        }

    private fun storedFiles(): List<File> = if (root.isDirectory) {
        root.walkTopDown().filter { it.isFile }.toList()
    } else {
        emptyList()
    }

    private fun storedBytes(): Long = storedFiles().sumOf { it.length() }
}
