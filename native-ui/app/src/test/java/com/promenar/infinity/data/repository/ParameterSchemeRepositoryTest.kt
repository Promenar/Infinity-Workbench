package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.ParameterSchemeDao
import com.promenar.infinity.data.local.db.entity.ParameterSchemeEntity
import com.promenar.infinity.data.model.params.SamplingValues
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ParameterSchemeRepositoryTest {
    private class FakeDao : ParameterSchemeDao {
        val rows = MutableStateFlow<Map<String, ParameterSchemeEntity>>(emptyMap())
        override fun observeForModel(modelKey: String): Flow<List<ParameterSchemeEntity>> =
            rows.map { all -> all.values.filter { it.modelKey == modelKey }.sortedBy { it.createdAt } }
        override suspend fun getById(id: String) = rows.value[id]
        override suspend fun upsert(scheme: ParameterSchemeEntity) { rows.value = rows.value + (scheme.id to scheme) }
        override suspend fun delete(id: String) { rows.value = rows.value - id }
    }

    @Test
    fun `方案按模型隔离，覆盖、重命名与删除只作用于目标方案`() = runBlocking {
        val dao = FakeDao()
        var now = 10L
        var seq = 0
        val repository = ParameterSchemeRepository(dao, clock = { now++ }, newId = { "s${++seq}" })

        val coding = repository.create("p::m", "  写代码 ", SamplingValues(temperature = 0.2))
        repository.create("p::other", "别的模型", SamplingValues(topK = 5))
        assertThat(coding.name).isEqualTo("写代码")
        assertThat(repository.observeForModel("p::m").first().map { it.name }).containsExactly("写代码")

        assertThat(repository.overwrite("s1", SamplingValues(temperature = 0.4, topP = 0.9))).isTrue()
        assertThat(repository.rename("s1", "代码审阅")).isTrue()
        val updated = repository.observeForModel("p::m").first().single()
        assertThat(updated.values).isEqualTo(SamplingValues(temperature = 0.4, topP = 0.9))
        assertThat(updated.name).isEqualTo("代码审阅")
        assertThat(updated.updatedAt).isGreaterThan(updated.createdAt)

        repository.delete("s1")
        assertThat(repository.observeForModel("p::m").first()).isEmpty()
        assertThat(repository.overwrite("s1", SamplingValues())).isFalse()
        assertThat(repository.observeForModel("p::other").first()).hasSize(1)
    }

    @Test
    fun `无法解析的方案内容按模型默认读出`() = runBlocking {
        val dao = FakeDao()
        dao.upsert(ParameterSchemeEntity("bad", "p::m", "损坏", "not json", 1, 1))
        assertThat(ParameterSchemeRepository(dao).observeForModel("p::m").first().single().values).isEqualTo(SamplingValues())
    }
}
