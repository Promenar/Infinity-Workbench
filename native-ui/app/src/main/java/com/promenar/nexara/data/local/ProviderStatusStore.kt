package com.promenar.nexara.data.local

import android.content.SharedPreferences
import com.promenar.nexara.data.remote.provider.ProviderConnectionProbeFailure
import com.promenar.nexara.data.remote.provider.ProviderConnectionProbeResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class ProviderConnectionOutcome { SUCCESS, UNSUPPORTED, FAILED }

data class ProviderConnectionRecord(
    val outcome: ProviderConnectionOutcome,
    val failure: ProviderConnectionProbeFailure? = null,
    val statusCode: Int? = null,
    val testedAtMillis: Long,
)

data class ProviderStatus(
    val lastConnection: ProviderConnectionRecord? = null,
    val lastModelSyncAtMillis: Long? = null,
)

class ProviderStatusStore(
    private val preferences: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _statuses = MutableStateFlow(readStatuses())
    val statuses: StateFlow<Map<String, ProviderStatus>> = _statuses.asStateFlow()

    fun status(providerId: String): ProviderStatus = _statuses.value[providerId] ?: ProviderStatus()

    @Synchronized
    fun recordConnectionTest(providerId: String, result: ProviderConnectionProbeResult) {
        val record = when (result) {
            ProviderConnectionProbeResult.Success -> ProviderConnectionRecord(
                ProviderConnectionOutcome.SUCCESS,
                testedAtMillis = clock(),
            )
            ProviderConnectionProbeResult.Unsupported -> ProviderConnectionRecord(
                ProviderConnectionOutcome.UNSUPPORTED,
                testedAtMillis = clock(),
            )
            is ProviderConnectionProbeResult.Failure -> ProviderConnectionRecord(
                ProviderConnectionOutcome.FAILED,
                failure = result.reason,
                statusCode = result.statusCode,
                testedAtMillis = clock(),
            )
        }
        persist(_statuses.value + (providerId to status(providerId).copy(lastConnection = record)))
    }

    @Synchronized
    fun recordModelSync(providerId: String) {
        persist(_statuses.value + (providerId to status(providerId).copy(lastModelSyncAtMillis = clock())))
    }

    /** 连接参数已改而未重新测试时，清掉过期的连接记录，保留同步时间。 */
    @Synchronized
    fun clearConnectionTest(providerId: String) {
        val current = _statuses.value[providerId] ?: return
        if (current.lastConnection == null) return
        val next = current.copy(lastConnection = null)
        persist(if (next.lastModelSyncAtMillis == null) _statuses.value - providerId else _statuses.value + (providerId to next))
    }

    @Synchronized
    fun remove(providerId: String) {
        if (providerId !in _statuses.value) return
        persist(_statuses.value - providerId)
    }

    @Synchronized
    fun retainOnly(providerIds: Set<String>) {
        val current = _statuses.value
        if (current.keys.all { it in providerIds }) return
        persist(current.filterKeys { it in providerIds })
    }

    // 写盘顺序与发布顺序共用方法锁，避免并发记录互相覆盖或落盘旧快照。
    private fun persist(values: Map<String, ProviderStatus>) {
        val editor = preferences.edit()
        if (values.isEmpty()) {
            editor.remove("statuses")
        } else {
            val payload = buildJsonObject {
                values.forEach { (providerId, status) ->
                    put(providerId, buildJsonObject {
                        status.lastConnection?.let { record ->
                            put("lastConnection", buildJsonObject {
                                put("outcome", record.outcome.name)
                                record.failure?.let { put("failure", it.name) }
                                record.statusCode?.let { put("statusCode", it) }
                                put("testedAtMillis", record.testedAtMillis)
                            })
                        }
                        status.lastModelSyncAtMillis?.let { put("lastModelSyncAtMillis", it) }
                    })
                }
            }
            editor.putString("statuses", payload.toString())
        }
        editor.apply()
        _statuses.value = values
    }

    private fun readStatuses(): Map<String, ProviderStatus> = runCatching {
        val raw = preferences.getString("statuses", null) ?: return emptyMap()
        val values = Json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
        values.mapNotNull { (providerId, value) ->
            val fields = value as? JsonObject ?: return@mapNotNull null
            val connection = readConnection(fields["lastConnection"])
            val syncAt = (fields["lastModelSyncAtMillis"] as? JsonPrimitive)?.longOrNull
            if (connection == null && syncAt == null) null
            else providerId to ProviderStatus(connection, syncAt)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun readConnection(value: JsonElement?): ProviderConnectionRecord? {
        val fields = value as? JsonObject ?: return null
        val outcome = enumValue<ProviderConnectionOutcome>(fields["outcome"]) ?: return null
        val testedAt = (fields["testedAtMillis"] as? JsonPrimitive)?.longOrNull ?: return null
        val failure = if (outcome == ProviderConnectionOutcome.FAILED) {
            enumValue<ProviderConnectionProbeFailure>(fields["failure"]) ?: return null
        } else null
        val statusCode = if (outcome == ProviderConnectionOutcome.FAILED) {
            fields["statusCode"]?.takeUnless { it == JsonNull }?.let {
                (it as? JsonPrimitive)?.intOrNull ?: return null
            }
        } else null
        return ProviderConnectionRecord(outcome, failure, statusCode, testedAt)
    }

    private inline fun <reified T : Enum<T>> enumValue(value: JsonElement?): T? {
        val name = (value as? JsonPrimitive)?.contentOrNull ?: return null
        return enumValues<T>().firstOrNull { it.name == name }
    }
}
