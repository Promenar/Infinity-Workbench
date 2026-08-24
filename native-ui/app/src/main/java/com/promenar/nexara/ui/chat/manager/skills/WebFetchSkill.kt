package com.promenar.nexara.ui.chat.manager.skills

import com.promenar.nexara.data.model.ToolResult
import com.promenar.nexara.ui.chat.manager.registry.SkillDefinition
import com.promenar.nexara.ui.chat.manager.registry.SkillExecutionContext
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import com.promenar.nexara.domain.tool.ToolRisk
import kotlinx.serialization.json.JsonObject
import com.promenar.nexara.ui.chat.manager.registry.intArgument
import com.promenar.nexara.ui.chat.manager.registry.stringArgument

class WebFetchSkill(
    private val httpClient: HttpClient,
    private val addressResolver: suspend (String) -> Array<InetAddress> = { host ->
        withContext(Dispatchers.IO) { InetAddress.getAllByName(host) }
    },
) : SkillDefinition {
    override val id = "web_fetch"
    override val name = "web_fetch"
    override val description = "Fetch and extract clean main text content from a specific web URL. Use this to read the detailed content of a search result or webpage."
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "url": {
                    "type": "string",
                    "description": "The exact URL of the webpage to fetch content from"
                },
                "startLine": {
                    "type": "integer",
                    "description": "The starting line number to read (1-indexed). Defaults to 1."
                },
                "lineCount": {
                    "type": "integer",
                    "description": "The number of lines (paragraphs) to read in this chunk. Defaults to 80."
                }
            },
            "required": ["url"]
        }
    """.trimIndent()

    override suspend fun execute(
        args: JsonObject,
        context: SkillExecutionContext
    ): ToolResult {
        val rawUrl = args.stringArgument("url") ?: return ToolResult(id = "err", content = "Missing 'url' argument", status = "error")
        val url = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            "https://$rawUrl"
        } else {
            rawUrl
        }

        val validationError = validatePublicHttpUrl(url)
        if (validationError != null) {
            return ToolResult(id = "err", content = validationError, status = "error")
        }

        // 解析并校验 startLine 和 lineCount 分页参数
        val rawStartLine = args.intArgument("startLine") ?: 1
        val rawLineCount = args.intArgument("lineCount") ?: 80

        val startLine = rawStartLine.coerceAtLeast(1)
        val lineCount = rawLineCount.coerceAtLeast(1).coerceAtMost(500) // 最大单次读取 500 行，防爆

        return try {
            val response = httpClient.get(url) {
                header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                header("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2")
            }

            if (response.status.value != 200) {
                return ToolResult(
                    id = "fetch_${System.currentTimeMillis()}",
                    content = "Failed to fetch webpage. HTTP Status Code: ${response.status.value}",
                    status = "error"
                )
            }

            val html = response.bodyAsText()
            val doc = Jsoup.parse(html, url)
            
            // 1. 获取网页 Title
            val title = doc.title().trim()
            
            // 2. 移除所有广告、样式、脚本、导航以及底部噪音元素进行深度清洗
            doc.select("script, style, iframe, header, footer, nav, aside, noscript, svg, form, .ads, #ads, .footer, #footer").remove()
            
            // 3. 优先尝试定位主要文章主体，避免杂波
            val mainElement = doc.select("article, main, #content, .content, .post, .article").firstOrNull() 
                ?: doc.body()

            // 4. 精准提取段落、标题、列表等有意义的排版行
            val elements = mainElement.select("p, h1, h2, h3, h4, h5, h6, li, table, pre")
            val rawLines = mutableListOf<String>()
            
            elements.forEach { el ->
                val cleanText = el.text().trim()
                if (cleanText.length > 8) {
                    rawLines.add(cleanText)
                }
            }

            // 5. 兜底提取：若精准提取得到的行数过少，则对 body 的纯文本进行按句断行切割
            var lines: List<String> = rawLines
            if (lines.size < 5) {
                val fallbackText = mainElement.text().replace("\\s+".toRegex(), " ").trim()
                if (fallbackText.isNotEmpty()) {
                    lines = fallbackText.split(Regex("(?<=[。！？；.!?;\n])"))
                        .map { it.trim() }
                        .filter { it.length > 5 }
                }
            }

            val totalLines = lines.size

            if (totalLines == 0) {
                return ToolResult(
                    id = "fetch_${System.currentTimeMillis()}",
                    content = "Webpage fetched successfully but no readable text content could be extracted.",
                    status = "error"
                )
            }

            val fromIndex = startLine - 1
            if (fromIndex >= totalLines) {
                return ToolResult(
                    id = "fetch_${System.currentTimeMillis()}",
                    content = buildString {
                        append("Webpage Title: ").append(title).append("\n")
                        append("URL: ").append(url).append("\n\n")
                        append("--- Metadata ---\n")
                        append("Total Lines: ").append(totalLines).append("\n")
                        append("Requested StartLine: ").append(startLine).append("\n")
                        append("Status: Out of bounds. You have reached the end of the document.\n")
                    },
                    status = "success"
                )
            }

            val toIndex = (fromIndex + lineCount).coerceAtMost(totalLines)
            val chunkLines = lines.subList(fromIndex, toIndex)

            val formattedContent = buildString {
                append("Webpage Title: ").append(title).append("\n")
                append("URL: ").append(url).append("\n\n")
                append("--- Metadata ---\n")
                append("Total Lines: ").append(totalLines).append("\n")
                append("Current Chunk: Lines ").append(startLine).append(" to ").append(toIndex).append("\n")
                
                if (toIndex < totalLines) {
                    append("Notice: There are more lines remaining. You can call 'web_fetch' again with startLine=").append(toIndex + 1).append(" to read the next segment.\n")
                } else {
                    append("Notice: This is the end of the webpage content.\n")
                }
                append("\n--- Content ---\n")
                chunkLines.forEachIndexed { idx, line ->
                    append("[").append(fromIndex + idx + 1).append("] ").append(line).append("\n\n")
                }
            }

            ToolResult(
                id = "fetch_${System.currentTimeMillis()}",
                content = formattedContent,
                status = "success"
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ToolResult(
                id = "fetch_${System.currentTimeMillis()}",
                content = "Error occurred while fetching or parsing the webpage: ${e.localizedMessage ?: e.message}",
                status = "error"
            )
        }
    }

    private suspend fun validatePublicHttpUrl(url: String): String? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return "Invalid URL"
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return "Unsupported URL scheme: ${uri.scheme}"
        }
        val host = uri.host ?: return "Invalid URL: missing host"
        val addresses = try {
            addressResolver(host)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            return "Unable to resolve host: ${e.localizedMessage ?: e.message}"
        }
        if (addresses.any { it.isPrivateOrLocalAddress() }) {
            return "Blocked URL: web_fetch cannot access localhost, private network, link-local, or multicast addresses."
        }
        return null
    }

    private fun InetAddress.isPrivateOrLocalAddress(): Boolean {
        if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
            return true
        }
        if (this is Inet4Address) {
            val bytes = address.map { it.toInt() and 0xff }
            return bytes[0] == 10 ||
                (bytes[0] == 172 && bytes[1] in 16..31) ||
                (bytes[0] == 192 && bytes[1] == 168) ||
                (bytes[0] == 169 && bytes[1] == 254) ||
                bytes[0] == 127 ||
                bytes[0] == 0
        }
        if (this is Inet6Address) {
            val first = address[0].toInt() and 0xff
            return first == 0xfc || first == 0xfd || first == 0xfe
        }
        return false
    }
}
