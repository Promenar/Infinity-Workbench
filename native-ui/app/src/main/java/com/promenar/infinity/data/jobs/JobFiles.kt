package com.promenar.infinity.data.jobs

import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException

/** 不跟随任务创建的符号链接，避免清理临时副本时触及链接目标。 */
internal fun deleteJobTree(root: File) {
    if (!Files.exists(root.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(root.toPath(), object: SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if(exc!=null) throw exc
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
