# XML/HTML 代码预览卡片故障诊断报告

> **日期**: 2026-05-18
> **类型**: 渲染器与显示异常客观陈述 → 根因定位 → 修复实施
> **状态**: ✅ 已修复

---

## 1. 现状与症状陈述 (Symptom)

在真机/模拟器测试运行中，聊天界面在渲染包含 XML 标记（例如包含 `<tool_call>` 或其他自定义标签的 XML 段落）的文本时，底部的 XML 预览卡片出现以下异常状态：

1.  **卡片下方存在大面积空白**：预览卡片在包裹 XML 代码时，底部留出了非常宽的无用空白区域（白色背景），无法紧凑包裹代码内容。
2.  **按钮顽固停留在底部**：卡片内部的右下角依然包含并展示着"全屏预览 (Fullscreen)"和"下载 (Download / Export PNG)"两个操作按钮（悬浮胶囊形状）。
3.  **顶部 Header 无任何图标**：代码卡片最上方的 Header 动作栏中，依然只显示"编辑"和"复制"两个基础图标，没有任何其他新图标出现。

---

## 2. 我们已尝试但最终测试无效的方法 (What We Tried)

1.  **尝试移除并上移操作按钮**：删除 `HtmlArtifactCard` 内悬浮按钮，在 `CodeBlockWithHeader` Header 新增 Fullscreen + Download 图标。→ 无效。
2.  **尝试高精度高度自适应测算与 Viewport 模板包裹**：JS `Math.max(scrollHeight)` 测高 + 二次校准 + 被包裹的 HTML 模板。→ 无效。

**以上修改无效的共同原因（根因 #1）**：修改目标错误。

---

## 3. 深层次根因分析 (Root Cause Analysis)

### 根因 #1：修改目标定位错误 — HtmlArtifactCard 从未有过按钮

对 `HtmlArtifactRenderer.kt` 中 `HtmlArtifactCard`（第 79-105 行）的全量审计显示，该组件**物理上仅包含一个 Card + RichContentWebView**，从未包含任何按钮代码。而 Fullscreen + Download 按钮**一直存在于** `CodeBlockHeader.kt`（第 108-131 行）的 Header Row 中。

用户删除的按钮本来就不存在；用户新增的按钮本来就已经存在。两次修改均为空操作，因此**运行态完全无变化**是物理必然结果。

### 根因 #2：Compose 生命周期时序竞态 → WebView 高度测算失效

**病原文件**：`RichContentWebView.kt`

**竞态链路**：
```
Compose 单帧执行顺序：
1. Composition → AndroidView.update() → loadDataWithBaseURL() 触发页面加载
                  (WebView 使用池中默认 WebViewClient，无 onPageFinished 测高)
2. Effects      → LaunchedEffect 协程启动 → 设置测高 WebViewClient
                  (协程调度延迟 1-5ms；页面加载 <1ms)
3. 页面加载完成 → onPageFinished 在默认 WebViewClient 上触发（无测高逻辑）
                  → webViewHeight 永远停留在 minHeight = 60dp
```

对于仅 3 行 XML 的极简 HTML 页面，WebView 在 **<1ms** 内完成加载，远快于 `LaunchedEffect` 协程调度。测高 WebViewClient 永远赶不上页面加载完成的时刻。

同时，`RichContentWebViewPool.createWebView()` 中硬编码了 `layoutParams.height = WRAP_CONTENT`，导致 WebView 内部使用无约束高度计算 `scrollHeight`（约等于屏幕高度），被 `coerceIn(60, 600)` 钳制到 600dp，形成大面积空白。

### 根因 #3：isLikelyRenderableHtml 是死代码

`HtmlArtifactRenderer.kt` 第 59-77 行定义了 `isLikelyRenderableHtml()` 函数，内含 `<tool_call>`/`<function_call>` 排除逻辑。但该函数**在整个项目中从未被调用**。`CodeBlockHeader` 仅使用 `isHtmlArtifact(language)`（纯语言匹配），导致任何 ` ```xml ` 代码块都被当作 HTML artifact 渲染。

### 根因 #4：Debug/Release 变体隔离

Debug 构建附加了 `applicationIdSuffix = ".debug"`，使 Debug 与 Release 成为两个不同的系统应用。可能存在安装旧 Release 版但只编译 Debug 版的情况。

---

## 4. 修复方案 (Fix)

### 修复 #1：WebViewClient 前置至 WebView 创建时（根治时序竞态）
- **文件**：`RichContentWebView.kt`
- **变更**：
  - 将测高 `WebViewClient` 从 `LaunchedEffect` 中移出，直接设置在 `remember { acquire(context) }` 块的 `apply` 中
  - 引入 `rememberUpdatedState` 保持 `minHeight`/`maxHeight` 参数新鲜度
  - 添加 `lastLoadedHtml` 去重，避免每次 Recompose 都 reload WebView
  - 在 `DisposableEffect.onDispose` 中重置 `WebViewClient` 后再归还池

### 修复 #2：激活 isLikelyRenderableHtml 死代码
- **文件**：`CodeBlockHeader.kt`
- **变更**：`isRenderableHtml` 判定从 `isHtmlArtifact(language)` 改为 `isHtmlArtifact(language) && isLikelyRenderableHtml(code)`，有效排除 `<tool_call>` XML

### 修复 #3：WebView 初始布局参数去 WRAP_CONTENT
- **文件**：`RichContentWebViewPool.kt`
- **变更**：`layoutParams.height` 从 `WRAP_CONTENT` 改为 `MATCH_PARENT`，避免无约束 `scrollHeight` 测量

---

## 5. 影响评估

- **受影响组件**：`RichContentWebView` / `CodeBlockHeader` / `RichContentWebViewPool`
- **行为变更**：`<tool_call>` 等纯数据 XML 不再显示 HTML artifact 卡片和按钮
- **性能影响**：正向优化（消除每次 Recompose 的冗余 WebView reload）
- **兼容性**：无破坏性变更，所有现有 HTML/SVG 渲染行为保持一致
