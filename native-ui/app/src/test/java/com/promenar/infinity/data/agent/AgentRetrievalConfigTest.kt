package com.promenar.infinity.data.agent

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.Converters
import com.promenar.infinity.data.mapper.AgentMapper
import com.promenar.infinity.data.model.Agent as SerializedAgent
import com.promenar.infinity.domain.model.Agent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AgentRetrievalConfigTest {
    @Test
    fun `旧配置缺少新字段时使用自动检索与按需申请`() {
        val config = requireNotNull(Converters.toRetrievalConfig("""{"memoryLimit":8}"""))

        assertThat(config.memoryLimit).isEqualTo(8)
        assertThat(config.retrievalMode).isEqualTo(AgentRetrievalMode.AUTO)
        assertThat(config.knowledgeFileAccess).isEqualTo(KnowledgeFileAccess.ON_REQUEST)
    }

    @Test
    fun `整体配置为null或省略时可按默认配置读取`() {
        val json = Json { ignoreUnknownKeys = true }
        for (source in listOf("""{"id":"old","name":"旧智能体"}""",
            """{"id":"old","name":"旧智能体","retrievalConfig":null}""")) {
            val agent = json.decodeFromString<SerializedAgent>(source)
            assertThat(agent.retrievalConfig).isNull()
            val effective = agent.retrievalConfig ?: AgentRetrievalConfig()
            assertThat(effective.retrievalMode).isEqualTo(AgentRetrievalMode.AUTO)
            assertThat(effective.knowledgeFileAccess).isEqualTo(KnowledgeFileAccess.ON_REQUEST)
        }
        assertThat(Converters.toRetrievalConfig(null)).isNull()
        assertThat(Converters.fromRetrievalConfig(null)).isNull()
    }

    @Test
    fun `全部检索与文件访问组合经过数据库转换和模型映射往返保持一致`() {
        for (mode in AgentRetrievalMode.entries) {
            for (access in KnowledgeFileAccess.entries) {
                val config = AgentRetrievalConfig(retrievalMode = mode, knowledgeFileAccess = access)
                val domain = Agent("agent", "智能体", retrievalConfig = config)
                val entity = AgentMapper.toEntity(domain)
                val restored = AgentMapper.toDomain(entity.copy(retrievalConfig =
                    Converters.toRetrievalConfig(Converters.fromRetrievalConfig(entity.retrievalConfig))))

                assertThat(restored.retrievalConfig).isEqualTo(config)
            }
        }
    }

    @Test
    fun `新字段序列化名称与枚举名称稳定`() {
        val config = AgentRetrievalConfig(retrievalMode = AgentRetrievalMode.BOTH,
            knowledgeFileAccess = KnowledgeFileAccess.ALWAYS)
        val encoded = Json.parseToJsonElement(requireNotNull(Converters.fromRetrievalConfig(config))).jsonObject

        assertThat(encoded["retrievalMode"]!!.jsonPrimitive.content).isEqualTo("BOTH")
        assertThat(encoded["knowledgeFileAccess"]!!.jsonPrimitive.content).isEqualTo("ALWAYS")
    }
}
