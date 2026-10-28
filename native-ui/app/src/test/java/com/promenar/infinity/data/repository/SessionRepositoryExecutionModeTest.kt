package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SessionRepositoryExecutionModeTest {
    private lateinit var database: InfinityDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SessionRepository(database.sessionDao(), database.messageDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `partial update normalizes blank unknown and null execution mode to workspace`() = runBlocking {
        database.sessionDao().insert(
            SessionEntity(
                id = "session",
                agentId = "agent",
                executionMode = "manual",
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        listOf<Any?>("", "future-mode", null).forEach { raw ->
            repository.updatePartial("session", mapOf("executionMode" to raw))
            assertThat(database.sessionDao().getById("session")!!.executionMode).isEqualTo("workspace")
        }
    }
}
