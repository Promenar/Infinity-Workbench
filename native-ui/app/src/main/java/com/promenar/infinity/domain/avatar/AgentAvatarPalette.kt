package com.promenar.infinity.domain.avatar

data class AgentAvatarSwatch(val key: String, val hex: String)

object AgentAvatarPalette {
    val swatches: List<AgentAvatarSwatch> = listOf(
        AgentAvatarSwatch("indigo", "#6366F1"), AgentAvatarSwatch("blue", "#3B82F6"),
        AgentAvatarSwatch("cyan", "#06B6D4"), AgentAvatarSwatch("emerald", "#10B981"),
        AgentAvatarSwatch("lime", "#65A30D"), AgentAvatarSwatch("yellow", "#EAB308"),
        AgentAvatarSwatch("amber", "#F59E0B"), AgentAvatarSwatch("orange", "#F97316"),
        AgentAvatarSwatch("rose", "#F43F5E"), AgentAvatarSwatch("pink", "#EC4899"),
        AgentAvatarSwatch("fuchsia", "#D946EF"), AgentAvatarSwatch("violet", "#8B5CF6"),
        AgentAvatarSwatch("slate", "#64748B"),
    )

    fun forSeed(seed: String): AgentAvatarSwatch {
        var hash = 0x811C9DC5u
        seed.forEach { codeUnit -> hash = (hash xor codeUnit.code.toUInt()) * 0x01000193u }
        return swatches[(hash % 12u).toInt()]
    }
}
