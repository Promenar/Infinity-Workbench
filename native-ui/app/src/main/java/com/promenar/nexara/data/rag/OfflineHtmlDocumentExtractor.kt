package com.promenar.nexara.data.rag

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 将本地 HTML 解析为供索引使用的结构化纯文本。
 *
 * 这里只解析传入字符串，不创建浏览器环境、不执行脚本，也不发起远程资源请求。
 */
internal object OfflineHtmlDocumentExtractor {
    private const val MAX_MARKUP_TOKENS = 100_000
    private const val MAX_NODES = 100_000
    private const val MAX_DEPTH = 256
    private val EXCLUDED_TAGS = setOf(
        "script", "style", "noscript", "iframe", "object", "embed", "template",
    )
    private val LINE_TAGS = setOf(
        "address", "article", "aside", "blockquote", "dd", "dt", "figcaption", "footer",
        "h1", "h2", "h3", "h4", "h5", "h6", "header", "main", "nav", "p", "pre", "section",
        "div", "ul", "ol", "table", "thead", "tbody", "tfoot",
    )

    fun extract(html: String): String {
        require(html.count { it == '<' } <= MAX_MARKUP_TOKENS) {
            "HTML 标记数量超过安全阈值"
        }
        val body = Jsoup.parse(html).apply {
            select(EXCLUDED_TAGS.joinToString(",")).remove()
        }.body() ?: return ""
        val text = StringBuilder()
        var visited = 0

        fun count(node: Node, depth: Int) {
            require(depth <= MAX_DEPTH) { "HTML 嵌套深度超过安全阈值" }
            visited += 1
            require(visited <= MAX_NODES) { "HTML 节点数超过安全阈值" }
            node.childNodes().forEach { count(it, depth + 1) }
        }

        fun boundary() {
            while (text.isNotEmpty() && text.last() == ' ') text.setLength(text.length - 1)
            if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> text.append(node.wholeText.replace(Regex("[\\s\\u00a0]+"), " "))
                is Element -> {
                    val tag = node.tagName().lowercase()
                    when {
                        tag in EXCLUDED_TAGS -> Unit
                        tag == "br" || tag == "hr" -> boundary()
                        tag == "pre" -> {
                            boundary()
                            text.append(node.wholeText())
                            boundary()
                        }
                        tag == "tr" -> {
                            boundary()
                            val cells = node.children()
                                .filter { it.tagName().equals("th", true) || it.tagName().equals("td", true) }
                            val cellText = cells.map { cell ->
                                // 单元格内部的段落边界只属于单元格内容；先隔离再拼接，避免
                                // `<td><p>甲</p></td><td>乙</td>` 把列分隔符推到下一行。
                                val start = text.length
                                cell.childNodes().forEach(::walk)
                                val rendered = text.substring(start).trim()
                                text.setLength(start)
                                rendered
                            }
                            text.append(cellText.joinToString(" | "))
                            boundary()
                        }
                        tag == "li" -> {
                            boundary()
                            text.append("- ")
                            node.childNodes().forEach(::walk)
                            boundary()
                        }
                        tag in LINE_TAGS -> {
                            boundary()
                            node.childNodes().forEach(::walk)
                            boundary()
                        }
                        else -> node.childNodes().forEach(::walk)
                    }
                }
            }
        }

        count(body, 0)
        body.childNodes().forEach(::walk)
        return text.toString().lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString("\n")
    }
}
