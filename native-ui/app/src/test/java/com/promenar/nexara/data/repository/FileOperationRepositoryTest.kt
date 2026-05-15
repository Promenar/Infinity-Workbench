package com.promenar.nexara.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.domain.repository.WriteResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FileOperationRepositoryTest {
    private lateinit var db: NexaraDatabase
    private lateinit var repo: FileOperationRepository
    private lateinit var testDir: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = FileOperationRepository(db.fileEntryDao())
        testDir = File(System.getProperty("java.io.tmpdir"), "nexara_test_${System.currentTimeMillis()}")
        testDir.mkdirs()
    }

    @After
    fun tearDown() {
        db.close()
        testDir.deleteRecursively()
    }

    private suspend fun insertTestFile(
        uuid: String = "file-1",
        content: String = "hello world",
        hash: String = "abc123"
    ): FileEntry {
        val file = File(testDir, "test.txt")
        file.writeText(content)
        val entry = FileEntry(
            uuid = uuid,
            parentUuid = null,
            name = "test.txt",
            hash = hash,
            sizeBytes = content.toByteArray().size.toLong(),
            isDirectory = false,
            physicalRootPath = testDir.absolutePath,
            materializedPath = "/test.txt",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        db.fileEntryDao().insert(entry)
        return entry
    }

    @Test
    fun `write success when hash matches`() = runBlocking {
        val entry = insertTestFile(hash = "abc123")
        val result = repo.writeFileAtomic("file-1", "new content", "session-1", "abc123")
        assertThat(result).isInstanceOf(WriteResult.Success::class.java)
        val success = result as WriteResult.Success
        assertThat(success.newHash).isNotEmpty()
        assertThat(success.newHash).isNotEqualTo("abc123")

        val updated = db.fileEntryDao().getByUuid("file-1")!!
        assertThat(updated.hash).isEqualTo(success.newHash)
        assertThat(updated.lastWriteSessionId).isEqualTo("session-1")
    }

    @Test
    fun `write returns Conflict when hash mismatches`() = runBlocking {
        insertTestFile(hash = "abc123")
        val result = repo.writeFileAtomic("file-1", "new content", "session-1", "wrong-hash")
        assertThat(result).isInstanceOf(WriteResult.Conflict::class.java)
        val conflict = result as WriteResult.Conflict
        assertThat(conflict.currentHash).isEqualTo("abc123")
        assertThat(conflict.expectedHash).isEqualTo("wrong-hash")
    }

    @Test
    fun `write returns NotFound when uuid does not exist`() = runBlocking {
        val result = repo.writeFileAtomic("nonexistent", "content", "session-1", "any")
        assertThat(result).isInstanceOf(WriteResult.NotFound::class.java)
    }

    @Test
    fun `read returns file content`() = runBlocking {
        insertTestFile(content = "line1\nline2\nline3")
        val result = repo.readFileRange("file-1")
        assertThat(result.content).isEqualTo("line1\nline2\nline3")
        assertThat(result.totalLines).isEqualTo(3)
        assertThat(result.name).isEqualTo("test.txt")
    }

    @Test
    fun `read with line range`() = runBlocking {
        insertTestFile(content = "line1\nline2\nline3\nline4\nline5")
        val result = repo.readFileRange("file-1", startLine = 2, endLine = 4)
        assertThat(result.startLine).isEqualTo(2)
        assertThat(result.endLine).isEqualTo(4)
        assertThat(result.content).isEqualTo("line2\nline3\nline4")
    }
}
