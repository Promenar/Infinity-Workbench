package com.promenar.nexara.domain.usecase

import android.content.SharedPreferences
import com.promenar.nexara.data.agent.AgentRagConfig
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RagConfigPersistenceTest {

    private fun emptyPrefs(): SharedPreferences {
        val p = mockk<SharedPreferences>(relaxed = true)
        every { p.getInt(any(), any()) } answers { secondArg() }
        every { p.getString(any(), any()) } answers { secondArg() }
        every { p.getFloat(any(), any()) } answers { secondArg() }
        every { p.getBoolean(any(), any()) } answers { secondArg() }
        return p
    }

    @Test
    fun `loadRagConfig returns defaults when prefs empty`() {
        val config = RagConfigPersistence(emptyPrefs()).loadRagConfig()
        assertThat(config.docChunkSize).isEqualTo(800)
        assertThat(config.chunkOverlap).isEqualTo(100)
        assertThat(config.memoryChunkSize).isEqualTo(1000)
        assertThat(config.contextWindow).isEqualTo(20)
        assertThat(config.summaryThreshold).isEqualTo(10)
    }

    @Test
    fun `loadRetrievalConfig returns defaults when prefs empty`() {
        val config = RagConfigPersistence(emptyPrefs()).loadRetrievalConfig()
        assertThat(config.memoryLimit).isEqualTo(5)
        assertThat(config.memoryThreshold).isWithin(0.001f).of(0.7f)
        assertThat(config.docLimit).isEqualTo(8)
        assertThat(config.enableMemory).isTrue()
        assertThat(config.enableDocs).isTrue()
        assertThat(config.enableKnowledgeGraph).isFalse()
    }

    @Test
    fun `loadRagConfig reads from prefs`() {
        val p = mockk<SharedPreferences>(relaxed = true)
        every { p.getInt(RagConfigPersistence.KEY_CHUNK_SIZE, 800) } returns 600
        every { p.getInt(RagConfigPersistence.KEY_CHUNK_OVERLAP, 100) } returns 50
        every { p.getInt(RagConfigPersistence.KEY_MEMORY_CHUNK_SIZE, 1000) } returns 800
        every { p.getInt(RagConfigPersistence.KEY_CONTEXT_WINDOW, 20) } returns 16
        every { p.getInt(RagConfigPersistence.KEY_SUMMARY_THRESHOLD, 10) } returns 8
        every { p.getString(any(), any()) } answers { secondArg() }
        every { p.getFloat(any(), any()) } answers { secondArg() }
        every { p.getBoolean(any(), any()) } answers { secondArg() }

        val config = RagConfigPersistence(p).loadRagConfig()
        assertThat(config.docChunkSize).isEqualTo(600)
        assertThat(config.chunkOverlap).isEqualTo(50)
        assertThat(config.memoryChunkSize).isEqualTo(800)
        assertThat(config.contextWindow).isEqualTo(16)
        assertThat(config.summaryThreshold).isEqualTo(8)
    }

    @Test
    fun `loadRetrievalConfig reads from prefs`() {
        // Mock all keys explicitly — avoid generic any() which conflicts with specific rules in MockK
        val p = mockk<SharedPreferences>(relaxed = true)
        // Custom values for keys we want to verify
        every { p.getInt(RagConfigPersistence.KEY_MEMORY_LIMIT, 5) } returns 15
        every { p.getFloat(RagConfigPersistence.KEY_MEMORY_THRESHOLD, 0.7f) } returns 0.7f
        every { p.getInt(RagConfigPersistence.KEY_DOC_LIMIT, 8) } returns 20
        every { p.getFloat(RagConfigPersistence.KEY_DOC_THRESHOLD, 0.45f) } returns 0.45f
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_RERANK, false) } returns true
        every { p.getInt(RagConfigPersistence.KEY_RERANK_TOP_K, 30) } returns 30
        every { p.getInt(RagConfigPersistence.KEY_RERANK_FINAL_K, 5) } returns 5
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_QUERY_REWRITE, false) } returns false
        every { p.getString(RagConfigPersistence.KEY_QUERY_REWRITE_STRATEGY, "multi-query") } returns "multi-query"
        every { p.getInt(RagConfigPersistence.KEY_QUERY_REWRITE_COUNT, 3) } returns 3
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_HYBRID_SEARCH, true) } returns true
        every { p.getFloat(RagConfigPersistence.KEY_HYBRID_ALPHA, 0.6f) } returns 0.8f
        every { p.getFloat(RagConfigPersistence.KEY_HYBRID_BM25_BOOST, 1.0f) } returns 1.0f
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_MEMORY, true) } returns false
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_DOCS, true) } returns true
        every { p.getBoolean(RagConfigPersistence.KEY_ENABLE_KG, false) } returns false
        every { p.getString(RagConfigPersistence.KEY_QUERY_REWRITE_MODEL, null as String?) } returns null
        every { p.getString(RagConfigPersistence.KEY_KG_MODEL, null as String?) } returns null
        every { p.getString(RagConfigPersistence.KEY_KG_PROMPT, null as String?) } returns null
        every { p.getBoolean(RagConfigPersistence.KEY_KG_FREE_MODE, false) } returns false
        every { p.getBoolean(RagConfigPersistence.KEY_KG_DOMAIN_AUTO, false) } returns false
        every { p.getInt(RagConfigPersistence.KEY_JIT_MAX_CHUNKS, 0) } returns 0

        val config = RagConfigPersistence(p).loadRetrievalConfig()
        assertThat(config.memoryLimit).isEqualTo(15)
        assertThat(config.docLimit).isEqualTo(20)
        assertThat(config.enableRerank).isTrue()
        assertThat(config.enableMemory).isFalse()
        assertThat(config.enableHybridSearch).isTrue()
        assertThat(config.hybridAlpha).isWithin(0.001f).of(0.8f)
    }

    @Test
    fun `key constants are unique`() {
        val allKeys = setOf(
            RagConfigPersistence.KEY_CHUNK_SIZE, RagConfigPersistence.KEY_CHUNK_OVERLAP,
            RagConfigPersistence.KEY_MEMORY_CHUNK_SIZE, RagConfigPersistence.KEY_CONTEXT_WINDOW,
            RagConfigPersistence.KEY_SUMMARY_THRESHOLD, RagConfigPersistence.KEY_SUMMARY_TEMPLATE,
            RagConfigPersistence.KEY_MEMORY_LIMIT, RagConfigPersistence.KEY_MEMORY_THRESHOLD,
            RagConfigPersistence.KEY_DOC_LIMIT, RagConfigPersistence.KEY_DOC_THRESHOLD,
            RagConfigPersistence.KEY_ENABLE_RERANK, RagConfigPersistence.KEY_RERANK_TOP_K,
            RagConfigPersistence.KEY_RERANK_FINAL_K, RagConfigPersistence.KEY_ENABLE_QUERY_REWRITE,
            RagConfigPersistence.KEY_QUERY_REWRITE_STRATEGY, RagConfigPersistence.KEY_QUERY_REWRITE_COUNT,
            RagConfigPersistence.KEY_QUERY_REWRITE_MODEL, RagConfigPersistence.KEY_ENABLE_HYBRID_SEARCH,
            RagConfigPersistence.KEY_HYBRID_ALPHA, RagConfigPersistence.KEY_HYBRID_BM25_BOOST,
            RagConfigPersistence.KEY_ENABLE_MEMORY, RagConfigPersistence.KEY_ENABLE_DOCS,
            RagConfigPersistence.KEY_ENABLE_KG, RagConfigPersistence.KEY_KG_MODEL,
            RagConfigPersistence.KEY_KG_PROMPT, RagConfigPersistence.KEY_KG_FREE_MODE,
            RagConfigPersistence.KEY_KG_DOMAIN_AUTO, RagConfigPersistence.KEY_JIT_MAX_CHUNKS,
            RagConfigPersistence.KEY_ENABLE_INCREMENTAL_HASH, RagConfigPersistence.KEY_ENABLE_LOCAL_PREPROCESS,
            RagConfigPersistence.KEY_COST_STRATEGY, RagConfigPersistence.KEY_SHOW_RETRIEVAL_PROGRESS,
            RagConfigPersistence.KEY_SHOW_RETRIEVAL_DETAILS, RagConfigPersistence.KEY_TRACK_RETRIEVAL_METRICS
        )
        assertThat(allKeys).hasSize(34)
    }
}
