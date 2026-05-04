package com.promenar.nexara.data.rag

class TrigramTextSplitter(
    private val chunkSize: Int,
    private val chunkOverlap: Int
) {
    init {
        require(chunkOverlap < chunkSize) { "chunkOverlap must be smaller than chunkSize" }
    }

    private val sentenceDelimiters = Regex("([。！？；\n])")

    fun splitText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val sentences = splitIntoSentences(text)
        val chunks = mutableListOf<String>()
        var currentChunk = ""

        for (sentence in sentences) {
            if (sentence.length > chunkSize) {
                if (currentChunk.isNotBlank()) {
                    chunks.add(currentChunk.trim())
                    currentChunk = ""
                }
                chunks.addAll(splitLongSentence(sentence))
            } else {
                val testChunk = currentChunk + sentence
                if (testChunk.length <= chunkSize) {
                    currentChunk = testChunk
                } else {
                    if (currentChunk.isNotBlank()) {
                        chunks.add(currentChunk.trim())
                    }
                    currentChunk = sentence
                }
            }
        }

        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.trim())
        }

        return addOverlap(chunks)
    }

    fun estimateChunkCount(text: String): Int {
        val effectiveChunkSize = chunkSize - chunkOverlap
        if (effectiveChunkSize <= 0) {
            return Math.ceil(text.length.toDouble() / chunkSize).toInt()
        }
        return maxOf(1, Math.ceil(text.length.toDouble() / effectiveChunkSize).toInt())
    }

    private fun splitIntoSentences(text: String): List<String> {
        val parts = sentenceDelimiters.split(text)
        val sentences = mutableListOf<String>()

        var i = 0
        while (i < parts.size) {
            val content = parts[i]
            val delimiter = if (i + 1 < parts.size) parts[i + 1] else ""
            i += 2

            if (content.isNotBlank()) {
                sentences.add(content + delimiter)
            }
        }

        return sentences.filter { it.isNotBlank() }
    }

    private fun splitLongSentence(sentence: String): List<String> {
        val chunks = mutableListOf<String>()
        var startIndex = 0
        val punctuation = Regex("[，、；：\u201c\u201d\u2018\u2019\uff08\uff09\\s]")

        while (startIndex < sentence.length) {
            var endIndex = minOf(startIndex + chunkSize, sentence.length)

            if (endIndex < sentence.length) {
                var breakPoint = endIndex
                val searchForward = minOf(endIndex + 10, sentence.length)
                for (i in endIndex until searchForward) {
                    if (punctuation.containsMatchIn(sentence[i].toString())) {
                        breakPoint = i + 1
                        break
                    }
                }

                if (breakPoint == endIndex) {
                    val searchBack = maxOf(endIndex - 10, startIndex)
                    for (i in (endIndex - 1) downTo searchBack) {
                        if (punctuation.containsMatchIn(sentence[i].toString())) {
                            breakPoint = i + 1
                            break
                        }
                    }
                }

                endIndex = breakPoint
            }

            val chunk = sentence.substring(startIndex, endIndex).trim()
            if (chunk.isNotEmpty()) {
                chunks.add(chunk)
            }

            val nextStartIndex = endIndex - chunkOverlap
            startIndex = if (nextStartIndex <= startIndex) endIndex else nextStartIndex
        }

        return chunks
    }

    private fun addOverlap(chunks: List<String>): List<String> {
        if (chunks.size <= 1 || chunkOverlap == 0) return chunks

        val overlappedChunks = mutableListOf(chunks[0])

        for (i in 1 until chunks.size) {
            val prevChunk = chunks[i - 1]
            val currentChunk = chunks[i]
            val overlapText = prevChunk.takeLast(chunkOverlap)

            if (!currentChunk.startsWith(overlapText)) {
                overlappedChunks.add(overlapText + currentChunk)
            } else {
                overlappedChunks.add(currentChunk)
            }
        }

        return overlappedChunks
    }
}
