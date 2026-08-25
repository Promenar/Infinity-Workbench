package com.promenar.nexara.ui.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.repository.TestWorkspaceFileOps
import com.promenar.nexara.data.repository.WorkspaceRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RagWorkspaceProvisionerTest {
    private lateinit var database: NexaraDatabase
    private lateinit var filesDir: File
    private lateinit var trustedParent: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesDir = Files.createTempDirectory(
            java.nio.file.Path.of(System.getProperty("user.dir")),
            ".nexara-rag-provisioner",
        ).toFile()
        trustedParent = File(filesDir, "session_workspaces")
    }

    @After
    fun tearDown() {
        database.close()
        filesDir.deleteRecursively()
    }

    @Test
    fun `新装根由统一仓库创建在受信父目录`() = runBlocking<Unit> {
        val root = provisioner().ensureRoot()

        assertThat(File(root.physicalRootPath).parentFile).isEqualTo(trustedParent.canonicalFile)
        assertThat(database.sessionDao().getById(RagWorkspaceProvisioner.SESSION_ID)!!.workspacePath)
            .isEqualTo(root.physicalRootPath)
    }

    @Test
    fun `已有旧目录原位认领并保留用户文件`() = runBlocking<Unit> {
        val legacyRoot = File(filesDir, "rag_workspace").apply { mkdirs() }
        val legacyFile = File(legacyRoot, "legacy-note.txt").apply { writeText("keep me") }

        val root = provisioner().ensureRoot()

        assertThat(root.physicalRootPath).isEqualTo(legacyRoot.canonicalPath)
        assertThat(legacyFile.readText()).isEqualTo("keep me")
    }

    @Test
    fun `公开v17迁移出的知识库根会绑定系统session而不会创建第二个空根`() = runBlocking<Unit> {
        val legacyRoot = File(filesDir, "rag_workspace").apply { mkdirs() }
        val legacyFile = File(legacyRoot, "legacy-note.txt").apply { writeText("keep me") }
        database.fileEntryDao().insert(
            FileEntry(
                uuid = "migrated-rag-root",
                workspaceRootUuid = "migrated-rag-root",
                parentUuid = null,
                name = "rag_workspace",
                hash = "",
                isDirectory = true,
                physicalRootPath = legacyRoot.canonicalPath,
                materializedPath = "/",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.fileEntryDao().insert(
            FileEntry(
                uuid = "migrated-rag-file",
                workspaceRootUuid = "migrated-rag-root",
                parentUuid = "migrated-rag-root",
                name = "legacy-note.txt",
                hash = "legacy-hash",
                isDirectory = false,
                physicalRootPath = legacyRoot.canonicalPath,
                materializedPath = "/legacy-note.txt",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val root = provisioner().ensureRoot()

        assertThat(root.uuid).isEqualTo("migrated-rag-root")
        assertThat(database.sessionDao().getById(RagWorkspaceProvisioner.SESSION_ID)!!.workspaceRootUuid)
            .isEqualTo("migrated-rag-root")
        assertThat(legacyFile.readText()).isEqualTo("keep me")
        assertThat(database.fileEntryDao().getAllStatesByWorkspaceRootForCleanup("migrated-rag-root"))
            .hasSize(2)
        assertThat(database.fileEntryDao().getByUuid("migrated-rag-root", "migrated-rag-file")!!.parentUuid)
            .isEqualTo("migrated-rag-root")
    }

    @Test
    fun `公开v17迁移出的知识库别名根会原位绑定且不会创建第二个根`() = runBlocking<Unit> {
        val legacyRoot = File(filesDir, "rag_workspace").apply { mkdirs() }
        val aliasPath = File(legacyRoot, ".").path
        val legacyFile = File(legacyRoot, "legacy-note.txt").apply { writeText("keep alias") }
        database.fileEntryDao().insert(
            FileEntry(
                uuid = "migrated-rag-alias-root",
                workspaceRootUuid = "migrated-rag-alias-root",
                parentUuid = null,
                name = "rag_workspace",
                hash = "",
                isDirectory = true,
                physicalRootPath = aliasPath,
                materializedPath = "/",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.fileEntryDao().insert(
            FileEntry(
                uuid = "migrated-rag-alias-file",
                workspaceRootUuid = "migrated-rag-alias-root",
                parentUuid = "migrated-rag-alias-root",
                name = "legacy-note.txt",
                hash = "legacy-hash",
                isDirectory = false,
                physicalRootPath = aliasPath,
                materializedPath = "/legacy-note.txt",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val root = provisioner().ensureRoot()

        assertThat(root.uuid).isEqualTo("migrated-rag-alias-root")
        assertThat(root.physicalRootPath).isEqualTo(legacyRoot.canonicalPath)
        assertThat(database.sessionDao().getById(RagWorkspaceProvisioner.SESSION_ID)!!.workspaceRootUuid)
            .isEqualTo("migrated-rag-alias-root")
        assertThat(legacyFile.readText()).isEqualTo("keep alias")
        assertThat(database.fileEntryDao().getAllWorkspaceRootsForMaintenance().map { it.uuid })
            .containsExactly("migrated-rag-alias-root")
        assertThat(database.fileEntryDao().getByUuid("migrated-rag-alias-root", "migrated-rag-alias-file")!!.physicalRootPath)
            .isEqualTo(legacyRoot.canonicalPath)
    }

    @Test
    fun `旧知识库物理路径存在多个迁移根时拒绝猜测认领`() = runBlocking<Unit> {
        val legacyRoot = File(filesDir, "rag_workspace").apply { mkdirs() }
        listOf("candidate-a", "candidate-b").forEach { uuid ->
            database.fileEntryDao().insert(
                FileEntry(
                    uuid = uuid,
                    workspaceRootUuid = uuid,
                    parentUuid = null,
                    name = "rag_workspace",
                    hash = "",
                    isDirectory = true,
                    physicalRootPath = legacyRoot.canonicalPath,
                    materializedPath = "/",
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            )
        }

        val failure = runCatching { provisioner().ensureRoot() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(database.sessionDao().getById(RagWorkspaceProvisioner.SESSION_ID)).isNull()
    }

    @Test
    fun `旧目录消失但系统session仍声明旧路径时清除残留并创建受信新根`() = runBlocking<Unit> {
        val missingLegacyRoot = File(filesDir, "rag_workspace")
        database.sessionDao().insert(
            com.promenar.nexara.data.local.db.entity.SessionEntity(
                id = RagWorkspaceProvisioner.SESSION_ID,
                agentId = "__system__",
                title = "RAG Workspace",
                workspacePath = missingLegacyRoot.absolutePath,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val root = provisioner().ensureRoot()

        assertThat(File(root.physicalRootPath).parentFile).isEqualTo(trustedParent.canonicalFile)
        assertThat(database.sessionDao().getById(RagWorkspaceProvisioner.SESSION_ID)!!.workspacePath)
            .isEqualTo(root.physicalRootPath)
        assertThat(root.physicalRootPath).isNotEqualTo(missingLegacyRoot.absolutePath)
    }

    private fun provisioner(): RagWorkspaceProvisioner {
        val repository = WorkspaceRepository(
            dao = database.fileEntryDao(),
            seqDao = database.workspaceSeqDao(),
            defaultWorkspaceParent = trustedParent,
            fileOps = TestWorkspaceFileOps(),
        )
        return RagWorkspaceProvisioner(
            filesDir = filesDir,
            database = database,
            workspaceRepository = repository,
            now = { 1L },
        )
    }
}
