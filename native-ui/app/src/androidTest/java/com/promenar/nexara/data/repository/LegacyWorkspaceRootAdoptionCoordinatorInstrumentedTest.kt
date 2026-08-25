package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.SessionEntity
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyWorkspaceRootAdoptionCoordinatorInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: NexaraDatabase
    private lateinit var workspaceRoot: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        if (::workspaceRoot.isInitialized) workspaceRoot.deleteRecursively()
    }

    @Test
    fun dataDataAndDataUserAliasesFailBeforeAnyRootIdentityWrite() = runBlocking<Unit> {
        val sessionId = "bind-alias-${UUID.randomUUID()}"
        val publicParent = File(context.filesDir, "workspaces").apply { mkdirs() }
        workspaceRoot = File(publicParent, sessionId).apply { mkdirs() }
        val dataDataFiles = File("/data/data/${context.packageName}/files")
        val aliasedRoot = File(dataDataFiles, "workspaces/$sessionId")
        assertThat(Files.isSameFile(context.filesDir.toPath(), dataDataFiles.toPath())).isTrue()
        assertThat(Files.isSameFile(workspaceRoot.toPath(), aliasedRoot.toPath())).isTrue()

        insertClaim(sessionId, "claimed-root", workspaceRoot.path)
        insertRoot("orphan-root", aliasedRoot.path)

        val failure = runCatching { coordinator().adoptAll() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(failure).hasMessageThat().contains("多个工作区根记录指向同一规范物理目录")
        assertThat(File(workspaceRoot, ".nexara_root_identity").exists()).isFalse()
        assertThat(database.fileEntryDao().getByUuid("claimed-root", "claimed-root")!!.hash).isEmpty()
        assertThat(database.fileEntryDao().getByUuid("orphan-root", "orphan-root")!!.hash).isEmpty()
    }

    private suspend fun insertClaim(sessionId: String, rootUuid: String, path: String) {
        database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                agentId = "agent",
                title = sessionId,
                workspacePath = path,
                workspaceRootUuid = rootUuid,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        insertRoot(rootUuid, path)
    }

    private suspend fun insertRoot(rootUuid: String, path: String) {
        database.fileEntryDao().insert(
            FileEntry(
                uuid = rootUuid,
                workspaceRootUuid = rootUuid,
                parentUuid = null,
                name = File(path).name,
                hash = "",
                isDirectory = true,
                physicalRootPath = path,
                materializedPath = "/",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private fun coordinator(): LegacyWorkspaceRootAdoptionCoordinator =
        LegacyWorkspaceRootAdoptionCoordinator(
            filesDir = context.filesDir,
            defaultWorkspaceParent = File(context.filesDir, "session_workspaces"),
            sessionDao = database.sessionDao(),
            fileEntryDao = database.fileEntryDao(),
            workspaceRepository = WorkspaceRepository(
                dao = database.fileEntryDao(),
                seqDao = database.workspaceSeqDao(),
                defaultWorkspaceParent = File(context.filesDir, "session_workspaces"),
            ),
        )
}
