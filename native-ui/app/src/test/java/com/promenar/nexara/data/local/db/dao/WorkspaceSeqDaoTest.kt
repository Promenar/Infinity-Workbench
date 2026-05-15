package com.promenar.nexara.data.local.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.NexaraDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceSeqDaoTest {
    private lateinit var db: NexaraDatabase
    private lateinit var dao: WorkspaceSeqDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.workspaceSeqDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `first increment returns 1`() = runBlocking {
        val seq = dao.getNextSeqForDate("2026-05-15")
        assertThat(seq).isEqualTo(1)
    }

    @Test
    fun `sequential increments produce ascending sequences`() = runBlocking {
        val seq1 = dao.getNextSeqForDate("2026-05-15")
        val seq2 = dao.getNextSeqForDate("2026-05-15")
        val seq3 = dao.getNextSeqForDate("2026-05-15")
        assertThat(seq1).isEqualTo(1)
        assertThat(seq2).isEqualTo(2)
        assertThat(seq3).isEqualTo(3)
    }

    @Test
    fun `different dates have independent sequences`() = runBlocking {
        val seq1 = dao.getNextSeqForDate("2026-05-15")
        val seq2 = dao.getNextSeqForDate("2026-05-16")
        val seq3 = dao.getNextSeqForDate("2026-05-15")
        assertThat(seq1).isEqualTo(1)
        assertThat(seq2).isEqualTo(1)
        assertThat(seq3).isEqualTo(2)
    }

    @Test
    fun `concurrent increment produces unique sequences`() = runBlocking {
        val results = mutableSetOf<Int>()
        val mutex = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

        coroutineScope {
            val jobs = (1..20).map {
                async {
                    val seq = dao.getNextSeqForDate("2026-05-15")
                    mutex.putIfAbsent(seq, true)
                    seq
                }
            }
            results.addAll(jobs.awaitAll())
        }

        assertThat(results.size).isEqualTo(20)
        assertThat(results.min()).isEqualTo(1)
        assertThat(results.max()).isEqualTo(20)
    }
}
