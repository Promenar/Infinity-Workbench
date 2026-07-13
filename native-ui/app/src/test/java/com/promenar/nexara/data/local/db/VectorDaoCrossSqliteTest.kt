package com.promenar.nexara.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.VectorEntity
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

    private fun vector(id: String, docId: String?, metadata: String) = VectorEntity(
        id = id,
        docId = docId,
        content = id,
        embedding = ByteBuffer.allocate(8).also { it.asFloatBuffer().put(floatArrayOf(1f, 0f)) }.array(),
        metadata = metadata,
        createdAt = 1,
    )
}
