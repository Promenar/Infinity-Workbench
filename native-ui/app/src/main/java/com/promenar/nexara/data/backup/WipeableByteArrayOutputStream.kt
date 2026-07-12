package com.promenar.nexara.data.backup

import java.io.ByteArrayOutputStream

/** ByteArrayOutputStream.close/reset 不会擦除内部缓冲，此类型为敏感备份字节提供显式清零。 */
internal class WipeableByteArrayOutputStream(initialSize: Int = 32) : ByteArrayOutputStream(initialSize) {
    fun wipe() {
        buf.fill(0)
        reset()
    }

    override fun close() {
        wipe()
        super.close()
    }
}
