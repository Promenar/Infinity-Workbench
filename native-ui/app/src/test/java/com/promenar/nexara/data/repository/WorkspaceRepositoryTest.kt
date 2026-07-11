package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceRepositoryTest {
    private lateinit var db: NexaraDatabase
    private lateinit var repo: WorkspaceRepository
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao())
        root = Files.createTempDirectory("nexara-workspace-test").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    @Test
    fun `createFile resolves slash-prefixed materialized path inside workspace root`() = runBlocking {
        val entry = repo.createFile(
            uuid = "file-1",
            name = "a.txt",
            content = "hello",
            parentUuid = null,
            physicalRootPath = root.absolutePath,
            materializedPath = "/docs/a.txt"
        )

        assertThat(entry.materializedPath).isEqualTo("/docs/a.txt")
        assertThat(java.io.File(root, "docs/a.txt").readText()).isEqualTo("hello")
    }

    @Test
    fun `createFile rejects materialized path escaping workspace root`() = runBlocking {
        var caught = false
        try {
            repo.createFile(
                uuid = "file-escape",
                name = "evil.txt",
                content = "nope",
                parentUuid = null,
                physicalRootPath = root.absolutePath,
                materializedPath = "/../evil.txt"
            )
        } catch (_: SecurityException) {
            caught = true
        }

        assertThat(caught).isTrue()
        assertThat(java.io.File(root.parentFile, "evil.txt").exists()).isFalse()
    }

    @Test
    fun `moveToRecycleBin and restore keep database and physical file in sync`() = runBlocking {
        repo.createFile(
            uuid = "file-1",
            name = "a.txt",
            content = "hello",
            parentUuid = null,
            physicalRootPath = root.absolutePath,
            materializedPath = "/docs/a.txt"
        )

        repo.moveToRecycleBin("file-1")

        val recycled = repo.getByUuid("file-1")!!
        assertThat(recycled.inRecycleBin).isTrue()
        assertThat(recycled.materializedPath).isEqualTo("/.recycle_bin/docs/a.txt")
        assertThat(java.io.File(root, ".recycle_bin/docs/a.txt").readText()).isEqualTo("hello")
        assertThat(java.io.File(root, "docs/a.txt").exists()).isFalse()

        repo.restoreFromRecycleBin("file-1")

        val restored = repo.getByUuid("file-1")!!
        assertThat(restored.inRecycleBin).isFalse()
        assertThat(restored.materializedPath).isEqualTo("/docs/a.txt")
        assertThat(java.io.File(root, "docs/a.txt").readText()).isEqualTo("hello")
        assertThat(java.io.File(root, ".recycle_bin/docs/a.txt").exists()).isFalse()
    }
}
