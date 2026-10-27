package com.promenar.nexara.data.branch

import java.io.File

/** 保留创建 Git worktree 时的 Linux 挂载命名空间，命令在分支目录内开始。 */
data class BranchLinuxCommandWorkspace(
    val baseRoot: File,
    val workingDirectory: String,
) {
    init {
        require(baseRoot.isAbsolute) { "Linux 基础工作区必须是宿主绝对路径" }
        require(workingDirectory.startsWith('/') && workingDirectory != "/" &&
            workingDirectory.none { it.code < 32 || it.code == 127 } &&
            workingDirectory.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }) {
            "Linux 分支工作目录必须是规范绝对路径"
        }
    }
}
