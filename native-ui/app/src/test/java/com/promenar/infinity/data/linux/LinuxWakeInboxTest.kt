package com.promenar.infinity.data.linux

import com.promenar.infinity.domain.trigger.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class LinuxWakeInboxTest {
    @TempDir lateinit var root: Path
    private fun json(key: String = "default") = "{\"v\":1,\"key\":\"$key\",\"body\":\"消息\"}"
    private fun box() = root.resolve("box").toFile().apply { mkdirs() }

    @Test fun `坏文件与无关文件删除而临时文件仅清理过期项`() {
        val box = box()
        File(box, "bad.json").writeText("秘密错误正文")
        File(box, "unrelated.txt").writeText("数据")
        val fresh = File(box, ".tmp-new").apply { writeText("暂存") }
        val old = File(box, ".tmp-old").apply { writeText("旧暂存") }
        val now = System.currentTimeMillis()
        Files.setLastModifiedTime(old.toPath(), FileTime.fromMillis(now - LinuxWakeLimits.TEMP_MAX_AGE - 1))
        val logs = mutableListOf<String>()
        assertTrue(LinuxWakeInboxScanner({ now }, logs::add).scan(box).isEmpty())
        assertEquals(listOf(fresh.name), box.list()!!.toList())
        assertFalse(logs.joinToString().contains("秘密错误正文"))
    }

    @Test fun `只处理文件名最新32个且读取后均删除`() {
        val box = box()
        for (i in 0..39) File(box, "%03d.json".format(i)).writeText(json("k$i"))
        val messages = LinuxWakeInboxScanner().scan(box)
        assertEquals((8..39).map { "k$it" }, messages.map { it.key })
        assertTrue(box.list()!!.isEmpty())
    }

    @Test fun `符号链接与链接目录不跟随且超大文件直接删除`() {
        val box = box()
        val outside = root.resolve("outside.json").toFile().apply { writeText(json()) }
        val directory = root.resolve("outside").toFile().apply { mkdirs() }
        File(directory, "keep").writeText("保留")
        Files.createSymbolicLink(File(box, "link.json").toPath(), outside.toPath())
        Files.createSymbolicLink(File(box, "directory.json").toPath(), directory.toPath())
        File(box, "huge.json").writeBytes(ByteArray(LinuxWakeLimits.MAX_FILE_BYTES + 1))
        assertTrue(LinuxWakeInboxScanner().scan(box).isEmpty())
        assertTrue(outside.exists()); assertTrue(File(directory, "keep").exists())
        assertTrue(box.list()!!.isEmpty())
    }

    private class Watcher : LinuxWakeWatcher {
        val callbacks = mutableMapOf<File, () -> Unit>()
        var closed = 0
        override fun watch(directory: File, onChange: () -> Unit): AutoCloseable {
            callbacks[directory] = onChange
            return AutoCloseable { callbacks.remove(directory); closed++ }
        }
    }

    private fun TestScope.inbox(watcher: Watcher, requests: MutableList<SystemTurnRequest>, exists: (String) -> Boolean = { true }): LinuxWakeInbox {
        val dispatch = LinuxWakeDispatch(backgroundScope, { object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome {
                requests += request; return SystemTurnOutcome.Completed
            }
        } }, { testScheduler.currentTime })
        return LinuxWakeInbox(root.toFile(), backgroundScope, dispatch, watcher, { exists(it) }, StandardTestDispatcher(testScheduler))
    }

    @Test fun `目录按摘要隔离且监听驱动投递不信任box内会话标识`() = runTest {
        val watcher = Watcher(); val requests = mutableListOf<SystemTurnRequest>(); val inbox = inbox(watcher, requests)
        val box = inbox.prepare("session-a")!!
        assertEquals("box", box.name)
        assertEquals(LinuxWakeInbox.hash("session-a"), box.parentFile!!.name)
        assertEquals("session-a", File(box.parentFile, "session").readText())
        runCurrent()
        File(box, "session").writeText("session-b")
        File(box, "1.json").writeText(json())
        watcher.callbacks.getValue(box).invoke(); runCurrent()
        assertEquals(listOf("session-a"), requests.map { it.sessionId })
        assertFalse(File(box, "session").exists())
        assertEquals(box, inbox.prepare("session-a")); assertEquals(1, watcher.callbacks.size)
        inbox.deleteSession("session-a"); runCurrent()
        assertFalse(box.parentFile!!.exists()); assertEquals(1, watcher.closed)
    }

    @Test fun `启动补扫恢复有效会话并移除失效和摘要不匹配目录`() = runTest {
        val watcher = Watcher(); val requests = mutableListOf<SystemTurnRequest>(); val inbox = inbox(watcher, requests) { it == "valid" }
        val base = root.resolve(LinuxWakeLimits.HOST_DIRECTORY).toFile().apply { mkdirs() }
        for ((directory, session) in listOf(LinuxWakeInbox.hash("valid") to "valid", LinuxWakeInbox.hash("gone") to "gone", "wrong-hash" to "valid")) {
            val parent = File(base, directory).apply { mkdirs() }
            File(parent, "session").writeText(session)
            File(parent, "box").mkdir()
            File(parent, "box/1.json").writeText(json())
        }
        inbox.recover(); runCurrent()
        assertEquals(1, requests.size); assertEquals("valid", requests.single().sessionId)
        assertEquals(listOf(LinuxWakeInbox.hash("valid")), base.list()!!.toList())
        assertNull(inbox.prepare("gone"))
    }

    @Test fun `任务补扫无需监听事件且删除撤销冷却中的消息`() = runTest {
        val watcher = Watcher(); val requests = mutableListOf<SystemTurnRequest>(); val inbox = inbox(watcher, requests)
        val box = inbox.prepare("s")!!; runCurrent()
        File(box, "1.json").writeText(json("one")); inbox.scan("s")
        File(box, "2.json").writeText(json("two")); inbox.scan("s"); runCurrent()
        inbox.deleteSession("s"); advanceTimeBy(LinuxWakeLimits.MIN_DISPATCH_INTERVAL); runCurrent()
        assertEquals(1, requests.size)
    }
}
