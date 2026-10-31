package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.*
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 内容放在临时文件，只允许固定缓冲读写，避免大文件测试自己依赖整块内存。 */
@RunWith(Parameterized::class)
class ExternalStreamingMoveTest(private val direction: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun directions() = listOf(
            "私有→挂载", "挂载→私有", "挂载A→挂载B", "rootfs→挂载", "挂载→rootfs", "rootfs→私有", "私有→rootfs",
        )
    }
    private fun fixture(block: suspend (StreamingDiskBackend, StreamingDiskBackend) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("stream-move-").toFile()
        try { block(StreamingDiskBackend(File(directory, "source").apply { mkdir() }), StreamingDiskBackend(File(directory, "target").apply { mkdir() })) }
        finally { directory.deleteRecursively() }
    }
    private suspend fun move(source: ExternalFolderBackend, from: String, target: ExternalFolderBackend, to: String, expected: String? = null) {
        val roles = direction.replace("挂载A", "挂载").replace("挂载B", "挂载").split("→")
        val sourceBackend = if (roles[0] == "私有") com.promenar.infinity.data.workspace.SessionPrivateWorkspaceBackend(source) else source
        val targetBackend = if (roles[1] == "私有") com.promenar.infinity.data.workspace.SessionPrivateWorkspaceBackend(target) else target
        val sourcePath = if (roles[0] == "私有") "/session$from" else from
        val targetPath = if (roles[1] == "私有") "/session$to" else to
        if ("rootfs" in roles) {
            // Linux 文件工具将独立命名空间映射为传输根，实际 IO 仍由各端点承担。
            try {
                ExternalTreeTransfer.move(com.promenar.infinity.ui.chat.manager.skills.LinuxToolTransferBackend(sourceBackend, sourcePath), "/item",
                    com.promenar.infinity.ui.chat.manager.skills.LinuxToolTransferBackend(targetBackend, targetPath), "/item", expected)
            } catch (failure: ExternalFolderException) {
                if (failure.code == "RECOVERY_REQUIRED") throw ExternalFolderException(failure.code, "$sourcePath $targetPath").apply { initCause(failure) }
                throw failure
            }
        } else ExternalTreeTransfer.move(sourceBackend, sourcePath, targetBackend, targetPath, expected)
    }
    @Test fun `80MiB文件不通过整块读写完成移动`() = fixture { source, target ->
        source.seed("/item", 80L * 1024 * 1024)
        val hash = source.hash("/item")
        move(source, "/item", target, "/moved", hash)
        assertNull(source.stat("/item")); assertEquals(hash, target.hash("/moved"))
        assertEquals(80L * 1024 * 1024, target.stat("/moved")!!.size)
    }
    @Test fun `目录超过1000条目可以移动`() = fixture { source, target ->
        source.createDirectory("/item"); repeat(1005) { source.seed("/item/f$it", 1) }
        move(source, "/item", target, "/moved")
        assertNull(source.stat("/item")); assertEquals(1005, target.list("/moved").size)
    }
    @Test fun `空间不足不创建条目`() = fixture { source, target ->
        source.seed("/item", 1024); target.space = 1023
        assertEquals("NO_SPACE", failure { move(source, "/item", target, "/moved") }.code)
        assertNotNull(source.stat("/item")); assertTrue(target.list("/").isEmpty())
    }
    @Test fun `来源读取在目标提交后才报错也能回滚`() = fixture { source, target ->
        source.seed("/item", 777)
        var failed = false
        source.afterRead = {
            if (!failed && target.stat("/moved") != null) {
                failed = true
                throw ExternalFolderException("CONFLICT", "读后版本冲突")
            }
        }
        assertEquals("CONFLICT", failure { move(source, "/item", target, "/moved") }.code)
        assertTrue(failed); assertNotNull(source.stat("/item")); assertTrue(target.list("/").isEmpty())
    }
    @Test fun `删除来源期间目标被改写不能报告成功并保全原文`() = fixture { source, target ->
        source.seed("/item", 777)
        val hash = source.hash("/item")
        source.afterDelete = { File(target.root, "moved").writeText("外部目标") }
        assertEquals("RECOVERY_REQUIRED", failure { move(source, "/item", target, "/moved") }.code)
        assertEquals(hash, source.hash("/item"))
        assertEquals("外部目标", File(target.root, "moved").readText())
    }
    @Test fun `删除后的来源被重建不能凭路径存在清理唯一旧副本`() = fixture { source, target ->
        source.createDirectory("/item"); source.seed("/item/a", 777); source.seed("/item/b", 888)
        val original = source.hash("/item/a")
        var removed = 0
        var changed = false
        source.afterDelete = { removed++ }
        target.afterRead = {
            if (removed == 1 && !changed) {
                changed = true
                File(source.root, "item/a").writeText("外部新建a")
                File(source.root, "item/b").writeText("外部改写b")
            }
        }
        assertEquals("RECOVERY_REQUIRED", failure { move(source, "/item", target, "/moved") }.code)
        assertEquals("外部新建a", File(source.root, "item/a").readText())
        assertEquals(original, target.hash("/moved/a"))
    }
    @Test fun `最后目标校验期间来源新增条目必须保留`() = fixture { source, target ->
        source.createDirectory("/item"); source.seed("/item/a", 777)
        var reads = 0
        source.afterRead = { reads++ }
        var changed = false
        target.afterRead = {
            if (reads >= 3 && !changed) {
                changed = true
                source.seed("/item/new", 2)
            }
        }
        failure { move(source, "/item", target, "/moved") }
        assertTrue(changed); assertNotNull(source.stat("/item/new"))
    }
    @Test fun `深度超限在创建目标前拒绝`() = fixture { source, target ->
        source.createDirectory("/item")
        var path = "/item"
        repeat(33) { path += "/child"; source.createDirectory(path) }
        assertEquals("TOO_LARGE", failure { move(source, "/item", target, "/moved") }.code)
        assertTrue(target.list("/").isEmpty())
    }
    @Test fun `复制中途失败只保留来源`() = fixture { source, target ->
        source.seed("/item", 1024 * 1024); target.failWrite = true
        failure { move(source, "/item", target, "/moved") }
        assertNotNull(source.stat("/item")); assertTrue(target.list("/").isEmpty())
    }
    @Test fun `复制取消先补偿再传播取消`() = fixture { source, target ->
        source.seed("/item", 1024 * 1024); target.cancelWrite = true
        try { move(source, "/item", target, "/moved"); fail("应传播取消") } catch (_: CancellationException) { }
        assertNotNull(source.stat("/item")); assertTrue(target.list("/").isEmpty())
    }
    @Test fun `来源部分删除时真实协程取消也恢复来源`() = fixture { source, target ->
        source.createDirectory("/item"); source.seed("/item/a", 65537); source.seed("/item/b", 777)
        val hash = source.hash("/item/a")
        source.deleteFailure = {
            File(source.root, "item/a").delete()
            kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
        }
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch {
            move(source, "/item", target, "/moved")
        }
        job.join()
        assertTrue(job.isCancelled); assertEquals(hash, source.hash("/item/a")); assertTrue(target.list("/").isEmpty())
    }
    @Test fun `来源删除一半失败从目标恢复`() = fixture { source, target ->
        source.createDirectory("/item"); source.seed("/item/a", 65537); source.seed("/item/b", 777)
        val hash = source.hash("/item/a")
        source.deleteFailure = { File(source.root, "item/a").delete(); throw ExternalFolderException("IO", "删除中断") }
        assertEquals("IO", failure { move(source, "/item", target, "/moved") }.code)
        assertEquals(hash, source.hash("/item/a")); assertNotNull(source.stat("/item/b")); assertTrue(target.list("/").isEmpty())
    }
    @Test fun `目标被外部修改保留双方并列出恢复路径`() = fixture { source, target ->
        source.createDirectory("/item"); source.seed("/item/a", 65537); source.seed("/item/b", 777)
        val originalHash = source.hash("/item/a")
        source.deleteFailure = {
            File(source.root, "item/a").delete(); File(target.root, "moved/a").appendText("外部变更")
            throw ExternalFolderException("IO", "删除中断")
        }
        val failure = failure { move(source, "/item", target, "/moved") }
        assertEquals("RECOVERY_REQUIRED", failure.code)
        assertTrue(failure.message.contains("/item")); assertTrue(failure.message.contains("/moved"))
        assertEquals(originalHash, source.hash("/item/a"))
        assertNotNull(source.stat("/item/b")); assertNotNull(target.stat("/moved/a")); assertNotNull(target.stat("/moved/b"))
    }
    @Test fun `来源在复制期间变化冲突回滚目标`() = fixture { source, target ->
        source.seed("/item", 777); target.afterWrite = { File(source.root, "item").appendText("变更") }
        assertEquals("CONFLICT", failure { move(source, "/item", target, "/moved") }.code)
        assertEquals(777L + "变更".toByteArray().size, source.stat("/item")!!.size); assertTrue(target.list("/").isEmpty())
    }
    private suspend fun failure(block: suspend () -> Unit): ExternalFolderException {
        try { block(); error("应拒绝操作：$direction") } catch (failure: ExternalFolderException) { return failure }
    }
}

internal class StreamingDiskBackend(val root: File) : ExternalFolderBackend {
    var space = Long.MAX_VALUE
    var failWrite = false
    var cancelWrite = false
    var afterWrite: (suspend () -> Unit)? = null
    var afterRead: (suspend () -> Unit)? = null
    var afterDelete: (suspend () -> Unit)? = null
    var deleteFailure: (suspend () -> Unit)? = null
    private val identities = mutableMapOf<String, String>()
    private fun file(path: String) = File(root, ExternalFolderPaths.normalize(path).trimStart('/'))
    suspend fun seed(path: String, size: Long) {
        val block = ByteArray(64 * 1024) { (it % 251).toByte() }
        file(path).outputStream().use { output -> var remaining = size; while (remaining > 0) { val count = minOf(remaining, block.size.toLong()).toInt(); output.write(block, 0, count); remaining -= count } }
    }
    suspend fun hash(path: String): String = readStream(path, Long.MAX_VALUE) { input ->
        val digest = java.security.MessageDigest.getInstance("SHA-256"); val block = ByteArray(65536)
        while (true) { val n = input.read(block); if (n < 0) break; digest.update(block, 0, n) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    override suspend fun stat(path: String): ExternalFolderEntry? = file(path).takeIf { it.exists() }?.let {
        ExternalFolderEntry(path, it.name, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified(), identity = identities.getOrPut(path) { java.util.UUID.randomUUID().toString() })
    }
    override suspend fun list(path: String) = file(path).listFiles().orEmpty().sortedBy { it.name }.map { stat(path.trimEnd('/') + "/" + it.name)!! }
    override suspend fun read(path: String, maxBytes: Long): ByteArray = error("移动禁止整块读取")
    override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (InputStream) -> T): T = file(path).inputStream().use { consume(it).also { afterRead?.invoke() } }
    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry = error("移动禁止整块写入")
    override suspend fun availableBytes(path: String): Long? = space
    override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (OutputStream) -> Unit): ExternalFolderEntry {
        val f = file(path)
        if (!f.createNewFile()) throw ExternalFolderException("CONFLICT", "已存在")
        try {
            f.outputStream().use { output ->
                produce(object : OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        assertTrue("单次写入不得大于64KiB", length <= 65536)
                        output.write(bytes, offset, length)
                        if (length > 0 && cancelWrite) throw CancellationException("复制取消")
                        if (length > 0 && failWrite) throw ExternalFolderException("IO", "复制中断")
                    }
                })
            }
            afterWrite?.invoke(); return stat(path)!!
        } catch (failure: Exception) { f.delete(); identities.remove(path); throw failure }
    }
    override suspend fun createDirectory(path: String): ExternalFolderEntry { check(file(path).mkdir()); return stat(path)!! }
    override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) {
        deleteFailure?.let { deleteFailure = null; it() }
        val current = stat(path) ?: throw ExternalFolderException("CONFLICT", "来源不存在")
        if (current.identity != expected.identity || current.isDirectory != expected.isDirectory || !current.isDirectory && hash(path) != expectedSha256) {
            throw ExternalFolderException("CONFLICT", "来源变化")
        }
        Files.delete(file(path).toPath()); identities.remove(path); afterDelete?.invoke()
    }
    override suspend fun delete(path: String, expectedSha256: String?) {
        deleteFailure?.let { deleteFailure = null; it() }
        if (expectedSha256 != null && hash(path) != expectedSha256) throw ExternalFolderException("CONFLICT", "摘要变化")
        file(path).deleteRecursively(); identities.keys.removeAll { it == path || it.startsWith("$path/") }; afterDelete?.invoke()
    }
}
