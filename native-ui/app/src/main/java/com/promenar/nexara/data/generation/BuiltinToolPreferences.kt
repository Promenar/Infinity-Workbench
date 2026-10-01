package com.promenar.nexara.data.generation

import android.content.SharedPreferences

/**
 * 内置工具全局启用状态。
 *
 * `enabled_skills` 保存用户启用的工具，`known_builtin_skills` 保存用户在设置中已经见过的工具。
 * 不在已知集合中的新内置工具默认启用，避免旧版本固化的启用集合把后续新增工具永久排除。
 * 两个集合都可能混用设置键（如 `file_read`）与运行时 id（如 `read_file`），读取时统一归一为运行时 id。
 */
object BuiltinToolPreferences {
    const val ENABLED_KEY = "enabled_skills"
    const val KNOWN_KEY = "known_builtin_skills"

    /** 设置键到运行时 id 的别名。 */
    val SETTINGS_ALIASES: Map<String, String> = mapOf(
        "file_read" to "read_file",
        "file_write" to "write_file",
        "file_list" to "list_files",
        "file_search" to "search_files",
        "file_diff" to "diff_file",
        "file_patch" to "patch_file",
    )

    /** 引入已知集合之前，设置页已经展示过的全部工具。 */
    val LEGACY_KNOWN: Set<String> = setOf(
        "web_search", "web_fetch", "search_tavily", "search_searxng",
        "calculator", "image_generation",
        "file_read", "file_write", "file_list", "file_search", "file_diff", "file_patch",
        "exec_js", "initialize_plan", "update_plan", "get_plan", "drop_plan",
    )

    fun normalize(id: String): String = SETTINGS_ALIASES[id] ?: id

    fun isEnabled(settings: SharedPreferences, runtimeId: String): Boolean {
        val enabled = settings.getStringSet(ENABLED_KEY, null) ?: return true
        if (enabled.any { normalize(it) == runtimeId }) return true
        val known = settings.getStringSet(KNOWN_KEY, null) ?: LEGACY_KNOWN
        return known.none { normalize(it) == runtimeId }
    }
}
