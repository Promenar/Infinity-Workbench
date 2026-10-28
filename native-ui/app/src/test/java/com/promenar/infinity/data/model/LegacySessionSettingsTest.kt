package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Test

class LegacySessionSettingsTest {
    @Test fun `完整旧会话在严格和数据库解码入口均兼容且默认保留联网搜索`() {
        val legacy = """{
            "id":"s","agentId":"a",
            "options":{"webSearch":true,"enableGeminiSearch":true,"toolsEnabled":false},
            "inferenceParams":{"streamTimeout":9},
            "ragOptions":{"memoryLimit":1,"memoryThreshold":0.1,"docLimit":2,"docThreshold":0.2,
                "rerankTopK":3,"rerankFinalK":4,"enableQueryRewrite":false,"queryRewriteStrategy":"hyde",
                "queryRewriteCount":1,"enableHybridSearch":false,"hybridAlpha":0.1,"hybridBM25Boost":9.0}
        }"""
        val strict = Json.decodeFromString<Session>(legacy)
        val decoded = json.decodeFromString<Session>(legacy)
        assertThat(decoded.options.keepWebSearchWhenToolsOff).isTrue()
        assertThat(strict.options.keepWebSearchWhenToolsOff).isTrue()
        assertThat(decoded.toEntity().toDomain().options.keepWebSearchWhenToolsOff).isTrue()
        assertThat(decoded.inferenceParams!!.streamTimeout).isEqualTo(9)
        assertThat(decoded.ragOptions!!.hybridBM25Boost).isEqualTo(9f)
        assertThat(Json.decodeFromString<SessionOptions>("""{"keepWebSearchWhenToolsOff":false}""")
            .keepWebSearchWhenToolsOff).isFalse()
    }
}
