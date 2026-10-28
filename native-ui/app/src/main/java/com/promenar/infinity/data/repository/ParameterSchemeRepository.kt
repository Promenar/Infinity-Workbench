package com.promenar.infinity.data.repository

import com.promenar.infinity.data.local.db.dao.ParameterSchemeDao
import com.promenar.infinity.data.local.db.entity.ParameterSchemeEntity
import com.promenar.infinity.data.model.params.ParameterScheme
import com.promenar.infinity.data.model.params.SamplingValues
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** 按模型保存的参数方案。方案内容以 [SamplingValues] JSON 落库，无法解析的行按模型默认读出。 */
class ParameterSchemeRepository(
    private val dao: ParameterSchemeDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { "scheme_${UUID.randomUUID()}" },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeForModel(modelKey: String): Flow<List<ParameterScheme>> =
        dao.observeForModel(modelKey).map { rows -> rows.map { it.toScheme() } }

    suspend fun create(modelKey: String, name: String, values: SamplingValues): ParameterScheme {
        val now = clock()
        val scheme = ParameterScheme(newId(), modelKey, name.trim(), values, now, now)
        dao.upsert(scheme.toEntity())
        return scheme
    }

    /** 覆盖方案的参数值；方案已删除时返回 false。 */
    suspend fun overwrite(id: String, values: SamplingValues): Boolean {
        val current = dao.getById(id) ?: return false
        dao.upsert(current.copy(params = json.encodeToString(SamplingValues.serializer(), values), updatedAt = clock()))
        return true
    }

    suspend fun rename(id: String, name: String): Boolean {
        val current = dao.getById(id) ?: return false
        dao.upsert(current.copy(name = name.trim(), updatedAt = clock()))
        return true
    }

    suspend fun delete(id: String) = dao.delete(id)

    private fun ParameterSchemeEntity.toScheme() = ParameterScheme(
        id = id,
        modelKey = modelKey,
        name = name,
        values = try {
            json.decodeFromString(SamplingValues.serializer(), params)
        } catch (_: SerializationException) {
            SamplingValues()
        } catch (_: IllegalArgumentException) {
            SamplingValues()
        },
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun ParameterScheme.toEntity() = ParameterSchemeEntity(
        id = id,
        modelKey = modelKey,
        name = name,
        params = json.encodeToString(SamplingValues.serializer(), values),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
