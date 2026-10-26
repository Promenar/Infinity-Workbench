package com.promenar.nexara.data.memory

import android.content.SharedPreferences
import com.promenar.nexara.data.local.db.NexaraDatabase
import com.promenar.nexara.data.model.Session
import com.promenar.nexara.data.model.toDomain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LongTermMemoryPreferences {
    const val KEY = "long_term_memory_enabled"
    val TOOL_IDS: Set<String> = setOf("memory_save", "memory_forget", "memory_read")
    fun isEnabled(settings: SharedPreferences): Boolean = settings.getBoolean(KEY, true)
    fun setEnabled(settings: SharedPreferences, enabled: Boolean) { settings.edit().putBoolean(KEY, enabled).apply() }
}

interface LongTermMemoryAccess {
    suspend fun isEnabled(session: Session): Boolean
    suspend fun isEnabled(sessionId: String): Boolean
}

/** 工具续轮与真正执行前都读取当前助手隐私设置，不复用旧的可见性快照。 */
class RoomLongTermMemoryAccess(
    private val database: NexaraDatabase,
    private val settings: SharedPreferences,
    private val liveSession: (String) -> Session? = { null },
) : LongTermMemoryAccess {
    override suspend fun isEnabled(session: Session): Boolean = withContext(Dispatchers.IO) {
        val current = liveSession(session.id) ?: session
        session.options.toolsEnabled && current.options.toolsEnabled && LongTermMemoryPreferences.isEnabled(settings) &&
            (current.agentId.isBlank() || database.agentDao().getById(current.agentId)
                ?.retrievalConfig?.memoryPolicy?.shareWithOtherAgents != false)
    }
    override suspend fun isEnabled(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val session = liveSession(sessionId) ?: database.sessionDao().getById(sessionId)?.toDomain()
        session != null && isEnabled(session)
    }
}

/** 兼容同步工具广告接口；调用方需在生成/执行工作线程使用，此桥会等待 IO 策略读取。 */
fun LongTermMemoryAccess.isEnabledForToolAdvertisement(session: Session): Boolean =
    kotlinx.coroutines.runBlocking(Dispatchers.IO) { isEnabled(session) }
