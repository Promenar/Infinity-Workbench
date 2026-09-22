package com.promenar.nexara.data.local.db.recovery

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView

/** Android公开API的mkdirat桥接；每一段都从存活FD出发并拒绝符号链接。 */
internal object AndroidSnapshotDirectoryCreator {
    fun create(anchorRoot: Path, anchorKey: String, parent: Path, expectedKey: String, name: String) {
        safeSnapshotName(name)
        require(parent.startsWith(anchorRoot)) { "快照mkdir路径越出受信根" }
        var current = openDirectory(anchorRoot.toString())
        try {
            verifyKey(current, anchorKey)
            if (parent != anchorRoot) anchorRoot.relativize(parent).forEach { segment ->
                safeSnapshotName(segment.toString())
                val next = openDirectory("${anchor(current)}/$segment")
                current.close()
                current = next
            }
            verifyKey(current, expectedKey)
            Os.mkdir("${anchor(current)}/$name", OsConstants.S_IRWXU)
            Os.fsync(current.fileDescriptor)
        } finally { current.close() }
    }

    private fun verifyKey(fd: ParcelFileDescriptor, expectedKey: String) {
            val raw = Files.newDirectoryStream(Paths.get(anchor(fd)))
            @Suppress("UNCHECKED_CAST")
            val secure = raw as? SecureDirectoryStream<Path> ?: run {
                raw.close(); throw SecurityException("快照mkdir锚点缺少安全目录能力")
            }
            secure.use {
                val actualKey = it.getFileAttributeView(Paths.get("."), BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS).readAttributes().fileKey()?.toString()
                if (actualKey != expectedKey) throw SecurityException("快照mkdir父目录已替换")
            }
    }

    private fun openDirectory(path: String): ParcelFileDescriptor {
        val fd = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        return try {
            if (!OsConstants.S_ISDIR(Os.fstat(fd).st_mode)) throw SecurityException("快照mkdir父节点不是目录")
            ParcelFileDescriptor.dup(fd)
        } finally { Os.close(fd) }
    }

    private fun anchor(fd: ParcelFileDescriptor) = "/proc/self/fd/${fd.fd}"
}
