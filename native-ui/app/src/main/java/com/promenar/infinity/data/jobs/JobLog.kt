package com.promenar.infinity.data.jobs

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 只维护当前日志和一个旧文件；固定大小块直接写盘。 */
class RotatingJobLog(private val file: File, private val limitBytes: Long = 20L * 1024 * 1024) : Closeable {
    private var output: FileOutputStream
    private var size: Long
    init {
        require(limitBytes > 0)
        file.parentFile?.mkdirs()
        size = file.length()
        output = FileOutputStream(file, true)
    }
    @Synchronized fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (size >= limitBytes) {
                output.close()
                Files.move(file.toPath(), File(file.path + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING)
                output = FileOutputStream(file)
                size = 0
            }
            val count = minOf((bytes.size - offset).toLong(), limitBytes - size).toInt()
            output.write(bytes, offset, count)
            output.flush()
            offset += count
            size += count
        }
    }
    @Synchronized override fun close() = output.close()
}

data class JobLogTail(val text: String, val truncated: Boolean)

/** 读盘上限为字符预算四倍，避免为大日志分配完整字符串。 */
fun readJobLogTail(file: File, limit: Int = 8_000): JobLogTail {
    if (!file.isFile) return JobLogTail("", false)
    return RandomAccessFile(file, "r").use { input ->
        val size = input.length()
        val bytes = ByteArray(minOf(size, limit * 4L + 4).toInt())
        input.seek(size - bytes.size)
        input.readFully(bytes)
        var text = bytes.toString(Charsets.UTF_8)
        val truncated = size > bytes.size || text.length > limit || File(file.path + ".1").exists()
        if (text.length > limit) text = text.takeLast(limit).dropWhile { Character.isLowSurrogate(it) }
        JobLogTail(text, truncated)
    }
}
