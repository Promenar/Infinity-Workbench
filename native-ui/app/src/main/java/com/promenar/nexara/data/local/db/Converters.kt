package com.promenar.nexara.data.local.db

import androidx.room.TypeConverter
import com.promenar.nexara.data.agent.AgentRagConfig
import com.promenar.nexara.data.agent.AgentRetrievalConfig
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

object Converters {
    @TypeConverter
    @JvmStatic
    fun floatArrayToBytes(value: FloatArray?): ByteArray? {
        if (value == null) return null
        val buffer = ByteBuffer.allocate(value.size * 4)
        buffer.asFloatBuffer().put(value)
        return buffer.array()
    }

    @TypeConverter
    @JvmStatic
    fun bytesToFloatArray(value: ByteArray?): FloatArray? {
        if (value == null) return null
        val buffer = ByteBuffer.wrap(value)
        val floatBuffer = buffer.asFloatBuffer()
        val result = FloatArray(floatBuffer.remaining())
        floatBuffer.get(result)
        return result
    }

    @TypeConverter
    @JvmStatic
    fun fromRagConfig(value: AgentRagConfig?): String? {
        return value?.let { Json.encodeToString(AgentRagConfig.serializer(), it) }
    }

    @TypeConverter
    @JvmStatic
    fun toRagConfig(value: String?): AgentRagConfig? {
        return value?.let { Json.decodeFromString(AgentRagConfig.serializer(), it) }
    }

    @TypeConverter
    @JvmStatic
    fun fromRetrievalConfig(value: AgentRetrievalConfig?): String? {
        return value?.let { Json.encodeToString(AgentRetrievalConfig.serializer(), it) }
    }

    @TypeConverter
    @JvmStatic
    fun toRetrievalConfig(value: String?): AgentRetrievalConfig? {
        return value?.let { Json.decodeFromString(AgentRetrievalConfig.serializer(), it) }
    }
}
