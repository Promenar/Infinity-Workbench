package com.promenar.nexara.data.rag

import org.jsoup.Jsoup

object HtmlExtractor {

    fun extractText(html: String): String {
        val doc = Jsoup.parse(html)
        return doc.text()
    }

    fun extractTextWithStructure(html: String): String {
        val doc = Jsoup.parse(html)
        val sb = StringBuilder()
        val body = doc.body() ?: return ""

        fun walk(node: org.jsoup.nodes.Node, depth: Int = 0) {
            when (node) {
                is org.jsoup.nodes.TextNode -> {
                    val text = node.text().trim()
                    if (text.isNotEmpty()) {
                        if (sb.isNotEmpty()) sb.append("\n")
                        sb.append(text)
                    }
                }
                is org.jsoup.nodes.Element -> {
                    val tag = node.tagName().lowercase()
                    if (tag in setOf("script", "style", "noscript")) return
                    if (tag in setOf("br", "hr")) {
                        sb.append("\n")
                        return
                    }
                    for (child in node.childNodes()) {
                        walk(child, depth + 1)
                    }
                    if (tag in setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr")) {
                        sb.append("\n")
                    }
                }
            }
        }

        walk(body)
        return sb.toString().trim().replace(Regex("\n{3,}"), "\n\n")
    }
}
