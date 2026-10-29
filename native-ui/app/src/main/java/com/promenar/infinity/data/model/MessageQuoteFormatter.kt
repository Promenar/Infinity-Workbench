package com.promenar.infinity.data.model

object MessageQuoteFormatter {
    /** 把引用与正文拼成发给模型的用户消息文本；没有引用时原样返回正文。 */
    fun formatUserContent(content: String, quotes: List<MessageQuote>?): String {
        val blocks = quotes.orEmpty().mapNotNull { quote ->
            val lines = quote.text.lines().dropWhile(String::isBlank).dropLastWhile(String::isBlank)
            val annotation = quote.annotation.trim()
            if (lines.isEmpty() || annotation.isEmpty()) return@mapNotNull null
            val text = lines.joinToString("\n") { if (it.isBlank()) ">" else "> $it" }
            "$text\n\n$annotation"
        }
        if (blocks.isEmpty()) return content
        return (blocks + listOfNotNull(content.takeIf(String::isNotBlank)?.trimEnd())).joinToString("\n\n")
    }
}

internal fun Message.contentForModel(): String =
    if (role == MessageRole.USER) MessageQuoteFormatter.formatUserContent(content, quotes) else content
