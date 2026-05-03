package com.promenar.nexara.data.local.db

import androidx.room.TypeConverter
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
}
