# XML/HTML 代码预览卡片故障诊断报告

> **日期**: 2026-05-18
> **类型**: 渲染器与显示异常客观陈述

---

## 1. 现状与症状陈述 (Symptom)

在真机/模拟器测试运行中，聊天界面在渲染包含 XML 标记（例如包含 `<tool_call>` 或其他自定义标签的 XML 段落）的文本时，底部的 XML 预览卡片出现以下异常状态：

1.  **卡片下方存在大面积空白**：预览卡片在包裹 XML 代码时，底部留出了非常宽的无用空白区域（白色背景），无法紧凑包裹代码内容。
2.  **按钮顽固停留在底部**：卡片内部的右下角依然包含并展示着“全屏预览 (Fullscreen)”和“下载 (Download / Export PNG)”两个操作按钮（悬浮胶囊形状）。
3.  **顶部 Header 无任何图标**：代码卡片最上方的 Header 动作栏中，依然只显示“编辑”和“复制”两个基础图标，没有任何其他新图标出现。

---

## 2. 我们已尝试但最终测试无效的方法 (What We Tried)

为了解决上述问题，我们在本地对 native UI 的 Kotlin 物理代码进行了如下修改，并执行了 Gradle Clean & Rebuild 重装，但**在测试中依然完全没有产生任何变化，上述三个症状依旧 100% 存在**：

1.  **尝试移除并上移操作按钮**：
    *   在 Kotlin 代码中，我们彻底删除了预览卡片（`HtmlArtifactCard` / `HtmlArtifactRenderer.kt`）内部右下角关于“全屏预览”和“下载 PNG”这两个悬浮按钮的全部 UI 代码，确保该卡片在物理上仅剩下一个 WebView。
    *   在顶部 `CodeBlockWithHeader` 的 Header Row 动作栏中，新增了 Fullscreen 和 Download 两个 IconButton。
    *   *测试结果*：运行态卡片底部按钮依旧存在，顶部 Header 依旧无新按钮，代码修改在运行态完全没有生效。
2.  **尝试高精度高度自适应测算与 Viewport 模板包裹**：
    *   在 `RichContentWebView` 加载数据时，对裸 XML 自动包裹带 Viewport 和 margin 归零的 HTML Monospace 模板，并调用 JS `evaluateJavascript` 提取 `Math.max(scrollHeight)`，在 `onPageFinished` 触发 100ms 后进行二次校准刷新高度。
    *   将 HTML 预览容器的 `minHeight` 压缩调整为 `60.dp`，废除之前写死的 `200.dp` 硬编码限制。
    *   *测试结果*：WebView 底部的大面积空白依然没有任何缩紧或高度变化。

---

## 3. 预期的正确效果 (Expected Results)

我们预期的完美渲染及交互行为应当如下：

1.  **高度紧凑包裹（零空白残留）**：XML 预览卡片的高度应当能够根据其内部渲染的代码行数完美自适应。例如仅有 3 行 XML 代码时，卡片高度应该刚好收紧包裹这 3 行字，底部不留任何白底和空余。
2.  **按钮合并至顶部 Header**：卡片内部及右下角没有任何悬浮按钮；“全屏预览”和“下载 PNG”以纯图标按钮的形式，直接合并并列展现在代码块最上方的 Header 动作栏中（即与“编辑”、“复制”图标在同一行内展示）。
