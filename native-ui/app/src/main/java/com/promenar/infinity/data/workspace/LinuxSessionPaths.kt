package com.promenar.infinity.data.workspace

import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ExternalFolderAdmission { ALLOWED, REAL_PATH_ONLY, NEED_ALL_FILES_ACCESS, MODE_REQUIRED, UNAVAILABLE }

/** 添加外部目录前的只读准入检查；实际目录与系统授权仍由挂载后端核验。 */
fun externalFolderAdmission(
    mode: SessionRunMode?,
    sourceType: String,
    allFilesAccessAvailable: Boolean,
    allFilesAccessGranted: Boolean,
): ExternalFolderAdmission = when {
    mode == null -> ExternalFolderAdmission.MODE_REQUIRED
    mode == SessionRunMode.LINUX && sourceType != "real_path" -> ExternalFolderAdmission.REAL_PATH_ONLY
    sourceType == "saf_tree" -> ExternalFolderAdmission.ALLOWED
    sourceType != "real_path" || !allFilesAccessAvailable -> ExternalFolderAdmission.UNAVAILABLE
    !allFilesAccessGranted -> ExternalFolderAdmission.NEED_ALL_FILES_ACCESS
    else -> ExternalFolderAdmission.ALLOWED
}

internal enum class LinuxSessionPathKind { PRIVATE, WORKSPACE, EXTERNAL, ROOTFS, MOUNTS }
internal data class LinuxSessionPath(val kind: LinuxSessionPathKind, val path: String)

/** 文件工具使用严格路径，不折叠点片段，避免审批和链接解析采用不同语义。 */
object LinuxSessionPaths {
    const val PRIVATE_ROOT = "/home/session"
    const val WORKSPACE_ROOT = "/workspace"
    const val SKILLS_ROOT = "/mnt/skills"

    fun normalize(raw: String, workingDirectory: String = WORKSPACE_ROOT): String {
        val cwd = normalizedWorkingDirectory(workingDirectory)
        val value = if (raw == cwd || raw.startsWith("$cwd/")) raw else raw.trim()
        if (value.isEmpty() || value.any(Character::isISOControl) || '\\' in value || Regex("^[A-Za-z]:").containsMatchIn(value)) invalid()
        val absolute = if (value.startsWith('/')) value else "$cwd/$value"
        // 登记根的名称属于可信路径前缀；保留目录与内部硬链接校验只作用于根下的工具路径。
        val selected = cwd.takeIf { LinuxWorkspaceLocation.isAllowedDirectory(it) && (absolute == it || absolute.startsWith("$it/")) }
        val relative = ExternalFolderPaths.normalize(if (selected != null) absolute.removePrefix(selected) else absolute)
        if (relative.split('/').any { it == ".l2s" || it.startsWith(".l2s.") || it.startsWith(".proot.l2s.") }) invalid()
        return if (selected != null) selected + relative.takeUnless { it == "/" }.orEmpty() else relative
    }

    internal fun route(raw: String, privateWorkspace: Boolean): LinuxSessionPath = route(raw, privateWorkspace, null)

    internal fun route(raw: String, privateWorkspace: Boolean, workspaceDirectory: String?): LinuxSessionPath =
        route(raw, privateWorkspace, workspaceDirectory, workspaceDirectory ?: WORKSPACE_ROOT)

    internal fun route(raw: String, privateWorkspace: Boolean, workspaceDirectory: String?, workingDirectory: String): LinuxSessionPath {
        val path = normalize(raw, workingDirectory)
        fun below(root: String) = path == root || path.startsWith("$root/")
        fun relative(root: String) = path.removePrefix(root).ifEmpty { "/" }
        return when {
            below(PRIVATE_ROOT) -> LinuxSessionPath(LinuxSessionPathKind.PRIVATE, relative(PRIVATE_ROOT))
            workspaceDirectory != null && below(workspaceDirectory) -> LinuxSessionPath(LinuxSessionPathKind.WORKSPACE, relative(workspaceDirectory))
            below(WORKSPACE_ROOT) -> LinuxSessionPath(if (privateWorkspace) LinuxSessionPathKind.PRIVATE else LinuxSessionPathKind.WORKSPACE, relative(WORKSPACE_ROOT))
            below(SKILLS_ROOT) -> LinuxSessionPath(LinuxSessionPathKind.EXTERNAL, "/skills" + relative(SKILLS_ROOT).takeUnless { it == "/" }.orEmpty())
            path == "/mnt" -> LinuxSessionPath(LinuxSessionPathKind.MOUNTS, path)
            path.startsWith("/mnt/") -> LinuxSessionPath(LinuxSessionPathKind.EXTERNAL, path)
            else -> LinuxSessionPath(LinuxSessionPathKind.ROOTFS, path)
        }
    }

    /** 以空别名绑定全体 Linux 路径，真实挂载仍额外绑定其持久挂载 id。 */
    fun aliasOf(path: String): String = when {
        path == SKILLS_ROOT || path.startsWith("$SKILLS_ROOT/") -> "skills"
        path.startsWith("/mnt/") -> path.removePrefix("/mnt/").substringBefore('/')
        else -> ""
    }

    fun normalizeArguments(tool: String, args: JsonObject, workingDirectory: String = WORKSPACE_ROOT): JsonObject = JsonObject(args.toMutableMap().apply {
        listOf("path", "parentPath", "newParentPath").forEach { key ->
            (get(key) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.let { put(key, JsonPrimitive(normalize(it.content, workingDirectory))) }
        }
        val defaultKey = when (tool) {
            "create_file", "create_directory" -> "parentPath".takeIf { !hasString(args, "parentUuid") }
            "list_files" -> "path".takeIf { !hasString(args, "parentUuid") }
            else -> null
        }
        if (defaultKey != null && !hasString(args, defaultKey)) put(defaultKey, JsonPrimitive(normalizedWorkingDirectory(workingDirectory)))
    })

    private fun normalizedWorkingDirectory(path: String): String {
        if (!path.startsWith('/') || path.any(Character::isISOControl) || '\\' in path) invalid()
        return if (LinuxWorkspaceLocation.isAllowedDirectory(path)) path else ExternalFolderPaths.normalize(path)
    }

    private fun hasString(args: JsonObject, key: String) = (args[key] as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } == true
    private fun invalid(): Nothing = throw ExternalFolderException("INVALID_PATH", "Linux 路径包含越界片段、无效名称或宿主路径写法")
}
