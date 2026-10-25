package com.promenar.nexara.data.linux

/** 调用方在 IO 线程执行；分块避免占满有界 ByteQueue，结束后清除临时输入。 */
internal fun writeLinuxPtyInput(
    text: String,
    running: () -> Boolean,
    write: (ByteArray, Int, Int) -> Unit,
) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    try {
        var offset = 0
        while (offset < bytes.size && running()) {
            val count = minOf(1_024, bytes.size - offset)
            write(bytes, offset, count)
            offset += count
        }
        check(offset == bytes.size) { "Terminal exited before input was fully submitted" }
    } finally {
        bytes.fill(0)
    }
}
