package com.promenar.nexara.data.local

import com.promenar.nexara.data.model.SessionHeaderMode

/** 模型设置与删除共用串行边界，拒绝已经失效的编辑命令。 */
class SessionHeaderModelEdits(private val preferences: SessionHeaderPreferences, private val exists: (String) -> Boolean) {
    fun update(modelId: String, mode: SessionHeaderMode, metadata: () -> Unit): Unit = synchronized(lock) {
        if (exists(modelId)) {
            preferences.saveModel(modelId, mode)
            metadata()
        }
    }

    fun remove(modelIds: List<String>, delete: () -> Unit): Unit = synchronized(lock) {
        delete()
        modelIds.forEach(preferences::removeModel)
    }

    private companion object { val lock = Any() }
}
