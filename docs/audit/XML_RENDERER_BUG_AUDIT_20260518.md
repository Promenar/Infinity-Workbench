# XML/HTML 预览卡片自适应失效与按钮未上移故障诊断报告

> **日期**: 2026-05-18
> **类型**: 渲染器与高度自适应专项架构审计

---

## 1. 现象陈述 (Symptom Statement)

我们在本地成功将 **思考容器行高/字号（微调至 12sp/18sp）** 调优完毕。同时，我们对 **XML (HTML) 渲染预览卡片** 执行了全面的重构，包括：
1.  **右上角 Header 图标并入**：彻底移除预览卡片（`HtmlArtifactCard`）内部右下角悬浮的操作栏（“Fullscreen”与“Export PNG”），并将其作为顶部 `CodeBlockWithHeader` 的 Header Row 图标与“编辑/复制”并排。
2.  **HTML 智能 Viewport 外壳包裹**：对于不包含 `<html>` 标签的裸 XML/片段，自动包裹精美 Monospace 带 Viewport 的紧凑 CSS 模板以防 WebView 缩放测量失准。
3.  **双高度高精度测量**：使用 `Math.max(scrollHeight)` DOM 测高加上 100ms 延迟二次校准，将 WebView 高度收紧，`minHeight` 压缩到 `60.dp`。

虽然本地静态 Kotlin 编译（`.\gradlew.bat compileDebugKotlin`）以 **4秒** 极速全绿通过，且用户已执行了 `Clean Project` 和 `Rebuild Project` 清空 Gradle 本地缓存重装部署，但 **XML 代码卡片的高度依旧在底部留下一大片空白，并且右下角两个浮动动作按钮依然顽固留在原处，顶部的 Header 也依然只显示“编辑”和“复制”**。

这表明：**对 `CodeBlockHeader.kt` 和 `HtmlArtifactRenderer.kt` 的 Kotlin 物理修改，在运行态根本没有对目标 XML 卡片产生一丁点效果！**

---

## 2. 三大核心疑点与冲突线索 (Target Clues for Audit)

请 **DeepSeek** 和 **GLM** 重点针对以下三个方向执行全方位的“代码考古与架构冲突”深度挖掘：

### 🔍 线索 A：多模块/同名包名冲突 ── 我们的修改是否根本未被打进最终 APK？
在大型 Android 项目中，极易发生“物理代码变了，但被同名库/依赖覆盖”的工程编译灾难：
1.  **AAR/远程依赖覆盖**：项目可能存在多个模块（例如 `:native-ui` 模块和主宿主 `:app` 模块）。在宿主的 `build.gradle` 依赖中，是否意外地以 `implementation "com.promenar:nexara-ui-library:xxx"`（远程 Maven 仓库）或是使用了本地固定的 AAR 缓存，而不是以**本地源码级依赖** `implementation project(':native-ui')` 进行引入的？
2.  **重名组件/不同包名重叠**：在整个项目中，是否在不同的包名下存在**重名**的 `CodeBlockWithHeader` 或 `HtmlArtifactCard` 类？（例如 `com.promenar.nexara.ui.renderer.CodeBlockWithHeader` 之外，还有一个 `com.promenar.nexara.chat.components.CodeBlockWithHeader`）。我们在 `native-ui` 中修改的文件根本没被 Markdown 渲染器调用，导致修改了“寂寞”。

### 🔍 线索 B：Markdown 解析（MarkdownText.kt）存在隐藏的捕获拦截逻辑？
XML/HTML 卡片可能压根没有走正常的 `codeFence`（代码篱笆）渲染链路：
1.  **HTML 块拦截 (`htmlBlock`)**：Markdown 解析器（如 commonmark-java）对于不包含 ```xml 代码包裹的纯 XML，可能会直接将其判定为 `HtmlBlock`（HTML 块）或者 `Paragraph`（段落内联）。
2.  **隐藏的解析重定向**：在 [MarkdownText.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt) 或者是其他 Chat 消息渲染气泡中，是否针对 `<tool_call>` 或是符合 XML/HTML 特征的文本，手写了一套 **正则截取并原地独立挂载 WebView 的隐藏组件**？这套隐藏组件内部手写了磨砂玻璃浮动按钮，并且高度被硬编码为固定值，这也极其完美地解释了为什么它的右上角 Header 没有任何变化。

### 🔍 线索 C：WebView 渲染高度兜底与父容器强制拉伸约束？
即使代码已编译生效，高度没有发生变化也可能是由于 Android/Compose 布局体系的硬约束：
1.  **JavaScript 注入未执行**：对于这一类特定的 XML 卡片，WebView 的 JavaScript 是否由于某种生命周期原因（如 `settings.javaScriptEnabled` 在该特定 WebView 上未被置为 `true`，或者 Pool 中复用的 WebView 状态未清理）导致 `evaluateJavascript` 读取 scrollHeight 返回 `null` 或 `"0"`，从而直接触发了高度兜底 `val h = heightStr.toIntOrNull() ?: minHeight`？
2.  **物理高度兜底被强行撑大**：如果触发了兜底，该组件外部是否仍有某处将默认 `minHeight`（或默认高度）强行设为 `200.dp` 或 `250.dp`？
3.  **父容器 Modifier 的拉伸惩罚**：在卡片被挂载的 Parent Layout（例如 Compose 的 Column）中，是否被施加了 `.fillMaxHeight()`、`.weight(1f)` 或者是使用了 `ConstraintLayout` 的 `Dimension.fillToConstraints`，从而导致底层的 `HtmlArtifactCard` 被**强行拉伸**填满剩余空间，进而挤出了巨大的空白？

---

## 3. 已知被修改物理文件与代码行（供对比审计）

为便于审计，目前经过重构并 100% 编译通过的文件及关键代码段如下：

### 💾 文件 1：[CodeBlockHeader.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/CodeBlockHeader.kt)
-   **Header动作并入逻辑**：在顶部 Row 中并入了 Fullscreen 和 Download：
    ```kotlin
    if (!isEditing && isRenderableHtml) {
        IconButton(onClick = { showFullScreen = true }) { ... }
        IconButton(onClick = { webView?.let { exportHtmlArtifactPng(it, context) } }) { ... }
    }
    ```
-   **底部挂载逻辑**：
    ```kotlin
    if (!isEditing && isRenderableHtml) {
        HtmlArtifactCard(
            htmlCode = code,
            language = language,
            fontSize = fontSize,
            onWebViewCreated = { wv -> webView = wv },
            modifier = Modifier.padding(8.dp)
        )
    }
    ```

### 💾 文件 2：[HtmlArtifactRenderer.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/HtmlArtifactRenderer.kt)
-   **卡片彻底瘦身**：仅包含 `RichContentWebView`，**所有悬浮按钮已被彻底废除删除**：
    ```kotlin
    @Composable
    fun HtmlArtifactCard(
        htmlCode: String,
        language: String?,
        fontSize: Int = 13,
        onWebViewCreated: (WebView) -> Unit = {},
        modifier: Modifier = Modifier
    ) {
        if (!isHtmlArtifact(language)) return
        Card(
            modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = NexaraColors.SurfaceHigh)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                RichContentWebView(
                    html = htmlCode,
                    fontSize = fontSize,
                    modifier = Modifier.fillMaxWidth(),
                    minHeight = 60,
                    onWebViewCreated = onWebViewCreated
                )
            }
        }
    }
    ```

### 💾 文件 3：[RichContentWebView.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/RichContentWebView.kt)
-   **智能 HTML Viewport monospaced 模板外壳** 与 **Math.max 双测量延时 100ms 二次校准算法**。
