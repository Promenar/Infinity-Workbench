package com.promenar.nexara.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.FileEntry
import com.promenar.nexara.data.local.db.entity.VectorizationTaskEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VectorizationTaskForeignKeyTest {
    private lateinit var database: NexaraDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NexaraDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `文件引用任务禁止跨root且文件删除级联任务`() = runTest {
        database.fileEntryDao().insert(root("root-a"))
        database.fileEntryDao().insert(root("root-b"))
        database.fileEntryDao().insert(file("root-b", "doc-b"))

        val crossRootFailure = runCatching {
            database.vectorizationTaskDao().insert(task("cross", "root-a", "doc-b"))
        }.exceptionOrNull()
        assertThat(crossRootFailure).isNotNull()

        database.vectorizationTaskDao().insert(task("valid", "root-b", "doc-b"))
        assertThat(database.vectorizationTaskDao().getById("valid")).isNotNull()
        database.fileEntryDao().deleteByUuid("root-b", "doc-b")
        assertThat(database.vectorizationTaskDao().getById("valid")).isNull()
    }

    @Test
    fun `清理仅删除完成任务并保留失败任务供用户重试`() = runTest {
        database.fileEntryDao().insert(root("root"))
        database.fileEntryDao().insert(file("root", "completed-doc"))
        database.fileEntryDao().insert(file("root", "failed-doc"))
        database.vectorizationTaskDao().insert(task("completed", "root", "completed-doc").copy(status = "completed"))
        database.vectorizationTaskDao().insert(task("failed", "root", "failed-doc").copy(status = "failed"))

        database.vectorizationTaskDao().deleteCompletedTasks()

        assertThat(database.vectorizationTaskDao().getById("completed")).isNull()
        assertThat(database.vectorizationTaskDao().getById("failed")).isNotNull()
        assertThat(database.vectorizationTaskDao().getAttentionTasks().map { it.id })
            .containsExactly("failed")
    }

    private fun root(uuid: String) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = uuid,
        parentUuid = null,
        name = uuid,
        hash = "hash-$uuid",
        isDirectory = true,
        physicalRootPath = "/tmp/$uuid",
        materializedPath = "/",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun file(root: String, uuid: String) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = root,
        parentUuid = root,
        name = "doc.txt",
        hash = "hash-doc",
        mimeType = "text/plain",
        sizeBytes = 3,
        physicalRootPath = "/tmp/$root",
        materializedPath = "/doc.txt",
        createdAt = 1,
        updatedAt = 1,
    )

    private fun task(id: String, root: String, doc: String) = VectorizationTaskEntity(
        id = id,
        type = "document_reference",
        status = "pending",
        docId = doc,
        workspaceRootUuid = root,
        sourceMimeType = "text/plain",
        createdAt = 1,
        updatedAt = 1,
    )

}
