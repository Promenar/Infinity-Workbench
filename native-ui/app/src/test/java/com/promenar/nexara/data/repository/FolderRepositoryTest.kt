package com.promenar.nexara.data.repository

import app.cash.turbine.test
import com.promenar.nexara.data.local.db.dao.FolderDao
import com.promenar.nexara.data.local.db.entity.FolderEntity
import com.promenar.nexara.domain.model.Folder
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FolderRepositoryTest {

    private val folderDao: FolderDao = mockk()
    private val repo = FolderRepository(folderDao)

    private fun createEntity(
        id: String = "f1",
        name: String = "Test",
        parentId: String? = null,
        createdAt: Long = 1000L
    ) = FolderEntity(
        id = id,
        name = name,
        parentId = parentId,
        createdAt = createdAt
    )

    @Test
    fun `observeAll maps entities to domain`() = runTest {
        val entities = listOf(
            createEntity(id = "f1", name = "Folder 1"),
            createEntity(id = "f2", name = "Folder 2")
        )
        every { folderDao.observeAll() } returns flowOf(entities)

        repo.observeAll().test {
            val folders = awaitItem()
            assertThat(folders).hasSize(2)
            assertThat(folders[0].id).isEqualTo("f1")
            assertThat(folders[0].name).isEqualTo("Folder 1")
            assertThat(folders[1].id).isEqualTo("f2")
            awaitComplete()
        }
    }

    @Test
    fun `observeAll returns empty when no folders`() = runTest {
        every { folderDao.observeAll() } returns flowOf(emptyList())

        repo.observeAll().test {
            assertThat(awaitItem()).isEmpty()
            awaitComplete()
        }
    }

    @Test
    fun `getById returns domain folder when found`() = runTest {
        val entity = createEntity(id = "f1", name = "Found")
        coEvery { folderDao.getById("f1") } returns entity

        val folder = repo.getById("f1")

        assertThat(folder).isNotNull()
        assertThat(folder!!.id).isEqualTo("f1")
        assertThat(folder.name).isEqualTo("Found")
    }

    @Test
    fun `getById returns null when not found`() = runTest {
        coEvery { folderDao.getById("missing") } returns null

        val folder = repo.getById("missing")

        assertThat(folder).isNull()
    }

    @Test
    fun `create delegates to dao insert`() = runTest {
        val domain = Folder(id = "new", name = "New Folder", createdAt = 500L)
        coEvery { folderDao.insert(any()) } returns Unit

        repo.create(domain)

        coVerify {
            folderDao.insert(match { entity ->
                entity.id == "new" && entity.name == "New Folder" && entity.createdAt == 500L
            })
        }
    }

    @Test
    fun `delete delegates to dao delete`() = runTest {
        val domain = Folder(id = "del", name = "Delete Me", createdAt = 600L)
        coEvery { folderDao.delete(any()) } returns Unit

        repo.delete(domain)

        coVerify {
            folderDao.delete(match { entity ->
                entity.id == "del" && entity.name == "Delete Me"
            })
        }
    }
}
