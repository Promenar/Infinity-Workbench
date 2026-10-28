package com.promenar.infinity.data.canvas

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder

class FileCanvasStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `创建落盘并在新实例恢复源码元数据`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("session", "交互图表", "<p>数据</p>", "message")
        assertThat(created.version).isEqualTo(1)
        assertThat(created.metadata.currentVersion).isEqualTo(1)
        assertThat(created.metadata.versions).containsExactly(1)
        assertThat(created.metadata.createdByMessageId).isEqualTo("message")
        assertThat(created.metadata.createdAt).isGreaterThan(0L)
        val restored = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        assertThat(restored.read("session", created.metadata.id)).isEqualTo(created)
        assertThat(restored.list("session")).containsExactly(created.metadata)
        assertThat(restored.list("other")).isEmpty()
    }

    @Test fun `整体替换递增版本且历史源码可以读取`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("s", "标题", "old", "m")
        val updated = store.update("s", created.metadata.id, html = "new", expectedVersion = 1)
        assertThat(updated.html).isEqualTo("new")
        assertThat(updated.version).isEqualTo(2)
        assertThat(updated.metadata.createdAt).isEqualTo(created.metadata.createdAt)
        assertThat(updated.metadata.updatedAt).isAtLeast(created.metadata.updatedAt)
        assertThat(store.read("s", created.metadata.id, 1)?.html).isEqualTo("old")
    }

    @Test fun `顺序补丁可以引用前一条的替换结果`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("s", "标题", "<p>one</p>", "m")
        val updated = store.update("s", created.metadata.id, patches = listOf(CanvasPatch("one", "two"), CanvasPatch("two", "three")))
        assertThat(updated.html).isEqualTo("<p>three</p>")
        assertThat(updated.version).isEqualTo(2)
    }

    @Test fun `零匹配重复匹配与重叠匹配补丁都被拒绝`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("s", "标题", "aaaa", "m")
        for (find in listOf("missing", "a", "aaa")) {
            assertCode("PATCH_NOT_UNIQUE") { store.update("s", created.metadata.id, patches = listOf(CanvasPatch(find, "x"))) }
        }
        assertThat(store.read("s", created.metadata.id)).isEqualTo(created)
    }

    @Test fun `批量补丁任一失败不落盘且错误包含条目编号`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("s", "标题", "one two", "m")
        val before = folder.root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder.root).path to it.readText() }
        val failure = runCatching {
            store.update("s", created.metadata.id, patches = listOf(CanvasPatch("one", "three"), CanvasPatch("absent", "four")))
        }.exceptionOrNull() as CanvasException
        assertThat(failure.message).contains("2")
        assertThat(store.read("s", created.metadata.id)).isEqualTo(created)
        assertThat(folder.root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(folder.root).path to it.readText() }).isEqualTo(before)
    }

    @Test fun `空补丁空查找替换与补丁并存以及缺少更新方式都拒绝`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("s", "标题", "one", "m")
        assertCode("INVALID_ARGUMENTS") { store.update("s", created.metadata.id, patches = emptyList()) }
        assertCode("INVALID_ARGUMENTS") { store.update("s", created.metadata.id, patches = listOf(CanvasPatch("", "x"))) }
        assertCode("INVALID_ARGUMENTS") { store.update("s", created.metadata.id, html = "x", patches = listOf(CanvasPatch("one", "x"))) }
        assertCode("INVALID_ARGUMENTS") { store.update("s", created.metadata.id) }
        assertThat(store.read("s", created.metadata.id)).isEqualTo(created)
    }

    @Test fun `乐观版本冲突保留原数据且并发仅一个成功`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("s", "标题", "one", "m")
        val results = listOf("two", "three").map { html -> async { runCatching { store.update("s", created.metadata.id, html = html, expectedVersion = 1) } } }.map { it.await() }
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat((results.single { it.isFailure }.exceptionOrNull() as CanvasException).code).isEqualTo("VERSION_CONFLICT")
        assertThat(store.read("s", created.metadata.id)?.version).isEqualTo(2)
    }

    @Test fun `默认只保留最近十版并清理被淘汰的源文件`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("s", "标题", "v1", "m")
        repeat(12) { store.update("s", created.metadata.id, html = "v${it + 2}") }
        val latest = store.read("s", created.metadata.id)!!
        assertThat(latest.version).isEqualTo(13)
        assertThat(latest.metadata.versions).containsExactlyElementsIn(4..13).inOrder()
        assertThat(store.read("s", created.metadata.id, 3)).isNull()
        assertThat(folder.root.walkTopDown().filter { it.extension == "html" }.count()).isEqualTo(10)
    }

    @Test fun `源码发布失败不更新元数据且清理临时文件`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileCanvasStore(folder.root, dispatcher)
        val created = store.create("s", "标题", "one", "m")
        val metadata = folder.root.walkTopDown().single { it.name == "metadata.json" }
        File(metadata.parentFile, "2.html").mkdir()
        assertCode("STORAGE_ERROR") { store.update("s", created.metadata.id, html = "two") }
        assertThat(FileCanvasStore(folder.root, dispatcher).read("s", created.metadata.id)).isEqualTo(created)
        assertThat(folder.root.walkTopDown().filter { it.name.contains(".tmp") }.toList()).isEmpty()
    }

    @Test fun `大小按UTF8字节限制且补丁结果超限不发布`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler), maxSourceBytes = 6)
        val created = store.create("s", "标题", "中文", "m")
        assertCode("SOURCE_TOO_LARGE") { store.create("s", "标题", "中文字", "m") }
        assertCode("SOURCE_TOO_LARGE") { store.update("s", created.metadata.id, html = "中文字") }
        assertCode("SOURCE_TOO_LARGE") { store.update("s", created.metadata.id, patches = listOf(CanvasPatch("文", "文字"))) }
        assertThat(store.read("s", created.metadata.id)).isEqualTo(created)
    }

    @Test fun `标题与创建消息均校验且源码支持空文档`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        assertCode("INVALID_ARGUMENTS") { store.create("s", " ", "html", "m") }
        assertCode("INVALID_ARGUMENTS") { store.create("s", "a".repeat(121), "html", "m") }
        assertCode("INVALID_ARGUMENTS") { store.create("s", "标题", "html", "") }
        assertThat(store.create("s", "a".repeat(120), "", "m").html).isEmpty()
    }

    @Test fun `损坏元数据和缺失源码跳过且健康画布仍可恢复`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val healthy = store.create("s", "健康", "healthy", "m")
        val corrupt = store.create("s", "损坏", "corrupt", "m")
        folder.root.walkTopDown().single { it.name == "metadata.json" && it.parentFile.name == corrupt.metadata.id }.writeText("{broken")
        val missing = store.create("s", "缺失", "missing", "m")
        folder.root.walkTopDown().single { it.extension == "html" && it.parentFile.name == missing.metadata.id }.delete()
        val restored = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        assertThat(restored.list("s")).containsExactly(healthy.metadata)
        assertThat(restored.read("s", corrupt.metadata.id)).isNull()
    }

    @Test fun `单项元数据读取失败不会阻断健康画布`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileCanvasStore(folder.root, dispatcher)
        val healthy = store.create("s", "健康", "healthy", "m")
        val unreadable = store.create("s", "不可读", "unreadable", "m")
        val metadata = folder.root.walkTopDown().single { it.name == "metadata.json" && it.parentFile.name == unreadable.metadata.id }
        try {
            assumeTrue(metadata.setReadable(false, false) && !metadata.canRead())
            assertThat(FileCanvasStore(folder.root, dispatcher).list("s")).containsExactly(healthy.metadata)
        } finally {
            metadata.setReadable(true, false)
        }
    }

    @Test fun `超过64KiB的合法元数据也被逐项跳过`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileCanvasStore(folder.root, dispatcher)
        val healthy = store.create("s", "健康", "healthy", "m")
        val oversized = store.create("s", "过大", "oversized", "m")
        val metadata = folder.root.walkTopDown().single { it.name == "metadata.json" && it.parentFile.name == oversized.metadata.id }
        metadata.appendText(" ".repeat(65_537))
        assertThat(FileCanvasStore(folder.root, dispatcher).list("s")).containsExactly(healthy.metadata)
    }

    @Test fun `元数据与源码符号链接逐项跳过`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileCanvasStore(folder.root, dispatcher)
        val healthy = store.create("s", "健康", "healthy", "m")
        val metadataLink = store.create("s", "元数据链接", "metadata-link", "m")
        val sourceLink = store.create("s", "源码链接", "source-link", "m")
        val healthyMetadata = folder.root.walkTopDown().single { it.name == "metadata.json" && it.parentFile.name == healthy.metadata.id }
        val linkedMetadata = folder.root.walkTopDown().single { it.name == "metadata.json" && it.parentFile.name == metadataLink.metadata.id }
        val linkedSource = folder.root.walkTopDown().single { it.extension == "html" && it.parentFile.name == sourceLink.metadata.id }
        linkedMetadata.delete()
        linkedSource.delete()
        val linksCreated = runCatching {
            Files.createSymbolicLink(linkedMetadata.toPath(), healthyMetadata.toPath())
            Files.createSymbolicLink(linkedSource.toPath(), File(healthyMetadata.parentFile, "1.html").toPath())
        }.isSuccess
        assumeTrue(linksCreated)
        assertThat(FileCanvasStore(folder.root, dispatcher).list("s")).containsExactly(healthy.metadata)
    }

    @Test fun `拒绝发布重启后无法恢复的超长元数据`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        assertCode("INVALID_ARGUMENTS") { store.create("s", "标题", "html", "m".repeat(65_536)) }
        assertThat(store.list("s")).isEmpty()
        assertThat(folder.root.walkTopDown().filter { it.name == "metadata.json" }.toList()).isEmpty()
    }

    @Test fun `元数据内路径或版本伪造不会越界读写`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("s", "标题", "one", "m")
        val metadata = folder.root.walkTopDown().single { it.name == "metadata.json" }
        metadata.writeText(Json.encodeToString(created.metadata.copy(id = "../outside")))
        val restored = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        assertThat(restored.list("s")).isEmpty()
        assertCode("INVALID_ARGUMENTS") { store.read("s", "../outside") }
        assertCode("INVALID_ARGUMENTS") { store.update("s", "/tmp/outside", html = "x") }
    }

    @Test fun `会话ID经目录映射不能变成任意路径`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val created = store.create("../../escape", "标题", "one", "m")
        assertThat(store.read("../../escape", created.metadata.id)?.html).isEqualTo("one")
        assertThat(folder.root.listFiles()!!.single().name).matches("[a-f0-9]{64}")
        assertCode("INVALID_ARGUMENTS") { store.list("") }
    }

    @Test fun `删除只清理目标会话且重启后禁止写复活`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileCanvasStore(folder.root, dispatcher)
        val deleted = store.create("s", "删除", "delete", "m")
        val kept = store.create("other", "保留", "keep", "m")
        store.deleteSession("s")
        assertThat(store.list("s")).isEmpty()
        assertThat(store.read("s", deleted.metadata.id)).isNull()
        assertCode("SESSION_DELETED") { store.create("s", "标题", "x", "m") }
        assertCode("SESSION_DELETED") { store.update("s", deleted.metadata.id, html = "x") }
        val restored = FileCanvasStore(folder.root, dispatcher)
        assertCode("SESSION_DELETED") { restored.create("s", "标题", "x", "m") }
        assertThat(restored.read("other", kept.metadata.id)).isEqualTo(kept)
        assertThat(folder.root.walkTopDown().filter { it.extension == "html" }.count()).isEqualTo(1)
    }

    @Test fun `观察流首次收集加载磁盘且更新删除后发出快照`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileCanvasStore(folder.root, dispatcher)
        val observation = store.observe("s")
        assertThat(folder.root.listFiles().orEmpty()).isEmpty()
        assertThat(observation.first()).isEmpty()
        val created = store.create("s", "标题", "one", "m")
        assertThat(observation.first()).containsExactly(created.metadata)
        val updated = store.update("s", created.metadata.id, html = "two")
        assertThat(observation.first()).containsExactly(updated.metadata)
        assertThat(FileCanvasStore(folder.root, dispatcher).observe("s").first()).containsExactly(updated.metadata)
        store.deleteSession("s")
        assertThat(observation.first()).isEmpty()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `同一观察订阅实时收到创建更新与删除`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val observed = mutableListOf<List<Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            store.observe("s").collect { observed += it.map(CanvasMetadata::currentVersion) }
        }
        runCurrent()
        val created = store.create("s", "标题", "one", "m")
        runCurrent()
        store.update("s", created.metadata.id, html = "two")
        runCurrent()
        store.deleteSession("s")
        runCurrent()
        assertThat(observed).containsExactly(emptyList<Int>(), listOf(1), listOf(2), emptyList<Int>()).inOrder()
    }

    @Test fun `分支只复制已映射消息的当前版本并保留身份与版本号`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val original = store.create("source", "保留", "v1", "original-message")
        val latest = store.update("source", original.metadata.id, html = "v2")
        store.create("source", "未纳入分支", "skip", "later-message")
        store.copySession("source", "target", mapOf("original-message" to "branch-message"))
        val branch = store.read("target", original.metadata.id)!!
        assertThat(branch.html).isEqualTo("v2")
        assertThat(branch.version).isEqualTo(2)
        assertThat(branch.metadata).isEqualTo(latest.metadata.copy(createdByMessageId = "branch-message", versions = listOf(2)))
        assertThat(store.read("target", original.metadata.id, 1)).isNull()
        assertThat(store.list("target")).hasSize(1)
        store.update("target", original.metadata.id, html = "branch")
        assertThat(store.read("source", original.metadata.id)?.html).isEqualTo("v2")
    }

    @Test fun `分支拒绝覆盖现有或删除的目标会话`() = runTest {
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler))
        val source = store.create("source", "来源", "source", "m")
        val target = store.create("target", "目标", "target", "m")
        assertCode("SESSION_EXISTS") { store.copySession("source", "target", mapOf("m" to "n")) }
        assertThat(store.read("target", target.metadata.id)).isEqualTo(target)
        store.deleteSession("target")
        assertCode("SESSION_DELETED") { store.copySession("source", "target", mapOf("m" to "n")) }
        assertThat(store.read("source", source.metadata.id)).isEqualTo(source)
    }

    @Test fun `分支复制失败清理目标并允许安全重试`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileCanvasStore(folder.root, dispatcher)
        val first = store.create("source", "一", "1234", "m")
        store.create("source", "二", "12345678", "m")
        val limited = FileCanvasStore(folder.root, dispatcher, maxSourceBytes = 6)
        assertCode("SOURCE_TOO_LARGE") { limited.copySession("source", "target", mapOf("m" to "n")) }
        assertThat(store.list("target")).isEmpty()
        store.copySession("source", "target", mapOf("m" to "n"))
        assertThat(store.read("target", first.metadata.id)?.html).isEqualTo("1234")
        assertThat(folder.root.walkTopDown().filter { it.name.contains(".tmp") }.toList()).isEmpty()
    }

    private suspend fun assertCode(expected: String, operation: suspend () -> Any?) {
        val failure = runCatching { operation() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CanvasException::class.java)
        assertThat((failure as CanvasException).code).isEqualTo(expected)
    }
}
