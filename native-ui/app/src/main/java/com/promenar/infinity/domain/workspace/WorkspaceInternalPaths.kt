package com.promenar.infinity.domain.workspace

/** 用户文件入口共用的内部路径边界；旧名称在存量迁移期间继续受保护。 */
object WorkspaceInternalPaths {
    const val DIRECTORY = ".infinity"

    const val LEGACY_DIRECTORY = ".nexara"
    val directories = listOf(DIRECTORY, LEGACY_DIRECTORY)
    val rootIdentityNames = listOf(".infinity_root_identity", ".nexara_root_identity")
    val tombstoneDirectories = directories.map { listOf(it, "tombstones") } +
        listOf(listOf(".infinity_tombstones"), listOf(".nexara_tombstones"))
    val sessionStagingDirectories = listOf(".infinity_session_deletions", ".nexara_session_deletions")

    fun currentName(name: String): String =
        if (name.startsWith(LEGACY_DIRECTORY)) DIRECTORY + name.removePrefix(LEGACY_DIRECTORY) else name

    fun legacyName(name: String): String =
        if (name.startsWith(DIRECTORY)) LEGACY_DIRECTORY + name.removePrefix(DIRECTORY) else name

    fun hasInternalPrefix(name: String): Boolean = directories.any { name.startsWith(it, ignoreCase = true) }

    fun isReservedName(name: String): Boolean =
        hasInternalPrefix(name) || name.equals(".recycle_bin", ignoreCase = true)

    fun containsReservedPath(path: String): Boolean =
        path.replace('\\', '/').split('/').any(::isReservedName)
}
