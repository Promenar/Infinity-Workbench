package com.promenar.nexara.share.core

import android.content.ContentResolver
import android.content.Intent
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ShareEnqueueResult
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class DurableShareInboxTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `ContentResolver每项只打开一次且进程重建恢复同一lease`() = runTest {
        val uri = Uri.parse("content://fixture/data.json")
        var opens = 0
        val resolver = resolver(
            mapOf(uri to Fixture("data.json", "application/json", "{\"ok\":true}".toByteArray())),
            onOpen = { opens++ },
        )
        val root = temporary.newFolder("no-backup")
        val inbox = DurableShareInbox(root)
        val intent = share(uri, "application/json")

        assertThat(inbox.stage(intent, resolver)).isEqualTo(ShareEnqueueResult.Accepted)
        assertThat(opens).isEqualTo(1)
        val first = inbox.claimNext()!!

        val afterProcessRecreation = DurableShareInbox(root)
        val recovered = afterProcessRecreation.claimNext()!!
        assertThat(recovered.token).isEqualTo(first.token)
        assertThat(recovered.request.uris).hasSize(1)
        assertThat(afterProcessRecreation.contentSource().open(recovered.request.uris.single()).readBytes())
            .isEqualTo("{\"ok\":true}".toByteArray())
        assertThat(afterProcessRecreation.ack(recovered.token)).isTrue()
        assertThat(DurableShareInbox(root).stage(intent, resolver)).isEqualTo(ShareEnqueueResult.Duplicate)
    }

    @Test
    fun `部分拒绝保留在manifest且已创建项重建后不会重复导入`() = runTest {
        val good = Uri.parse("content://fixture/good.txt")
        val fakePdf = Uri.parse("content://fixture/fake.pdf")
        val root = temporary.newFolder("partial")
        val inbox = DurableShareInbox(root)
        val resolver = resolver(
            mapOf(
                good to Fixture("good.txt", "text/plain", "hello".toByteArray()),
                fakePdf to Fixture("fake.pdf", "application/pdf", "not-pdf".toByteArray()),
            )
        )
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(good, fakePdf))
        }
        assertThat(inbox.stage(intent, resolver)).isEqualTo(ShareEnqueueResult.Accepted)
        val lease = inbox.claimNext()!!
        val source = inbox.contentSource()
        val goodStaged = lease.request.uris.first()
        val rejectedStaged = lease.request.uris.last()
        assertThat(source.preflightReason(rejectedStaged)).isEqualTo(ShareRejectReason.MimeMismatch)

        inbox.recordCreated(
            lease.request.requestId,
            listOf(
                ShareImportItem(
                    uri = goodStaged,
                    displayName = "good.txt",
                    mimeType = "text/plain",
                    sizeBytes = 5,
                    status = ShareImportStatus.Created,
                    indexStatus = ShareIndexStatus.Pending,
                    fileUuid = "11111111-1111-1111-1111-111111111111",
                    indexTaskId = "doc-ref-persisted",
                )
            ),
        )
        inbox.nack(lease.token)

        val restored = DurableShareInbox(root).claimNext()!!
        val restoredCreated = DurableShareInbox(root).contentSource().preflightItem(restored.request.uris.first())
        assertThat(restoredCreated?.status).isEqualTo(ShareImportStatus.Created)
        assertThat(restoredCreated?.fileUuid).isEqualTo("11111111-1111-1111-1111-111111111111")
        assertThat(restoredCreated?.indexTaskId).isEqualTo("doc-ref-persisted")
        assertThat(DurableShareInbox(root).contentSource().preflightReason(restored.request.uris.last()))
            .isEqualTo(ShareRejectReason.MimeMismatch)
    }

    @Test
    fun `超时lease原子恢复且旧token不能ack`() = runTest {
        var clock = 1_000L
        val uri = Uri.parse("content://fixture/a.txt")
        val root = temporary.newFolder("lease")
        val inbox = DurableShareInbox(root, now = { clock })
        assertThat(inbox.stage(share(uri, "text/plain"), resolver(
            mapOf(uri to Fixture("a.txt", "text/plain", "a".toByteArray()))
        ))).isEqualTo(ShareEnqueueResult.Accepted)
        val first = inbox.claimNext()!!
        clock += DurableShareInbox.LEASE_TIMEOUT_MS
        val recovered = inbox.claimNext()!!
        assertThat(recovered.token).isNotEqualTo(first.token)
        assertThat(inbox.ack(first.token)).isFalse()
        assertThat(inbox.ack(recovered.token)).isTrue()
    }

    @Test
    fun `非法JSON以逐项拒绝持久化而非伪装文本`() = runTest {
        val uri = Uri.parse("content://fixture/bad.json")
        val inbox = DurableShareInbox(temporary.newFolder("json"))
        assertThat(inbox.stage(share(uri, "application/json"), resolver(
            mapOf(uri to Fixture("bad.json", "application/json", "{bad".toByteArray()))
        ))).isEqualTo(ShareEnqueueResult.Accepted)
        val lease = inbox.claimNext()!!
        assertThat(inbox.contentSource().preflightReason(lease.request.uris.single()))
            .isEqualTo(ShareRejectReason.MimeMismatch)
    }

    @Test
    fun `未知长度流超限后不遗留部分staging文件`() = runTest {
        val uri = Uri.parse("content://fixture/large.txt")
        val root = temporary.newFolder("oversize")
        val inbox = DurableShareInbox(root, maxItemBytes = 4, maxBatchBytes = 4)
        assertThat(inbox.stage(share(uri, "text/plain"), resolver(
            mapOf(uri to Fixture("large.txt", "text/plain", "12345".toByteArray(), reportedSize = null))
        ))).isEqualTo(ShareEnqueueResult.Accepted)
        val lease = inbox.claimNext()!!
        assertThat(inbox.contentSource().preflightReason(lease.request.uris.single()))
            .isEqualTo(ShareRejectReason.ItemTooLarge)
        assertThat(root.walkTopDown().any { it.isFile && it.name.startsWith("item-") }).isFalse()
    }

    @Test
    fun `未知长度流仅超过批次阈值时返回批次过大`() = runTest {
        val uri = Uri.parse("content://fixture/batch.txt")
        val inbox = DurableShareInbox(
            temporary.newFolder("batch-limit"),
            maxItemBytes = 10,
            maxBatchBytes = 4,
        )
        assertThat(inbox.stage(share(uri, "text/plain"), resolver(
            mapOf(uri to Fixture("batch.txt", "text/plain", "12345".toByteArray(), reportedSize = null))
        ))).isEqualTo(ShareEnqueueResult.Accepted)

        val lease = inbox.claimNext()!!
        assertThat(inbox.contentSource().preflightReason(lease.request.uris.single()))
            .isEqualTo(ShareRejectReason.BatchTooLarge)
    }

    @Test
    fun `DOCX恰好2048条目仍在安全上限内`() = runTest {
        val uri = Uri.parse("content://fixture/limit.docx")
        val bytes = docxWithEntries(2_048)
        val inbox = DurableShareInbox(temporary.newFolder("docx-limit"))
        assertThat(inbox.stage(share(uri, DOCX_MIME), resolver(
            mapOf(uri to Fixture("limit.docx", DOCX_MIME, bytes))
        ))).isEqualTo(ShareEnqueueResult.Accepted)

        val lease = inbox.claimNext()!!
        assertThat(inbox.contentSource().preflightReason(lease.request.uris.single())).isNull()
    }

    @Test
    fun `request目录rename后index写入前崩溃可由新实例恢复`() = runTest {
        val uri = Uri.parse("content://fixture/crash.txt")
        val root = temporary.newFolder("rename-crash")
        val crashing = DurableShareInbox(
            noBackupFilesDir = root,
            faultInjector = { checkpoint ->
                if (checkpoint == DurableShareCheckpoint.AfterRequestRenameBeforeIndexWrite) {
                    throw SimulatedProcessDeath()
                }
            },
        )

        var crashed = false
        try {
            crashing.stage(share(uri, "text/plain"), resolver(
                mapOf(uri to Fixture("crash.txt", "text/plain", "durable".toByteArray()))
            ))
        } catch (_: SimulatedProcessDeath) {
            crashed = true
        }

        assertThat(crashed).isTrue()
        val restored = DurableShareInbox(root)
        assertThat(restored.pendingCount.value).isEqualTo(1)
        val lease = restored.claimNext()!!
        assertThat(restored.contentSource().open(lease.request.uris.single()).readBytes())
            .isEqualTo("durable".toByteArray())
    }

    @Test
    fun `index引用缺失目录时启动修复移除记录`() = runTest {
        val uri = Uri.parse("content://fixture/missing.txt")
        val root = temporary.newFolder("missing-directory")
        val inbox = DurableShareInbox(root)
        assertThat(inbox.stage(share(uri, "text/plain"), resolver(
            mapOf(uri to Fixture("missing.txt", "text/plain", "data".toByteArray()))
        ))).isEqualTo(ShareEnqueueResult.Accepted)
        root.resolve("share-inbox-v1/requests").listFiles()!!.single { !it.name.startsWith(".stage-") }
            .deleteRecursively()

        assertThat(DurableShareInbox(root).pendingCount.value).isEqualTo(0)
    }

    @Test
    fun `启动扫描发现staged内容SHA不匹配会丢弃损坏请求`() = runTest {
        val uri = Uri.parse("content://fixture/tampered.txt")
        val root = temporary.newFolder("tampered-request")
        val inbox = DurableShareInbox(root)
        assertThat(inbox.stage(share(uri, "text/plain"), resolver(
            mapOf(uri to Fixture("tampered.txt", "text/plain", "original".toByteArray()))
        ))).isEqualTo(ShareEnqueueResult.Accepted)
        root.resolve("share-inbox-v1/requests").walkTopDown().single { it.isFile && it.name == "item-000" }
            .writeText("tampered")

        val restored = DurableShareInbox(root)
        assertThat(restored.pendingCount.value).isEqualTo(0)
        assertThat(root.resolve("share-inbox-v1/requests").listFiles().orEmpty()).isEmpty()
    }

    private fun resolver(fixtures: Map<Uri, Fixture>, onOpen: () -> Unit = {}): ContentResolver {
        val resolver = mockk<ContentResolver>()
        every { resolver.getType(any()) } answers { fixtures[firstArg()]?.mime }
        every { resolver.query(any(), any(), null, null, null) } answers {
            val fixture = checkNotNull(fixtures[firstArg()])
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf(fixture.name, fixture.reportedSize))
            }
        }
        every { resolver.openInputStream(any()) } answers {
            onOpen()
            ByteArrayInputStream(checkNotNull(fixtures[firstArg()]).bytes)
        }
        return resolver
    }

    private fun share(uri: Uri, mime: String) = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
    }

    private fun docxWithEntries(count: Int): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<document/>".toByteArray())
            zip.closeEntry()
            repeat(count - 2) { index ->
                zip.putNextEntry(ZipEntry("padding/$index"))
                zip.closeEntry()
            }
        }
        bytes.toByteArray()
    }

    private data class Fixture(
        val name: String,
        val mime: String,
        val bytes: ByteArray,
        val reportedSize: Long? = bytes.size.toLong(),
    )

    private companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }

    private class SimulatedProcessDeath : Error()
}
