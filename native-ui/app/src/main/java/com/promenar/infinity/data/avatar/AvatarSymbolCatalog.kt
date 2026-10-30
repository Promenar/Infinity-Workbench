package com.promenar.infinity.data.avatar

import java.text.Normalizer
import java.util.Locale

enum class AvatarSymbolCategory { GENERAL, CODING, WRITING, LEARNING, WORK, CREATIVE, LIFE, NATURE, CHARACTERS }

data class AvatarSymbol(
    val name: String,
    val codepoint: Int,
    val category: AvatarSymbolCategory,
    val labelZh: String,
    val labelEn: String,
    val keywords: List<String>,
)

object AvatarSymbolCatalog {
    val symbols: List<AvatarSymbol> = avatarSymbolCatalogData
    private val byName = symbols.associateBy { it.name }
    val popular: List<AvatarSymbol> = avatarPopularSymbolNames.map { byName.getValue(it) }
    private val whitespace = Regex("\\s+")
    private data class SearchEntry(val symbol: AvatarSymbol, val labels: List<String>, val fields: List<String>)
    private val searchEntries = symbols.map { symbol ->
        val labels = listOf(symbol.name.replace('_', ' '), symbol.labelZh, symbol.labelEn).map(::normalize)
        SearchEntry(symbol, labels, labels + symbol.keywords.map(::normalize))
    }

    fun find(name: String): AvatarSymbol? = byName[name]
    fun contains(name: String): Boolean = name in byName
    fun inCategory(category: AvatarSymbolCategory): List<AvatarSymbol> = symbols.filter { it.category == category }

    fun search(query: String): List<AvatarSymbol> {
        val normalized = normalize(query)
        if (normalized.isBlank()) return emptyList()
        val words = normalized.split(whitespace)
        return searchEntries.mapNotNull { entry ->
            if (!words.all { word -> entry.fields.any { word in it } }) return@mapNotNull null
            val rank = when {
                words.all { word -> entry.labels.any { it == word } } -> 0
                words.all { word -> entry.fields.any { it.startsWith(word) } } -> 1
                else -> 2
            }
            rank to entry.symbol
        }.sortedBy { it.first }.map { it.second }
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()
}
