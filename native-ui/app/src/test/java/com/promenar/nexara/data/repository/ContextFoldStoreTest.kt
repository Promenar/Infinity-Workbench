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

class ContextFoldStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val root: File get() = temporaryDirectory.resolve("context-folds").toFile()

    @Test
    fun `新实例能够复用相同会话消息及正文的折叠结果`() = runTest {
        ContextFoldStore(root).put("session", "message", "原始正文😀", "保留事实😀")

        assertThat(ContextFoldStore(root).get("session", "message", "原始正文😀"))
            .isEqualTo("保留事实😀")
    }

    @Test
    fun `正文变化不命中并使旧正文对应的记录失效`() = runTest {
        val store = ContextFoldStore(root)
        store.put("session", "message", "old body", "old fold")

        assertThat(store.get("session", "message", "new body")).isNull()
        assertThat(ContextFoldStore(root).get("session", "message", "old body")).isNull()
        store.put("session", "message", "new body", "new fold")
        assertThat(ContextFoldStore(root).get("session", "message", "new body")).isEqualTo("new fold")
    }

    @Test
    fun `只读查询错误正文不使原记录失效而生成查询仍会失效旧记录`() = runTest {
        val store = ContextFoldStore(root)
        store.put("session", "message", "old body", "old fold")
        val filesBefore = snapshot(root)

        assertThat(store.peek("session", "message", "new body")).isNull()
        assertThat(ContextFoldStore(root).peek("session", "message", "old body")).isEqualTo("old fold")
        assertThat(snapshot(root)).isEqualTo(filesBefore)

        assertThat(store.get("session", "message", "new body")).isNull()
        assertThat(store.peek("session", "message", "old body")).isNull()
    }

    @Test
    fun `只读查询没有缓存时不会创建存储目录`() = runTest {
        val store = ContextFoldStore(root)

        assertThat(store.peek("session", "message", "body")).isNull()

        assertThat(root.exists()).isFalse()
    }

    @Test
    fun `相同消息及正文的缓存按会话隔离`() = runTest {
        val store = ContextFoldStore(root)
        store.put("first", "message", "same body", "first fold")
        store.put("second", "message", "same body", "second fold")

        assertThat(store.get("first", "message", "same body")).isEqualTo("first fold")
        assertThat(store.get("second", "message", "same body")).isEqualTo("second fold")
        assertThat(store.get("third", "message", "same body")).isNull()
    }

    @Test
    fun `会话清理可重复执行且保留其它会话缓存`() = runTest {
        val store = ContextFoldStore(root)
        store.put("first", "one", "body", "first fold")
        store.put("first", "two", "body", "second fold")
        store.put("second", "one", "body", "other session")

        store.deleteSession("first")
        store.deleteSession("first")

        val restarted = ContextFoldStore(root)
        assertThat(restarted.get("first", "one", "body")).isNull()
        assertThat(restarted.get("first", "two", "body")).isNull()
        assertThat(restarted.get("second", "one", "body")).isEqualTo("other session")
    }

    @Test
    fun `会话和消息标识中的路径字符不能逸出存储根`() = runTest {
        val store = ContextFoldStore(root)

        store.put("../../session", "../../message", "body", "fold")

        assertThat(store.get("../../session", "../../message", "body")).isEqualTo("fold")
        assertThat(temporaryDirectory.resolve("session").toFile().exists()).isFalse()
        assertThat(temporaryDirectory.resolve("message").toFile().exists()).isFalse()
        assertThat(root.walkTopDown().filter(File::isFile).all {
            it.canonicalPath.startsWith(root.canonicalPath + File.separator)
        }).isTrue()
    }

    @Test
    fun `存储根为文件时读写降级但删除报告失败且不覆盖现有文件`() = runTest {
        root.writeText("existing file")
        val store = ContextFoldStore(root)

        store.put("session", "message", "body", "fold")
        assertThat(store.get("session", "message", "body")).isNull()
        assertThat(runCatching { store.deleteSession("session") }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)

        assertThat(root.readText()).isEqualTo("existing file")
    }

    @Test
    fun `删除符号链接根目录报告失败且不删除外部会话或链接`() = runTest {
        val outside = temporaryDirectory.resolve("outside").toFile()
        val externalStore = ContextFoldStore(outside)
        externalStore.put("session", "message", "body", "external fold")
        val sentinel = File(outside, "sentinel.txt").apply { writeText("external sentinel") }
        val filesBefore = snapshot(outside)
        Files.createSymbolicLink(root.toPath(), outside.toPath())

        assertThat(runCatching { ContextFoldStore(root).deleteSession("session") }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)

        assertThat(Files.isSymbolicLink(root.toPath())).isTrue()
        assertThat(snapshot(outside)).isEqualTo(filesBefore)
        assertThat(externalStore.peek("session", "message", "body")).isEqualTo("external fold")
        assertThat(sentinel.readText()).isEqualTo("external sentinel")
    }

    @Test
    fun `多个实例并发写入相同正文不会暴露半份缓存或遗留临时文件`() = runTest {
        val values = (0 until 12).map { "fold-$it:" + "$it".repeat(1_000) }
        values.map { value ->
            async { ContextFoldStore(root).put("session", "message", "body", value) }
        }.awaitAll()

        assertThat(ContextFoldStore(root).get("session", "message", "body")).isIn(values)
        assertThat(root.walkTopDown().filter(File::isFile).toList()).hasSize(1)
    }

    @Test
    fun `缓存文件符号链接不能读取外部内容且清理不删除链接目标`() = runTest {
        val store = ContextFoldStore(root)
        store.put("session", "message", "body", "fold")
        val cached = root.walkTopDown().single(File::isFile)
        val external = temporaryDirectory.resolve("external.txt").toFile().apply { writeText("external") }
        check(cached.delete())
        Files.createSymbolicLink(cached.toPath(), external.toPath())

        assertThat(store.get("session", "message", "body")).isNull()
        store.deleteSession("session")

        assertThat(external.readText()).isEqualTo("external")
        assertThat(Files.exists(cached.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse()
    }

    private fun snapshot(directory: File): Map<String, Pair<Long, String?>> = directory.walkTopDown().associate {
        it.relativeTo(directory).path to (it.lastModified() to if (it.isFile) it.readText() else null)
    }
}
