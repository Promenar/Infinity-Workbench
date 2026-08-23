package com.promenar.nexara.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayload
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationPayloadCodec
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationStage
import com.promenar.nexara.data.local.db.entity.WorkspaceMutationType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceMutationDaoTest {
    private lateinit var database: NexaraDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexaraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `启动扫描在数据库提交后进程死亡仍能发现 unfinished journal`() = runBlocking {
        val payload = WorkspaceMutationPayloadCodec.encode(
            WorkspaceMutationPayload(sourceRelativePath = "docs/a.md"),
        )
        val dao = database.workspaceMutationDao()
        dao.insert(
            WorkspaceMutationEntity(
                operationId = "operation-1",
                workspaceRootUuid = "root-1",
                operationType = WorkspaceMutationType.CREATE,
                payload = payload,
                payloadDigest = "digest-1",
                createdAt = 10L,
                updatedAt = 10L,
            ),
        )

        assertThat(dao.getUnfinished().map { it.state })
            .containsExactly(WorkspaceMutationStage.PREPARED)
        assertThat(dao.markDbCommitted("operation-1", 20L)).isEqualTo(1)

        assertThat(dao.getPrepared()).isEmpty()
        assertThat(dao.getUnfinished().map { it.operationId })
            .containsExactly("operation-1")
        assertThat(dao.getUnfinished().single().state)
            .isEqualTo(WorkspaceMutationStage.DB_COMMITTED)
    }
}
