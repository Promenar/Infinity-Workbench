package com.promenar.nexara.data.rag

class RecursiveCharacterTextSplitter(
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val separators: List<String> = listOf("\n\n", "\n", " ", "")
) {
    fun splitText(text: String): List<String> {
        var goodCuts = listOf(text)

        for (separator in separators) {
            val newCuts = mutableListOf<String>()
            for (chunk in goodCuts) {
                if (chunk.length > chunkSize) {
                    newCuts.addAll(splitBySeparator(chunk, separator))
                } else {
                    newCuts.add(chunk)
                }
            }
            goodCuts = newCuts
        }

        val finalChunks = mutableListOf<String>()
        var currentChunk = ""
        for (cut in goodCuts) {
            if (currentChunk.length + cut.length + 1 > chunkSize) {
                if (currentChunk.isNotEmpty()) finalChunks.add(currentChunk.trim())
                currentChunk = cut
            } else {
                currentChunk = if (currentChunk.isNotEmpty()) "$currentChunk $cut" else cut
            }
        }
        if (currentChunk.isNotEmpty()) finalChunks.add(currentChunk.trim())

        return finalChunks.filter { it.isNotEmpty() }
    }

    private fun splitBySeparator(text: String, separator: String): List<String> {
        if (separator.isEmpty()) {
            return text.toList().map { it.toString() }
        }
        return text.split(separator).filter { it.isNotEmpty() }
    }
}
