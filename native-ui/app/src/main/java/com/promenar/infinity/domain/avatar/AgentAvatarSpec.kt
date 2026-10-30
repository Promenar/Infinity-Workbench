package com.promenar.infinity.domain.avatar

import com.promenar.infinity.data.avatar.AvatarSymbolCatalog

sealed interface AgentAvatarSpec {
    data class Symbol(val name: String) : AgentAvatarSpec
    data class Generated(val seed: String = "") : AgentAvatarSpec
}

object AgentAvatarCodec {
    const val SYMBOL_PREFIX = "sym:"
    const val GENERATED_PREFIX = "gen:"
    const val DEFAULT_SYMBOL = "auto_awesome"
    const val NEW_AGENT_DEFAULT = "gen:"
    val LEGACY_SYMBOLS: Map<String, String> = linkedMapOf(
        "✨" to DEFAULT_SYMBOL, "💻" to "code", "📝" to "edit_note", "🤖" to "smart_toy",
        "🧠" to "psychology", "⭐" to "star", "🔥" to "local_fire_department", "💡" to "lightbulb",
        "⚡" to "bolt", "🎯" to "track_changes", "🌐" to "translate", "A" to "translate",
    )
    private val symbolName = Regex("[a-z0-9_]+")

    fun decode(raw: String?): AgentAvatarSpec {
        if (raw.isNullOrBlank()) return AgentAvatarSpec.Symbol(DEFAULT_SYMBOL)
        if (raw.startsWith(GENERATED_PREFIX)) {
            return AgentAvatarSpec.Generated(raw.removePrefix(GENERATED_PREFIX).trim().take(64))
        }
        val name = if (raw.startsWith(SYMBOL_PREFIX)) raw.removePrefix(SYMBOL_PREFIX)
        else LEGACY_SYMBOLS[raw.replace("\uFE0F", "")]
        return AgentAvatarSpec.Symbol(name?.takeIf { symbolName.matches(it) && AvatarSymbolCatalog.contains(it) } ?: DEFAULT_SYMBOL)
    }

    fun encode(spec: AgentAvatarSpec): String = when (spec) {
        is AgentAvatarSpec.Symbol -> SYMBOL_PREFIX + (decode(SYMBOL_PREFIX + spec.name) as AgentAvatarSpec.Symbol).name
        is AgentAvatarSpec.Generated -> GENERATED_PREFIX + spec.seed.trim().take(64)
    }
}
