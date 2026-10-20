package com.promenar.nexara.data.media

import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.*
import com.promenar.nexara.data.rag.FileIndexEvent
import com.promenar.nexara.data.rag.FileIndexEventSink
import com.promenar.nexara.data.repository.*
import com.promenar.nexara.data.storage.*
import com.promenar.nexara.data.workspace.*
import com.promenar.nexara.domain.media.*
import com.promenar.nexara.domain.repository.IWorkspaceRepository
import com.promenar.nexara.domain.storage.*
import kotlinx.coroutines.*
import io.mockk.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class DefaultWorkspaceMediaTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var directory: File
    private lateinit var privateRoot: File
    private lateinit var skillsRoot: File
    private lateinit var db: NexaraDatabase
    private lateinit var repo: WorkspaceRepository
    private lateinit var roots: SessionWorkspaceResolver
    private lateinit var mounts: ExternalWorkspaceAccess
    private lateinit var service: DefaultWorkspaceMedia
    private lateinit var root: FileEntry
    private lateinit var provider: ExternalFolderTestDocumentsProvider
    private val events = mutableListOf<FileIndexEvent>()
    private val clock = Clock.fixed(Instant.parse("2026-10-19T16:32:00Z"), ZoneId.of("Asia/Shanghai"))
    private val fs = JvmBoundRealPathFileSystem()
    private val png by lazy {
        ByteArrayOutputStream().use { out ->
            val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    @Before fun setup() = runBlocking {
        directory = Files.createTempDirectory("workspace-media").toFile().canonicalFile
        privateRoot = File(directory, "private")
        skillsRoot = File(context.filesDir, "agent_skills").canonicalFile.apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java).allowMainThreadQueries().build()
        db.sessionDao().insert(SessionEntity(id = "session", agentId = "agent", workspacePath = privateRoot.path, createdAt = 1, updatedAt = 1))
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(), deleteCommitter = WorkspaceDeletionTransaction(db)::delete)
        root = repo.ensureSessionRoot("session")
        roots = SessionWorkspaceResolver(db.sessionDao(), db.workspaceDao(), File(directory, "projects"), object : SharedWorkspaceAccess {
            override fun root(location: String) = File(location)
            override fun backend(root: File) = real(root)
        }, ::real)
        mounts = ExternalFolderManager(context, db.externalMountDao(), repo, FileIndexEventSink.None, roots, skillsRoot) { mount ->
            when {
                mount.isUserSkills -> real(skillsRoot)
                mount.sourceType == "real_path" -> real(File(mount.sourceId))
                mount.isWorkspaceRoot -> real(File(directory, "projects/project"))
                mount.sourceType == "saf_tree" -> SafExternalFolderBackend(context.contentResolver, Uri.parse(mount.sourceId))
                else -> null
            }
        }
        val info = ProviderInfo().apply {
            authority = AUTHORITY; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        }
        provider = Robolectric.buildContentProvider(ExternalFolderTestDocumentsProvider::class.java).create(info).get()
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        service = newService()
    }

    private fun real(root: File) = RealPathExternalFolderBackend(root, emptySet(), { true }, fs)
    private fun newService(workspace: IWorkspaceRepository = repo, index: FileIndexEventSink = FileIndexEventSink { events.add(it) }, skillsDirectory: File = skillsRoot) =
        DefaultWorkspaceMedia(context, SessionRepository(db.sessionDao(), db.messageDao()), workspace, mounts, roots::resolve, index, clock, fs, skillsDirectory)

    @After fun cleanup() {
        db.close()
        Files.walk(directory.toPath()).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        if (::provider.isInitialized) provider.root.deleteRecursively()
        File(skillsRoot, "media-test.png").delete()
    }

    private suspend fun privateFile(path: String, bytes: ByteArray = png): FileEntry {
        val entry = repo.createFileInWorkspaceStreaming(root.uuid, "f-${System.nanoTime()}", path.substringAfterLast('/'), "application/octet-stream", root.uuid, path, 1024 * 1024) { it.write(bytes) }
        repo.confirmCreatedEntry(root.uuid, entry.uuid)
        return entry
    }
    private suspend fun addMount(alias: String, source: String, type: String = "real_path", readOnly: Boolean = false) {
        db.externalMountDao().insert(ExternalMountEntity("id-$alias", alias, type, source, "session", readOnly, 1))
    }
    private suspend fun bindDirectory(): File {
        val project = File(directory, "projects/project").apply { mkdirs() }
        db.workspaceDao().insert(WorkspaceEntity("project-id", WorkspaceEntity.KIND_PROJECT, "项目", "project", 1))
        db.sessionDao().update(db.sessionDao().getById("session")!!.copy(workspaceId = "project-id"))
        return project
    }
    private suspend fun available(path: String) = service.resolve("session", path).let {
        assertThat(it).isInstanceOf(MediaResolution.Available::class.java)
        it as MediaResolution.Available
    }
    private suspend fun reason(path: String, expected: MediaUnavailableReason) {
        assertThat((service.resolve("session", path) as MediaResolution.Unavailable).reason).isEqualTo(expected)
    }
    private suspend fun code(expected: String, block: suspend () -> Unit) {
        val failure = try { block(); null } catch (failure: WorkspaceMediaException) { failure }
        assertThat(failure?.code).isEqualTo(expected)
        assertThat(failure?.message).isNotEmpty()
    }

    @Test fun `私有路径解析真实来源和索引哈希且读取图片尺寸`() = runBlocking {
        val indexed = privateFile("/a.png")
        val result = available("a.png")
        assertThat(result.ref.path).isEqualTo("/a.png")
        assertThat(result.ref.mimeType).isEqualTo("image/png")
        assertThat(result.ref.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(result.ref.sizeBytes).isEqualTo(png.size.toLong())
        assertThat(result.ref.sha256).isEqualTo(indexed.hash)
        assertThat(result.ref.width).isEqualTo(3)
        assertThat(result.ref.height).isEqualTo(2)
        assertThat((result.source as MediaSource.LocalFile).file).isEqualTo(File(privateRoot, "a.png"))
        assertThat(service.open(result).use { it.readBytes() }).isEqualTo(png)
    }

    @Test fun `根模式和真实挂载与用户技能均按同一命名空间解析`() = runBlocking {
        val project = bindDirectory()
        File(project, "a.png").writeBytes(png)
        val album = File(directory, "album").apply { mkdirs() }
        File(album, "a.png").writeBytes(png)
        addMount("相册", album.path)
        File(skillsRoot, "media-test.png").writeBytes(png)
        for ((path, file) in mapOf("a.png" to File(project, "a.png"), "/mnt/相册/a.png" to File(album, "a.png"), "/skills/media-test.png" to File(skillsRoot, "media-test.png"))) {
            assertThat((available(path).source as MediaSource.LocalFile).file).isEqualTo(file)
        }
        privateFile("/private-only.png")
        reason("/private-only.png", MediaUnavailableReason.NOT_FOUND)
    }

    @Test fun `SAF按文档标识解析ContentUri并打开无上限流`() = runBlocking {
        val file = File(provider.root, "大文件.bin")
        RandomAccessFile(file, "rw").use { it.setLength(9L * 1024 * 1024) }
        addMount("文档", DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root").toString(), "saf_tree")
        val result = available("/mnt/文档/大文件.bin")
        assertThat(result.source).isInstanceOf(MediaSource.ContentUri::class.java)
        assertThat(result.ref.sha256).isNull()
        assertThat(service.open(result).use { input -> input.copyTo(java.io.OutputStream.nullOutputStream()) }).isEqualTo(9L * 1024 * 1024)
        File(provider.root, "picture.png").writeBytes(png)
        val image = available("/mnt/文档/picture.png")
        assertThat(image.ref.width).isEqualTo(3)
        assertThat(image.ref.height).isEqualTo(2)
        provider.revoked = true
        reason("/mnt/文档/picture.png", MediaUnavailableReason.UNREADABLE)
    }

    @Test fun `非法路径保留目录和URI不能作为虚拟路径`() = runBlocking {
        for (path in listOf("file:/a.png", "content://provider/a", "https://host/a", "custom:a", " /../a", "../a", "/.nexara-root/a", "/.RECYCLE_BIN/a", "a\\b", "a\u0000b", "/mnt/skills/a")) {
            reason(path, MediaUnavailableReason.INVALID_PATH)
        }
    }

    @Test fun `不存在目录失效挂载及工作区不可用原因各自正确`() = runBlocking {
        reason("missing.png", MediaUnavailableReason.NOT_FOUND)
        reason("/", MediaUnavailableReason.IS_DIRECTORY)
        reason("/mnt", MediaUnavailableReason.IS_DIRECTORY)
        reason("/mnt/missing/a.png", MediaUnavailableReason.UNREADABLE)
        val project = bindDirectory()
        project.deleteRecursively()
        reason("a.png", MediaUnavailableReason.UNREADABLE)
        reason("/skills/a.png", MediaUnavailableReason.UNREADABLE)
        code("WORKSPACE_UNAVAILABLE") { service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED) }
    }

    @Test fun `解析和重新打开均拒绝文件及父目录符号链接`() = runBlocking {
        privateFile("/a.png")
        val resolution = available("/a.png")
        File(privateRoot, "a.png").delete()
        val outside = File(directory, "outside.png").apply { writeBytes(png) }
        Files.createSymbolicLink(File(privateRoot, "a.png").toPath(), outside.toPath())
        reason("/a.png", MediaUnavailableReason.INVALID_PATH)
        assertThat(runCatching { service.open(resolution) }.isFailure).isTrue()
        val folder = repo.createDirectoryInWorkspace(root.uuid, "dir", "dir", root.uuid, "/dir")
        repo.createFileInWorkspaceStreaming(root.uuid, "nested", "a.png", "image/png", folder.uuid, "/dir/a.png", 1024) { it.write(png) }
        File(privateRoot, "dir").deleteRecursively()
        Files.createSymbolicLink(File(privateRoot, "dir").toPath(), directory.toPath())
        reason("/dir/a.png", MediaUnavailableReason.INVALID_PATH)
    }

    @Test fun `大文件本地读取不受八MiB限制且元数据失败不影响可用性`() = runBlocking {
        privateFile("/large.mp4", "bad".toByteArray())
        RandomAccessFile(File(privateRoot, "large.mp4"), "rw").use { it.setLength(9L * 1024 * 1024) }
        val result = available("large.mp4")
        assertThat(result.ref.kind).isEqualTo(MediaKind.VIDEO)
        assertThat(result.ref.durationMs).isNull()
        assertThat(service.open(result).use { it.copyTo(java.io.OutputStream.nullOutputStream()) }).isEqualTo(9L * 1024 * 1024)
    }

    @Test fun `两个会话的等值媒体引用保持独立授权绑定`() = runBlocking {
        val album = File(directory, "album").apply { mkdirs() }
        File(album, "a.png").writeBytes(png)
        addMount("共享", album.path)
        db.sessionDao().insert(SessionEntity(id = "second", agentId = "agent", createdAt = 1, updatedAt = 1))
        db.externalMountDao().insert(ExternalMountEntity("id-second", "共享", "real_path", album.path, "second", false, 1))
        val first = available("/mnt/共享/a.png")
        val second = service.resolve("second", "/mnt/共享/a.png") as MediaResolution.Available
        assertThat(first).isEqualTo(second)
        db.externalMountDao().delete("id-共享")
        assertThat(runCatching { service.open(first) }.isFailure).isTrue()
        assertThat(service.open(second).use { it.readBytes() }).isEqualTo(png)
    }

    @Test fun `可信应用目录的父路径别名不导致用户技能不可读`() = runBlocking {
        File(skillsRoot, "media-test.png").writeBytes(png)
        val alias = File(directory, "files-alias")
        Files.createSymbolicLink(alias.toPath(), skillsRoot.parentFile!!.toPath())
        service = newService(skillsDirectory = File(alias, "agent_skills"))
        assertThat(available("/skills/media-test.png").ref.kind).isEqualTo(MediaKind.IMAGE)
    }

    @Test fun `默认目录按本地日期创建且同名后缀从二开始`() = runBlocking {
        val one = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED)
        val two = service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED)
        assertThat(one.path).matches("/media/2026-10-20/gen-0032-[0-9a-f]{6}\\.png")
        assertThat(two.path).isEqualTo(one.path.removeSuffix(".png") + "-2.png")
        assertThat(one.mimeType).isEqualTo("image/png")
        assertThat(one.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(one.width).isEqualTo(3)
        assertThat(one.height).isEqualTo(2)
        assertThat(one.sha256).hasLength(64)
        assertThat(one.sizeBytes).isEqualTo(png.size.toLong())
        val entry = repo.getByMaterializedPath(root.uuid, one.path)!!
        assertThat(entry.hash).isEqualTo(one.sha256)
        assertThat(events.filterIsInstance<FileIndexEvent.Changed>().map { it.fileUuid }).contains(entry.uuid)
        assertThat(File(privateRoot, one.path.trimStart('/')).readBytes()).isEqualTo(png)
    }

    @Test fun `显式路径规范化并创建父目录且已有目标不覆盖`() = runBlocking {
        val one = service.save("session", MediaPayload.Bytes(png), MediaOrigin.MCP, targetPath = "folder//picture.jpg")
        assertThat(one.path).isEqualTo("/folder/picture.jpg")
        assertThat(one.mimeType).isEqualTo("image/png")
        code("EXISTS") { service.save("session", MediaPayload.Bytes(byteArrayOf(1)), MediaOrigin.MCP, targetPath = one.path) }
        assertThat(File(privateRoot, "folder/picture.jpg").readBytes()).isEqualTo(png)
    }

    @Test fun `头部优先于提示且未知类型使用提示或建议名`() = runBlocking {
        val one = service.save("session", MediaPayload.Bytes(png), MediaOrigin.FRAME, "audio/mpeg", "wrong.mp3")
        assertThat(one.path).endsWith(".png")
        assertThat(one.kind).isEqualTo(MediaKind.IMAGE)
        val hinted = service.save("session", MediaPayload.Bytes(byteArrayOf(1)), MediaOrigin.DOWNLOAD, "audio/mpeg", "wrong.png")
        assertThat(hinted.path).endsWith(".mp3")
        val named = service.save("session", MediaPayload.Bytes(byteArrayOf(2)), MediaOrigin.MCP, suggestedName = "voice.M4A")
        assertThat(named.mimeType).isEqualTo("audio/mp4")
        assertThat(named.path).endsWith(".m4a")
        val unknown = service.save("session", MediaPayload.Bytes(byteArrayOf(3)), MediaOrigin.DOWNLOAD, suggestedName = "file.foo")
        assertThat(unknown.path).endsWith(".foo")
        assertThat(unknown.kind).isEqualTo(MediaKind.FILE)
    }

    @Test fun `只读挂载和两级技能目录拒绝且不打开载荷`() = runBlocking {
        val album = File(directory, "readonly").apply { mkdirs() }
        addMount("只读", album.path, readOnly = true)
        var opened = false
        val payload = MediaPayload.Stream { opened = true; png.inputStream() }
        for (path in listOf("/skills/a.png", "/skills", "/.agents/skills/a.png", "/.agents/skills", "/mnt/只读/.agents/skills/a.png")) {
            code("PROTECTED_PATH") { service.save("session", payload, MediaOrigin.MCP, targetPath = path) }
        }
        code("READ_ONLY") { service.save("session", payload, MediaOrigin.MCP, targetPath = "/mnt/只读/a.png") }
        code("INVALID_PATH") { service.save("session", payload, MediaOrigin.MCP, targetPath = "https://host/a") }
        assertThat(opened).isFalse()
        assertThat(album.list()).isEmpty()
    }

    @Test fun `流载荷在成功和读取失败时关闭且清理缓存`() = runBlocking {
        val before = context.cacheDir.list()!!.toSet()
        var closed = false
        service.save("session", MediaPayload.Stream { object : java.io.ByteArrayInputStream(png) { override fun close() { closed = true; super.close() } } }, MediaOrigin.MCP)
        assertThat(closed).isTrue()
        assertThat(context.cacheDir.list()!!.toSet()).isEqualTo(before)
        closed = false
        code("IO") { service.save("session", MediaPayload.Stream { object : InputStream() {
            override fun read(): Int = throw java.io.IOException("测试读取失败")
            override fun close() { closed = true }
        } }, MediaOrigin.MCP) }
        assertThat(closed).isTrue()
        assertThat(context.cacheDir.list()!!.toSet()).isEqualTo(before)
    }

    @Test fun `超限流立即停止不创建目标并清理缓存`() = runBlocking {
        val before = context.cacheDir.list()!!.toSet()
        var read = 0L
        var closed = false
        code("TOO_LARGE") { service.save("session", MediaPayload.Stream { object : InputStream() {
            override fun read() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int { read += length; java.util.Arrays.fill(bytes, offset, offset + length, 0); return length }
            override fun close() { closed = true }
        } }, MediaOrigin.MCP, targetPath = "/not-created/a.bin") }
        assertThat(read).isAtMost(256L * 1024 * 1024 + 64 * 1024)
        assertThat(closed).isTrue()
        assertThat(repo.getByMaterializedPath(root.uuid, "/not-created")).isNull()
        assertThat(File(privateRoot, "not-created").exists()).isFalse()
        assertThat(context.cacheDir.list()!!.toSet()).isEqualTo(before)
    }

    @Test fun `同会话并发落地无覆盖且序号唯一`() = runBlocking {
        val refs = (1..5).map { async(Dispatchers.Default) { service.save("session", MediaPayload.Bytes(png), MediaOrigin.GENERATED) } }.awaitAll()
        assertThat(refs.map { it.path }.toSet()).hasSize(5)
        assertThat(refs.map { File(privateRoot, it.path.trimStart('/')).readBytes().toList() }.distinct()).hasSize(1)
    }

    @Test fun `根模式与SAF目标经现有外部写入可读取`() = runBlocking {
        val project = bindDirectory()
        val rootRef = service.save("session", MediaPayload.Bytes(png), MediaOrigin.DOWNLOAD, targetPath = "nested/root.png")
        assertThat(File(project, "nested/root.png").readBytes()).isEqualTo(png)
        assertThat(repo.getByMaterializedPath(root.uuid, rootRef.path)).isNull()
        addMount("文档", DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root").toString(), "saf_tree")
        val saf = service.save("session", MediaPayload.Stream { png.inputStream() }, MediaOrigin.MCP, targetPath = "/mnt/文档/nested/a.png")
        assertThat(File(provider.root, "nested/a.png").readBytes()).isEqualTo(png)
        assertThat(saf.width).isEqualTo(3)
        assertThat(available(saf.path).source).isInstanceOf(MediaSource.ContentUri::class.java)
    }

    @Test fun `SAF音视频元数据使用文档描述符读取时长及旋转尺寸`() = runBlocking {
        mockkConstructor(MediaMetadataRetriever::class)
        try {
            every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<java.io.FileDescriptor>()) } just Runs
            every { anyConstructed<MediaMetadataRetriever>().setDataSource(any<java.io.FileDescriptor>(), any<Long>(), any<Long>()) } just Runs
            every { anyConstructed<MediaMetadataRetriever>().extractMetadata(any()) } returns null
            every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) } returns "4200"
            every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) } returns "640"
            every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) } returns "360"
            every { anyConstructed<MediaMetadataRetriever>().extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) } returns "90"
            every { anyConstructed<MediaMetadataRetriever>().release() } just Runs
            every { anyConstructed<MediaMetadataRetriever>().close() } just Runs
            addMount("文档", DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root").toString(), "saf_tree")
            File(provider.root, "movie.mp4").writeText("invalid")
            File(provider.root, "voice.m4a").writeText("invalid")
            val movie = available("/mnt/文档/movie.mp4").ref
            assertThat(movie.durationMs).isEqualTo(4200)
            assertThat(movie.width).isEqualTo(360)
            assertThat(movie.height).isEqualTo(640)
            val voice = available("/mnt/文档/voice.m4a").ref
            assertThat(voice.durationMs).isEqualTo(4200)
            assertThat(voice.width).isNull()
        } finally { unmockkConstructor(MediaMetadataRetriever::class) }
    }

    @Test fun `索引发布失败回滚私有文件且不残留数据库条目`() = runBlocking {
        service = newService(index = FileIndexEventSink { throw java.io.IOException("测试索引失败") })
        code("IO") { service.save("session", MediaPayload.Bytes(png), MediaOrigin.MCP, targetPath = "/a.png") }
        assertThat(repo.getByMaterializedPath(root.uuid, "/a.png")).isNull()
        assertThat(File(privateRoot, "a.png").exists()).isFalse()
    }

    @Test fun `取消必须传播且流和缓存都会清理`() = runBlocking {
        val before = context.cacheDir.list()!!.toSet()
        var closed = false
        val failure = runCatching { service.save("session", MediaPayload.Stream { object : InputStream() {
            override fun read(): Int = throw CancellationException("测试取消")
            override fun close() { closed = true }
        } }, MediaOrigin.MCP) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(closed).isTrue()
        assertThat(context.cacheDir.list()!!.toSet()).isEqualTo(before)
    }

    companion object { private const val AUTHORITY = "com.promenar.nexara.test.media" }
}
