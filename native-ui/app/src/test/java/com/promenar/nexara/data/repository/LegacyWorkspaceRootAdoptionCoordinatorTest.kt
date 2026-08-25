package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.SessionEntity
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
class LegacyWorkspaceRootAdoptionCoordinatorTest {
    private lateinit var database: NexaraDatabase
    private lateinit var filesDir: File
    private lateinit var currentParent: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        filesDir = Files.createTempDirectory(
            java.nio.file.Path.of(System.getProperty("user.dir")),
            ".nexara-legacy-adoption",
        ).toFile()
        currentParent = File(filesDir, "session_workspaces")
    }

    @After
    fun tearDown() {
        database.close()
        filesDir.deleteRecursively()
    }

    @Test
    fun `规范别名冲突会在任何身份写入前整体失败`() = runBlocking<Unit> {
        val shared = File(filesDir, "workspaces/session-a").apply { mkdirs() }
        insertClaim("session-a", "root-a", shared.path)
        insertClaim("session-b", "root-b", File(shared, ".").path)

        val failure = runCatching { coordinator().adoptAll() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(File(shared, ".nexara_root_identity").exists()).isFalse()
        assertThat(database.fileEntryDao().getByUuid("root-a", "root-a")!!.hash).isEmpty()
        assertThat(database.fileEntryDao().getByUuid("root-b", "root-b")!!.hash).isEmpty()
    }

    @Test
    fun `已认领根与未认领别名根冲突会在任何身份写入前整体失败`() = runBlocking<Unit> {
        val shared = File(filesDir, "workspaces/session-a").apply { mkdirs() }
        insertClaim("session-a", "root-a", shared.path)
        insertRoot("orphan-alias-root", File(shared, ".").path)

        val failure = runCatching { coordinator().adoptAll() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(File(shared, ".nexara_root_identity").exists()).isFalse()
        assertThat(database.fileEntryDao().getByUuid("root-a", "root-a")!!.hash).isEmpty()
        assertThat(database.fileEntryDao().getByUuid("orphan-alias-root", "orphan-alias-root")!!.hash).isEmpty()
    }

    @Test
    fun `规范路径不同但文件身份相同的系统别名仍会在任何写入前整体失败`() = runBlocking<Unit> {
        val primary = File(filesDir, "workspaces/session-a").apply { mkdirs() }
        val simulatedBindAlias = File(filesDir, "system-bind-alias/session-a").apply { mkdirs() }
        insertClaim("session-a", "root-a", primary.path)
        insertRoot("orphan-bind-alias", simulatedBindAlias.path)
        val primaryCanonical = primary.canonicalFile
        val aliasCanonical = simulatedBindAlias.canonicalFile
        val identityComparator: (File, File) -> Boolean = { left, right ->
            val pair = setOf(left.canonicalFile.path, right.canonicalFile.path)
            pair == setOf(primaryCanonical.path, aliasCanonical.path) ||
                LegacyWorkspaceRootPolicy.isSameFile(left, right)
        }

        val failure = runCatching { coordinator(identityComparator).adoptAll() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SecurityException::class.java)
        assertThat(File(primary, ".nexara_root_identity").exists()).isFalse()
        assertThat(database.fileEntryDao().getByUuid("root-a", "root-a")!!.hash).isEmpty()
        assertThat(database.fileEntryDao().getByUuid("orphan-bind-alias", "orphan-bind-alias")!!.hash).isEmpty()
    }

    @Test
    fun `两个精确公开旧路径会在完整预检后分别认领`() = runBlocking<Unit> {
        val first = File(filesDir, "workspaces/session-a").apply { mkdirs() }
        val second = File(filesDir, "workspaces/session-b").apply { mkdirs() }
        insertClaim("session-a", "root-a", first.path)
        insertClaim("session-b", "root-b", second.path)

        coordinator().adoptAll()

        assertThat(database.fileEntryDao().getByUuid("root-a", "root-a")!!.hash).isNotEmpty()
        assertThat(database.fileEntryDao().getByUuid("root-b", "root-b")!!.hash).isNotEmpty()
        assertThat(File(first, ".nexara_root_identity").isFile).isTrue()
        assertThat(File(second, ".nexara_root_identity").isFile).isTrue()
    }

    private suspend fun insertClaim(sessionId: String, rootUuid: String, path: String) {
        val now = 1L
        database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                agentId = "agent",
                title = sessionId,
                workspacePath = path,
                workspaceRootUuid = rootUuid,
                createdAt = now,
                updatedAt = now,
            ),
        )
        insertRoot(rootUuid, path)
    }

    private suspend fun insertRoot(rootUuid: String, path: String) {
        val now = 1L
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
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun coordinator(
        sameFile: (File, File) -> Boolean = LegacyWorkspaceRootPolicy::isSameFile,
    ): LegacyWorkspaceRootAdoptionCoordinator {
        val repository = WorkspaceRepository(
            dao = database.fileEntryDao(),
            seqDao = database.workspaceSeqDao(),
            defaultWorkspaceParent = currentParent,
            fileOps = TestWorkspaceFileOps(),
        )
        return LegacyWorkspaceRootAdoptionCoordinator(
            filesDir = filesDir,
            defaultWorkspaceParent = currentParent,
            sessionDao = database.sessionDao(),
            fileEntryDao = database.fileEntryDao(),
            workspaceRepository = repository,
            sameFile = sameFile,
        )
    }
}
