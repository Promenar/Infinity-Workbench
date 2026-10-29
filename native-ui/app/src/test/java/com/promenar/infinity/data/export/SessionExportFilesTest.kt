package com.promenar.infinity.data.export

import android.content.Context
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.repository.IMessageRepository
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.domain.export.*
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class SessionExportFilesTest {
    @TempDir lateinit var cache: File
    private fun exporter(input: List<Message>, repository: IMessageRepository = mockk()): DefaultSessionExporter {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.cacheDir } returns cache
        val sessions = mockk<ISessionRepository>()
        coEvery { sessions.getById("s") } returns Session("s", "a", title = "导出", modelId = "m", createdAt = 1)
        coEvery { repository.getBySession("s") } returns input
        return DefaultSessionExporter(context, repository, sessions, mockk<WorkspaceMediaResolver>())
    }

    @Test fun `导出实际zip条目安全去重正文链接与归档一致且release只删除所属目录`() = runBlocking {
        val payload = """[{"uri":"data:application/octet-stream;base64,AQID","mimeType":"application/octet-stream","fileName":"../../a.bin"},{"uri":"data:application/octet-stream;base64,BAUG","mimeType":"application/octet-stream","fileName":"..\\a.bin"}]"""
        val service = exporter(listOf(Message("u", MessageRole.USER, "正文", legacyAttachmentsPayload = payload)))
        val result = service.export("s", SessionExportFormat.MARKDOWN, SessionExportOptions())
        assertEquals(listOf("text/markdown", "application/zip"), result.files.map { it.mimeType })
        assertTrue(result.files.first().file.name.removeSuffix(".md") == result.files.last().file.name.removeSuffix(".zip"))
        ZipFile(result.files.last().file).use { zip ->
            assertEquals(listOf("a.bin", "a_2.bin"), zip.entries().asSequence().map { it.name }.toList())
            assertArrayEquals(byteArrayOf(1, 2, 3), zip.getInputStream(zip.getEntry("a.bin")).readBytes())
            assertTrue(result.files.first().file.readText().contains("[a_2.bin](a_2.bin)"))
        }
        service.release(result)
        withTimeout(3000) { while (result.workDir.exists()) delay(10) }
        val unrelated = File(cache, "unrelated").apply { mkdir(); resolve("keep").writeText("keep") }
        service.release(result.copy(workDir = unrelated))
        delay(30)
        assertTrue(unrelated.resolve("keep").exists())
    }

    @Test fun `取消读取时移除已创建目录且失败不留下半成品`() = runBlocking {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.cacheDir } returns cache
        val messages = mockk<IMessageRepository>()
        val sessions = mockk<ISessionRepository>()
        val started = CompletableDeferred<Unit>()
        coEvery { sessions.getById(any()) } coAnswers { started.complete(Unit); awaitCancellation() }
        val service = DefaultSessionExporter(context, messages, sessions, mockk())
        val job = launch { service.export("s", SessionExportFormat.TXT, SessionExportOptions()) }
        started.await()
        job.cancelAndJoin()
        assertTrue(File(cache, "session-export").listFiles().orEmpty().isEmpty())
        coEvery { sessions.getById(any()) } returns null
        assertThrows(NoSuchElementException::class.java) {
            runBlocking { service.export("missing", SessionExportFormat.TXT, SessionExportOptions()) }
        }
        assertTrue(File(cache, "session-export").listFiles().orEmpty().isEmpty())
    }

    @Test fun `历史文本data附件内联且无需归档`() = runBlocking {
        val payload = """[{"uri":"data:text/plain;base64,aGVsbG8=","mimeType":"text/plain","fileName":"old.txt"}]"""
        val service = exporter(listOf(Message("u", MessageRole.USER, "正文", legacyFilesPayload = payload)))
        val result = service.export("s", SessionExportFormat.TXT, SessionExportOptions())
        try {
            assertEquals(1, result.files.size)
            assertTrue(result.files.first().file.readText().contains("--- Attachment: old.txt (text/plain) ---\nhello"))
        } finally {
            service.release(result)
            withTimeout(3000) { while (result.workDir.exists()) delay(10) }
        }
    }

    @Test fun `只清理超过24小时的所属目录且保留接收方仍可能读取的文件`() {
        val root = File(cache, "session-export").apply { mkdir() }
        val now = System.currentTimeMillis()
        val old = root.resolve("11111111-1111-1111-1111-111111111111").apply { mkdir(); setLastModified(now - 86_400_001) }
        val recent = root.resolve("22222222-2222-2222-2222-222222222222").apply { mkdir(); setLastModified(now - 1000) }
        val unknown = root.resolve("unknown").apply { mkdir(); setLastModified(1) }
        cleanupExpiredExportDirectories(root, now)
        assertFalse(old.exists())
        assertTrue(recent.exists())
        assertTrue(unknown.exists())
    }

    @Test fun `只有文本附件时主文件单独生成且估算不构造WebView`() = runBlocking {
        val doc = MessageDocumentAttachment("d", "notes.txt", "text/plain", "附件内容", 10, "hash", 3)
        val service = exporter(listOf(Message("u", MessageRole.USER, "正文", userDocuments = listOf(doc))))
        val result = service.export("s", SessionExportFormat.TXT, SessionExportOptions())
        try {
            assertEquals(1, result.files.size)
            val estimate = service.estimate("s", SessionExportFormat.TXT, SessionExportOptions())
            assertFalse(estimate.attachmentArchive)
            assertEquals(result.files.first().file.length(), estimate.approxBytes)
            assertTrue(service.estimate("s", SessionExportFormat.PDF, SessionExportOptions()).approxPages!! >= 1)
        } finally {
            service.release(result)
            withTimeout(3000) { while (result.workDir.exists()) delay(10) }
        }
    }
}
