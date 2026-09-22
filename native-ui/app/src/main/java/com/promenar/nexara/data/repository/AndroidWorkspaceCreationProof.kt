package com.promenar.nexara.data.repository

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream

/** 公开 Android FD API 的有界桥接；所有后续段均经 O_NOFOLLOW 打开并固定到存活目录 FD。 */
internal object AndroidWorkspaceCreationProof {
    fun moveNoReplace(
        root: Path, source: List<String>, target: List<String>,
        rootKey: String, sourceParentKey: String, targetParentKey: String,
    ) {
        openDirectory(root.toString()).use { rootFd ->
            requireDirectoryKey(rootFd, rootKey)
            walk(rootFd, source.dropLast(1)).use { sourceFd ->
                walk(rootFd, target.dropLast(1)).use { targetFd ->
                    requireDirectoryKey(sourceFd, sourceParentKey)
                    requireDirectoryKey(targetFd, targetParentKey)
                    AndroidWorkspaceFileMoves.moveNoReplace(sourceFd.fd, source.last(), targetFd.fd, target.last())
                }
            }
        }
    }

    private fun requireDirectoryKey(fd: ParcelFileDescriptor, expected: String) {
        secure(fd).use { stream ->
            val actual = stream.getFileAttributeView(Paths.get("."),
                java.nio.file.attribute.BasicFileAttributeView::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).readAttributes().fileKey()?.toString()
            check(actual == expected) { "工作区移动目录能力已替换" }
        }
    }

    fun <T> withFile(
        root: Path,
        node: List<String>,
        validate: (SecureDirectoryStream<Path>, SecureDirectoryStream<Path>, String) -> Unit,
        action: (ParcelFileDescriptor) -> T,
    ): T {
        openDirectory(root.toString()).use { rootFd ->
            walk(rootFd, node.dropLast(1)).use { sourceFd ->
                val opened = Os.open("${anchor(sourceFd)}/${node.last()}",
                    OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
                val duplicate = try { ParcelFileDescriptor.dup(opened) } finally { Os.close(opened) }
                duplicate.use { fileFd ->
                    if (!OsConstants.S_ISREG(Os.fstat(fileFd.fileDescriptor).st_mode)) {
                        throw SecurityException("创建持久身份只接受普通文件")
                    }
                    // 仅跟随由本函数持有的文件FD，不解析用户可替换的路径。
                    val key = Files.readAttributes(Paths.get(anchor(fileFd)),
                        java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()?.toString()
                        ?: throw SecurityException("创建文件FD缺少稳定身份")
                    secure(rootFd).use { rootStream ->
                        secure(sourceFd).use { sourceStream ->
                            validate(rootStream, sourceStream, key)
                            val result = action(fileFd)
                            validate(rootStream, sourceStream, key)
                            return result
                        }
                    }
                }
            }
        }
    }

    private fun walk(root: ParcelFileDescriptor, segments: List<String>): ParcelFileDescriptor {
        var current = ParcelFileDescriptor.dup(root.fileDescriptor)
        try {
            segments.forEach { segment ->
                require(segment.isNotBlank() && segment != "." && segment != ".." &&
                    '/' !in segment && '\\' !in segment && '\u0000' !in segment)
                val next = openDirectory("${anchor(current)}/$segment")
                current.close()
                current = next
            }
            return current
        } catch (failure: Throwable) {
            current.close()
            throw failure
        }
    }

    private fun openDirectory(path: String): ParcelFileDescriptor {
        val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        return try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) {
                throw SecurityException("创建身份锚点不是目录")
            }
            ParcelFileDescriptor.dup(fd)
        } finally { Os.close(fd) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun secure(fd: ParcelFileDescriptor): SecureDirectoryStream<Path> {
        // 此路径只由本方法持有的 FD 构成，不读取用户可替换的绝对目录链。
        val stream = Files.newDirectoryStream(Paths.get(anchor(fd)))
        return stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            throw SecurityException("创建身份锚点不支持 SecureDirectoryStream")
        }
    }

    private fun anchor(fd: ParcelFileDescriptor): String = "/proc/self/fd/${fd.fd}"
}
