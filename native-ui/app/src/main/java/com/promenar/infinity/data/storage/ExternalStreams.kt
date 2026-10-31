package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalFolderBackend
import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val EXTERNAL_STREAM_BUFFER = 64 * 1024
internal fun MessageDigest.externalHex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 255) }

internal suspend fun externalStreamHash(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(EXTERNAL_STREAM_BUFFER)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.externalHex()
}

internal suspend fun ExternalFolderBackend.streamHash(path: String): String = readStream(path, Long.MAX_VALUE, ::externalStreamHash)

/** 写入时校验精确长度并计算写出摘要，缓冲区大小与文件大小无关。 */
internal suspend fun produceExternalStream(output: OutputStream, expectedSize: Long, produce: suspend (OutputStream) -> Unit): String {
    if (expectedSize < 0) throw ExternalFolderException("INVALID_ARGUMENT", "文件大小无效")
    val context = currentCoroutineContext()
    val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
    val checked = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            context.ensureActive()
            if (length.toLong() > expectedSize - count) throw ExternalFolderException("CONFLICT", "来源大小在复制期间发生变化")
            output.write(bytes, offset, length)
            digest.update(bytes, offset, length)
            count += length
        }
        override fun flush() = output.flush()
    }
    produce(checked)
    context.ensureActive()
    checked.flush()
    if (count != expectedSize) throw ExternalFolderException("CONFLICT", "来源大小在复制期间发生变化")
    return digest.externalHex()
}

internal suspend fun copyExternalStream(input: InputStream, output: OutputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(EXTERNAL_STREAM_BUFFER)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
    }
    return digest.externalHex()
}

/** 调用方持有后端锁和已校验的读取句柄；回调可能写入同一后端，结束后重新持锁复核版本。 */
internal suspend fun <T> kotlinx.coroutines.sync.Mutex.consumeUnlocked(block: suspend () -> T): T {
    unlock()
    try { return block() }
    finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { lock() } }
}
