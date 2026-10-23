package com.promenar.nexara.data.canvas

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanvasWorkspaceSyncTest {
    @get:Rule val folder = TemporaryFolder()

    /** [workspaceOf] 把会话映射到工作区；默认每个会话独立，分支共用自选工作区时映射到同一个键。 */
    private class Files(private val workspaceOf: (String) -> String = { it }) : CanvasWorkspaceFiles {
        val files = mutableMapOf<Pair<String, String>, ByteArray>()
        var failed = false
        var failAfterWrite = false
        override suspend fun exists(sessionId: String, path: String): Boolean {
            if (failed) throw IOException("测试写出失败")
            return (workspaceOf(sessionId) to path) in files
        }
        override suspend fun read(sessionId: String, path: String, maxBytes: Int): ByteArray? {
            if (failed) throw IOException("测试读取失败")
            return files[workspaceOf(sessionId) to path]?.also { require(it.size <= maxBytes) }
        }
        override suspend fun write(sessionId: String, path: String, html: String, expectedHash: String?) {
            if (failed) throw IOException("测试写出失败")
            val old = files[workspaceOf(sessionId) to path]
            check(if (expectedHash == null) old == null else old?.let(::canvasSha256) == expectedHash)
            files[workspaceOf(sessionId) to path] = html.toByteArray(Charsets.UTF_8)
            if (failAfterWrite) throw IOException("测试工作区已写入但尚未确认")
        }
    }

    @Test fun `名称折叠截断空值与文件及画布冲突均不覆盖`() = runTest {
        val files = Files()
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler), workspaceFiles = files)
        files.files["s" to "canvas/中文-Ab-12.html"] = "保留".toByteArray()
        val first = store.create("s", " 中文 / Ab_12! ", "first", "m")
        val second = store.create("s", "中文 / Ab_12!", "second", "m")
        assertThat(first.metadata.workspacePath).isEqualTo("canvas/中文-Ab-12-2.html")
        assertThat(second.metadata.workspacePath).isEqualTo("canvas/中文-Ab-12-3.html")
        assertThat(store.create("s", "！？", "empty", "m").metadata.workspacePath).isEqualTo("canvas/canvas.html")
        assertThat(store.create("s", "中".repeat(60), "long", "m").metadata.workspacePath).isEqualTo("canvas/${"中".repeat(48)}.html")
        assertThat(store.create("s", "a--b", "hyphen", "m").metadata.workspacePath).isEqualTo("canvas/a--b.html")
        assertThat(files.files["s" to "canvas/中文-Ab-12.html"]!!.decodeToString()).isEqualTo("保留")
    }

    @Test fun `写出字节相同并在重启后比较哈希及导入来源`() = runTest {
        val files = Files()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files)
        val first = store.create("s", "标题", "中文\r\n<p>原文</p>\n", "m")
        val path = first.metadata.workspacePath!!
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(files.files["s" to path]).isEqualTo(first.html.toByteArray())
        val restored = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files)
        assertThat(restored.syncFromWorkspace("s", first.metadata.id)).isEqualTo(first)
        files.files["s" to path] = "<p>外部</p>".toByteArray()
        val imported = restored.syncFromWorkspace("s", first.metadata.id)!!
        assertThat(imported.version).isEqualTo(2)
        assertThat(imported.html).isEqualTo("<p>外部</p>")
        assertThat(imported.metadata.createdByMessageId).isEqualTo("m")
        assertThat(imported.metadata.versionSources[2]).isEqualTo(CanvasVersionSource.WORKSPACE)
        assertThat(restored.syncFromWorkspace("s", first.metadata.id)).isEqualTo(imported)
        assertThat(restored.read("s", first.metadata.id, 1)?.html).isEqualTo(first.html)
    }

    @Test fun `更新先导入外部修改补丁使用最新内容且乐观锁在导入后校验`() = runTest {
        val files = Files()
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler), workspaceFiles = files)
        val first = store.create("s", "标题", "原文", "m")
        val key = "s" to first.metadata.workspacePath!!
        files.files[key] = "外部".toByteArray()
        val conflict = runCatching { store.update("s", first.metadata.id, html = "覆盖", expectedVersion = 1) }.exceptionOrNull() as CanvasException
        assertThat(conflict.code).isEqualTo("VERSION_CONFLICT")
        val updated = store.update("s", first.metadata.id, patches = listOf(CanvasPatch("外部", "补丁")), expectedVersion = 2)
        assertThat(updated.version).isEqualTo(3)
        assertThat(updated.html).isEqualTo("补丁")
        assertThat(updated.metadata.workspacePath).isEqualTo(first.metadata.workspacePath)
        assertThat(files.files[key]!!.decodeToString()).isEqualTo("补丁")
    }

    @Test fun `删除文件只标记缺失更新才重新写出`() = runTest {
        val files = Files()
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler), workspaceFiles = files)
        val first = store.create("s", "标题", "原文", "m")
        val key = "s" to first.metadata.workspacePath!!
        files.files.remove(key)
        repeat(2) {
            val missing = store.syncFromWorkspace("s", first.metadata.id)!!
            assertThat(missing.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.MISSING)
            assertThat(missing.version).isEqualTo(1)
            assertThat(files.files).doesNotContainKey(key)
        }
        val updated = store.update("s", first.metadata.id, html = "更新")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(files.files[key]!!.decodeToString()).isEqualTo("更新")
    }

    @Test fun `写出失败私有版本完整恢复权限后同步重试`() = runTest {
        val files = Files().apply { failed = true }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files)
        val first = store.create("s", "标题", "原文", "m")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.FAILED)
        val updated = store.update("s", first.metadata.id, html = "更新")
        assertThat(updated.version).isEqualTo(2)
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.FAILED)
        assertThat(store.read("s", first.metadata.id, 1)?.html).isEqualTo("原文")
        files.failed = false
        val synced = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files).syncFromWorkspace("s", first.metadata.id)!!
        assertThat(synced.version).isEqualTo(2)
        assertThat(synced.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(files.files["s" to synced.metadata.workspacePath!!]!!.decodeToString()).isEqualTo("更新")
    }

    @Test fun `已同步画布更新失败后重试写出私有新版本不会导入旧内容`() = runTest {
        val files = Files()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files)
        val first = store.create("s", "标题", "原文", "m")
        files.failed = true
        val updated = store.update("s", first.metadata.id, html = "更新")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.FAILED)
        files.failed = false
        val synced = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files).syncFromWorkspace("s", first.metadata.id)!!
        assertThat(synced.version).isEqualTo(2)
        assertThat(synced.html).isEqualTo("更新")
        assertThat(files.files["s" to synced.metadata.workspacePath!!]!!.decodeToString()).isEqualTo("更新")
    }

    @Test fun `工作区落盘后确认中断首次同步和更新均不会卡死或重复导入`() = runTest {
        val files = Files().apply { failAfterWrite = true }
        val dispatcher = StandardTestDispatcher(testScheduler)
        var store = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files)
        val first = store.create("s", "标题", "原文", "m")
        assertThat(first.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.FAILED)
        files.failAfterWrite = false
        store = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files)
        val synced = store.syncFromWorkspace("s", first.metadata.id)!!
        assertThat(synced.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(synced.version).isEqualTo(1)
        files.failAfterWrite = true
        val updated = store.update("s", first.metadata.id, html = "更新")
        assertThat(updated.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.FAILED)
        files.failAfterWrite = false
        store = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files)
        val recovered = store.syncFromWorkspace("s", first.metadata.id)!!
        assertThat(recovered.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(recovered.version).isEqualTo(2)
        assertThat(recovered.html).isEqualTo("更新")
        assertThat(recovered.metadata.versionSources[2]).isEqualTo(CanvasVersionSource.CANVAS)
    }

    @Test fun `私有版本已发布工作区待写出时重启仍会补写`() = runTest {
        val files = Files()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files).create("s", "标题", "原文", "m")
        FileCanvasStore(folder.root, dispatcher).update("s", first.metadata.id, html = "待写出")
        val restored = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files)
        val pending = restored.syncFromWorkspace("s", first.metadata.id)!!
        assertThat(pending.version).isEqualTo(2)
        assertThat(pending.html).isEqualTo("待写出")
        assertThat(pending.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        assertThat(files.files["s" to pending.metadata.workspacePath!!]!!.decodeToString()).isEqualTo("待写出")
    }

    @Test fun `非法UTF8和超限外部内容保留私有版本并标记失败`() = runTest {
        val files = Files()
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler), maxSourceBytes = 8, workspaceFiles = files)
        val first = store.create("s", "标题", "原文", "m")
        val key = "s" to first.metadata.workspacePath!!
        for (bytes in listOf(byteArrayOf(0xc3.toByte(), 0x28), ByteArray(9) { 65 })) {
            files.files[key] = bytes
            val result = store.syncFromWorkspace("s", first.metadata.id)!!
            assertThat(result.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.FAILED)
            assertThat(result.version).isEqualTo(1)
            assertThat(result.html).isEqualTo("原文")
            assertThat(files.files[key]).isEqualTo(bytes)
        }
    }

    @Test fun `旧元数据兼容且首次同步写出分支清空路径状态和哈希`() = runTest {
        val files = Files()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val initial = FileCanvasStore(folder.root, dispatcher)
        val first = initial.create("s", "标题", "原文", "m")
        val metadata = folder.root.walkTopDown().single { it.name == "metadata.json" }
        val old = Json.parseToJsonElement(metadata.readText()).jsonObject.filterKeys { !it.startsWith("workspace") && it != "versionSources" }
        metadata.writeText(JsonObject(old).toString())
        val store = FileCanvasStore(folder.root, dispatcher, workspaceFiles = files)
        assertThat(store.list("s").single().workspaceSync).isEqualTo(CanvasWorkspaceSync.NOT_WRITTEN)
        val synced = store.syncFromWorkspace("s", first.metadata.id)!!
        store.copySession("s", "branch", mapOf("m" to "branch-m"))
        val branch = store.read("branch", first.metadata.id)!!
        assertThat(branch.metadata.workspacePath).isNull()
        assertThat(branch.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.NOT_WRITTEN)
        assertThat(files.files.keys.none { it.first == "branch" }).isTrue()
        val copy = store.syncFromWorkspace("branch", first.metadata.id)!!
        assertThat(copy.version).isEqualTo(first.version)
        assertThat(copy.metadata.workspaceSync).isEqualTo(CanvasWorkspaceSync.SYNCED)
        // 分支拿到全新工作区时沿用同名路径。
        assertThat(copy.metadata.workspacePath).isEqualTo(synced.metadata.workspacePath)
        store.deleteSession("s")
        assertThat(store.syncFromWorkspace("s", first.metadata.id)).isNull()
        assertThat(files.files["s" to synced.metadata.workspacePath!!]!!.decodeToString()).isEqualTo("原文")
    }

    @Test fun `分支与源会话共用自选工作区时画布写到新路径互不覆盖`() = runTest {
        val files = Files { "shared" }
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler), workspaceFiles = files)
        val source = store.create("s", "看板", "原文", "m")
        store.copySession("s", "branch", mapOf("m" to "branch-m"))
        val branch = store.syncFromWorkspace("branch", source.metadata.id)!!

        assertThat(branch.metadata.workspacePath).isEqualTo("canvas/看板-2.html")
        store.update("branch", source.metadata.id, html = "分支改动")
        assertThat(files.files["shared" to "canvas/看板.html"]!!.decodeToString()).isEqualTo("原文")
        assertThat(files.files["shared" to "canvas/看板-2.html"]!!.decodeToString()).isEqualTo("分支改动")
        assertThat(store.syncFromWorkspace("s", source.metadata.id)!!.version).isEqualTo(source.version)
    }

    @Test fun `并发创建分配唯一路径且并发同步只导入一次`() = runTest {
        val files = Files()
        val store = FileCanvasStore(folder.root, StandardTestDispatcher(testScheduler), workspaceFiles = files)
        val documents = (1..3).map { async { store.create("s", "标题", "原文", "m") } }.map { it.await() }
        assertThat(documents.map { it.metadata.workspacePath }.distinct()).hasSize(3)
        val first = documents.first()
        files.files["s" to first.metadata.workspacePath!!] = "外部".toByteArray()
        val synced = (1..3).map { async { store.syncFromWorkspace("s", first.metadata.id)!! } }.map { it.await() }
        assertThat(synced.map { it.version }).containsExactly(2, 2, 2)
    }
}
