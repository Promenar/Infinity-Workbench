package com.promenar.nexara.data.rag

import com.promenar.nexara.data.local.db.dao.VectorDao

class VectorStatsService(
    private val vectorDao: VectorDao
) {
    suspend fun getStats(): VectorStats {
        val total = vectorDao.getCount()
        val typeCounts = vectorDao.countByType()

        val byType = VectorTypeStats()
        for (tc in typeCounts) {
            when (tc.type) {
                "memory" -> byType.memory = tc.count
                "summary" -> byType.summary = tc.count
                "doc" -> byType.doc = tc.count
            }
        }

        val sessionCounts = vectorDao.countBySession(10)
        val bySession = sessionCounts.map { sc ->
            SessionVectorCount(sessionId = sc.session_id, count = sc.count)
        }

        val storageSizeMb = (total.toFloat() * 2) / 1024

        return VectorStats(
            total = total,
            byType = byType,
            bySession = bySession,
            redundancyRate = 0f,
            storageSizeMb = storageSizeMb
        )
    }
}
