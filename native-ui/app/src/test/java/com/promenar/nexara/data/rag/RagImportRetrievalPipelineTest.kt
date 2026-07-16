package com.promenar.nexara.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.RagOptions
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.repository.MessageRepository
import com.promenar.nexara.data.repository.TestWorkspaceFileOps
import com.promenar.nexara.data.repository.WorkspaceRepository
import com.promenar.nexara.infra.util.Sha256Utils
import com.promenar.nexara.ui.chat.manager.ContextBuilder
import com.promenar.nexara.ui.chat.manager.ContextBuilderParams
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RagImportRetrievalPipelineTest {
    private lateinit var database: NexaraDatabase
    private lateinit var workspaceParent: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        workspaceParent = Files.createTempDirectory(
            java.nio.file.Path.of(System.getProperty("user.dir")),
            ".nexara-rag-pipeline",
        ).toFile()
    }

    @After
    fun tearDown() {
        database.close()
        workspaceParent.deleteRecursively()
    }

    @Test
    fun `工作区文件经真实索引检索进入提示词并随消息引用往返`() = runTest {
        val sessionId = "rag-pipeline-session"
        val fileId = "rag-pipeline-file"
        val fileName = "nexara-release-fact.txt"
        val uniqueFact = "NEXARA_RAG_PIPELINE_SENTINEL_7319"
        val query = "Nexara release sentinel"
        seedSession(sessionId)
        val workspace = WorkspaceRepository(
            database.fileEntryDao(),
            database.workspaceSeqDao(),
            workspaceParent,
            TestWorkspaceFileOps(),
        )
        val root = workspace.ensureSessionRoot(sessionId)
        val created = workspace.createFileInWorkspaceStreaming(
            workspaceRootUuid = root.uuid,
            uuid = fileId,
            name = fileName,
            mimeType = "text/plain",
            parentUuid = root.uuid,
            materializedPath = "/$fileName",
            maxBytes = 1024,
            writer = { it.write(uniqueFact.toByteArray()) },
        )
        val physical = File(created.physicalRootPath, created.materializedPath.trimStart('/'))
        assertThat(physical.readText()).isEqualTo(uniqueFact)
        assertThat(created.hash).isEqualTo(Sha256Utils.hash(uniqueFact))
        assertThat(workspace.getByUuid(root.uuid, fileId)).isEqualTo(created)

        val embedding = mockk<EmbeddingClient>()
        coEvery { embedding.embedDocuments(any()) } answers {
            EmbeddingResult(firstArg<List<String>>().map { floatArrayOf(1f, 0f) })
        }
        coEvery { embedding.embedQuery(query) } returns (floatArrayOf(1f, 0f) to null)
        every { embedding.isConfigured } returns true
        every { embedding.hasLocalFallback } returns false
        val config = RagConfiguration(
            enableMemory = false,
            enableDocs = true,
            enableHybridSearch = false,
            enableRerank = false,
            docThreshold = 0.1f,
            docChunkSize = 256,
            chunkOverlap = 16,
        )
        val indexService = RoomDocumentIndexService(
            database = database,
            candidateBuilder = WorkspaceDocumentIndexCandidateBuilder(
                database.fileEntryDao(),
                embedding,
                config,
            ),
        )
        database.vectorizationTaskDao().insert(VectorizationTaskEntity(
            id = "pipeline-index-task",
            type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            status = "processing",
            docId = fileId,
            workspaceRootUuid = root.uuid,
            sourceMimeType = "text/plain",
            targetContentHash = created.hash,
            targetEpoch = created.updatedAt,
            createdAt = created.updatedAt,
            updatedAt = created.updatedAt,
        ))

        val indexed = indexService.rebuild(FileIndexEvent.Changed(
            root.uuid,
            fileId,
            created.hash,
            created.updatedAt,
            activeTaskId = "pipeline-index-task",
        ))

        assertThat(indexed).isEqualTo(DocumentIndexResult.Rebuilt(fileId))
        assertThat(database.fileEntryDao().getByUuid(root.uuid, fileId)!!.vectorizedAt).isNotNull()
        assertThat(database.vectorDao().getByDocId(fileId)).isNotEmpty()

        val memoryManager = MemoryManager(
            vectorStore = VectorStore(
                database.vectorDao(),
                database.kgNodeDao(),
                database.kgEdgeDao(),
            ),
            keywordSearcher = KeywordSearcher(database.vectorDao()),
            graphStore = GraphStore(database.kgNodeDao(), database.kgEdgeDao()),
            embeddingClient = embedding,
            ragConfig = config,
        )
        val ragOptions = RagOptions(
            enableMemory = false,
            enableDocs = true,
            isGlobal = true,
            enableRerank = false,
            enableHybridSearch = false,
            docThreshold = 0.1f,
        )
        val context = ContextBuilder(ragProvider = MemoryManagerRagAdapter(memoryManager)).buildContext(
            ContextBuilderParams(
                sessionId = sessionId,
                content = query,
                assistantMsgId = "assistant-rag",
                session = Session(
                    id = sessionId,
                    agentId = "test-agent",
                    ragOptions = ragOptions,
                ),
                ragOptions = ragOptions,
            ),
        )

        assertThat(context.ragReferences).hasSize(1)
        val reference = context.ragReferences.single()
        assertThat(reference.documentId).isEqualTo(fileId)
        assertThat(reference.source).isEqualTo("文档: $fileName")
        assertThat(reference.content).contains(uniqueFact)
        assertThat(context.finalSystemPrompt).contains(uniqueFact)
        assertThat(context.finalSystemPrompt.windowed(uniqueFact.length).count { it == uniqueFact })
            .isEqualTo(1)

        val messages = MessageRepository(database.messageDao())
        messages.insert(
            Message(
                id = "assistant-rag",
                role = MessageRole.ASSISTANT,
                content = "fixture answer",
                ragReferences = context.ragReferences,
            ),
            sessionId,
        )

        assertThat(messages.getById("assistant-rag")!!.ragReferences)
            .containsExactly(reference)
    }

    private suspend fun seedSession(sessionId: String) {
        val now = System.currentTimeMillis()
        database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                agentId = "test-agent",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
