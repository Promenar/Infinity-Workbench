package com.promenar.nexara.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.dao.DocumentDao
import com.promenar.nexara.data.local.db.dao.SessionDao
import com.promenar.nexara.data.local.db.dao.VectorDao
import com.promenar.nexara.data.local.db.entity.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class VectorDaoTest {
    private lateinit var db: NexaraDatabase
    private lateinit var vectorDao: VectorDao
    private lateinit var documentDao: DocumentDao
    private lateinit var sessionDao: SessionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vectorDao = db.vectorDao()
        documentDao = db.documentDao()
        sessionDao = db.sessionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGetById(): Unit = runBlocking {
        val embedding = floatArrayOf(0.1f, 0.2f, 0.3f)
        val vector = VectorEntity(
            id = "v1", content = "test chunk", embedding = Converters.floatArrayToBytes(embedding)!!,
            createdAt = 1000L,
        )
        vectorDao.insert(vector)
        val result = vectorDao.getById("v1")
        assertThat(result).isNotNull()
        assertThat(result!!.content).isEqualTo("test chunk")
        val resultEmbedding = Converters.bytesToFloatArray(result.embedding)
        assertThat(resultEmbedding).isNotNull()
        assertThat(resultEmbedding!!.size).isEqualTo(3)
        assertThat(resultEmbedding[0]).isWithin(0.001f).of(0.1f)
        assertThat(resultEmbedding[1]).isWithin(0.001f).of(0.2f)
        assertThat(resultEmbedding[2]).isWithin(0.001f).of(0.3f)
    }

    @Test
    fun insertAndGetByDocId(): Unit = runBlocking {
        sessionDao.insert(SessionEntity(id = "s1", agentId = "a1", createdAt = 1000L, updatedAt = 1000L))
        documentDao.insert(DocumentEntity(id = "d1", title = "Doc", createdAt = 1000L))
        val v1 = VectorEntity(id = "v1", docId = "d1", content = "chunk1", embedding = ByteArray(4), createdAt = 1000L)
        val v2 = VectorEntity(id = "v2", docId = "d1", content = "chunk2", embedding = ByteArray(4), createdAt = 2000L)
        val v3 = VectorEntity(id = "v3", sessionId = "s1", content = "chunk3", embedding = ByteArray(4), createdAt = 3000L)
        vectorDao.insertAll(listOf(v1, v2, v3))
        val docVectors = vectorDao.getByDocId("d1")
        assertThat(docVectors).hasSize(2)
        assertThat(docVectors.map { it.id }).containsExactly("v1", "v2")
    }

    @Test
    fun deleteByDocId(): Unit = runBlocking {
        documentDao.insert(DocumentEntity(id = "d1", title = "Doc", createdAt = 1000L))
        vectorDao.insert(VectorEntity(id = "v1", docId = "d1", content = "c1", embedding = ByteArray(4), createdAt = 1000L))
        vectorDao.insert(VectorEntity(id = "v2", content = "c2", embedding = ByteArray(4), createdAt = 2000L))
        vectorDao.deleteByDocId("d1")
        assertThat(vectorDao.getById("v1")).isNull()
        assertThat(vectorDao.getById("v2")).isNotNull()
    }

    @Test
    fun searchByKeyword(): Unit = runBlocking {
        vectorDao.insert(VectorEntity(id = "v1", content = "Kotlin is a great language", embedding = ByteArray(4), createdAt = 1000L))
        vectorDao.insert(VectorEntity(id = "v2", content = "Python is also nice", embedding = ByteArray(4), createdAt = 2000L))
        val results = vectorDao.searchByKeyword("Kotlin")
        assertThat(results).hasSize(1)
        assertThat(results[0].id).isEqualTo("v1")
    }
}
