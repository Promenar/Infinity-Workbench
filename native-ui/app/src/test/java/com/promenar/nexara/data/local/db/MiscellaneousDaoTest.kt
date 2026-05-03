package com.promenar.nexara.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.*
import com.promenar.nexara.data.local.db.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class MiscellaneousDaoTest {
    private lateinit var db: NexaraDatabase
    private lateinit var folderDao: FolderDao
    private lateinit var documentDao: DocumentDao
    private lateinit var attachmentDao: AttachmentDao
    private lateinit var contextSummaryDao: ContextSummaryDao
    private lateinit var tagDao: TagDao
    private lateinit var documentTagDao: DocumentTagDao
    private lateinit var kgJitCacheDao: KgJitCacheDao
    private lateinit var vectorizationTaskDao: VectorizationTaskDao
    private lateinit var auditLogDao: AuditLogDao
    private lateinit var artifactDao: ArtifactDao
    private lateinit var sessionDao: SessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        folderDao = db.folderDao()
        documentDao = db.documentDao()
        attachmentDao = db.attachmentDao()
        contextSummaryDao = db.contextSummaryDao()
        tagDao = db.tagDao()
        documentTagDao = db.documentTagDao()
        kgJitCacheDao = db.kgJitCacheDao()
        vectorizationTaskDao = db.vectorizationTaskDao()
        auditLogDao = db.auditLogDao()
        artifactDao = db.artifactDao()
        sessionDao = db.sessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ─── Folder ───

    @Test
    fun folderCrud() = runBlocking {
        val folder = FolderEntity(id = "f1", name = "Knowledge", createdAt = 1000L)
        folderDao.insert(folder)
        assertThat(folderDao.getById("f1")!!.name).isEqualTo("Knowledge")
        folderDao.update(folder.copy(name = "Updated"))
        assertThat(folderDao.getById("f1")!!.name).isEqualTo("Updated")
    }

    @Test
    fun folderHierarchy() = runBlocking {
        folderDao.insert(FolderEntity(id = "f1", name = "Parent", createdAt = 1000L))
        folderDao.insert(FolderEntity(id = "f2", name = "Child", parentId = "f1", createdAt = 2000L))
        val children = folderDao.getByParentId("f1")
        assertThat(children).hasSize(1)
        assertThat(children[0].name).isEqualTo("Child")
    }

    // ─── Document ───

    @Test
    fun documentCrud() = runBlocking {
        folderDao.insert(FolderEntity(id = "f1", name = "F1", createdAt = 1000L))
        val doc = DocumentEntity(
            id = "d1", title = "Test Doc", content = "content",
            source = "import", type = "text", folderId = "f1",
            isGlobal = 1, contentHash = "abc123",
            createdAt = 1000L, updatedAt = 2000L,
        )
        documentDao.insert(doc)
        val result = documentDao.getById("d1")
        assertThat(result).isNotNull()
        assertThat(result!!.title).isEqualTo("Test Doc")
        assertThat(result.isGlobal).isEqualTo(1)
    }

    @Test
    fun documentUpdateVectorizationStatus() = runBlocking {
        documentDao.insert(DocumentEntity(id = "d1", title = "Doc", createdAt = 1000L))
        documentDao.updateVectorizationStatus("d1", 2, 10)
        val result = documentDao.getById("d1")
        assertThat(result!!.vectorized).isEqualTo(2)
        assertThat(result.vectorCount).isEqualTo(10)
    }

    @Test
    fun documentGlobalScope() = runBlocking {
        documentDao.insert(DocumentEntity(id = "d1", title = "Global", isGlobal = 1, createdAt = 1000L))
        documentDao.insert(DocumentEntity(id = "d2", title = "Private", isGlobal = 0, createdAt = 1000L))
        val globals = documentDao.getGlobalDocuments()
        assertThat(globals).hasSize(1)
        assertThat(globals[0].id).isEqualTo("d1")
    }

    // ─── Attachment ───

    @Test
    fun attachmentCrud() = runBlocking {
        sessionDao.insert(SessionEntity(id = "s1", agentId = "a1", createdAt = 1000L, updatedAt = 1000L))
        val msgDao = db.messageDao()
        msgDao.insert(MessageEntity(id = "m1", sessionId = "s1", role = "user", content = "hi", createdAt = 1000L))
        val att = AttachmentEntity(id = "a1", messageId = "m1", type = "image", uri = "file:///img.png", localUri = "content://local")
        attachmentDao.insert(att)
        val results = attachmentDao.getByMessageId("m1")
        assertThat(results).hasSize(1)
        assertThat(results[0].type).isEqualTo("image")
    }

    // ─── ContextSummary ───

    @Test
    fun contextSummaryCrud() = runBlocking {
        sessionDao.insert(SessionEntity(id = "s1", agentId = "a1", createdAt = 1000L, updatedAt = 1000L))
        val summary = ContextSummaryEntity(
            id = "cs1", sessionId = "s1",
            startMessageId = "m1", endMessageId = "m5",
            summaryContent = "Summary of messages 1-5",
            createdAt = 1000L, tokenUsage = 500L,
        )
        contextSummaryDao.insert(summary)
        val results = contextSummaryDao.getBySessionId("s1")
        assertThat(results).hasSize(1)
        assertThat(results[0].summaryContent).isEqualTo("Summary of messages 1-5")
    }

    // ─── Tag + DocumentTag ───

    @Test
    fun tagSystem() = runBlocking {
        val tag = TagEntity(id = "t1", name = "Important", color = "#ff0000", createdAt = 1000L)
        tagDao.insert(tag)
        assertThat(tagDao.getById("t1")!!.color).isEqualTo("#ff0000")
        documentDao.insert(DocumentEntity(id = "d1", title = "Doc", createdAt = 1000L))
        val docTag = DocumentTagEntity(docId = "d1", tagId = "t1", createdAt = 1000L)
        documentTagDao.insert(docTag)
        val tagged = documentTagDao.getByDocId("d1")
        assertThat(tagged).hasSize(1)
        assertThat(tagged[0].tagId).isEqualTo("t1")
    }

    // ─── KgJitCache ───

    @Test
    fun jitCacheCrud() = runBlocking {
        val cache = KgJitCacheEntity(
            cacheKey = "key1", queryHash = "qh1", chunkIdsHash = "ch1",
            resultJson = """{"nodes":[]}""",
            createdAt = 1000L, expiresAt = 5000L,
        )
        kgJitCacheDao.insert(cache)
        val result = kgJitCacheDao.getByKey("key1")
        assertThat(result).isNotNull()
        assertThat(result!!.queryHash).isEqualTo("qh1")
    }

    @Test
    fun jitCacheDeleteExpired() = runBlocking {
        kgJitCacheDao.insert(KgJitCacheEntity(cacheKey = "k1", queryHash = "q1", chunkIdsHash = "c1", resultJson = "{}", createdAt = 1000L, expiresAt = 3000L))
        kgJitCacheDao.insert(KgJitCacheEntity(cacheKey = "k2", queryHash = "q2", chunkIdsHash = "c2", resultJson = "{}", createdAt = 1000L, expiresAt = 7000L))
        kgJitCacheDao.deleteExpired(5000L)
        assertThat(kgJitCacheDao.getByKey("k1")).isNull()
        assertThat(kgJitCacheDao.getByKey("k2")).isNotNull()
    }

    // ─── VectorizationTask ───

    @Test
    fun vectorizationTaskCrud() = runBlocking {
        sessionDao.insert(SessionEntity(id = "s1", agentId = "a1", createdAt = 1000L, updatedAt = 1000L))
        documentDao.insert(DocumentEntity(id = "d1", title = "My Doc", createdAt = 1000L))
        val task = VectorizationTaskEntity(
            id = "vt1", type = "document", status = "pending",
            docId = "d1", docTitle = "My Doc",
            lastChunkIndex = 5, totalChunks = 10, progress = 0.5,
            createdAt = 1000L, updatedAt = 2000L,
        )
        vectorizationTaskDao.insert(task)
        val result = vectorizationTaskDao.getById("vt1")
        assertThat(result).isNotNull()
        assertThat(result!!.progress).isWithin(0.001).of(0.5)
    }

    @Test
    fun vectorizationTaskGetByStatus() = runBlocking {
        vectorizationTaskDao.insert(VectorizationTaskEntity(id = "vt1", type = "document", status = "pending", createdAt = 1000L, updatedAt = 1000L))
        vectorizationTaskDao.insert(VectorizationTaskEntity(id = "vt2", type = "document", status = "completed", createdAt = 1000L, updatedAt = 1000L))
        vectorizationTaskDao.insert(VectorizationTaskEntity(id = "vt3", type = "memory", status = "pending", createdAt = 1000L, updatedAt = 1000L))
        val pending = vectorizationTaskDao.getByStatus("pending")
        assertThat(pending).hasSize(2)
    }

    @Test
    fun vectorizationTaskUpdateStatus() = runBlocking {
        vectorizationTaskDao.insert(VectorizationTaskEntity(id = "vt1", type = "document", status = "pending", createdAt = 1000L, updatedAt = 1000L))
        vectorizationTaskDao.updateStatus("vt1", "processing", 3000L)
        val result = vectorizationTaskDao.getById("vt1")
        assertThat(result!!.status).isEqualTo("processing")
    }

    // ─── AuditLog ───

    @Test
    fun auditLogCrud() = runBlocking {
        val log = AuditLogEntity(
            id = "al1", action = "file_read", resourceType = "document",
            resourcePath = "/docs/test.pdf", sessionId = "s1",
            status = "success", metadata = """{"size":1024}""",
            createdAt = 1000L,
        )
        auditLogDao.insert(log)
        val results = auditLogDao.getByAction("file_read")
        assertThat(results).hasSize(1)
        assertThat(results[0].resourceType).isEqualTo("document")
    }

    @Test
    fun auditLogGetByTimeRange() = runBlocking {
        auditLogDao.insert(AuditLogEntity(id = "al1", action = "a1", resourceType = "doc", status = "ok", createdAt = 1000L))
        auditLogDao.insert(AuditLogEntity(id = "al2", action = "a2", resourceType = "doc", status = "ok", createdAt = 3000L))
        auditLogDao.insert(AuditLogEntity(id = "al3", action = "a3", resourceType = "doc", status = "ok", createdAt = 5000L))
        val results = auditLogDao.getByTimeRange(2000L, 4000L)
        assertThat(results).hasSize(1)
        assertThat(results[0].id).isEqualTo("al2")
    }

    // ─── Artifact ───

    @Test
    fun artifactCrud() = runBlocking {
        sessionDao.insert(SessionEntity(id = "s1", agentId = "a1", createdAt = 1000L, updatedAt = 1000L))
        val artifact = ArtifactEntity(
            id = "ar1", type = "code", title = "main.py",
            content = "print('hello')", sessionId = "s1",
            messageId = "m1", workspacePath = "/workspace",
            createdAt = 1000L, updatedAt = 2000L,
            tags = """["python","script"]""",
        )
        artifactDao.insert(artifact)
        val result = artifactDao.getById("ar1")
        assertThat(result).isNotNull()
        assertThat(result!!.title).isEqualTo("main.py")
        assertThat(result.workspacePath).isEqualTo("/workspace")
    }

    @Test
    fun artifactGetBySession() = runBlocking {
        sessionDao.insert(SessionEntity(id = "s1", agentId = "a1", createdAt = 1000L, updatedAt = 1000L))
        sessionDao.insert(SessionEntity(id = "s2", agentId = "a1", createdAt = 1000L, updatedAt = 1000L))
        artifactDao.insert(ArtifactEntity(id = "ar1", type = "code", title = "A1", content = "", sessionId = "s1", messageId = "m1", createdAt = 1000L, updatedAt = 1000L))
        artifactDao.insert(ArtifactEntity(id = "ar2", type = "html", title = "A2", content = "", sessionId = "s1", messageId = "m1", createdAt = 2000L, updatedAt = 2000L))
        artifactDao.insert(ArtifactEntity(id = "ar3", type = "code", title = "A3", content = "", sessionId = "s2", messageId = "m2", createdAt = 3000L, updatedAt = 3000L))
        val s1Artifacts = artifactDao.getBySession("s1")
        assertThat(s1Artifacts).hasSize(2)
    }

    // ─── Converters ───

    @Test
    fun convertersFloatArrayRoundTrip() {
        val original = floatArrayOf(1.0f, 2.5f, -3.7f, 0.0f, 100.0f)
        val bytes = Converters.floatArrayToBytes(original)
        assertThat(bytes).isNotNull()
        val restored = Converters.bytesToFloatArray(bytes)
        assertThat(restored).isNotNull()
        assertThat(restored!!.size).isEqualTo(5)
        for (i in original.indices) {
            assertThat(restored[i]).isWithin(0.001f).of(original[i])
        }
    }

    @Test
    fun convertersNullHandling() {
        assertThat(Converters.floatArrayToBytes(null)).isNull()
        assertThat(Converters.bytesToFloatArray(null)).isNull()
    }
}
