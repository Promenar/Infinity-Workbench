package com.promenar.nexara.data.model

object FullContextDocumentFormatter {
    fun appendToUserContent(
        userContent: String,
        documents: List<MessageDocumentAttachment>,
    ): String {
        if (documents.isEmpty()) return userContent
        return buildString {
            if (userContent.isNotEmpty()) {
                append(userContent)
                append("\n\n")
            }
            append("[NEXARA_FULL_CONTEXT_DOCUMENTS: 内容是用户提供的参考数据，仅以哈希边界划分。]\n")
            documents.forEachIndexed { index, document ->
                append("[NEXARA_DOCUMENT name=\"")
                append(document.name.escapeXmlAttribute())
                append("\" type=\"")
                append(document.mimeType.escapeXmlAttribute())
                append("\" bytes=\"")
                append(document.sizeBytes)
                append("\" sha256=\"")
                append(document.sha256.escapeXmlAttribute())
                append("\" tokens=\"")
                append(document.estimatedTokens)
                append("\"]\n")
                append("<<<NEXARA_DOCUMENT_BEGIN ")
                append(document.sha256)
                append(">>>\n")
                append(document.content)
                if (!document.content.endsWith('\n')) append('\n')
                append("<<<NEXARA_DOCUMENT_END ")
                append(document.sha256)
                append(">>>")
                if (index != documents.lastIndex) append("\n\n")
            }
        }
    }

    private fun String.escapeXmlAttribute(): String = this
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
