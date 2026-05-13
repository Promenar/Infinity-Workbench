package com.promenar.nexara.data.mapper

import com.promenar.nexara.data.local.db.entity.FolderEntity
import com.promenar.nexara.domain.model.Folder
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FolderMapperTest {

    @Test
    fun `toDomain maps all fields`() {
        val entity = FolderEntity(
            id = "f1",
            name = "Test Folder",
            parentId = "p1",
            createdAt = 1000L
        )
        val folder = FolderMapper.toDomain(entity)

        assertThat(folder.id).isEqualTo("f1")
        assertThat(folder.name).isEqualTo("Test Folder")
        assertThat(folder.parentId).isEqualTo("p1")
        assertThat(folder.createdAt).isEqualTo(1000L)
    }

    @Test
    fun `toDomain handles null parentId`() {
        val entity = FolderEntity(
            id = "f2",
            name = "Root",
            parentId = null,
            createdAt = 2000L
        )
        val folder = FolderMapper.toDomain(entity)

        assertThat(folder.parentId).isNull()
    }

    @Test
    fun `toEntity maps domain to entity correctly`() {
        val folder = Folder(
            id = "f3",
            name = "Mapped",
            parentId = "p3",
            createdAt = 3000L
        )
        val entity = FolderMapper.toEntity(folder)

        assertThat(entity.id).isEqualTo("f3")
        assertThat(entity.name).isEqualTo("Mapped")
        assertThat(entity.parentId).isEqualTo("p3")
        assertThat(entity.createdAt).isEqualTo(3000L)
    }

    @Test
    fun `roundtrip preserves all fields`() {
        val entity = FolderEntity(
            id = "rt",
            name = "Roundtrip",
            parentId = "rt-parent",
            createdAt = 999L
        )
        val domain = FolderMapper.toDomain(entity)
        val back = FolderMapper.toEntity(domain)

        assertThat(back.id).isEqualTo(entity.id)
        assertThat(back.name).isEqualTo(entity.name)
        assertThat(back.parentId).isEqualTo(entity.parentId)
        assertThat(back.createdAt).isEqualTo(entity.createdAt)
    }
}
