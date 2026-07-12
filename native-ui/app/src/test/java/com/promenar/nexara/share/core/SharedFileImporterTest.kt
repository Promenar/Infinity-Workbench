package com.promenar.nexara.share.core

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ShareRequest
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class SharedFileImporterTest {
    @Test
    fun `部分成功会保留逐项拒绝且只调度已创建文件`() = runTest {
        val good = Uri.parse("content://fixture/good.txt")
        val denied = Uri.parse("content://fixture/denied.txt")
        val source = FakeSource(
            metadata = mapOf(good to meta("good.txt", "text/plain", 5)),
            bytes = mapOf(good to "hello".toByteArray()),
            denied = setOf(denied),
        )
        val scheduled = mutableListOf<FileEntry>()
        val importer = SharedFileImporter(source, repository(), ShareIndexScheduler { _, entry ->
            scheduled += entry
            ShareIndexReceipt("task-${entry.uuid}", entry.uuid)
        })

        val result = importer.import(request(listOf(good, denied)), ROOT)

        assertThat(result.created.map { it.displayName }).containsExactly("good.txt")
        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.PermissionDenied)
        assertThat(scheduled.map { it.name }).containsExactly("good.txt")
    }

    @Test
    fun `内容嗅探不匹配与空文件均拒绝且不创建可见结果`() = runTest {
        val fakePdf = Uri.parse("content://fixture/fake.pdf")
        val empty = Uri.parse("content://fixture/empty.txt")
        val source = FakeSource(
            metadata = mapOf(
                fakePdf to meta("fake.pdf", "application/pdf", 4),
                empty to meta("empty.txt", "text/plain", 0),
            ),
            bytes = mapOf(fakePdf to "nope".toByteArray(), empty to byteArrayOf()),
        )
        val importer = SharedFileImporter(source, repository())

        val pdfResult = importer.import(request(listOf(fakePdf), "application/pdf"), ROOT)
        val emptyResult = importer.import(request(listOf(empty)), ROOT)

        assertThat(pdfResult.rejected.single().reason).isEqualTo(ShareRejectReason.MimeMismatch)
        assertThat(emptyResult.rejected.single().reason).isEqualTo(ShareRejectReason.EmptyFile)
    }

    @Test
    fun `单项和批次一百MiB边界按元数据提前拒绝`() = runTest {
        val oversized = Uri.parse("content://fixture/oversized.txt")
        val first = Uri.parse("content://fixture/first.txt")
        val second = Uri.parse("content://fixture/second.txt")
        val source = FakeSource(
            metadata = mapOf(
                oversized to meta("oversized.txt", "text/plain", SharedFileImporter.MAX_ITEM_BYTES + 1),
                first to meta("first.txt", "text/plain", SharedFileImporter.MAX_BATCH_BYTES),
                second to meta("second.txt", "text/plain", 1),
            ),
            bytes = mapOf(first to "a".toByteArray(), second to "b".toByteArray()),
        )
        val importer = SharedFileImporter(
            source,
            repository(reportedSizes = mapOf("first.txt" to SharedFileImporter.MAX_BATCH_BYTES)),
        )

        val itemResult = importer.import(request(listOf(oversized)), ROOT)
        val batchResult = importer.import(request(listOf(first, second)), ROOT)

        assertThat(itemResult.rejected.single().reason).isEqualTo(ShareRejectReason.ItemTooLarge)
        assertThat(batchResult.items.last().reason).isEqualTo(ShareRejectReason.BatchTooLarge)
    }

    @Test
    fun `已有同名文件使用稳定编号且不覆盖`() = runTest {
        val uri = Uri.parse("content://fixture/report.md")
        val source = FakeSource(
            metadata = mapOf(uri to meta("report.md", "text/markdown", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
        )
        val repository = repository(existingPaths = setOf("/report.md", "/report (1).md"))

        val result = SharedFileImporter(source, repository).import(
            request(listOf(uri), "text/markdown"),
            ROOT,
        )

        assertThat(result.created.single().displayName).isEqualTo("report (2).md")
    }

    @Test
    fun `ack重放时按staged哈希复用已创建文件而不重复写入`() = runTest {
        val uri = Uri.parse("content://fixture/replay.txt")
        val source = FakeSource(
            metadata = mapOf(uri to meta("replay.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
            hashes = mapOf(uri to "hash"),
        )
        val repository = repository(existingPaths = setOf("/replay.txt"))
        val result = SharedFileImporter(source, repository).import(request(listOf(uri)), ROOT)

        assertThat(result.created.single().displayName).isEqualTo("replay.txt")
        coVerify(exactly = 0) {
            repository.createFileInWorkspaceStreaming(
                workspaceRootUuid = any(),
                uuid = any(),
                name = any(),
                mimeType = any(),
                parentUuid = any(),
                materializedPath = any(),
                maxBytes = any(),
                writer = any(),
            )
        }
    }

    @Test
    fun `复用同SHA既有文件时调度失败绝不删除用户文件`() = runTest {
        val uri = Uri.parse("content://fixture/existing.txt")
        val source = FakeSource(
            metadata = mapOf(uri to meta("existing.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
            hashes = mapOf(uri to "hash"),
        )
        val repository = repository(existingPaths = setOf("/existing.txt"))
        val importer = SharedFileImporter(source, repository, ShareIndexScheduler { _, _ ->
            throw java.io.IOException("schedule failed")
        })

        val result = importer.import(request(listOf(uri)), ROOT)

        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.IndexScheduleFailed)
        coVerify(exactly = 0) { repository.permanentDelete(any(), any()) }
    }

    @Test
    fun `复用同SHA既有文件时取消必须传播且绝不删除用户文件`() = runTest {
        val uri = Uri.parse("content://fixture/cancel.txt")
        val source = FakeSource(
            metadata = mapOf(uri to meta("cancel.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
            hashes = mapOf(uri to "hash"),
        )
        val repository = repository(existingPaths = setOf("/cancel.txt"))
        val cancellation = CancellationException("cancelled")
        val importer = SharedFileImporter(source, repository, ShareIndexScheduler { _, _ ->
            throw cancellation
        })

        val failure = try {
            importer.import(request(listOf(uri)), ROOT)
            null
        } catch (caught: CancellationException) {
            caught
        }

        assertThat(failure).isNotNull()
        assertThat(failure?.message).isEqualTo(cancellation.message)
        assertThat(failure === cancellation || failure?.cause === cancellation).isTrue()
        coVerify(exactly = 0) { repository.permanentDelete(any(), any()) }
    }

    @Test
    fun `本轮新建文件遇到调度SecurityException先删除再映射权限拒绝`() = runTest {
        val uri = Uri.parse("content://fixture/new-security.txt")
        val source = FakeSource(
            metadata = mapOf(uri to meta("new-security.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
        )
        val repository = repository()
        val importer = SharedFileImporter(source, repository, ShareIndexScheduler { _, _ ->
            throw SecurityException("scheduler denied")
        })

        val result = importer.import(request(listOf(uri)), ROOT)

        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.PermissionDenied)
        coVerify(exactly = 1) { repository.permanentDelete(ROOT, "entry-new-security.txt") }
    }

    @Test
    fun `复用既有文件遇到调度SecurityException不删除并映射权限拒绝`() = runTest {
        val uri = Uri.parse("content://fixture/existing-security.txt")
        val source = FakeSource(
            metadata = mapOf(uri to meta("existing-security.txt", "text/plain", 4)),
            bytes = mapOf(uri to "body".toByteArray()),
            hashes = mapOf(uri to "hash"),
        )
        val repository = repository(existingPaths = setOf("/existing-security.txt"))
        val importer = SharedFileImporter(source, repository, ShareIndexScheduler { _, _ ->
            throw SecurityException("scheduler denied")
        })

        val result = importer.import(request(listOf(uri)), ROOT)

        assertThat(result.rejected.single().reason).isEqualTo(ShareRejectReason.PermissionDenied)
        coVerify(exactly = 0) { repository.permanentDelete(any(), any()) }
    }

    private fun repository(
        existingPaths: Set<String> = emptySet(),
        reportedSizes: Map<String, Long> = emptyMap(),
    ): IWorkspaceRepository {
        val repository = mockk<IWorkspaceRepository>()
        coEvery { repository.getByMaterializedPath(ROOT, any()) } answers {
            val path = secondArg<String>()
            if (path in existingPaths) entry(path.removePrefix("/"), 1) else null
        }
        coEvery {
            repository.createFileInWorkspaceStreaming(
                workspaceRootUuid = ROOT,
                uuid = any(),
                name = any(),
                mimeType = any(),
                parentUuid = ROOT,
                materializedPath = any(),
                maxBytes = any(),
                writer = any(),
            )
        } coAnswers {
            val name = arg<String>(2)
            val output = ByteArrayOutputStream()
            arg<(java.io.OutputStream) -> Unit>(7)(output)
            entry(name, reportedSizes[name] ?: output.size().toLong())
        }
        coJustRun { repository.permanentDelete(any(), any()) }
        return repository
    }

    private fun request(uris: List<Uri>, mime: String = "text/plain") = ShareRequest(
        uris = uris,
        mimeType = mime,
        fingerprint = "0".repeat(64),
        canonicalSizeBytes = 1,
        requestId = "request",
    )

    private fun entry(name: String, size: Long) = FileEntry(
        uuid = "entry-$name",
        workspaceRootUuid = ROOT,
        parentUuid = ROOT,
        name = name,
        hash = "hash",
        mimeType = if (name.endsWith(".md")) "text/markdown" else "text/plain",
        sizeBytes = size,
        physicalRootPath = "/workspace",
        materializedPath = "/$name",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun meta(name: String, mime: String, size: Long) =
        SharedContentMetadata(name, mime, size)

    private class FakeSource(
        private val metadata: Map<Uri, SharedContentMetadata>,
        private val bytes: Map<Uri, ByteArray>,
        private val denied: Set<Uri> = emptySet(),
        private val hashes: Map<Uri, String> = emptyMap(),
    ) : SharedContentSource {
        override fun metadata(uri: Uri): SharedContentMetadata {
            if (uri in denied) throw SecurityException("denied")
            return checkNotNull(metadata[uri])
        }

        override fun open(uri: Uri): InputStream = ByteArrayInputStream(checkNotNull(bytes[uri]))
        override fun contentSha256(uri: Uri): String? = hashes[uri]
    }

    private companion object {
        const val ROOT = "workspace-root"
    }
}
