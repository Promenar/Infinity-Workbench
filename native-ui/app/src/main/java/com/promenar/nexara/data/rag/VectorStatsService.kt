package com.promenar.nexara.data.rag

import com.promenar.nexara.domain.repository.IVectorRepository

class VectorStatsService(
    private val vectorRepository: IVectorRepository
) {
    suspend fun getStats(): VectorStats {
        val total = vectorRepository.getCount()
        val typeCounts = vectorRepository.countByType()
        val byType = VectorTypeStats()

        for (tc in typeCounts) {
            val type = tc.type.trim().lowercase()
            when (type) {
                "memory" -> byType.memory = tc.count
                "summary" -> byType.summary = tc.count
                "doc" -> byType.doc = tc.count
            }
        }

        val sessionCounts = vectorRepository.countBySession(10)
        val bySession = sessionCounts.map { sc ->
            SessionVectorCount(sessionId = sc.sessionId, count = sc.count)
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
