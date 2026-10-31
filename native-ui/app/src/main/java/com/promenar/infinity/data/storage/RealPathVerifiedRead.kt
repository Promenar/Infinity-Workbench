package com.promenar.infinity.data.storage

import com.promenar.infinity.domain.storage.ExternalFolderException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class VerifiedRealPathRead<T>(val value: T, val state: RealPathFileState, val sha256: String)

/** 私有卷严格核对版本；FUSE 属性漂移必须由独立完整读取的摘要证明内容一致。 */
internal suspend fun <T> readVerifiedRealPath(
    parent: BoundRealPathDirectory, name: String, maxBytes: Long = Long.MAX_VALUE,
    consume: suspend (InputStream) -> T,
): VerifiedRealPathRead<T> {
    val pathBefore = parent.stat(name) ?: throw ExternalFolderException("NOT_FOUND", "文件不存在 / File not found")
    parent.openRead(name).use { source ->
        val before = source.state()
        checkRealPathReadState(pathBefore, before, timestamps = !parent.readTimestampsMayDrift)
        if (!before.isRegularFile || before.isSymbolicLink) {
            throw ExternalFolderException("UNSUPPORTED", "仅支持普通文件 / Only regular files are supported")
        }
        if (before.size > maxBytes) throw ExternalFolderException("TOO_LARGE", "文件超过读取上限 / File exceeds read limit")
        val input = HashingRealPathInput(source.input, maxBytes)
        val value = consume(input)
        input.drain()
        val after = source.state()
        val pathAfter = parent.stat(name)
        checkRealPathReadState(before, after, timestamps = !parent.readTimestampsMayDrift)
        checkRealPathReadState(pathBefore, pathAfter, timestamps = !parent.readTimestampsMayDrift)
        checkRealPathReadLength(before.size, input.count)
        val hash = input.hash()
        if (parent.readTimestampsMayDrift &&
            (before.revision != after.revision || pathBefore.revision != pathAfter!!.revision || before.revision != pathBefore.revision)) {
            // 重新打开独立读句柄，不能仅以时间戳不可信为由接受第一次读取。
            var checkpoint = pathAfter!!
            repeat(3) {
                parent.openRead(name).use { verification ->
                    checkRealPathReadState(checkpoint, verification.state(), timestamps = false)
                    val second = HashingRealPathInput(verification.input, maxBytes)
                    second.drain()
                    checkRealPathReadState(checkpoint, verification.state(), timestamps = false)
                    val latest = parent.stat(name)
                    checkRealPathReadState(checkpoint, latest, timestamps = false)
                    checkRealPathReadLength(input.count, second.count)
                    val checkedHash = second.hash()
                    if (hash != checkedHash) realPathReadConflict("sha256=$hash -> $checkedHash")
                    if (checkpoint.revision == latest!!.revision) return VerifiedRealPathRead(value, latest, hash)
                    checkpoint = latest
                }
            }
            realPathReadConflict("mtime=${pathAfter.modifiedTime} -> ${checkpoint.modifiedTime}, ctime=${pathAfter.changedTime} -> ${checkpoint.changedTime}")
        }
        return VerifiedRealPathRead(value, pathAfter!!, hash)
    }
}

internal fun checkRealPathReadState(expected: RealPathFileState, actual: RealPathFileState?, timestamps: Boolean = true) {
    val differences = mutableListOf<String>()
    if (actual == null || actual.objectId != expected.objectId || actual.isDirectory != expected.isDirectory ||
        actual.isRegularFile != expected.isRegularFile || actual.isSymbolicLink != expected.isSymbolicLink) {
        differences += "identity=${expected.objectId} -> ${actual?.objectId ?: "missing"}"
    }
    if (actual != null) {
        if (actual.size != expected.size) differences += "size=${expected.size} -> ${actual.size}"
        if (timestamps && actual.modifiedTime != expected.modifiedTime) differences += "mtime=${expected.modifiedTime} -> ${actual.modifiedTime}"
        if (timestamps && actual.changedTime != expected.changedTime) differences += "ctime=${expected.changedTime} -> ${actual.changedTime}"
        if (timestamps && actual.revision != expected.revision && differences.isEmpty()) differences += "ctime(revision)=${expected.revision} -> ${actual.revision}"
    }
    if (differences.isNotEmpty()) realPathReadConflict(differences.joinToString(", "))
}

private fun checkRealPathReadLength(expected: Long, actual: Long) {
    if (expected != actual) realPathReadConflict("size=$expected -> $actual")
}

internal fun realPathReadConflict(fields: String): Nothing =
    throw ExternalFolderException("CONFLICT", "文件状态不一致 / File state mismatch: $fields")

private class HashingRealPathInput(private val source: InputStream, private val limit: Long) : InputStream() {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set
    override fun read(): Int {
        val value = source.read()
        if (value >= 0) { accept(1); digest.update(value.toByte()) }
        return value
    }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        val read = source.read(bytes, offset, length)
        if (read > 0) { accept(read); digest.update(bytes, offset, read) }
        return read
    }
    private fun accept(length: Int) {
        if (length > limit - count) throw ExternalFolderException("TOO_LARGE", "文件超过读取上限 / File exceeds read limit")
        count += length
    }
    suspend fun drain() {
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (read(buffer) < 0) return
        }
    }
    fun hash(): String = digest.digest().joinToString("") { "%02x".format(it) }
}
