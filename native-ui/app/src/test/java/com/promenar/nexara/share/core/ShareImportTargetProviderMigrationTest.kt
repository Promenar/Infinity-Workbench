package com.promenar.nexara.share.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.repository.TestWorkspaceFileOps
import com.promenar.nexara.data.repository.WorkspaceRepository
import com.promenar.nexara.ui.rag.RagWorkspaceProvisioner
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ShareImportTargetProviderMigrationTest {
    private lateinit var database: NexaraDatabase
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesDir = Files.createTempDirectory(
            java.nio.file.Path.of(System.getProperty("user.dir")),
            ".nexara-share-rag-migration",
        ).toFile()
    }

    @After
    fun tearDown() {
        database.close()
        filesDir.deleteRecursively()
    }

    @Test
    fun `分享入口首次加载会认领公开v17知识库别名根并保留子项`() = runBlocking<Unit> {
        val legacyRoot = File(filesDir, "rag_workspace").apply { mkdirs() }
        val aliasPath = File(legacyRoot, ".").path
        database.fileEntryDao().insert(
            FileEntry(
                uuid = "migrated-rag-root",
                workspaceRootUuid = "migrated-rag-root",
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
                uuid = "migrated-rag-child",
                workspaceRootUuid = "migrated-rag-root",
                parentUuid = "migrated-rag-root",
                name = "legacy.md",
                hash = "legacy-hash",
                isDirectory = false,
                physicalRootPath = aliasPath,
                materializedPath = "/legacy.md",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val workspace = WorkspaceRepository(
            dao = database.fileEntryDao(),
            seqDao = database.workspaceSeqDao(),
            defaultWorkspaceParent = File(filesDir, "session_workspaces"),
            fileOps = TestWorkspaceFileOps(),
        )

        val provisioner = RagWorkspaceProvisioner(filesDir, database, workspace)
        val targets = ShareImportTargetProvider(
            database.sessionDao(),
            workspace,
            provisioner::ensureRoot,
        )
            .load(null, null, "知识库")

        assertThat(targets.single().workspaceRootUuid).isEqualTo("migrated-rag-root")
        assertThat(database.sessionDao().getById(ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID)!!.workspaceRootUuid)
            .isEqualTo("migrated-rag-root")
        assertThat(database.fileEntryDao().getAllWorkspaceRootsForMaintenance().map { it.uuid })
            .containsExactly("migrated-rag-root")
        assertThat(database.fileEntryDao().getByUuid("migrated-rag-root", "migrated-rag-child")!!.physicalRootPath)
            .isEqualTo(legacyRoot.canonicalPath)
    }

    @Test
    fun `分享与知识库并发首次进入只会原位认领一个公开v17根`() = runBlocking<Unit> {
        val legacyRoot = File(filesDir, "rag_workspace").apply { mkdirs() }
        val aliasPath = File(legacyRoot, ".").path
        database.fileEntryDao().insert(
            FileEntry(
                uuid = "migrated-rag-root",
                workspaceRootUuid = "migrated-rag-root",
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
                uuid = "migrated-rag-child",
                workspaceRootUuid = "migrated-rag-root",
                parentUuid = "migrated-rag-root",
                name = "legacy.md",
                hash = "legacy-hash",
                isDirectory = false,
                physicalRootPath = aliasPath,
                materializedPath = "/legacy.md",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val workspace = WorkspaceRepository(
            dao = database.fileEntryDao(),
            seqDao = database.workspaceSeqDao(),
            defaultWorkspaceParent = File(filesDir, "session_workspaces"),
            fileOps = TestWorkspaceFileOps(),
        )
        val provisioner = RagWorkspaceProvisioner(filesDir, database, workspace)
        val share = ShareImportTargetProvider(
            database.sessionDao(),
            workspace,
            provisioner::ensureRoot,
        )

        coroutineScope {
            listOf(
                async { share.load(null, null, "知识库").single().workspaceRootUuid },
                async { provisioner.ensureRoot().uuid },
            ).awaitAll()
        }.also { roots -> assertThat(roots).containsExactly("migrated-rag-root", "migrated-rag-root") }

        assertThat(database.sessionDao().getAll().map { it.id })
            .containsExactly(ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID)
        assertThat(database.fileEntryDao().getAllWorkspaceRootsForMaintenance().map { it.uuid })
            .containsExactly("migrated-rag-root")
        assertThat(database.fileEntryDao().getByUuid("migrated-rag-root", "migrated-rag-child"))
            .isNotNull()
    }
}
