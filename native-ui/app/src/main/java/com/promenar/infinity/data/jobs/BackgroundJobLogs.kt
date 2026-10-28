package com.promenar.infinity.data.jobs

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption

/** 公开的是不含宿主路径的引用，读取权限仍由会话归属检查控制。 */
object BackgroundJobLogs {
    fun reference(jobId: String): String = "job-log:$jobId"

    fun jobId(reference: String): String? = reference.removePrefix("job-log:")
        .takeIf { reference.startsWith("job-log:") && it.matches(Regex("job-[A-Za-z0-9-]+")) }

    internal fun file(record: BackgroundJobRecord, runRoot: File): File {
        val directory = File(record.runDirectory).toPath().toAbsolutePath().normalize()
        val expected = File(runRoot, record.id).toPath().toAbsolutePath().normalize()
        require(record.id.matches(Regex("job-[A-Za-z0-9-]+")) && directory == expected) { "任务日志目录无效" }
        require(!Files.isSymbolicLink(directory) && !Files.isSymbolicLink(runRoot.toPath())) { "任务日志目录不可为符号链接" }
        return directory.resolve("output.log").toFile()
    }

    private fun sameContent(left: java.nio.file.Path, right: java.nio.file.Path): Boolean {
        if (Files.size(left) != Files.size(right)) return false
        fun digest(path: java.nio.file.Path): ByteArray {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val bytes = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    hash.update(bytes, 0, count)
                }
            }
            return hash.digest()
        }
        return digest(left).contentEquals(digest(right))
    }

    internal fun legacyFile(record: BackgroundJobRecord): File? {
        if (record.logWorkspacePath != "jobs/${record.id}.log") return null
        val workspace = record.workspaceDirectory?.let(::File)?.toPath()?.toAbsolutePath()?.normalize() ?: return null
        val jobs = workspace.resolve("jobs")
        val source = jobs.resolve("${record.id}.log")
        require(!Files.isSymbolicLink(workspace) && !Files.isSymbolicLink(jobs) && !Files.isSymbolicLink(source)) { "旧日志路径不可为符号链接" }
        return source.toFile().takeIf { Files.isRegularFile(source, NOFOLLOW_LINKS) }
    }

    /** 仅迁移持久任务记录明确归属的旧日志，不收养用户 jobs 目录中的其它文件。 */
    internal fun migrate(record: BackgroundJobRecord, runRoot: File) {
        if (record.logWorkspacePath != "jobs/${record.id}.log") return
        val workspace = record.workspaceDirectory?.let(::File)?.toPath()?.toAbsolutePath()?.normalize() ?: return
        val jobs = workspace.resolve("jobs")
        require(!Files.isSymbolicLink(workspace) && !Files.isSymbolicLink(jobs)) { "旧日志目录不可为符号链接" }
        val raw = file(record, runRoot).toPath()
        val destination = if (record.isolatedWorkspace) raw.resolveSibling("legacy-workspace.log") else raw
        Files.createDirectories(destination.parent)
        for ((oldName, target) in listOf("${record.id}.log.1" to destination.resolveSibling("${destination.fileName}.1"), "${record.id}.log" to destination)) {
            val source = jobs.resolve(oldName)
            if (!Files.exists(source, NOFOLLOW_LINKS)) continue
            require(Files.isRegularFile(source, NOFOLLOW_LINKS)) { "旧日志不是普通文件" }
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                require(Files.isRegularFile(target, NOFOLLOW_LINKS) && sameContent(source, target)) { "新旧日志内容冲突，旧日志已保留" }
            } else {
                val temporary = target.resolveSibling("${target.fileName}.migration")
                if (Files.exists(temporary, NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(temporary, NOFOLLOW_LINKS)) { "日志迁移暂存无效" }
                    Files.delete(temporary)
                }
                Files.copy(source, temporary)
                check(sameContent(source, temporary)) { "日志迁移校验失败" }
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            }
            Files.delete(source)
        }
        // 只删除已空的目录，用户文件与后台冲突副本留在原处。
        runCatching { Files.delete(jobs) }
    }
}
