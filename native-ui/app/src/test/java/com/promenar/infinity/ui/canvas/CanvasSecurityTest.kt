package com.promenar.infinity.ui.canvas

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.junit.Test

class CanvasSecurityTest {
    @Test fun `仅已发布原生连接隔离的官方WebView允许脚本且响应采用不透明来源`() {
        assertThat(supportsCanvasNetworkIsolation("com.google.android.webview", "152.0.0.0")).isTrue()
        assertThat(supportsCanvasNetworkIsolation("com.android.chrome", "153.0.0.0")).isTrue()
        for ((name, version) in listOf("com.google.android.webview" to "151.0.0.0", "vendor.webview" to "152.0.0.0", "com.android.webview" to "152.0.0.0", "com.google.android.webview" to "invalid", null to null)) {
            assertThat(supportsCanvasNetworkIsolation(name, version)).isFalse()
        }
        assertThat(CANVAS_SECURITY_HEADERS["Content-Security-Policy"]).contains("sandbox allow-scripts")
        assertThat(CANVAS_SECURITY_HEADERS["Content-Security-Policy"]).doesNotContain("allow-same-origin")
        assertThat(CANVAS_SECURITY_HEADERS["Connection-Allowlist"]).isEqualTo("()")
    }

    @Test fun `桥接仅接受当前主文档的有效文本和令牌`() {
        val gate = CanvasBridgeGate("https://canvas-a.invalid/index.html", "token", enabled = true)
        val message = payload("hello")
        assertThat(gate.accept(message, "https://canvas-a.invalid/index.html")).isEqualTo("hello")
        for (url in listOf(null, "about:blank", "https://other.invalid/index.html", "https://canvas-a.invalid/other", "https://canvas-a.invalid/index.html#other")) {
            assertThat(gate.accept(message, url)).isNull()
        }
        assertThat(gate.accept(payload("x", url = "https://other.invalid"), "https://canvas-a.invalid/index.html")).isNull()
        assertThat(gate.accept(payload("x", token = "other"), "https://canvas-a.invalid/index.html")).isNull()
        assertThat(gate.accept(payload("x", mainFrame = false), "https://canvas-a.invalid/index.html")).isNull()
        gate.revoke()
        assertThat(gate.accept(message, "https://canvas-a.invalid/index.html")).isNull()
    }

    @Test fun `桥接文本上限完整拒绝且预览历史没有桥接权限`() {
        val gate = CanvasBridgeGate("https://canvas-a.invalid/index.html", "token", enabled = true)
        val url = "https://canvas-a.invalid/index.html"
        assertThat(gate.accept(payload("字".repeat(8_000)), url)).hasLength(8_000)
        for (text in listOf("", " ", "字".repeat(8_001))) assertThat(gate.accept(payload(text), url)).isNull()
        assertThat(gate.accept("{broken", url)).isNull()
        assertThat(gate.accept("{\"text\":123}", url)).isNull()
        assertThat(CanvasBridgeGate(url, "token", enabled = false).accept(payload("x"), url)).isNull()
    }

    @Test fun `草稿仅向匹配会话追加且不超过总长度上限`() {
        assertThat(appendCanvasDraft("s", "s", "已有", "追加")).isEqualTo("已有\n追加")
        assertThat(appendCanvasDraft("s", "s", "", "追加")).isEqualTo("追加")
        assertThat(appendCanvasDraft("old", "new", "已有", "追加")).isNull()
        assertThat(appendCanvasDraft("s", "s", "a".repeat(64_000), "b")).isNull()
        assertThat(appendCanvasDraft("s", "s", "", "b".repeat(8_001))).isNull()
    }

    @Test fun `画布文档随机来源只对当前主框架返回源码`() {
        val documents = CanvasDocumentSession()
        val first = documents.register("<h1>一</h1>", "", true)
        assertThat(documents.contentFor(first, true)).contains("<h1>一</h1>")
        assertThat(documents.contentFor(first, false)).isNull()
        assertThat(documents.contentFor("$first?other", true)).isNull()
        assertThat(documents.register("<h1>一</h1>", "", true)).isEqualTo(first)
        val second = documents.register("<h1>二</h1>", "", true)
        assertThat(second.substringBefore("/index.html")).isNotEqualTo(first.substringBefore("/index.html"))
        assertThat(documents.contentFor(first, true)).isNull()
        assertThat(documents.contentFor(second, true)).contains("<h1>二</h1>")
    }

    @Test fun `安全文档先安装隔离策略且保留内联交互与片段`() {
        val documents = CanvasDocumentSession()
        val url = documents.register("""<base href="https://evil.invalid"><meta http-equiv="refresh" content="0; url=file:///secret"><iframe srcdoc="<script>bad()</script>"></iframe><script src="https://evil.invalid/a.js"></script><script>window.ready = true;</script><svg><text>统计</text></svg>""", "body { color: red; }", true)
        val html = documents.contentFor(url, true)!!
        val dom = Jsoup.parse(html)
        assertThat(dom.select("base,iframe,frame,frameset,object,embed,script[src]")).isEmpty()
        assertThat(dom.select("meta[http-equiv=refresh]")).isEmpty()
        assertThat(html.indexOf("Content-Security-Policy")).isLessThan(html.indexOf("<script>"))
        assertThat(dom.selectFirst("script")!!.data()).contains("'infinity'")
        assertThat(dom.selectFirst("script")!!.data()).contains("RTCPeerConnection")
        assertThat(html).contains("window.ready = true;")
        assertThat(dom.selectFirst("svg text")!!.text()).isEqualTo("统计")
        val policy = dom.selectFirst("meta[http-equiv=Content-Security-Policy]")!!.attr("content")
        for (rule in listOf("default-src 'none'", "connect-src 'none'", "frame-src 'none'", "worker-src 'none'", "form-action 'none'", "base-uri 'none'")) assertThat(policy).contains(rule)
        assertThat(policy).doesNotContain("https:")
    }

    private fun payload(text: String, url: String = "https://canvas-a.invalid/index.html", token: String = "token", mainFrame: Boolean = true) = buildJsonObject {
        put("text", text); put("url", url); put("token", token); put("mainFrame", mainFrame)
    }.toString()
}
