package com.promenar.nexara.ui.renderer

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient

object RichContentWebViewPool {
    private const val MAX_POOL_SIZE = 3
    private val pool = ArrayDeque<WebView>(MAX_POOL_SIZE)
    private var totalCreated = 0

    fun acquire(context: Context): WebView {
        val webView = pool.removeFirstOrNull()
        if (webView != null) {
            webView.visibility = View.VISIBLE
            return webView
        }
        totalCreated++
        return createWebView(context)
    }

    fun release(webView: WebView) {
        webView.visibility = View.GONE
        webView.loadUrl("about:blank")
        if (pool.size < MAX_POOL_SIZE) {
            pool.addLast(webView)
        } else {
            webView.destroy()
        }
    }

    fun destroyAll() {
        pool.forEach { it.destroy() }
        pool.clear()
    }

    private fun createWebView(context: Context): WebView {
        return WebView(context).apply {
            // 使用 MATCH_PARENT 而非 WRAP_CONTENT，避免 WebView 内部测量时
            // scrollHeight 返回无约束视口高度（数千 px），被 maxHeight 钳制后造成大面积空白
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            setBackgroundColor(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webViewClient = WebViewClient()
        }
    }
}
