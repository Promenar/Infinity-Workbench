package com.promenar.nexara.data.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.repository.IVectorRepository
import com.promenar.nexara.domain.repository.VectorSessionCount
import com.promenar.nexara.domain.repository.VectorTypeCount
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class VectorStatsServiceTest {

    private lateinit var repository: IVectorRepository
    private lateinit var service: VectorStatsService

    @BeforeEach
    fun setUp() {
        repository = mockk(relaxed = true)
        service = VectorStatsService(repository)
    }

    @Nested
    inner class GetStats {

        @Test
        fun `aggregates repository data correctly`() = runTest {
            coEvery { repository.getCount() } returns 150
            coEvery { repository.countByType() } returns listOf(
                VectorTypeCount("memory", 50),
                VectorTypeCount("summary", 30),
                VectorTypeCount("doc", 70)
            )
            coEvery { repository.countBySession(10) } returns listOf(
                VectorSessionCount("s1", 80),
                VectorSessionCount("s2", 70)
            )

            val stats = service.getStats()

            assertThat(stats.total).isEqualTo(150)
            assertThat(stats.byType.memory).isEqualTo(50)
            assertThat(stats.byType.summary).isEqualTo(30)
            assertThat(stats.byType.doc).isEqualTo(70)
            assertThat(stats.bySession).hasSize(2)
            assertThat(stats.bySession[0].sessionId).isEqualTo("s1")
            assertThat(stats.bySession[0].count).isEqualTo(80)
            assertThat(stats.bySession[1].sessionId).isEqualTo("s2")
            assertThat(stats.bySession[1].count).isEqualTo(70)
            assertThat(stats.redundancyRate).isEqualTo(0f)
        }

        @Test
        fun `computes storageSizeMb correctly`() = runTest {
            coEvery { repository.getCount() } returns 512
            coEvery { repository.countByType() } returns emptyList()
            coEvery { repository.countBySession(10) } returns emptyList()

            val stats = service.getStats()

            assertThat(stats.storageSizeMb).isEqualTo(1.0f)
        }

        @Test
        fun `storageSizeMb formula is total times 2 divided by 1024`() = runTest {
            coEvery { repository.getCount() } returns 100
            coEvery { repository.countByType() } returns emptyList()
            coEvery { repository.countBySession(10) } returns emptyList()

            val stats = service.getStats()

            val expected = (100.toFloat() * 2) / 1024
            assertThat(stats.storageSizeMb).isEqualTo(expected)
        }

        @Test
        fun `handles empty repository`() = runTest {
            coEvery { repository.getCount() } returns 0
            coEvery { repository.countByType() } returns emptyList()
            coEvery { repository.countBySession(10) } returns emptyList()

            val stats = service.getStats()

            assertThat(stats.total).isEqualTo(0)
            assertThat(stats.byType.memory).isEqualTo(0)
            assertThat(stats.byType.summary).isEqualTo(0)
            assertThat(stats.byType.doc).isEqualTo(0)
            assertThat(stats.bySession).isEmpty()
            assertThat(stats.storageSizeMb).isEqualTo(0f)
        }

        @Test
        fun `handles case-insensitive type matching`() = runTest {
            coEvery { repository.getCount() } returns 10
            coEvery { repository.countByType() } returns listOf(
                VectorTypeCount("Memory", 3),
                VectorTypeCount("SUMMARY", 2),
                VectorTypeCount("Doc", 5)
            )
            coEvery { repository.countBySession(10) } returns emptyList()

            val stats = service.getStats()

            assertThat(stats.byType.memory).isEqualTo(3)
            assertThat(stats.byType.summary).isEqualTo(2)
            assertThat(stats.byType.doc).isEqualTo(5)
        }
    }
}
