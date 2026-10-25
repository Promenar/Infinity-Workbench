package com.promenar.nexara.data.workspace

import com.promenar.nexara.domain.model.SessionRunMode
import com.promenar.nexara.domain.storage.ExternalFolderException
import com.promenar.nexara.domain.storage.ExternalFolderPaths
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

    fun normalize(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty() || value.any(Character::isISOControl) || '\\' in value || Regex("^[A-Za-z]:").containsMatchIn(value)) invalid()
        val absolute = if (value.startsWith('/')) value else "$WORKSPACE_ROOT/$value"
        val normalized = ExternalFolderPaths.normalize(absolute)
        if (normalized.split('/').any { it == ".l2s" || it.startsWith(".l2s.") || it.startsWith(".proot.l2s.") }) invalid()
        return normalized
    }

    internal fun route(raw: String, privateWorkspace: Boolean): LinuxSessionPath {
        val path = normalize(raw)
        fun below(root: String) = path == root || path.startsWith("$root/")
        fun relative(root: String) = path.removePrefix(root).ifEmpty { "/" }
        return when {
            below(PRIVATE_ROOT) -> LinuxSessionPath(LinuxSessionPathKind.PRIVATE, relative(PRIVATE_ROOT))
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

    fun normalizeArguments(tool: String, args: JsonObject): JsonObject = JsonObject(args.toMutableMap().apply {
        listOf("path", "parentPath", "newParentPath").forEach { key ->
            (get(key) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.let { put(key, JsonPrimitive(normalize(it.content))) }
        }
        val defaultKey = when (tool) {
            "create_file", "create_directory" -> "parentPath".takeIf { !hasString(args, "parentUuid") }
            "list_files" -> "path".takeIf { !hasString(args, "parentUuid") }
            else -> null
        }
        if (defaultKey != null && !hasString(args, defaultKey)) put(defaultKey, JsonPrimitive(WORKSPACE_ROOT))
    })

    private fun hasString(args: JsonObject, key: String) = (args[key] as? JsonPrimitive)?.let { it.isString && it.content.isNotBlank() } == true
    private fun invalid(): Nothing = throw ExternalFolderException("INVALID_PATH", "Linux 路径包含越界片段、无效名称或宿主路径写法")
}
