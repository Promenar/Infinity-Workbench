package com.promenar.infinity.data.exec

/** 已有用户脚本的兼容别名；提示词与技能说明仅使用新名。 */
private val LEGACY_WORKSPACE_ALIASES = mapOf(
    "INFINITY_WORKSPACE" to "NEXARA_WORKSPACE",
    "INFINITY_WORKSPACE_KIND" to "NEXARA_WORKSPACE_KIND",
    "INFINITY_SKILLS" to "NEXARA_SKILLS",
)

/** 在环境覆盖完成后派生别名，避免旧名残留过期值。 */
internal fun Map<String, String>.withLegacyWorkspaceAliases(): Map<String, String> = toMutableMap().apply {
    LEGACY_WORKSPACE_ALIASES.forEach { (current, legacy) ->
        val value = this@withLegacyWorkspaceAliases[current]
        if (value == null) remove(legacy) else put(legacy, value)
    }
}
