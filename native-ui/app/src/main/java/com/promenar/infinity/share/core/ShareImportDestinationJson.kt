package com.promenar.infinity.share.core

import org.json.JSONObject

internal fun ShareImportDestination.toJson(): JSONObject = when (this) {
    is ShareImportDestination.SessionFolder -> JSONObject().put("type", "session")
        .put("sessionId", sessionId).put("workspaceRootUuid", workspaceRootUuid).put("current", current)
    is ShareImportDestination.LinuxDirectory -> JSONObject().put("type", "linux").put("path", path)
    is ShareImportDestination.KnowledgeFolder -> JSONObject().put("type", "knowledge")
        .put("workspaceRootUuid", workspaceRootUuid).put("folderUuid", folderUuid).put("displayPath", displayPath)
}

internal fun shareImportDestination(json: JSONObject): ShareImportDestination {
    fun text(key: String): String = json.getString(key).also { require(it.isNotBlank() && it.length <= 4096 && it.none(Char::isISOControl)) }
    return when (json.getString("type")) {
        "session" -> ShareImportDestination.SessionFolder(text("sessionId"), text("workspaceRootUuid"), json.getBoolean("current"))
        "linux" -> ShareImportDestination.LinuxDirectory(text("path").also { require(it.startsWith('/')) })
        "knowledge" -> ShareImportDestination.KnowledgeFolder(text("workspaceRootUuid"), text("folderUuid"), text("displayPath"))
        else -> throw IllegalArgumentException("未知分享落点")
    }
}
