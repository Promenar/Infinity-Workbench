package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.SessionEntity
import com.promenar.nexara.infra.util.Sha256Utils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceRepositoryTest {
    private lateinit var db: NexaraDatabase
    private lateinit var repo: WorkspaceRepository
    private lateinit var rootA: File
    private lateinit var rootB: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao())
        rootA = Files.createTempDirectory("nexara-workspace-a").toFile()
        rootB = Files.createTempDirectory("nexara-workspace-b").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        rootA.deleteRecursively()
        rootB.deleteRecursively()
    }

    private suspend fun insertSession(id: String, path: String?) {
        val now = System.currentTimeMillis()
        db.sessionDao().insert(
            SessionEntity(
                id = id,
                agentId = "agent",
                workspacePath = path,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun `ensureSessionRoot creates safe default path when session path is absent`() = runBlocking<Unit> {
        insertSession("session-without-path", null)
        val parent = File(rootA, "sessions")
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(), parent)

        val root = repo.ensureSessionRoot("session-without-path")

        assertThat(File(root.physicalRootPath).parentFile).isEqualTo(parent.canonicalFile)
        assertThat(File(root.physicalRootPath).name).isEqualTo(Sha256Utils.hash("session-without-path"))
        assertThat(db.sessionDao().getById("session-without-path")!!.workspacePath)
            .isEqualTo(root.physicalRootPath)
    }

    @Test
    fun `ensureSessionRoot is concurrent idempotent and backfills session`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)

        val roots = List(16) { async(Dispatchers.Default) { repo.ensureSessionRoot("session-a") } }.awaitAll()

        assertThat(roots.map { it.uuid }.distinct()).hasSize(1)
        val root = roots.first()
        assertThat(root.workspaceRootUuid).isEqualTo(root.uuid)
        assertThat(root.isDirectory).isTrue()
        assertThat(root.parentUuid).isNull()
        assertThat(root.materializedPath).isEqualTo("/")
        assertThat(db.sessionDao().getById("session-a")!!.workspaceRootUuid).isEqualTo(root.uuid)
        assertThat(repo.observeRoots(root.uuid).first()).containsExactly(root)
    }

    @Test
    fun `scoped reads and writes never cross workspace roots`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        insertSession("session-b", rootB.absolutePath)
        val a = repo.ensureSessionRoot("session-a")
        val b = repo.ensureSessionRoot("session-b")
        val childA = repo.createFileInWorkspace(a.uuid, "a-file", "a.txt", "A", a.uuid, "/a.txt")
        val childB = repo.createFileInWorkspace(b.uuid, "b-file", "b.txt", "B", b.uuid, "/b.txt")

        assertThat(repo.getByUuid(a.uuid, childA.uuid)).isEqualTo(childA)
        assertThat(repo.getByUuid(a.uuid, childB.uuid)).isNull()
        assertThat(repo.observeChildren(a.uuid, a.uuid).first()).containsExactly(childA)
        assertThat(repo.observeChildren(a.uuid, b.uuid).first()).isEmpty()
        assertThat(repo.searchByName(a.uuid, ".txt").first()).containsExactly(childA)
        assertThat(repo.searchByName(a.uuid, "b.txt").first()).isEmpty()
        assertThat(repo.getByMaterializedPath(a.uuid, "/b.txt")).isNull()
        assertThat(repo.getSubtree(a.uuid, "/")).containsExactly(a, childA)

        repo.rename(a.uuid, childA.uuid, "renamed.txt")
        assertThat(repo.getByMaterializedPath(a.uuid, "/renamed.txt")!!.name).isEqualTo("renamed.txt")
        assertThat(File(rootA, "renamed.txt").readText()).isEqualTo("A")

        var rejected = false
        try {
            repo.createFileInWorkspace(a.uuid, "cross", "x.txt", "x", b.uuid, "/x.txt")
        } catch (_: SecurityException) {
            rejected = true
        }
        assertThat(rejected).isTrue()
    }

    @Test
    fun `path validation rejects traversal physical absolute and symlink escape`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")

        val invalidPaths = listOf(
            "/../evil.txt",
            "../evil.txt",
            rootA.absolutePath + "/absolute.txt",
            "C:\\outside\\evil.txt",
        )
        invalidPaths.forEachIndexed { index, path ->
            var rejected = false
            try {
                repo.createFileInWorkspace(root.uuid, "bad-$index", "bad.txt", "nope", root.uuid, path)
            } catch (_: SecurityException) {
                rejected = true
            }
            assertThat(rejected).isTrue()
        }

        val outside = File(rootA.parentFile, "outside-${System.nanoTime()}").apply { mkdirs() }
        try {
            Files.createSymbolicLink(File(rootA, "link").toPath(), outside.toPath())
            var rejected = false
            try {
                repo.createFileInWorkspace(root.uuid, "symlink", "evil.txt", "nope", root.uuid, "/link/evil.txt")
            } catch (_: SecurityException) {
                rejected = true
            }
            assertThat(rejected).isTrue()
            assertThat(File(outside, "evil.txt").exists()).isFalse()
        } finally {
            outside.deleteRecursively()
        }
    }

    @Test
    fun `recycle restore move and delete stay inside declared root`() = runBlocking<Unit> {
        insertSession("session-a", rootA.absolutePath)
        val root = repo.ensureSessionRoot("session-a")
        repo.createFileInWorkspace(root.uuid, "file-1", "a.txt", "hello", root.uuid, "/docs/a.txt")

        repo.moveToRecycleBin(root.uuid, "file-1")
        val recycled = repo.getByUuid(root.uuid, "file-1")!!
        assertThat(recycled.inRecycleBin).isTrue()
        assertThat(File(rootA, ".recycle_bin/docs/a.txt").readText()).isEqualTo("hello")

        repo.restoreFromRecycleBin(root.uuid, "file-1")
        assertThat(repo.getByUuid(root.uuid, "file-1")!!.inRecycleBin).isFalse()
        assertThat(File(rootA, "docs/a.txt").readText()).isEqualTo("hello")
    }
}
