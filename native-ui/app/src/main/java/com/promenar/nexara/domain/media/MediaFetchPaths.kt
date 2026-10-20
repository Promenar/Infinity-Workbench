package com.promenar.nexara.domain.media

import com.promenar.nexara.data.storage.ExternalMountPaths
import com.promenar.nexara.domain.storage.ExternalFolderPaths

/** 审批和实际保存共享路径语义；空路径代表默认媒体目录。 */
object MediaFetchPaths {
    fun targetPath(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val path = raw.trim()
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(path)) {
            throw WorkspaceMediaException("INVALID_PATH", "媒体只接受工作区虚拟路径，不接受 URI 或盘符")
        }
        return ExternalFolderPaths.normalize(path).also { ExternalMountPaths.parse(it) }
    }
}
