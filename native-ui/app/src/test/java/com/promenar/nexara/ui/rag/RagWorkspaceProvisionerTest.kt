package com.promenar.nexara.ui.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
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
