package com.promenar.infinity.data.export

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.promenar.infinity.domain.export.SessionExportFile
import com.promenar.infinity.domain.export.SessionExportFormat
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.floor

/** 只使用公开 API。分段滚动软件绘制，不改变全进程 WebView 的整页绘制策略。 */
internal class ExportWebViewRenderer(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(html: String, directory: File, base: String, format: SessionExportFormat): List<SessionExportFile> = withContext(Dispatchers.Main) {
        val view = ExportRenderView(context)
        try {
            view.setBackgroundColor(Color.WHITE)
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            view.settings.apply {
                javaScriptEnabled = true
                blockNetworkLoads = true
                blockNetworkImage = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                domStorageEnabled = false
                textZoom = 100
                builtInZoomControls = false
            }
            view.isVerticalScrollBarEnabled = false
            view.isHorizontalScrollBarEnabled = false
            // 先用固定物理宽度布局，再按 JS 报告的 CSS 宽度换算实际绘制坐标。
            view.setInitialScale(100)
            val width = if (format == SessionExportFormat.PDF) 760 else EXPORT_WIDTH
            layout(view, width, 1200)
            var loaded = false
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(webView: WebView, url: String?) { loaded = true }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            }
            val metrics = withTimeout(30_000) {
                view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                var result: JsonObject? = null
                while (result == null) {
                    ensureActive()
                    if (loaded) {
                        val raw = evaluate(view, "(window.__sessionExportReady && window.__sessionExportMetrics) ? JSON.stringify(window.__sessionExportMetrics()) : null")
                        if (raw != "null") {
                            val json = Json.parseToJsonElement(raw).jsonPrimitive.content
                            result = Json.parseToJsonElement(json).jsonObject
                        }
                    }
                    if (result == null) delay(60)
                }
                result
            }
            val cssWidth = metrics.getValue("width").jsonPrimitive.double
            require(cssWidth > 0)
            val scale = width / cssWidth
            val height = ceil(metrics.getValue("height").jsonPrimitive.double * scale).toLong()
            require(height in 1..Int.MAX_VALUE.toLong()) { "导出页面高度超限" }
            val total = height.toInt()
            if (format == SessionExportFormat.PNG) {
                val boundaries = metrics.getValue("boundaries").jsonArray.map { floor(it.jsonPrimitive.double * scale).toInt() }
                val slices = splitExportHeight(total, boundaries)
                val names = exportImageNames(base, slices.size)
                slices.mapIndexed { index, slice ->
                    ensureActive()
                    val bitmap = Bitmap.createBitmap(EXPORT_WIDTH, slice.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        drawSlice(view, Canvas(bitmap), slice, width)
                        val file = File(directory, names[index])
                        withContext(Dispatchers.IO) {
                            file.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "长图压缩失败" } }
                            ensureActive()
                        }
                        SessionExportFile(file, file.name, format.mimeType)
                    } finally { bitmap.recycle() }
                }
            } else {
                // A4 的 595×842 点；每边 42 点（约 15 mm）。
                val pageWidth = 595
                val pageHeight = 842
                val margin = 42
                val ratio = (pageWidth - margin * 2).toFloat() / width
                val pixelsPerPage = floor((pageHeight - margin * 2) / ratio.toDouble()).toInt()
                val blocks = metrics.getValue("blocks").jsonArray.map { item ->
                    ExportSlice(floor(item.jsonObject.getValue("top").jsonPrimitive.double * scale).toInt(),
                        ceil(item.jsonObject.getValue("bottom").jsonPrimitive.double * scale).toInt())
                }
                val slices = splitExportPdfHeight(total, blocks, pixelsPerPage)
                val pdf = PdfDocument()
                try {
                    slices.forEachIndexed { index, slice ->
                        ensureActive()
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create())
                        try {
                            page.canvas.drawColor(Color.WHITE)
                            page.canvas.save()
                            page.canvas.translate(margin.toFloat(), margin.toFloat())
                            page.canvas.scale(ratio, ratio)
                            page.canvas.clipRect(0, 0, width, slice.height)
                            drawSlice(view, page.canvas, slice, width)
                            page.canvas.restore()
                        } finally { pdf.finishPage(page) }
                    }
                    val file = File(directory, "$base.pdf")
                    withContext(Dispatchers.IO) {
                        file.outputStream().buffered().use { pdf.writeTo(it) }
                        ensureActive()
                    }
                    listOf(SessionExportFile(file, file.name, format.mimeType))
                } finally { pdf.close() }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                view.disposed = true
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.removeAllViews()
                view.destroy()
            }
        }
    }

    private suspend fun drawSlice(view: ExportRenderView, canvas: Canvas, slice: ExportSlice, width: Int) {
        currentCoroutineContext().ensureActive()
        layout(view, width, slice.height)
        view.scrollTo(0, slice.top)
        // 布局与滚动均经渲染进程确认后再绘制，避免重复上一段。
        evaluate(view, "document.documentElement.offsetHeight")
        delay(32)
        currentCoroutineContext().ensureActive()
        canvas.save()
        canvas.clipRect(0, 0, width, slice.height)
        // 手工 View.draw 没有父容器的 -scrollY 变换；先补偿滚动，再对齐目标切片。
        canvas.translate(0f, -slice.top.toFloat())
        view.draw(canvas)
        canvas.restore()
    }

    private fun layout(view: WebView, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private suspend fun evaluate(view: ExportRenderView, script: String): String = withTimeout(5000) { suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) }
        continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { if (!view.disposed) view.stopLoading() } }
    } }
}

private class ExportRenderView(context: Context) : WebView(context) {
    var disposed = false
}

internal const val EXPORT_WIDTH = 1080
