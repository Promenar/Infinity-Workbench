package com.promenar.nexara.ui.canvas

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode

internal const val MAX_CANVAS_INSERT_CHARS = 8_000
private const val MAX_CANVAS_DRAFT_CHARS = 64_000
internal const val CANVAS_CSP = "default-src 'none'; script-src 'unsafe-inline'; " +
    "style-src 'unsafe-inline'; img-src data:; font-src data:; media-src data:; " +
    "connect-src 'none'; frame-src 'none'; child-src 'none'; object-src 'none'; " +
    "worker-src 'none'; base-uri 'none'; form-action 'none'"
internal const val CANVAS_RESPONSE_CSP = "$CANVAS_CSP; sandbox allow-scripts"
internal val CANVAS_SECURITY_HEADERS = mapOf(
    "Content-Security-Policy" to CANVAS_RESPONSE_CSP,
    "Connection-Allowlist" to "()",
    "Cache-Control" to "no-store",
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "no-referrer",
    "X-DNS-Prefetch-Control" to "off",
)

/** Chromium 152 在 Android WebView 发布原生连接白名单；未知提供程序使用静态预览。 */
internal fun supportsCanvasNetworkIsolation(packageName: String?, versionName: String?): Boolean {
    val officialPackages = setOf("com.google.android.webview", "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "com.google.android.webview.beta", "com.google.android.webview.dev", "com.google.android.webview.canary")
    return packageName in officialPackages && (versionName?.substringBefore('.')?.toIntOrNull() ?: 0) >= 152
}

/** 消息端口只交付给主框架；令牌、文档地址与存活状态共同限制其有效期。 */
internal class CanvasBridgeGate(
    private val documentUrl: String,
    private val token: String,
    private val enabled: Boolean,
) {
    private var active = true

    fun revoke() { active = false }

    fun accept(payload: String?, currentUrl: String?): String? {
        if (!active || !enabled || currentUrl != documentUrl || payload == null || payload.length > MAX_CANVAS_INSERT_CHARS * 6 + 1_024) return null
        val value = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        fun text(key: String): String? = (value[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (text("token") != token || text("url") != documentUrl ||
            (value["mainFrame"] as? JsonPrimitive)?.booleanOrNull != true
        ) return null
        return text("text")?.takeIf { it.isNotBlank() && it.length <= MAX_CANVAS_INSERT_CHARS }
    }
}

/** 草稿读取与写入在主线程完成，切换会话后的旧页面不能写入新会话。 */
internal fun appendCanvasDraft(expectedSessionId: String, currentSessionId: String?, draft: String, text: String): String? {
    if (expectedSessionId != currentSessionId || text.isBlank() || text.length > MAX_CANVAS_INSERT_CHARS) return null
    val separator = if (draft.isEmpty() || draft.endsWith('\n')) "" else "\n"
    if (draft.length + separator.length + text.length > MAX_CANVAS_DRAFT_CHARS) return null
    return draft + separator + text
}

/** 每次内容变化分配独立来源，旧页面与新页面不共享端口、存储或文档地址。 */
internal class CanvasDocumentSession {
    private data class Document(val key: Triple<String, String, Boolean>, val url: String, val token: String, val html: String, val bridge: Boolean)
    @Volatile private var document: Document? = null
    val currentUrl: String? get() = document?.url
    val currentToken: String? get() = document?.token
    val bridgeEnabled: Boolean get() = document?.bridge == true

    fun register(source: String, style: String, bridgeEnabled: Boolean): String {
        val key = Triple(source, style, bridgeEnabled)
        document?.takeIf { it.key == key }?.let { return it.url }
        val url = "https://canvas-${UUID.randomUUID()}.invalid/index.html"
        val token = UUID.randomUUID().toString()
        val html = secureCanvasDocument(source, style, token, bridgeEnabled)
        document = Document(key, url, token, html, bridgeEnabled)
        return url
    }

    fun contentFor(url: String, isMainFrame: Boolean): String? = document?.takeIf { isMainFrame && it.url == url }?.html
}

private fun secureCanvasDocument(source: String, style: String, token: String, bridgeEnabled: Boolean): String {
    val document = Jsoup.parse(source)
    document.outputSettings().prettyPrint(false)
    document.select("base,iframe,frame,frameset,object,embed,meta[http-equiv],meta[name=viewport],script[src],script[type=speculationrules],link").remove()
    document.head().prependElement("style").appendChild(DataNode(style))
    document.head().prependElement("script").appendChild(DataNode(canvasBootstrap(token, bridgeEnabled)))
    document.head().prependElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1.0, user-scalable=yes")
    document.head().prependElement("meta").attr("name", "referrer").attr("content", "no-referrer")
    document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", CANVAS_CSP)
    return document.outerHtml()
}

private fun canvasBootstrap(token: String, enabled: Boolean): String = """
    (() => {
      'use strict';
      // 阻断不受资源请求回调约束的实时通信；画布不创建子框架或工作线程。
      for (const name of ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCIceGatherer']) {
        Object.defineProperty(window, name, { value: undefined, writable: false, configurable: false });
      }
      if (window.top !== window || ${!enabled}) return;
      let port = null;
      const queued = [];
      const serialize = JSON.stringify.bind(JSON);
      const send = text => serialize({ token: '$token', url: location.href, mainFrame: window.top === window, text });
      window.addEventListener('message', event => {
        if (event.data !== '$token' || port || event.ports.length !== 1) return;
        port = event.ports[0];
        for (const text of queued.splice(0)) port.postMessage(send(text));
      });
      Object.defineProperty(window, 'nexara', { configurable: false, writable: false, value: Object.freeze({
        insertText(text) {
          if (window.top !== window || typeof text !== 'string' || !text.trim() || text.length > $MAX_CANVAS_INSERT_CHARS) return false;
          if (port) port.postMessage(send(text));
          else if (queued.length < 8) queued.push(text);
          else return false;
          return true;
        }
      }) });
    })();
""".trimIndent()
