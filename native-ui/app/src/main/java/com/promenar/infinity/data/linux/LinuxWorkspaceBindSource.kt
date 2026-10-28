package com.promenar.infinity.data.linux

import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** proot 的 -b 在第一个冒号拆分路径；内部宿主别名避免合法目录名被解释为参数。 */
internal object LinuxWorkspaceBindSource {
    fun path(workspace: File, cache: File, invalidMessage: String): String {
        val target = workspace.absoluteFile.toPath().normalize()
        if (':' !in target.toString()) return target.toString()
        val parent = File(cache.canonicalFile, "proot-workspace-binds").toPath()
        try { Files.createDirectory(parent) } catch (_: FileAlreadyExistsException) { }
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { invalidMessage }
        val name = MessageDigest.getInstance("SHA-256").digest(target.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val alias = parent.resolve(name)
        try { Files.createSymbolicLink(alias, target) } catch (_: FileAlreadyExistsException) { }
        require(Files.isSymbolicLink(alias) && Files.readSymbolicLink(alias) == target && ':' !in alias.toString()) {
            invalidMessage
        }
        return alias.toString()
    }
}
