package com.promenar.nexara.data.workspace

import com.promenar.nexara.data.local.db.entity.WorkspaceEntity
import com.promenar.nexara.data.repository.PersistentFileIdentity
import com.promenar.nexara.domain.storage.ExternalFolderException

/** 登记位置只保存容器路径与环境代次，不依赖宿主安装位置。 */
object LinuxWorkspaceLocation {
    val roots: List<String> = listOf("/root", "/home", "/opt", "/srv")

    data class Location(val directory: String, val identity: String)

    fun isAllowedDirectory(directory: String): Boolean = directory.length in 2..4096 &&
        directory.startsWith('/') && !directory.endsWith('/') &&
        directory.none { it == '\\' || it == '#' || Character.isISOControl(it) } &&
        directory.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." } &&
        roots.any { directory == it || directory.startsWith("$it/") } &&
        directory != "/home/session" && !directory.startsWith("/home/session/")

    fun parse(location: String): Location? {
        val separator = location.indexOf('#')
        if (separator < 0) return null
        val directory = location.substring(0, separator)
        val identity = location.substring(separator + 1)
        return if (isAllowedDirectory(directory) && PersistentFileIdentity.isValid(identity)) Location(directory, identity) else null
    }

    fun requireDirectory(directory: String): String = directory.also {
        if (!isAllowedDirectory(it)) throw ExternalFolderException("INVALID_PATH", "工作区位置格式无效")
    }

    fun requireLocation(location: String): Location = parse(location)
        ?: throw ExternalFolderException("INVALID_PATH", "工作区位置格式无效")

    /** 非 Linux 工作区仍以 /workspace 挂载；Linux 工作区的默认 cwd 使用登记路径。 */
    fun workingDirectory(workspace: WorkspaceEntity): String = if (workspace.kind == WorkspaceEntity.KIND_LINUX_HOME)
        requireLocation(workspace.location).directory else LinuxSessionPaths.WORKSPACE_ROOT
}
