package com.promenar.nexara.data.storage

import com.promenar.nexara.domain.storage.ExternalMount
import com.promenar.nexara.domain.storage.ExternalFolderPaths
import com.promenar.nexara.domain.storage.ExternalFolderException
import java.util.Base64

object ExternalMountPaths {
    const val ROOT_UUID = "external:root"
    fun validateAlias(alias: String): String = ExternalFolderPaths.validateName(alias.trim()).also {
        if (it == "mnt") throw ExternalFolderException("INVALID_PATH", "mnt 是保留名称")
    }
    fun parse(path: String): Pair<String, String>? {
        val normalized = ExternalFolderPaths.normalize(path)
        if (!normalized.startsWith("/mnt/")) return null
        val parts = normalized.removePrefix("/mnt/").split('/', limit = 2)
        validateAlias(parts[0])
        return parts[0] to if (parts.size == 1) "/" else "/${parts[1]}"
    }
    fun uuid(mount: ExternalMount, path: String): String = "external:${mount.id}:" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ExternalFolderPaths.normalize(path).toByteArray(Charsets.UTF_8))
    fun uuidPath(uuid: String, mounts: List<ExternalMount>): String? {
        if (uuid == ROOT_UUID) return "/mnt"
        val parts = uuid.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != "external") return null
        val mount = mounts.singleOrNull { it.id == parts[1] } ?: return null
        return try {
            val relative = ExternalFolderPaths.normalize(String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8))
            "/mnt/${mount.alias}" + relative.takeIf { it != "/" }.orEmpty()
        } catch (_: IllegalArgumentException) { null }
    }
    fun conflicts(first: ExternalMount, second: ExternalMount): Boolean =
        first.id != second.id && first.alias == second.alias &&
            (first.sessionId == null || second.sessionId == null || first.sessionId == second.sessionId)
}
