package com.promenar.nexara.domain.workspace

/** 用户文件入口共用的内部路径边界；旧名称在存量迁移期间继续受保护。 */
object WorkspaceInternalPaths {
    const val DIRECTORY = ".nexara"

    fun isReservedName(name: String): Boolean =
        name.startsWith(DIRECTORY, ignoreCase = true) || name.equals(".recycle_bin", ignoreCase = true)

    fun containsReservedPath(path: String): Boolean =
        path.replace('\\', '/').split('/').any(::isReservedName)
}
