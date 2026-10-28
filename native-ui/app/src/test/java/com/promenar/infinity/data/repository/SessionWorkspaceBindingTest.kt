package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.session.RoomSessionDeletionTransaction
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 会话的自选工作区绑定：可切换、可改回私有工作区，删除会话只解除引用。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SessionWorkspaceBindingTest {
    private lateinit var database: InfinityDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = SessionRepository(database.sessionDao(), database.messageDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `局部更新可设置与清除工作区且其它更新不丢失绑定`(): Unit = runBlocking {
        database.sessionDao().insert(SessionEntity("session", "agent", createdAt = 1, updatedAt = 1))
        repository.updatePartial("session", mapOf("workspaceId" to "ws-1"))
        repository.updatePartial("session", mapOf("title" to "改名"))
        assertThat(repository.getById("session")!!.workspaceId).isEqualTo("ws-1")
        repository.updatePartial("session", mapOf("workspaceId" to null))
        assertThat(database.sessionDao().getById("session")!!.workspaceId).isNull()
    }

    @Test
    fun `删除会话保留自选工作区登记与目录内容`(): Unit = runBlocking {
        val folder = Files.createTempDirectory("project").toFile()
        try {
            folder.resolve("README.md").writeText("keep")
            database.workspaceDao().register(WorkspaceEntity("ws-1", WorkspaceEntity.KIND_PROJECT, "项目", "demo", 1))
            database.sessionDao().insert(SessionEntity("a", "agent", workspaceId = "ws-1", createdAt = 1, updatedAt = 1))
            database.sessionDao().insert(SessionEntity("b", "agent", workspaceId = "ws-1", createdAt = 1, updatedAt = 1))

            assertThat(RoomSessionDeletionTransaction(database).deleteWithoutWorkspace("a")).isTrue()
            assertThat(database.workspaceDao().getById("ws-1")).isNotNull()
            assertThat(database.workspaceDao().deleteIfUnreferenced("ws-1")).isFalse()
            RoomSessionDeletionTransaction(database).deleteWithoutWorkspace("b")
            assertThat(database.workspaceDao().getById("ws-1")).isNotNull()
            assertThat(folder.resolve("README.md").readText()).isEqualTo("keep")
            assertThat(database.workspaceDao().deleteIfUnreferenced("ws-1")).isTrue()
        } finally {
            folder.deleteRecursively()
        }
    }
}
