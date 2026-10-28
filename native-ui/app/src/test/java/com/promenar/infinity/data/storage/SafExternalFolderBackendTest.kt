package com.promenar.infinity.data.storage

import android.app.Application
import android.content.pm.ProviderInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.promenar.infinity.domain.storage.ExternalFolderBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class SafExternalFolderBackendTest : ExternalFolderBackendContractTest() {
    private lateinit var provider: ExternalFolderTestDocumentsProvider
    private val application: Application get() = ApplicationProvider.getApplicationContext()
    private val treeUri: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")

    @Before
    fun registerProvider() {
        val info = ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider = Robolectric.buildContentProvider(ExternalFolderTestDocumentsProvider::class.java).create(info).get()
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @After
    fun cleanFixture() {
        provider.root.deleteRecursively()
    }

    override fun createBackend(): ExternalFolderBackend = SafExternalFolderBackend(application.contentResolver, treeUri)

    @Test fun `临时文档与备份仅位于根隐藏目录且忽略规则不复写`() = runBlocking {
        val backend = createBackend()
        backend.createDirectory("/notes")
        var stagedInsideRoot = false
        provider.onTemporaryWrite = {
            val staged = provider.root.walkTopDown().filter { it.name.startsWith(".infinity-write-") }.toList()
            stagedInsideRoot = staged.isNotEmpty() && staged.all { it.toPath().startsWith(File(provider.root, ".infinity").toPath()) }
        }
        val original = backend.write("/notes/file.txt", "original".toByteArray())
        assertTrue(stagedInsideRoot)
        val ignore = File(provider.root, ".infinity/.gitignore")
        assertEquals("*\n", ignore.readText())
        ignore.writeText("*.tmp\n")
        backend.write("/notes/file.txt", "new".toByteArray(), original.sha256)
        assertEquals("*.tmp\n", ignore.readText())
        ignore.delete()
        createBackend().write("/next", "next".toByteArray())
        assertFalse(ignore.exists())
        assertEquals(listOf("file.txt"), File(provider.root, "notes").list()!!.toList())
        assertTrue(File(provider.root, ".infinity").list()!!.isEmpty())
    }

    @Test fun `提供商缺少跨目录移动能力时拒绝替换并保留原内容`() = runBlocking {
        val backend = createBackend()
        val original = backend.write("/file.txt", "original".toByteArray())
        provider.moveSupported = false
        expectCode("UNSUPPORTED") { backend.write("/file.txt", "new".toByteArray(), original.sha256) }
        assertArrayEquals("original".toByteArray(), backend.read("/file.txt"))
    }

    @Test fun `旧文档隔离已提交但回执丢失时仍能恢复原文档`() = runBlocking {
        for (stableIds in listOf(false, true)) {
            provider.stableDocumentIds = stableIds
            val backend = createBackend()
            val name = if (stableIds) "stable.txt" else "path.txt"
            val original = backend.write("/$name", "original".toByteArray())
            provider.failNextMoveAfterCommit = true
            expectCode("UNAVAILABLE") { backend.write("/$name", "new".toByteArray(), original.sha256) }
            assertArrayEquals("original".toByteArray(), backend.read("/$name"))
            assertEquals(listOf(".gitignore"), File(provider.root, ".infinity").list()!!.toList())
        }
    }

    @Test fun `备份重命名已提交但回执或查询失败时按真实名称恢复原文档`() = runBlocking {
        for (stableIds in listOf(false, true)) {
            for (queryFailure in listOf(false, true)) {
                provider.stableDocumentIds = stableIds
                val backend = createBackend()
                val name = "original-$stableIds-$queryFailure.txt"
                val original = backend.write("/$name", "original".toByteArray())
                provider.failBackupRenameAfterCommit = !queryFailure
                provider.failBackupRenameQueryAfterCommit = queryFailure
                expectCode("UNAVAILABLE") { backend.write("/$name", "new".toByteArray(), original.sha256) }
                assertArrayEquals("original".toByteArray(), backend.read("/$name"))
                assertFalse(provider.root.list()!!.any { it.startsWith(".infinity-") })
                assertEquals(listOf(".gitignore"), File(provider.root, ".infinity").list()!!.toList())
            }
        }
    }

    @Test fun `首次访问保留旧暂存的根与嵌套路径且不收养未知内部名称`() = runBlocking {
        val name = ".infinity-backup-11111111-2222-4333-8444-555555555555"
        File(provider.root, name).writeText("root-recovery")
        File(provider.root, "notes/$name").apply { parentFile!!.mkdirs(); writeText("nested-recovery") }
        File(provider.root, ".infinity-unknown-11111111-2222-4333-8444-555555555555").writeText("user")
        val backend = createBackend()
        backend.list("/")
        backend.list("/notes")
        assertEquals("root-recovery", File(provider.root, ".infinity/legacy/$name").readText())
        assertEquals("nested-recovery", File(provider.root, ".infinity/legacy/notes/$name").readText())
        assertFalse(File(provider.root, name).exists())
        assertFalse(File(provider.root, "notes/$name").exists())
        assertEquals("user", File(provider.root, ".infinity-unknown-11111111-2222-4333-8444-555555555555").readText())
        createBackend().list("/notes")
        assertEquals("nested-recovery", File(provider.root, ".infinity/legacy/notes/$name").readText())
    }

    @Test fun `旧暂存缺少移动能力或目标冲突时保留两份且不阻断访问`() = runBlocking {
        val name = ".infinity-write-11111111-2222-4333-8444-555555555555"
        val source = File(provider.root, "notes/$name").apply { parentFile!!.mkdirs(); writeText("source") }
        provider.moveSupported = false
        createBackend().list("/notes")
        assertEquals("source", source.readText())
        val target = File(provider.root, ".infinity/legacy/notes/$name").apply { parentFile!!.mkdirs(); writeText("preserved") }
        provider.moveSupported = true
        createBackend().list("/notes")
        assertEquals("source", source.readText())
        assertEquals("preserved", target.readText())
        target.delete()
        createBackend().list("/notes")
        assertFalse(source.exists())
        assertEquals("source", target.readText())
    }

    @Test
    fun largeFileStatDoesNotOpenDocumentBody() = runBlocking {
        val backend = createBackend()
        File(provider.root, "large.bin").writeBytes(ByteArray(1024 * 1024 + 1))
        provider.openedDocuments.clear()
        val entry = backend.stat("/large.bin")!!
        assertEquals(1024L * 1024 + 1, entry.size)
        assertNull(entry.sha256)
        assertTrue(provider.openedDocuments.isEmpty())
    }

    @Test
    fun unknownSizeAndUnderreportedSizeNeverMakeStatHashAnUnboundedDocument() = runBlocking {
        val backend = createBackend()
        File(provider.root, "large.bin").writeBytes(ByteArray(2 * 1024 * 1024))
        provider.omitReportedSize = true
        assertNull(backend.stat("/large.bin")!!.sha256)
        provider.omitReportedSize = false
        provider.reportedSize = 0
        assertNull(backend.stat("/large.bin")!!.sha256)
    }

    @Test
    fun statHashReadsAtMostOneMiBPlusOneProbeByte() = runBlocking {
        var bytesRead = 0
        val input = object : ByteArrayInputStream(ByteArray(2 * 1024 * 1024)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
                if (it > 0) bytesRead += it
            }
        }
        assertNull(SafExternalFolderBackend.boundedStatSha256(input))
        assertEquals(1024 * 1024 + 1, bytesRead)
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            SafExternalFolderBackend.boundedStatSha256("hello".byteInputStream()))
    }

    @Test
    fun sourceDeleteFailureRollsBackCopiedSafDirectoryDespiteChildTimestampChanges() = runBlocking {
        provider.bumpDirectoryRevisions = true
        val backend = createBackend()
        backend.createDirectory("/source")
        backend.write("/source/a.txt", "original".toByteArray())
        val source = object : ExternalFolderBackend by backend {
            override suspend fun delete(path: String, expectedSha256: String?) {
                throw com.promenar.infinity.domain.storage.ExternalFolderException("UNAVAILABLE", "测试来源拒绝删除")
            }
        }
        expectCode("UNAVAILABLE") { ExternalTreeTransfer.move(source, "/source", backend, "/copied") }
        assertNull(backend.stat("/copied"))
        assertArrayEquals("original".toByteArray(), backend.read("/source/a.txt"))
    }

    @Test
    fun renameWithStableDocumentIdsNeverDeletesPublishedFileDuringCleanup() = runBlocking {
        provider.stableDocumentIds = true
        val backend = createBackend()
        val original = backend.write("/file.txt", "original".toByteArray(), createOnly = true)
        assertArrayEquals("original".toByteArray(), backend.read("/file.txt"))
        backend.write("/file.txt", "new".toByteArray(), original.sha256)
        assertArrayEquals("new".toByteArray(), backend.read("/file.txt"))
        assertEquals(setOf(".infinity", "file.txt"), provider.root.list()!!.toSet())
    }

    @Test
    fun newFileWithoutRenameCapabilityUsesIndependentCreationAndCleansTemporaryBytes() = runBlocking {
        provider.renameSupported = false
        val backend = createBackend()
        backend.write("/new.txt", "new".toByteArray(), createOnly = true)
        assertArrayEquals("new".toByteArray(), backend.read("/new.txt"))
        assertEquals(setOf(".infinity", "new.txt"), provider.root.list()!!.toSet())
    }

    @Test
    fun cancellationWhileWritingTemporaryDocumentPreservesOriginalAndCancellationIdentity() = runBlocking {
        val backend = createBackend()
        val original = backend.write("/file.txt", "original".toByteArray())
        val cancellation = CancellationException("测试临时写取消")
        provider.onTemporaryWrite = { throw cancellation }
        val caught = try {
            backend.write("/file.txt", "new".toByteArray(), original.sha256)
            null
        } catch (exception: CancellationException) {
            exception
        }
        assertCancellation(cancellation, caught)
        assertArrayEquals("original".toByteArray(), backend.read("/file.txt"))
        assertEquals(setOf(".infinity", "file.txt"), provider.root.list()!!.toSet())
    }

    @Test
    fun recursiveDeletePreservesHiddenRecoveryDocumentsInsideDirectory() = runBlocking {
        val backend = createBackend()
        backend.createDirectory("/notes")
        backend.write("/notes/visible.txt", "visible".toByteArray())
        File(provider.root, "notes/.infinity-backup-preserved").writeText("recovery")
        expectCode("INVALID_PATH") { backend.delete("/notes") }
        assertArrayEquals("visible".toByteArray(), backend.read("/notes/visible.txt"))
        assertEquals("recovery", File(provider.root, "notes/.infinity-backup-preserved").readText())
    }

    @Test
    fun providerCancellationDuringCleanupKeepsTheOriginalCancellation() = runBlocking {
        val backend = createBackend()
        val original = backend.write("/file.txt", "original".toByteArray())
        val cancellation = CancellationException("测试提供商持续取消")
        provider.onTemporaryWrite = { provider.queryFailure = cancellation }
        val caught = try {
            backend.write("/file.txt", "new".toByteArray(), original.sha256)
            null
        } catch (exception: CancellationException) {
            exception
        }
        provider.queryFailure = null
        assertCancellation(cancellation, caught)
        assertArrayEquals("original".toByteArray(), backend.read("/file.txt"))
    }

    @Test
    fun missingRenameCapabilityRefusesReplacementWithoutChangingOriginal() = runBlocking {
        val backend = createBackend()
        val original = backend.write("/file.txt", "original".toByteArray())
        provider.renameSupported = false
        expectCode("UNSUPPORTED") { backend.write("/file.txt", "new".toByteArray(), original.sha256) }
        assertArrayEquals("original".toByteArray(), backend.read("/file.txt"))
        assertEquals(setOf(".infinity", "file.txt"), provider.root.list()!!.toSet())
    }

    @Test
    fun changedSourceDuringTemporaryWriteIsRecheckedBeforeCommit() = runBlocking {
        val backend = createBackend()
        val original = backend.write("/file.txt", "original".toByteArray())
        provider.onTemporaryWrite = { File(provider.root, "file.txt").writeText("concurrent") }
        expectCode("CONFLICT") { backend.write("/file.txt", "new".toByteArray(), original.sha256) }
        assertArrayEquals("concurrent".toByteArray(), backend.read("/file.txt"))
        assertEquals(setOf(".infinity", "file.txt"), provider.root.list()!!.toSet())
    }

    @Test
    fun failedReplacementRestoresOriginalAndHidesTemporaryDocuments() = runBlocking {
        val backend = createBackend()
        val original = backend.write("/file.txt", "original".toByteArray())
        provider.failTemporaryRename = true
        expectCode("UNAVAILABLE") { backend.write("/file.txt", "new".toByteArray(), original.sha256) }
        assertArrayEquals("original".toByteArray(), backend.read("/file.txt"))
        assertEquals(listOf("file.txt"), backend.list("/").map { it.name })
        assertEquals(listOf(".gitignore"), File(provider.root, ".infinity").list()!!.toList())
    }

    @Test
    fun revokedAuthorizationAndProviderCancellationRemainDistinguishable() = runBlocking {
        val backend = createBackend()
        provider.revoked = true
        expectCode("UNAVAILABLE") { backend.stat("/") }
        provider.revoked = false
        val cancellation = CancellationException("测试取消")
        provider.queryFailure = cancellation
        val caught = try {
            backend.list("/")
            null
        } catch (exception: CancellationException) {
            exception
        }
        assertCancellation(cancellation, caught)
    }

    @Test
    fun readBudgetDoesNotTrustProviderSizeAndQueriesUseBatchProjection() = runBlocking {
        val backend = createBackend()
        backend.write("/file.txt", "12345".toByteArray())
        provider.reportedSize = 0L
        expectCode("TOO_LARGE") { backend.read("/file.txt", 4) }
        File(provider.root, ".infinity-write-orphan").writeText("hidden")
        File(provider.root, ".recycle_bin").mkdir()
        assertEquals(listOf("file.txt"), backend.list("/").map { it.name })
        assertEquals(setOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        ), provider.lastProjection!!.toSet())
    }

    companion object { const val AUTHORITY = "com.promenar.infinity.test.externalfolders" }

    private fun assertCancellation(original: CancellationException, caught: CancellationException?) {
        assertNotNull(caught)
        assertEquals(original.message, caught!!.message)
        var current: Throwable? = caught
        val seen = mutableSetOf<Throwable>()
        while (current != null && current !== original && seen.add(current)) current = current.cause
        assertTrue("协程恢复栈副本必须保留原始取消异常", current === original)
    }
}
