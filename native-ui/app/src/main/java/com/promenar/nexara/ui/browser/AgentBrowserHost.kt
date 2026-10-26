package com.promenar.nexara.ui.browser

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.promenar.nexara.data.browser.BrowserActionException
import com.promenar.nexara.data.browser.BrowserAvailability
import com.promenar.nexara.data.browser.BrowserController
import com.promenar.nexara.data.browser.BrowserElement
import com.promenar.nexara.data.browser.BrowserHandoffReason
import com.promenar.nexara.data.browser.BrowserSnapshot
import com.promenar.nexara.data.browser.BrowserUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * 应用级 Agent 浏览器宿主：每个聊天会话最多一个页面，最近使用的 [MAX_SESSIONS] 个保留在内存。
 * WebView 只在主线程创建与操作；页面不随进程持久化，Cookie 保存在独立的 Agent 浏览器配置文件。
 */
class AgentBrowserHost(private val app: Context) : BrowserController {
    private val main = Dispatchers.Main.immediate
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val sessions = LinkedHashMap<String, AgentBrowserSession>(4, 0.75f, true)
    private val _sessionIds = MutableStateFlow<Set<String>>(emptySet())
    /** 已打开浏览器的聊天会话，界面据此显示浏览器入口。 */
    val sessionIds: StateFlow<Set<String>> = _sessionIds.asStateFlow()
    private val script by lazy { app.assets.open("browser/agent-page.js").bufferedReader().use { it.readText() } }
    private val availability by lazy { browserAvailability(app) }

    override fun availability(): BrowserAvailability = availability

    /** 仅主线程调用；会话未打开过浏览器时为空。 */
    fun session(sessionId: String): AgentBrowserSession? = sessions[sessionId]

    fun state(sessionId: String): StateFlow<BrowserUiState>? = sessions[sessionId]?.control?.state

    fun close(sessionId: String) {
        sessions.remove(sessionId)?.destroy()
        _sessionIds.update { it - sessionId }
    }

    override suspend fun open(sessionId: String, url: String): BrowserSnapshot = withContext(main) { obtain(sessionId).open(url) }

    override suspend fun snapshot(sessionId: String, includeText: Boolean, textOffset: Int): BrowserSnapshot =
        withContext(main) { existing(sessionId).snapshot(includeText, textOffset) }

    override suspend fun describe(sessionId: String, ref: String): BrowserElement? = withContext(main) { existing(sessionId).describe(ref) }

    override suspend fun click(sessionId: String, element: BrowserElement): BrowserSnapshot =
        withContext(main) { existing(sessionId).click(element) }

    override suspend fun type(sessionId: String, element: BrowserElement, text: String, submit: Boolean): BrowserSnapshot =
        withContext(main) { existing(sessionId).type(element, text, submit) }

    override suspend fun handOff(sessionId: String, element: BrowserElement, reason: BrowserHandoffReason): BrowserSnapshot =
        withContext(main) { existing(sessionId).handOff(element, reason) }

    private fun existing(sessionId: String): AgentBrowserSession =
        sessions[sessionId] ?: throw BrowserActionException("NO_PAGE", "No page is open in the Agent browser.")

    private fun obtain(sessionId: String): AgentBrowserSession {
        sessions[sessionId]?.let { return it }
        while (sessions.size >= MAX_SESSIONS) {
            // 优先回收不在屏幕上、也没有等待用户的页面。
            val victim = sessions.entries.firstOrNull { !it.value.isAttached && !it.value.waitingForUser }?.key ?: sessions.keys.first()
            close(victim)
        }
        val session = AgentBrowserSession(app, script, scope) { close(sessionId) }
        sessions[sessionId] = session
        _sessionIds.update { it + sessionId }
        return session
    }

    companion object {
        const val MAX_SESSIONS = 3
        const val PROFILE_NAME = "nexara-agent-browser"
    }
}

/** 浏览器依赖 WebView 多配置文件隔离 Cookie 与存储；不支持时整个功能不可用。 */
fun browserAvailability(context: Context): BrowserAvailability {
    val provider = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        ?: return BrowserAvailability.Unsupported(null)
    val supported = runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    return if (supported) BrowserAvailability.Supported else BrowserAvailability.Unsupported(provider.versionName)
}
