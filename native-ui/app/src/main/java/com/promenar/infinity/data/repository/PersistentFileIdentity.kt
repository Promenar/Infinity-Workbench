package com.promenar.infinity.data.repository

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.security.MessageDigest
import java.util.UUID

/** 跨进程身份只编码随机 nonce 与 inode；设备号只可用于存活句柄的瞬时校验。 */
object PersistentFileIdentity {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val identity = Regex("nx1:(${uuid.pattern})[|][0-9a-f]{64}")
    private val legacyFileKey = Regex("\\(dev=[0-9a-fA-F]+,ino=[0-9]+\\)")
    private const val ATTRIBUTE = "infinity_persistent_identity"
    private const val LEGACY_ATTRIBUTE = "nexara_persistent_identity"

    fun inode(fileKey: Any?): String {
        val raw = fileKey?.toString() ?: throw SecurityException("文件系统缺少 inode 身份")
        val value = Regex("(?:\\(dev=[^,()]+,ino=|ino=)([0-9]+)\\)?").matchEntire(raw)?.groupValues?.get(1)
            ?: throw SecurityException("文件系统 inode 身份格式无效")
        val number = value.toBigInteger()
        if (number.signum() <= 0 || number.bitLength() > 64) throw SecurityException("文件系统 inode 身份无效")
        return "ino=$number"
    }

    fun encode(nonce: String, fileKey: Any?): String {
        requireNonce(nonce)
        return "nx1:$nonce|${digest(inode(fileKey))}"
    }

    fun nonce(value: String): String = identity.matchEntire(value)?.groupValues?.get(1)
        ?: throw SecurityException("持久身份格式无效")

    fun isValid(value: String): Boolean = identity.matches(value)

    fun marker(nonce: String, fileKey: Any?): String {
        requireNonce(nonce)
        return "nx1:$nonce\n${inode(fileKey)}"
    }

    fun parseMarker(value: String): Pair<String, String> {
        parseLegacyMarker(value)?.let { return it.first to inode(it.second) }
        val parts = value.split('\n')
        if (parts.size != 2 || !parts[0].startsWith("nx1:")) throw SecurityException("持久身份标记格式无效")
        val nonce = parts[0].removePrefix("nx1:")
        requireNonce(nonce)
        val key = inode(parts[1])
        if (key != parts[1]) throw SecurityException("持久身份 inode 编码无效")
        return nonce to key
    }

    /** 保留旧标记的完整 fileKey 原文，供旧数据库摘要核验；持久目录比较只使用 inode。 */
    internal fun parseLegacyMarker(value: String): Pair<String, String>? {
        val parts = value.split('\n')
        if (parts.size != 2 || !uuid.matches(parts[0]) || !legacyFileKey.matches(parts[1])) return null
        try { inode(parts[1]) } catch (_: SecurityException) { return null }
        return parts[0] to parts[1]
    }

    fun verifyMarker(value: String, fileKey: Any?): String {
        val proof = parseMarker(value)
        if (proof.second != inode(fileKey)) throw SecurityException("持久身份标记与 inode 不匹配")
        return encode(proof.first, fileKey)
    }

    /** 不支持 nonce 属性时失败关闭，不用路径、设备号或时间戳替代目录身份。 */
    fun read(path: Path): String {
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (before.isSymbolicLink || (!before.isDirectory && !before.isRegularFile)) {
            throw SecurityException("持久身份只接受普通文件或目录")
        }
        val result = if (System.getProperty("java.vm.name") == "Dalvik") {
            val fd = android.system.Os.open(path.toString(), android.system.OsConstants.O_RDONLY or
                android.system.OsConstants.O_NOFOLLOW or android.system.OsConstants.O_NONBLOCK or android.system.OsConstants.O_CLOEXEC, 0)
            try {
                android.os.ParcelFileDescriptor.dup(fd).use { descriptor ->
                    val opened = Files.readAttributes(java.nio.file.Paths.get("/proc/self/fd/${descriptor.fd}"), BasicFileAttributes::class.java)
                    if (opened.fileKey() != before.fileKey()) throw SecurityException("持久身份节点在打开期间被替换")
                    readFd(descriptor.fd, opened.fileKey())
                }
            } finally { android.system.Os.close(fd) }
        } else {
            val view = Files.getFileAttributeView(path, UserDefinedFileAttributeView::class.java, NOFOLLOW_LINKS)
                ?: throw UnsupportedOperationException("文件系统不支持持久 nonce 属性")
            synchronized(this) {
                val attributes = view.list()
                if (ATTRIBUTE !in attributes && LEGACY_ATTRIBUTE !in attributes) {
                    view.write(ATTRIBUTE, ByteBuffer.wrap(UUID.randomUUID().toString().toByteArray(Charsets.US_ASCII)))
                }
                fun readAttribute(name: String): String {
                    if (view.size(name) != 36) throw SecurityException("持久 nonce 属性长度无效")
                    val bytes = ByteBuffer.allocate(36)
                    if (view.read(name, bytes) != 36) throw SecurityException("持久 nonce 属性读取不完整")
                    return String(bytes.array(), Charsets.US_ASCII)
                }
                val current = if (ATTRIBUTE in view.list()) readAttribute(ATTRIBUTE) else null
                val legacy = if (LEGACY_ATTRIBUTE in attributes) readAttribute(LEGACY_ATTRIBUTE) else null
                if (current != null && legacy != null && current != legacy) throw SecurityException("新旧持久 nonce 属性冲突")
                encode(current ?: legacy ?: throw SecurityException("持久 nonce 属性缺失"), before.fileKey())
            }
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (before.fileKey() != after.fileKey()) throw SecurityException("持久身份节点在读取期间被替换")
        return result
    }

    /** 只接收调用者持有的 FD；nonce 位于独立属性，不覆盖创建归属令牌。 */
    internal fun readFd(fd: Int, fileKey: Any?): String {
        val nonce = claimPersistentNonce(
            read = { AndroidWorkspaceFileIdentity.readPersistent(fd) },
            create = { AndroidWorkspaceFileIdentity.createPersistent(fd, it) },
            sync = { android.os.ParcelFileDescriptor.fromFd(fd).use { android.system.Os.fsync(it.fileDescriptor) } },
        )
        return encode(nonce, fileKey)
    }

    /** 读取方也同步可见属性，覆盖另一进程写入后尚未完成 fsync 的窗口。 */
    internal fun claimPersistentNonce(
        read: () -> String?,
        create: (String) -> Unit,
        sync: () -> Unit,
        newNonce: () -> String = { UUID.randomUUID().toString() },
    ): String {
        var nonce = read()
        if (nonce == null) {
            val candidate = newNonce()
            try { create(candidate) }
            catch (failure: java.io.IOException) {
                nonce = read() ?: throw failure
                if (nonce == candidate) throw failure
            }
            nonce = nonce ?: read()
        }
        val actual = nonce ?: throw SecurityException("持久 nonce 属性写入后不可读")
        requireNonce(actual)
        sync()
        return actual
    }

    private fun requireNonce(value: String) {
        if (!uuid.matches(value)) throw SecurityException("持久 nonce 格式无效")
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
