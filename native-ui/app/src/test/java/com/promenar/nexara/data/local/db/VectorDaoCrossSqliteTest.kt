package com.promenar.nexara.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.VectorEntity
import com.promenar.nexara.data.local.db.entity.FileEntry
import java.nio.ByteBuffer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VectorDaoCrossSqliteTest {
    private lateinit var database: NexaraDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `类型查询不依赖JSON1且只匹配根对象首字段`() = runTest {
        database.vectorDao().insertAll(
            listOf(
                vector("document", "doc", "{\"type\":\"document\",\"fileUuid\":\"doc\"}"),
                vector("memory", null, "{\"type\":\"memory\",\"chunkIndex\":0}"),
                vector("nested-decoy", "decoy", "{\"nested\":{\"type\":\"document\"}}"),
            ),
        )

        assertThat(database.vectorDao().getByType("document").map { it.id })
            .containsExactly("document")
        assertThat(database.vectorDao().getByTypeAndDocIds("document", listOf("doc", "decoy")).map { it.id })
            .containsExactly("document")
    }

    @Test
    fun `workspace FTS candidate set is root scoped and active only`() = runTest {
        listOf(
                file("root-a", "root-a", "/"),
                file("doc-a-low", "root-a", "/low.md"),
                file("doc-a-high", "root-a", "/high.md"),
                file("doc-a-recycled", "root-a", "/.recycle_bin/recycled.md", recycled = true),
                file("root-b", "root-b", "/"),
                file("doc-b", "root-b", "/other.md"),
            ).forEach { database.fileEntryDao().insert(it) }
        database.vectorDao().insertAll(
            listOf(
                vector("low", "doc-a-low", "{}", "needle once"),
                vector("high", "doc-a-high", "{}", "needle needle needle needle"),
                vector("stale", "doc-a-high", "{}", "needle stale").copy(stale = true),
                vector("recycled", "doc-a-recycled", "{}", "needle needle needle needle needle"),
                vector("other-root", "doc-b", "{}", "needle needle needle needle needle needle"),
            ),
        )

        val rows = database.vectorDao().searchFtsByWorkspaceRoot("needle", "root-a")
        val fallbackRows = database.vectorDao().getByWorkspaceRoot("root-a")

        assertThat(rows.map { it.id }).containsExactly("high", "low")
        assertThat(fallbackRows.map { it.id }).containsExactly("high", "low")
    }

    private fun file(uuid: String, root: String, path: String, recycled: Boolean = false) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = root,
        parentUuid = if (uuid == root) null else root,
        name = path.substringAfterLast('/').ifEmpty { "root" },
        hash = uuid,
        physicalRootPath = "/tmp/$root",
        materializedPath = path,
        inRecycleBin = recycled,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun vector(
        id: String,
        docId: String?,
        metadata: String,
        content: String = id,
    ) = VectorEntity(
        id = id,
        docId = docId,
        content = content,
        embedding = ByteBuffer.allocate(8).also { it.asFloatBuffer().put(floatArrayOf(1f, 0f)) }.array(),
        metadata = metadata,
        createdAt = 1,
    )
}
