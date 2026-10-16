package com.promenar.nexara.ui.renderer

import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 在真实 Chromium 中验证打包资源与应用安全客户端，不需要外网或像素基线。 */
class RichContentWebViewDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var view: WebView
    private lateinit var client: RichContentWebViewClient
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun acquire() {
        rule.runOnUiThread {
            RichContentWebViewPool.destroyAll()
            view = RichContentWebViewPool.acquire(rule.activity)
            client = view.webViewClient as RichContentWebViewClient
            attach(view)
        }
    }

    @After fun release() {
        rule.runOnUiThread {
            if (::view.isInitialized) {
                (view.parent as? ViewGroup)?.removeView(view)
                RichContentWebViewPool.release(view)
            }
            RichContentWebViewPool.destroyAll()
        }
    }

    @Test fun katexInlineAndDisplayProduceNonEmptyLayoutNodes() {
        load("""
            <link rel="stylesheet" href="${ASSETS}katex/katex.min.css">
            <script src="${ASSETS}katex/katex.min.js"></script>
            <span id="inline"></span><div id="display"></div>
            <script>
              katex.render('x^2+1', document.getElementById('inline'));
              katex.render('\\frac{1}{2}', document.getElementById('display'), {displayMode:true});
            </script>
        """)
        await("""['inline','display'].every(id => {
            const n = document.querySelector('#'+id+' .katex-html');
            return n && n.textContent.trim().length > 0 && n.getBoundingClientRect().width > 0 && n.getBoundingClientRect().height > 0;
        }) && !!document.querySelector('#display .katex-display')""")
    }

    @Test fun mermaidFlowchartProducesSvg() {
        // 打包的 Mermaid 使用 Chromium 94 起支持的语法；更旧的系统 WebView 上产品模板退化为显示图表源码。
        val webViewMajor = WebView.getCurrentWebViewPackage()?.versionName?.substringBefore('.')?.toIntOrNull() ?: 0
        assumeTrue("系统 WebView $webViewMajor 低于 Mermaid 所需的 94，跳过渲染断言", webViewMajor >= 94)
        load("""
            <script src="${ASSETS}mermaid/mermaid.min.js"></script>
            <div class="mermaid">graph TD; A[Start] --> B[Finish];</div>
            <script>mermaid.initialize({startOnLoad:false,securityLevel:'strict'}); mermaid.run();</script>
        """)
        await("""(() => { const svg=document.querySelector('.mermaid svg');
            return !!svg && svg.querySelectorAll('.node').length === 2 && svg.getBoundingClientRect().width > 0; })()""")
    }

    @Test fun echartsProducesNonZeroCanvasOrSvg() {
        load("""
            <script src="${ASSETS}echarts/echarts.min.js"></script>
            <div id="chart" style="width:480px;height:320px"></div>
            <script>echarts.init(document.getElementById('chart')).setOption({animation:false,
              xAxis:{type:'category',data:['A','B']},yAxis:{type:'value'},series:[{type:'bar',data:[2,5]}]});</script>
        """)
        await("""(() => { const node=document.querySelector('#chart canvas,#chart svg');
            return !!node && node.getBoundingClientRect().width > 0 && node.getBoundingClientRect().height > 0
              && (node.tagName.toLowerCase() !== 'canvas' || (node.width > 0 && node.height > 0)); })()""")
    }

    @Test fun htmlScriptsRunButFileAndContentReadsAreDenied() {
        val marker = "rich-content-private-${UUID.randomUUID()}"
        val file = File(context.filesDir, "rich-content-device-${UUID.randomUUID()}.txt").apply { writeText(marker) }
        val provider = Uri.parse("content://${com.promenar.nexara.test.BuildConfig.APPLICATION_ID}.richcontentfixture/probe")
        val resolver = context.contentResolver
        try {
            // 先证明测试目标确实可读，避免把不存在的资源误判为门禁生效。
            assertThat(file.readText()).isEqualTo(marker)
            assertThat(resolver.openInputStream(provider)!!.bufferedReader().use { it.readText() }).isEqualTo("content-fixture-readable")
            resolver.call(provider, "reset", null, null)
            load("<div id='script'></div><script>document.getElementById('script').textContent='script-ran';</script>")
            await("document.getElementById('script').textContent === 'script-ran'")
            requestDenied(listOf(Uri.fromFile(file).toString(), provider.toString()))
            assertThat(resolver.call(provider, "reads", null, null)!!.getInt("reads")).isEqualTo(0)
            rule.runOnUiThread {
                assertThat(view.settings.allowFileAccess).isFalse()
                assertThat(view.settings.allowContentAccess).isFalse()
            }
        } finally { file.delete() }
    }

    @Test fun privateAddressesAreDeniedByRealPageAndRequestGate() {
        load("<div id='document'>private-request-probe</div>")
        val urls = listOf("https://10.0.2.2:2222/probe", "https://127.0.0.1:2222/probe", "https://192.168.1.1:2222/probe")
        requestDenied(urls)
        // CORS 会把 403 隐藏为 JS 网络错误；额外检查安全客户端的精确拒绝响应。
        urls.forEach { url ->
            val response = client.shouldInterceptRequest(view, request(url))
            assertThat(response.statusCode).isEqualTo(403)
            response.data.close()
        }
        await("document.getElementById('document').textContent === 'private-request-probe'")
    }

    @Test fun linkClickAndScriptNavigationKeepCurrentDocument() {
        load("<a id='link' href='https://127.0.0.1:2222/navigation'>link</a><div id='current'>kept</div>")
        await("!!document.getElementById('current')")
        val original = evaluate("location.href")
        evaluate("document.getElementById('link').click(); true")
        SystemClock.sleep(350)
        assertThat(evaluate("location.href")).isEqualTo(original)
        await("document.getElementById('current').textContent === 'kept'")
        evaluate("location.assign('https://127.0.0.1:2222/script-navigation'); true")
        SystemClock.sleep(350)
        assertThat(evaluate("location.href")).isEqualTo(original)
        await("document.getElementById('current').textContent === 'kept'")
    }

    @Test fun pooledWebViewDropsPreviousDomGlobalsAndDocumentAccess() {
        load("<div id='old'>old-document</div><script>window.previousDocumentState='old';</script>")
        await("window.previousDocumentState === 'old'")
        val oldUrl = JSONObject("{\"url\":" + evaluate("location.href") + "}").getString("url")
        val previous = view
        rule.runOnUiThread {
            (view.parent as ViewGroup).removeView(view)
            RichContentWebViewPool.release(view)
        }
        await("location.href === 'about:blank'")
        rule.runOnUiThread {
            view = RichContentWebViewPool.acquire(rule.activity)
            client = view.webViewClient as RichContentWebViewClient
            assertThat(view).isSameInstanceAs(previous)
            attach(view)
        }
        load("<div id='new'>new-document</div>")
        await("!!document.getElementById('new') && !document.getElementById('old') && typeof window.previousDocumentState === 'undefined'")
        assertThat(evaluate("location.href")).isNotEqualTo(JSONObject.quote(oldUrl))
        val old = client.shouldInterceptRequest(view, request(oldUrl, mainFrame = true))
        assertThat(old.statusCode).isEqualTo(403)
        old.data.close()
    }

    private fun attach(webView: WebView) {
        rule.activity.setContentView(FrameLayout(rule.activity).apply {
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600))
        })
    }

    private fun load(body: String) {
        val documentId = UUID.randomUUID().toString()
        rule.runOnUiThread { view.loadRichContent("<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head><body data-device-document='$documentId'><script>window.__deviceErrors=[];window.addEventListener('error',e=>window.__deviceErrors.push(String(e.message||e.type)+' '+String((e.target&&(e.target.src||e.target.href))||'')),true);</script>$body</body></html>") }
        // 等待目标文档，不能把初始 about:blank 的 readyState 误判为加载完成。
        await("document.readyState === 'complete' && document.body && document.body.dataset.deviceDocument === '$documentId'")
    }

    private fun requestDenied(urls: List<String>) {
        evaluate("""window.deniedResults=null; Promise.all(${org.json.JSONArray(urls)}.map(url =>
            fetch(url).then(response => response.status === 403 ? 'denied' : 'unexpected-response', () => 'denied')
        )).then(results => { window.deniedResults=results; }); true""")
        await("Array.isArray(window.deniedResults) && window.deniedResults.length === ${urls.size} && window.deniedResults.every(value => value === 'denied')")
    }

    private fun await(expression: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (evaluate("Boolean($expression)") == "true") return
            SystemClock.sleep(50)
        }
        // 失败时附带页面状态，便于区分资源未加载、脚本异常与断言条件本身。
        val diagnostics = evaluate("""JSON.stringify({url: location.href, readyState: document.readyState,
            errors: window.__deviceErrors || [], body: document.body ? document.body.innerHTML.slice(0, 600) : null})""")
        assertWithMessage(diagnostics).that(evaluate("Boolean($expression)")).isEqualTo("true")
    }

    private fun evaluate(script: String): String {
        val result = AtomicReference<String>()
        val done = CountDownLatch(1)
        rule.runOnUiThread { view.evaluateJavascript(script) { result.set(it); done.countDown() } }
        check(done.await(5, TimeUnit.SECONDS)) { "WebView JS 求值超时" }
        return result.get()
    }

    private fun request(url: String, mainFrame: Boolean = false) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = mainFrame
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    private companion object { const val ASSETS = "https://appassets.androidplatform.net/assets/" }
}
