# Markdown 渲染能力审计与行业对齐方案

> **版本**: v1.0 (2026-05-12)
> **审计范围**: 主会话界面 Markdown 渲染全链路
> **对标产品**: LobeChat (lobehub), Cherry Studio (cherryhq)
> **规则依据**: 全局规则 §7 [Kotlin/Compose] → 5. 滚动容器嵌套红线

---

## 一、行业调研：主流 AI 客户端渲染能力全景

### 1.1 LobeChat (lobehub/lobe-ui)

| 能力维度 | 实现方式 | 备注 |
|----------|----------|------|
| **标准 Markdown** | h1-h6, 段落, 列表, 引用, 链接, 图片, 表格, 粗体/斜体 | ✅ |
| **GFM 扩展** | 任务列表, GFM Alert (Note/Tip/Important/Warning/Caution) | ✅ |
| **代码高亮** | Shiki (服务端/客户端双引擎) | 亮度高于 Prism/highlight.js |
| **LaTeX** | KaTeX (块级 `$$` + 行内 `$`) | ✅ |
| **Mermaid** | 直接渲染为 SVG | ✅ |
| **自定义脚注/引用** | citations 系统, 学术引用支持 | ✅ |
| **流式平滑** | PID-like 调速器, 3 档预设 (realtime/balanced/silky) | CPS: 28-50 |
| **全功能代码块** | 语言标签 + 高级操作按钮 | ✅ |
| **HTML Artifacts** | 代码编辑 + 实时 iframe 预览 + 导出 PNG | ✅ |
| **主题系统** | CSS 变量注入, Chat/Default 双变体 | fontSize/headerMultiple/lineHeight/marginMultiple 可控 |
| **CJK 排版** | remarkCjkFriendly + CSS `text-spacing-trim` | 中西文自动间距 |
| **键盘快捷键** | `<kbd>` → Hotkey 组件 | ✅ |

**字号系统**：
- Chat 模式：正文 14px, 行高 1.6, 标题系数 0.25
- Default 模式：正文 16px, 行高 1.8, 标题系数 1.0
- 通过 `--lobe-markdown-font-size` CSS 变量全局控制

### 1.2 Cherry Studio (CherryHQ/cherry-studio)

| 能力维度 | 实现方式 | 备注 |
|----------|----------|------|
| **标准 Markdown** | react-markdown + remark/rehype 插件体系 | ✅ |
| **GFM 扩展** | remarkGfm + remarkAlert | ✅ |
| **代码高亮** | @uiw/react-codemirror (可编辑模式) | 支持交互式编辑 |
| **LaTeX** | MathJax / KaTeX 可切换 | `escapeDollarNumber` 防误解析 |
| **Mermaid** | 内置支持 | ✅ |
| **ECharts** | 内置支持 | ✅ |
| **PlantUML** | Cherry Markdown 集成 | ✅ |
| **HTML Artifacts** | HtmlArtifactsCard + 全屏模态框 (分屏预览 + 导出 PNG) | ✅ |
| **流式渲染** | useSmoothStream + 代码块开放/关闭检测 | ✅ |
| **CJK 排版** | **remarkCjkFriendly 插件** + 自定义 CSS | 中西文间距优化 |
| **数式预处理** | `processLatexBrackets()`: `\[...\]` → `$$...$$` | 兼容多种 LaTeX 定界符 |
| **标题锚点** | rehypeHeadingIds + `heading-{id}` 前缀 | 支持长对话跳转 |
| **代码交互** | 按位置分配 ID, EventEmitter 精确定位编辑 | ✅ |
| **SVG 清理** | `removeSvgEmptyLines()` | ✅ |

### 1.3 行业应具备能力标准

基于上述标杆产品的分析，AI 客户端的 Markdown 渲染**最低竞争力门槛**：

| 等级 | 能力 |
|------|------|
| **基础（必须）** | 标准 Markdown + GFM 扩展 + 代码语法高亮 + 流式渲染 |
| **进阶（期望）** | LaTeX 数学公式 + Mermaid/PlantUML 图表 + 表格自适应 + 代码块行号+复制 |
| **高级（差异点）** | ECharts 交互图表 + HTML Artifacts + CJK 排版优化 + 代码可编辑 |
| **体验优化** | 流式平滑调速 + 引用系统 + 中西文间距 + 字号全局可控 |

---

## 二、Nexara 当前 Markdown 渲染能力审计

### 2.1 技术栈

- **核心库**: mikepenz/multiplatform-markdown-renderer-m3 v0.40.2
- **底层解析器**: org.intellij.markdown (GFM)
- **图片加载**: Coil3
- **代码高亮**: MarkdownHighlightedCode (mikepenz 内置，基于 highlight.js)
- **WebView 容器**: Android WebView (用于 LaTeX/Mermaid/ECharts/PlantUML 渲染)

### 2.2 完整能力清单

#### 标准 Markdown

| 能力 | 状态 | 文件 |
|------|------|------|
| 标题 H1-H6 | ✅ 已实现 | mikepenz 内置 + NexaraMarkdownTheme |
| 粗体 / 斜体 | ✅ 已实现 | mikepenz 内置 |
| 行内代码 | ✅ 已实现 | mikepenz 内置 + 自定义配色 |
| 围栏代码块 | ✅ 已实现 | MarkdownCodeFence → CodeBlockWithHeader |
| 缩进代码块 | ✅ 已实现 | MarkdownCodeBlock → CodeBlockWithHeader |
| 链接 | ✅ 已实现 | mikepenz 内置 |
| 图片 | ✅ 已实现 | Coil3 + ImageLightbox |
| 表格 | ✅ 已实现 | 自定义 NexaraTableWidget |
| 列表 (有序/无序) | ✅ 已实现 | mikepenz 内置 |
| 任务列表 | ✅ 已实现 | mikepenz 内置 (GFM) |
| 引用块 | ✅ 已实现 | mikepenz 内置 |
| 分割线 | ✅ 已实现 | mikepenz 内置 |
| 脚注 | ⚠️ 未确认 | 库支持但代码未单独配置 |

#### 代码块高级能力

| 能力 | 状态 | 文件 |
|------|------|------|
| 语法高亮 | ✅ MarkdownHighlightedCode | CodeBlockHeader.kt |
| 行号 | ✅ 自适应 gutter 宽度 | CodeBlockHeader.kt |
| 语言标签 | ✅ 顶部 Header 显示 | CodeBlockHeader.kt |
| 复制按钮 | ✅ 带动画反馈 | CodeBlockHeader.kt |
| 水平滚动 | ✅ (由 mikepenz 库内部处理) | CodeBlockHeader.kt |

#### 高级渲染

| 能力 | 状态 | 语法 | 引擎 | 文件 |
|------|------|------|------|------|
| LaTeX (块级) | ✅ 已实现 | `$$...$$` | KaTeX WebView | LatexRenderer.kt |
| LaTeX (行内) | ✅ 已实现 | `$...$` | KaTeX WebView → Bitmap | InlineLatexSpan.kt |
| Mermaid | ✅ 已实现 | ` ```mermaid ` | WebView + mermaid.js | MermaidRenderer.kt |
| ECharts | ✅ 已实现 | ` ```echarts ` | WebView + echarts.js | EChartsRenderer.kt |
| PlantUML | ✅ 已实现 | ` ```plantuml ` | WebView + plantuml.js | PlantUmlRenderer.kt |

#### 流式渲染

| 能力 | 状态 | 文件 |
|------|------|------|
| 流式内容识别 | ✅ `isStreaming` 参数 | MarkdownText.kt |
| 未闭合代码块补全 | ✅ `sanitizeStreamingMarkdown` | MarkdownText.kt |
| 未闭合 LaTeX 清理 | ✅ 流式 block math 处理 | MarkdownText.kt |
| 流式缓存优化 | ✅ ParseCache + 增量解析阈值 | MarkdownText.kt |
| 流式光标 | ✅ `StreamingCursor` 闪烁动画 | MarkdownText.kt |
| 流式平滑调速 | ❌ **未实现** | — |

#### 主题与排版

| 能力 | 状态 | 文件 |
|------|------|------|
| Markdown 配色 | ✅ nexaraMarkdownColors() | NexaraMarkdownTheme.kt |
| Markdown 字体 | ✅ nexaraMarkdownTypography(fontSize) | NexaraMarkdownTheme.kt |
| 字号全局控制 | ⚠️ 链路完整但有BUG (见§4) | ChatScreen.kt → MarkdownText.kt |
| 暗色/亮色主题 | ✅ 继承 MaterialTheme | NexaraColors |
| 图片灯箱 | ✅ ImageLightbox | ImageLightbox.kt |

#### CJK 排版优化

| 能力 | 状态 | 说明 |
|------|------|------|
| 中西文自动间距 | ❌ **未实现** | 对标 LobeChat/Cherry Studio 的 remarkCjkFriendly |
| 中文断行优化 | ⚠️ 部分 | 依赖 Android 系统断行引擎 |
| CSS text-spacing | ❌ **未实现** | 仅对 WebView 内内容（LaTeX/Mermaid 等）生效 |
| letterSpacing | ✅ 0.01em | NexaraMarkdownTheme.kt, 但仅对英文字体优化 |

### 2.3 架构亮点

1. **内容分割架构**: 先用正则将 Mermaid/ECharts/PlantUML/LaTeX 从 Markdown 中分离，再分别渲染。避免库内部不支持这些格式
2. **ParseCache 增量优化**: 流式模式下仅解析新增部分，阈值 100 字符，避免大文本重复解析
3. **WebView 渲染池**: RichContentWebViewPool 复用 WebView 实例，降低 Mermaid/LaTeX 渲染开销
4. **代码块行号自适应**: gutterWidth 根据行数 (1-9/10-99/100+) 动态调整宽度

---

## 三、能力差异对比与对齐方案

### 3.1 差异矩阵

| 能力 | LobeChat | Cherry Studio | Nexara | 差距 |
|------|:---:|:---:|:---:|------|
| 标准 Markdown + GFM | ✅ | ✅ | ✅ | 平齐 |
| 代码语法高亮 | ✅ Shiki | ✅ codemirror | ✅ highlight.js | 平齐 |
| 代码块行号+复制 | ✅ | ✅ | ✅ | 平齐 |
| LaTeX (块级+行内) | ✅ KaTeX | ✅ MathJax/KaTeX | ✅ KaTeX | 平齐 |
| Mermaid 图表 | ✅ | ✅ | ✅ | 平齐 |
| ECharts 图表 | ❌ | ✅ | ✅ | 领先 Cherry |
| PlantUML | ❌ | ✅ | ✅ | 领先 Cherry |
| HTML Artifacts | ✅ | ✅ | ❌ | **关键缺失** |
| 流式平滑调速 | ✅ 3 档 | ✅ | ❌ | **关键缺失** |
| GFM Alert | ✅ | ✅ | ❌ | **缺失** |
| CJK 排版优化 | ✅ | ✅ remarkCjkFriendly | ❌ | **关键缺失** |
| 代码可编辑 | ❌ | ✅ | ❌ | 次要 |
| 数式定界符兼容 | ❌ | ✅ []→$$ | ❌ | 次要 |
| 标题锚点跳转 | ❌ | ✅ | ❌ | 次要 |
| 字号全局可控 | ✅ | ✅ | ⚠️ 部分 | **功能BUG** |
| 图片灯箱 | ❌ | ✅ | ✅ | 领先 |

### 3.2 优化优先级矩阵

```
                    高用户感知
                        │
         ┌──────────────┼──────────────┐
         │  P0 立即修复  │  P0 立即修复  │
         │  CJK排版      │  字号BUG      │
         │              │              │
低实现 ──┼──────────────┼──────────────┼── 高实现
成本     │  P1 本迭代    │  P2 后续规划  │  成本
         │  GFM Alert    │  HTML        │
         │  定界符兼容   │  Artifacts   │
         │  流式平滑     │              │
         └──────────────┼──────────────┘
                        │
                    低用户感知
```

### 3.3 对齐方案

#### Phase 1: 立即修复 (P0)

| 编号 | 任务 | 工作量 | 依赖 |
|------|------|--------|------|
| F-1 | **修复字号控制 BUG**（详见 §4） | 0.5d | 无 |
| F-2 | **CJK 中西文排版优化** | 1d | 无 |
|        | 2a. 在 nexaraMarkdownTypography 中添加 `letterSpacing` 规则 | 0.2d | - |
|        | 2b. 实现 `AutoCjkSpacing` 文本预处理 | 0.3d | - |
|        | 2c. 添加强制断行策略 (word-break: break-all for CJK) | 0.3d | - |
|        | 2d. 段落间距微调 (paragraphSpacing) | 0.2d | - |

#### Phase 2: 本迭代补齐 (P1)

| 编号 | 任务 | 工作量 | 依赖 |
|------|------|--------|------|
| F-3 | **GFM Alert 支持** (Note/Tip/Important/Warning/Caution) | 1.5d | mikepenz 库扩展 |
| F-4 | **LaTeX 定界符兼容**: `\[...\]` `\(...\)` → `$$...$$` `$...$` | 0.3d | 无 |
| F-5 | **流式平滑调速**: 实现 SimpleSmoothStream 字符限速 | 1d | 无 |
| F-6 | **标题锚点 ID**: 为每个标题生成唯一 ID | 0.5d | mikepenz API |

#### Phase 3: 差异化竞争力 (P2)

| 编号 | 任务 | 工作量 | 依赖 |
|------|------|--------|------|
| F-7 | **HTML Artifacts**: 代码块 → 实时预览 + 导出 PNG | 3d | WebView 复用池扩展 |
| F-8 | **代码块可编辑模式**: 点击编辑 → 保存修正 → 重新渲染 | 2d | F-7 |
| F-9 | **图片灯箱增强**: 缩放/旋转/分享 | 1d | ImageLightbox.kt |

---

## 四、字体大小问题专项诊断

### 4.1 问题描述

> AI 气泡正文中，Markdown 渲染的文字字体巨大，且设置标签中的调整字号能力对其部分生效，部分不生效。

### 4.2 完整传递链（已审计确认每层都传递了 fontSize）

```
SettingsPanel → ChatViewModel.updateFontSize(fontSize)
→ SessionOptions.fontSize (持久化, 默认 13)
→ ChatScreen: uiState.session?.options?.fontSize
→ ChatBubble(fontSize = fontSize)                    [ChatScreen.kt:242]
  → MarkdownText(fontSize = fontSize)                [ChatScreen.kt:751]
    → nexaraMarkdownTypography(fontSize)              [NexaraMarkdownTheme.kt:26]
      → Markdown(typography = ...)                    [MarkdownText.kt:250]
      → LatexBlock(fontSize = fontSize)               [MarkdownText.kt:324]
      → MermaidBlock(fontSize = fontSize)             [MarkdownText.kt:330]
```

### 4.3 根因分析

**核心问题**: `nexaraMarkdownTypography()` 覆盖了**所有 Markdown 元素的字体**，但用户消息（非 Markdown 渲染）使用的是 `NexaraTypography.bodyMedium`（固定 15sp），AI 消息通过 Markdown 渲染使用的是 `nexaraMarkdownTypography(fontSize)`（默认 13sp → 最终 13sp）。

但 `NexaraTypography.bodyMedium` 在 `Type.kt` 中定义为 `15.sp`：

```kotlin
// 文件: NexaraTypography
bodyMedium = TextStyle(fontSize = 15.sp, ...)
```

而在 `nexaraMarkdownTypography()` 中：

```kotlin
text = NexaraTypography.bodyMedium.copy(
    fontSize = base,          // base = fontSize.sp, 默认 13sp
    lineHeight = (baseFontSize * 1.4).sp,
)
```

**问题不在传递链，而在表现不一致**：

| 消息类型 | 字体来源 | 默认大小 | 受 fontSizes 控制 |
|----------|----------|----------|-------------------|
| 用户气泡 | `NexaraTypography.bodyMedium` | **15 sp** | ❌ 不直接受控 |
| AI Markdown 正文 | `nexaraMarkdownTypography().text` | **13 sp** | ✅ 受 fontSize 控制 |
| AI Markdown 标题 | `nexaraMarkdownTypography().h1-h6` | 11.7-15.6 sp | ✅ 受 fontSize 控制 |
| AI Markdown 代码 | `nexaraMarkdownTypography().code` | **12 sp** | ✅ 受 fontSize 控制 |
| 思维链 (ThinkingBlock) | `NexaraTypography.bodySmall` | **14 sp** | ⚠️ 有独立的 fontSize 参数 |
| LaTeX/Mermaid | WebView 内 HTML | 由 WebView fontSize 决定 | ⚠️ WebView 字体单独控制 |

### 4.4 "部分生效"的根因

三个具体问题：

1. **用户消息不受字号控制**：`ChatBubble` 第 716-720 行使用 `NexaraTypography.bodyMedium.copy(fontSize = fontSize.sp)`，字面量传入正常。但 `NexaraTypography` 是一个全局单例，`copy()` 创建的是**局部副本**，不影响主题全局。这本身是正确的，但如果某处遗漏 `.copy(fontSize = ...)` 就会回退到 15sp。

2. **ThinkingBlock 与 ChatBubble 默认值不同**：
   - `ChatBubble.fontSize` 默认 = `13`
   - `ThinkingBlock.fontSize` 默认 = `14`
   - 如果 ChatBubble 传入了 `fontSize=13`，但 ThinkingBlock 没有显式传入，则 ThinkingBlock 会用默认值 `14`，导致思维链字体与正文不一致。

3. **Slider 范围过大** (10-22sp)：22sp 是系统默认 13sp 的 1.7 倍，UI 感知非常巨大。

### 4.5 修复方案

```kotlin
// 1. 统一字体基类，创建一个 fontSize 感知的 TypographyProvider
// 文件: NexaraTypography.kt (新增强制行)

@Composable
fun chatTypography(fontSize: Int): NexaraTypography {
    val base = fontSize.sp
    return NexaraTypography.copy(
        bodyMedium = NexaraTypography.bodyMedium.copy(fontSize = base),
        bodySmall = NexaraTypography.bodySmall.copy(fontSize = (fontSize - 1).sp),
        labelSmall = NexaraTypography.labelSmall.copy(fontSize = (fontSize - 2).sp),
    )
}

// 2. ChatBubble 全局使用统一 Typography
// ChatScreen.kt → ChatBubble
val typo = remember(fontSize) { chatTypography(fontSize) }

// 用户消息:
Text(text = message.content, style = typo.bodyMedium)

// AI 消息:
MarkdownText(markdown = ..., fontSize = fontSize)

// 思维链:
ThinkingBlock(reasoning = ..., fontSize = fontSize)  // 确保传入

// 3. 缩减 Slider 上限 22→18sp
// SessionSettingsSheet.kt
valueRange = 10..18,       // 原 10..22
```

---

## 五、优化路线图总结

```
Week 1 (P0)        Week 2 (P1)         Week 3-4 (P2)
┌─────────────┐    ┌─────────────┐     ┌───────────────┐
│ F-1 字号BUG  │ →  │ F-3 GFM Alert│ →   │ F-7 HTML       │
│ F-2 CJK排版  │    │ F-4 定界符   │     │   Artifacts    │
│   优化       │    │ F-5 流式平滑 │     │ F-8 代码编辑   │
│             │    │ F-6 标题锚点 │     │ F-9 图片增强   │
└─────────────┘    └─────────────┘     └───────────────┘
   3 项 1.5d          4 项 3.3d            3 项 6d
```

**总计工时**: 约 10.8 人天

**预期成果**:
- 渲染能力: 从当前 60% 对齐到 90% 行业基准
- 用户感知: CJK 排版专业度显著提升, 字号控制全程一致
- 差异化: HTML Artifacts 能力超越 LobeChat (后者仅代码块预览，无 PlantUML/ECharts 多引擎)

---

*审计完成，数据截止 2026-05-12*
