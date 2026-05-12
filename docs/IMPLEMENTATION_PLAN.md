# Markdown 渲染优化分阶段实施计划

> **版本**: v1.0 (2026-05-12)
> **前置阅读**: `docs/MARKDOWN_RENDERING_AUDIT.md`（必需）
> **项目路径**: `k:/Nexara/native-ui`
> **技术栈**: Kotlin + Jetpack Compose + mikepenz Markdown v0.40.2

---

## 并行执行总览

```
Phase 1 (P0) ──── 4 个 Agent 完全并行 ────
  │
  ├─ Agent A: P0-T1  字号统一修复      (0.5h)
  ├─ Agent B: P0-T2  CJK中西文间距     (0.5h)
  ├─ Agent C: P0-T3  段落排版+断行      (0.5h)
  └─ Agent D: P0-T4  WebView字号联动   (0.3h)

Phase 2 (P1) ──── 4 个 Agent 完全并行 (Phase 1 完成后) ────
  │
  ├─ Agent E: P1-T1  GFM Alert 支持    (1.5h)
  ├─ Agent F: P1-T2  LaTeX定界符兼容   (0.3h)
  ├─ Agent G: P1-T3  流式平滑调速      (1h)
  └─ Agent H: P1-T4  标题锚点ID        (0.5h)

Phase 3 (P2) ──── 串行 + 并行混合 ────
  │
  ├─ Agent I: P2-T1  HTML Artifacts    (3h) ──┐
  │                                          ├─ 依赖 I 完成
  └─ Agent J: P2-T2  代码可编辑模式    (2h) ──┘
  └─ Agent K: P2-T3  图片灯箱增强      (1h) ← 独立并行
```

---

## Phase 1: P0 立即修复（4 个 Agent 并行）

所有 4 个任务互不依赖，修改的文件完全无重叠，可同时启动。

### P0-T1：字号统一修复

| 属性 | 值 |
|------|-----|
| **依赖** | 无 |
| **并行组** | Phase 1 (可与 T2/T3/T4 同时执行) |
| **文件列表** | `NexaraTypography.kt`, `ChatScreen.kt`, `SessionSettingsSheet.kt`, `NexaraMarkdownTheme.kt` |

**提示词**:

```
## 任务：Nexara AI 气泡字号统一修复

### 背景
当前项目存在 3 个字号默认值不一致的问题：
- `ChatBubble.fontSize` 默认=13
- `ThinkingBlock.fontSize` 默认=14  
- `NexaraTypography.bodyMedium.fontSize` 硬编码=15sp
- Slider 上限 22sp 过大

导致设置中调整字号后，部分文本生效、部分不生效。

### 项目路径
k:/Nexara/native-ui

### 需要修改的文件

#### 1. 创建统一的 chatTypography 函数

**文件**: `app/src/main/java/com/promenar/nexara/ui/theme/Type.kt`

查找 `NexaraTypography` 定义，在文件末尾（Class/object 定义之后）新增：

```kotlin
@Composable
fun chatTypography(fontSize: Int): NexaraTypography {
    val base = fontSize.sp
    return NexaraTypography.copy(
        bodyMedium = NexaraTypography.bodyMedium.copy(
            fontSize = base,
            lineHeight = (fontSize * 1.5).sp
        ),
        bodySmall = NexaraTypography.bodySmall.copy(
            fontSize = (fontSize - 1).sp,
            lineHeight = ((fontSize - 1) * 1.4).sp
        ),
        labelSmall = NexaraTypography.labelSmall.copy(
            fontSize = (fontSize - 2).sp
        ),
        labelMedium = NexaraTypography.labelMedium.copy(
            fontSize = (fontSize - 1).sp
        ),
    )
}
```

#### 2. ChatScreen.kt 中统一字号来源

**文件**: `app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt`

在 ChatBubble 函数中（约第 687-695 行），找到 fontSize 参数的默认值定义：

```
fun ChatBubble(
    message: Message,
    isGenerating: Boolean = false,
    streamingContent: String = "",
    onApprove: () -> Unit = {},
    onDecline: () -> Unit = {},
    onLongClick: () -> Unit = {},
    fontSize: Int = 13   // ← 保持不变
)
```

确认第 242 行附近调用 ChatBubble 时传入 `fontSize = uiState.session?.options?.fontSize ?: 13`

检查用户消息 Text 组件（约 716-720 行）：确保使用 `fontSize.sp` 的 `.copy()` 而非直接用 `NexaraTypography.bodyMedium`

#### 3. 修复 ThinkingBlock 默认值不一致

**文件**: `app/src/main/java/com/promenar/nexara/ui/chat/ChatInlineComponents.kt`

找到 `ThinkingBlock` composable 的 fontSize 参数（约第 84 行），将默认值从 `14` 改为 `13`：

```kotlin
fun ThinkingBlock(
    reasoning: String,
    isGenerating: Boolean = false,
    fontSize: Int = 13   // 从 14 改为 13，与 ChatBubble 对齐
)
```

确认 ThinkingBlock 内部 MarkdownText 调用传入了 fontSize（约 168-171 行）。

#### 4. 缩减 Slider 范围

**文件**: `app/src/main/java/com/promenar/nexara/ui/chat/SessionSettingsSheet.kt`

找到 NexaraSliderInt 调用（约 773 行），将范围从 `10..22` 缩减为 `10..18`：

```kotlin
NexaraSliderInt(
    value = currentFontSize,
    onValueChange = { ... },
    onValueChangeFinished = { ... },
    valueRange = 10..18,     // 原 10..22
    steps = 8,               // 原 12
    modifier = Modifier.weight(1f)
)
```

#### 5. NexaraMarkdownTheme 中移除 NexaraTypography 硬编码依赖

**文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/NexaraMarkdownTheme.kt`

确认 `nexaraMarkdownTypography(fontSize)` 函数正确使用传入参数：
- 正文 `text` 使用 `base = fontSize.sp`
- 行内代码 `inlineCode` 也使用 `base`（目前未设置 inlineCode，需要添加）

在返回的 `markdownTypography()` 中添加 `inlineCode`：

```kotlin
inlineCode = NexaraTypography.bodySmall.copy(
    fontSize = base,
    fontFamily = FontFamily.Monospace,
    background = NexaraColors.SurfaceHigh,
    lineHeight = (baseFontSize * 1.3).sp
),
```

### 验证标准
- 设置中调整字号 10→18 时，用户气泡、AI Markdown 正文、思维链三者字号同步变化
- 默认值 13sp 时，所有文本大小一致
- ThinkingBlock 字体不再比正文大一号
```

### P0-T2：CJK 中西文自动间距

| 属性 | 值 |
|------|-----|
| **依赖** | 无 |
| **并行组** | Phase 1 (可与 T1/T3/T4 同时执行) |
| **文件列表** | `MarkdownText.kt`（新增函数）, `NexaraMarkdownTheme.kt`（微调） |

**提示词**:

```
## 任务：Nexara Markdown CJK 中西文自动间距优化

### 背景
LobeChat 和 Cherry Studio 都已实现 `remarkCjkFriendly` 插件——自动在中文与西文（英文/数字）之间插入微间距（窄空格），显著提升混合文本可读性。

Nexara 当前没有此优化，中文与英文紧贴显示，视觉拥挤。

### 项目路径
k:/Nexara/native-ui

### 需要修改的文件

#### 1. 新增 CJK 间距预处理函数

**文件**: `app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt`

在文件末尾（`private fun StreamingCursor()` 之后，或 `private fun sanitizeStreamingMarkdown` 旁边）新增：

```kotlin
/**
 * 在中文字符与西文/数字之间自动插入 hair space (U+200A)，
 * 对标 LobeChat/Cherry Studio 的 remarkCjkFriendly 能力。
 * 在传入 Markdown 解析器之前调用。
 */
private fun insertCjkSpacing(text: String): String {
    // CJK 字符范围: \u4e00-\u9fff (CJK Unified), \u3400-\u4dbf (CJK Extension A)
    // 西文: a-zA-Z, 数字: 0-9
    val cjkRange = "\\u4e00-\\u9fff\\u3400-\\u4dbf\\uf900-\\ufaff\\u3000-\\u303f"

    return text
        // 中文后跟西文/数字 → 插入窄空格
        .replace(Regex("([$cjkRange])([a-zA-Z0-9])")) { "${it.groupValues[1]}\u200A${it.groupValues[2]}" }
        // 西文/数字后跟中文 → 插入窄空格
        .replace(Regex("([a-zA-Z0-9])([$cjkRange])")) { "${it.groupValues[1]}\u200A${it.groupValues[2]}" }
}
```

#### 2. 在 MarkdownText 渲染流程中调用

**文件**: 同上 `MarkdownText.kt`

在 `MarkdownText` composable 中，找到 `processed` 变量的定义（约第 136 行），在 `sanitizeStreamingMarkdown` 之后、传入 splitRichSegments 之前插入间距处理：

```kotlin
val processed = remember(markdown, isStreaming) {
    val sanitized = if (isStreaming) sanitizeStreamingMarkdown(markdown) else markdown
    insertCjkSpacing(sanitized)   // ← 新增此行
}
```

#### 3. 微调 letterSpacing 以适配中文

**文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/NexaraMarkdownTheme.kt`

在 `nexaraMarkdownTypography` 函数中，正文 `text` 的 `letterSpacing` 调整为更友好的中文值：

```kotlin
text = NexaraTypography.bodyMedium.copy(
    fontSize = base,
    lineHeight = (baseFontSize * 1.5).sp,   // 微增行高适配中文
    letterSpacing = 0.sp                     // 中文不需要字母间距
),
```

### 验证标准
- 混合中英文文本中，"使用React开发"显示为"使用 React 开发"（视觉微间距）
- AI 模型输出的 Markdown 中英文混排段落可读性明显提升
- 纯中文段落或纯英文段落不受影响
```

### P0-T3：段落排版与断行优化

| 属性 | 值 |
|------|-----|
| **依赖** | 无 |
| **并行组** | Phase 1 (可与 T1/T2/T4 同时执行) |
| **文件列表** | `NexaraMarkdownTheme.kt` |

**提示词**:

```
## 任务：Nexara Markdown 段落排版与断行优化

### 背景
中文 Markdown 排版标准要求：
1. 段落间距应有清晰的视觉层次
2. 中英文混排行不应在英文单词中间断开，但中文可在任意字符间换行
3. 引用块、列表项需要适当的边距

当前 Nexara 的 MarkdownTypography 缺少段落级别排版控制。

### 项目路径
k:/Nexara/native-ui

### 需要修改的文件

#### 1. 增强 nexaraMarkdownTypography 的段落排版

**文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/NexaraMarkdownTheme.kt`

完整重写 `nexaraMarkdownTypography` 函数，增加段落排版参数：

```kotlin
@Composable
fun nexaraMarkdownTypography(baseFontSize: Int = 13): MarkdownTypography {
    val base = baseFontSize.sp
    val h1 = (baseFontSize * 1.2).sp
    val h2 = (baseFontSize * 1.15).sp
    val h3 = (baseFontSize * 1.1).sp
    val h4 = (baseFontSize * 1.1).sp
    val h5 = base
    val h6 = (baseFontSize * 0.9).sp

    return markdownTypography(
        h1 = NexaraTypography.headlineLarge.copy(
            fontSize = h1,
            lineHeight = (h1.value * 1.4).sp
        ),
        h2 = NexaraTypography.headlineMedium.copy(
            fontSize = h2,
            lineHeight = (h2.value * 1.4).sp
        ),
        h3 = NexaraTypography.headlineMedium.copy(
            fontSize = h3,
            lineHeight = (h3.value * 1.4).sp
        ),
        h4 = NexaraTypography.headlineMedium.copy(
            fontSize = h4,
            fontWeight = FontWeight.SemiBold,
            lineHeight = (h4.value * 1.4).sp
        ),
        h5 = NexaraTypography.headlineMedium.copy(
            fontSize = h5,
            fontWeight = FontWeight.Medium,
            lineHeight = (h5.value * 1.4).sp
        ),
        h6 = NexaraTypography.headlineMedium.copy(
            fontSize = h6,
            fontWeight = FontWeight.Medium,
            lineHeight = (h6.value * 1.4).sp
        ),
        text = NexaraTypography.bodyMedium.copy(
            fontSize = base,
            lineHeight = (baseFontSize * 1.6).sp,  // 中文正文行高 1.6
        ),
        code = NexaraTypography.bodySmall.copy(
            fontSize = (baseFontSize - 1).sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = (baseFontSize * 1.4).sp
        ),
        inlineCode = NexaraTypography.bodySmall.copy(
            fontSize = base,
            fontFamily = FontFamily.Monospace,
            lineHeight = (baseFontSize * 1.3).sp
        ),
        // 新增：段落间距
        paragraph = NexaraTypography.bodyMedium.copy(
            lineHeight = (baseFontSize * 1.6).sp
        ),
        // 新增：引用块样式
        quote = NexaraTypography.bodyMedium.copy(
            fontSize = base,
            lineHeight = (baseFontSize * 1.5).sp
        ),
    )
}
```

#### 2. 如果 mikepenz MarkdownTypography 不支持 paragraph/quote 直接设置

则改用以下方式——在 MarkdownText.kt 中通过 `modifier` 控制：

**文件**: `app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt`

在 `Markdown()` 组件的 modifier 参数中（约第 291 行），确保段落间距可控：

```kotlin
Markdown(
    content = segment.content,
    colors = nexaraMarkdownColors(),
    typography = nexaraMarkdownTypography(fontSize),
    components = markdownComponents(/* ... */),
    modifier = Modifier.fillMaxWidth()
)
```

不需要额外修改——mikepenz 的 Markdown 组件已通过 typography 参数控制字号和行高。

### 验证标准
- Markdown 段落之间视觉间距合理（约 1.5 倍行高）
- 中英文混排文本在窄屏幕（手机竖屏）上不异常断行
- H1-H6 标题与正文的层次感清晰
```

### P0-T4：WebView 字号联动

| 属性 | 值 |
|------|-----|
| **依赖** | 无 |
| **并行组** | Phase 1 (可与 T1/T2/T3 同时执行) |
| **文件列表** | `EChartsRenderer.kt`, `PlantUmlRenderer.kt`, `RichContentWebView.kt` |

**提示词**:

```
## 任务：Nexara ECharts/PlantUML WebView 字号联动修复

### 背景
P0-T1 修复后，Markdown 正文、LaTeX、Mermaid 的字号都能随设置调节。但 ECharts 和 PlantUML 使用独立的 WebView 渲染，目前未接入 fontSize 参数，内部文本仍为硬编码大小。

### 项目路径
k:/Nexara/native-ui

### 需要修改的文件

#### 1. ECharts 渲染器接入字号

**文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/EChartsRenderer.kt`

找到 `EChartsBlock` composable，添加 `fontSize: Int = 13` 参数，并传递给 `RichContentWebView`：

```kotlin
@Composable
fun EChartsBlock(
    optionJson: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 13    // 新增参数
) {
    // ... 现有逻辑 ...

    RichContentWebView(
        html = html,
        modifier = modifier,
        fontSize = fontSize,  // ← 确保传入
        minHeight = 300,
        maxHeight = 600
    )
}
```

#### 2. PlantUML 渲染器接入字号

**文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/PlantUmlRenderer.kt`

同上，添加 `fontSize: Int = 13` 参数并传递给 `RichContentWebView`。

#### 3. 在 MarkdownText 中传递 fontSize

**文件**: `app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt`

找到 `EChartsBlock` 和 `PlantUmlBlock` 的调用处（约第 304-308 行），确保传入 fontSize：

```kotlin
is ContentSegment.ECharts -> {
    EChartsBlock(optionJson = segment.content, fontSize = fontSize)
}
is ContentSegment.PlantUml -> {
    PlantUmlBlock(code = segment.content, fontSize = fontSize)
}
```

检查是否已经传入——如果已传入则不需修改。

#### 4. 确认 RichContentWebView 正确处理 fontSize

**文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/RichContentWebView.kt`

检查 `RichContentWebView` composable 是否接受 `fontSize: Int` 参数，并注入到 WebView 的 HTML 模板中（通过 CSS `body { font-size: ${fontSize}px; }`）。

如果已实现则跳过。

### 验证标准
- 设置中调字号后，ECharts 图表内文本大小同步变化
- PlantUML 图表内文本同步变化  
- LaTeX 和 Mermaid 已正常（作为对照）
```

---

## Phase 2: P1 本迭代补齐（4 个 Agent 并行）

Phase 2 所有任务在 Phase 1 全部完成后启动。4 个任务修改的文件互不重叠，可并行。

### P1-T1：GFM Alert 支持

| 属性 | 值 |
|------|-----|
| **依赖** | Phase 1 完成（无代码依赖，但需基于修复后的基线） |
| **并行组** | Phase 2 (可与 T2/T3/T4 同时执行) |
| **文件列表** | `MarkdownText.kt`（新增 GFM Alert 组件）, `NexaraMarkdownTheme.kt`（新增配色） |

**提示词**:

```
## 任务：Nexara GFM Alert（GitHub 风格警告块）渲染支持

### 背景
LobeChat 和 Cherry Studio 都支持 GFM Alert 语法：

```markdown
> [!NOTE]
> 这是一个提示信息

> [!TIP]
> 这是一个小贴士

> [!IMPORTANT]
> 重要信息

> [!WARNING]
> 警告内容

> [!CAUTION]
> 危险警告
```

Nexara 当前将 Alert 块当普通引用渲染，丢失了语义和视觉区分。

### 项目路径
k:/Nexara/native-ui

### 需要创建/修改的文件

#### 1. 新建 GFM Alert 组件

**新建文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/GfmAlertBlock.kt`

创建 5 种 Alert 类型的 Composable 组件：

```kotlin
package com.promenar.nexara.ui.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.ui.theme.NexaraColors

enum class GfmAlertType(val label: String, val emoji: String) {
    NOTE("NOTE", "📝"),
    TIP("TIP", "💡"),
    IMPORTANT("IMPORTANT", "❗"),
    WARNING("WARNING", "⚠️"),
    CAUTION("CAUTION", "🔥")
}

private fun parseGfmAlert(text: String): Pair<GfmAlertType, String>? {
    val regex = Regex("""^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\s*\n""", RegexOption.IGNORE_CASE)
    val match = regex.find(text) ?: return null
    val type = GfmAlertType.entries.first { it.label.equals(match.groupValues[1], ignoreCase = true) }
    val content = text.substring(match.range.last + 1).trim()
    return type to content
}

@Composable
fun GfmAlertBlock(
    quoteContent: String,
    fontSize: Int = 13,
    modifier: Modifier = Modifier
) {
    val parsed = parseGfmAlert(quoteContent)
    if (parsed == null) return  // 不是 GFM Alert，由默认引用渲染处理

    val (type, content) = parsed
    val accentColor = when (type) {
        GfmAlertType.NOTE -> Color(0xFF0969DA)
        GfmAlertType.TIP -> Color(0xFF1A7F37)
        GfmAlertType.IMPORTANT -> Color(0xFF8250DF)
        GfmAlertType.WARNING -> Color(0xFF9A6700)
        GfmAlertType.CAUTION -> Color(0xFFCF222E)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accentColor.copy(alpha = 0.08f))
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Text(
            text = "${type.emoji} ${type.label}",
            fontWeight = FontWeight.SemiBold,
            fontSize = (fontSize - 1).sp,
            color = accentColor,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = content,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.5).sp,
            color = NexaraColors.OnBackground
        )
    }
}
```

#### 2. 集成到 Markdown 渲染流程

**文件**: `app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt`

两种方案选其一：

**方案 A（推荐）**：在内容传入 Markdown 组件前预处理，将 GFM Alert 语法转为内容块单独渲染。

在 `splitRichSegments` 中或新增预处理步骤，将 `> [!NOTE]\n...` 模式识别为独立的 ContentSegment：

```kotlin
// 新增 ContentSegment 类型
data class GfmAlert(val type: String, val content: String) : ContentSegment()
```

**方案 B（简单）**：通过 mikepenz 的 `blockquote` custom component 拦截引用块，在其中检测 GFM Alert 语法。

在 `markdownComponents` 的 `blockquote` 回调中调用 `GfmAlertBlock` 组件。

推荐方案 B，因为不需要修改内容分割逻辑。

具体实现：在 MarkdownText.kt 的 `markdownComponents` 块中添加（约 224-290 行位置）：

```kotlin
components = markdownComponents(
    // ... 现有 components ...
    blockquote = { model ->
        val isGfmAlert = parseGfmAlert(model.content)
        if (isGfmAlert != null) {
            GfmAlertBlock(
                quoteContent = model.content,
                fontSize = fontSize
            )
        }
        // else: 使用 mikepenz 默认引用渲染（不需要额外处理）
    },
)
```

需要确认 mikepenz 库版本 v0.40.2 是否支持 `blockquote` component override。如果 API 不支持，则回退到方案 A（内容预处理）。

#### 3. 更新 NexaraMarkdownTheme 配色

**文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/NexaraMarkdownTheme.kt`

如果需要使用 NexaraColors 中的语义色，在 `nexaraMarkdownColors()` 中确认：
- `codeBackground` 已设置
- `dividerColor` 已设置

不需要额外修改（GFM Alert 颜色已在 GfmAlertBlock.kt 中硬编码为符合 GitHub 规范的色彩）。

### 验证标准
- 在对话中输入 `> [!TIP]\n> 这是测试` → 渲染为带绿色边框和 💡 图标的提示框
- 5 种 Alert 类型均有正确颜色和图标
- 普通引用 `> 普通引用` 不受影响
```

### P1-T2：LaTeX 定界符兼容

| 属性 | 值 |
|------|-----|
| **依赖** | Phase 1 完成 |
| **并行组** | Phase 2 (可与 T1/T3/T4 同时执行) |
| **文件列表** | `MarkdownText.kt` |

**提示词**:

```
## 任务：Nexara LaTeX 定界符兼容 —— 支持 `\[...\]` 和 `\(...\)` 语法

### 背景
Standard LaTeX 使用 `\[...\]` 和 `\(...\)` 作为块级和行内公式定界符。但 Nexara 当前只支持 `$$...$$` 和 `$...$`。部分模型（如 Anthropic Claude）会输出 `\[...\]` 格式的公式，导致无法渲染。

Cherry Studio 的 `processLatexBrackets()` 已解决此问题。

### 项目路径
k:/Nexara/native-ui

### 需要修改的文件

#### 1. 新增定界符转换函数

**文件**: `app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt`

在 `sanitizeStreamingMarkdown` 函数旁边新增：

```kotlin
/**
 * 将 LaTeX 标准定界符 \[...\] 和 \(...\) 转换为 Nexara 支持的 $$...$$ 和 $...$
 * 对标 Cherry Studio 的 processLatexBrackets()
 */
private fun normalizeLatexDelimiters(text: String): String {
    return text
        // 块级: \[...\] → $$...$$
        .replace(Regex("""\\\[(.*?)\\]""", RegexOption.DOT_MATCHES_ALL)) {
            "$$\n${it.groupValues[1].trim()}\n$$"
        }
        // 行内: \(...\) → $...$
        .replace(Regex("""\\\((.*?)\\\)""")) {
            "$${it.groupValues[1].trim()}$"
        }
}
```

#### 2. 在 MarkdownText 渲染流程中调用

**文件**: 同上 `MarkdownText.kt`

在 `processed` 的计算中，`sanitizeStreamingMarkdown` 之前或之后调用：

```kotlin
val processed = remember(markdown, isStreaming) {
    val withLatex = normalizeLatexDelimiters(markdown)
    if (isStreaming) sanitizeStreamingMarkdown(withLatex) else withLatex
}
```

### 验证标准
- `\[E = mc^2\]` 正确渲染为块级 KaTeX 公式
- `\(x^2 + y^2 = 1\)` 正确渲染为行内 KaTeX 公式
- 不影响现有的 `$$...$$` 和 `$...$` 语法
```

### P1-T3：流式平滑调速

| 属性 | 值 |
|------|-----|
| **依赖** | Phase 1 完成 |
| **并行组** | Phase 2 (可与 T1/T2/T4 同时执行) |
| **文件列表** | `MarkdownText.kt`（新增 SimpleSmoothStream） |

**提示词**:

```
## 任务：Nexara 流式 Markdown 渲染平滑调速

### 背景
LobeChat 有 PID-like 调速器，3 档预设（realtime=50cps, balanced=38cps, silky=28cps）。Nexara 当前直接全量渲染流式内容，无字符输出速率控制，导致快速输出时 UI 抖动。

### 项目路径
k:/Nexara/native-ui

### 需要修改的文件

#### 1. 创建 SimpleSmoothStream composable

**新建文件**: `app/src/main/java/com/promenar/nexara/ui/common/SmoothStreamContent.kt`

```kotlin
package com.promenar.nexara.ui.common

import androidx.compose.runtime.*
import kotlinx.coroutines.delay

/**
 * 平滑流式内容输出——将源文本按字符限速逐步显示。
 * 对标 LobeChat 的 useSmoothStreamContent。
 */
@Composable
fun rememberSmoothStreamContent(
    content: String,
    isStreaming: Boolean,
    cps: Int = 38  // 每秒字符数 (默认 balanced)
): String {
    var displayed by remember { mutableStateOf("") }

    LaunchedEffect(content, isStreaming, cps) {
        if (!isStreaming) {
            displayed = content
            return@LaunchedEffect
        }
        
        val target = content
        var pos = displayed.length
        
        // 渐进追赶：每次输出 1~3 个字符
        while (pos < target.length) {
            val chunkSize = minOf(3, (cps / 30))  // 30fps 每帧输出量
            val chunk = target.substring(pos, minOf(pos + chunkSize, target.length))
            pos += chunk.length
            displayed = target.substring(0, pos)
            delay(1000L / 30)  // ~30fps
        }
        
        // 确保最终完全同步
        displayed = content
    }

    return displayed
}
```

#### 2. 集成到 MarkdownText

**文件**: `app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt`

在 MarkdownText composable 中（约 136 行 `processed` 变量定义后），增加平滑处理：

```kotlin
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    fontSize: Int = 13,
    smoothingCps: Int = 38     // 新增参数
) {
    val processed = remember(markdown, isStreaming) {
        val sanitized = if (isStreaming) sanitizeStreamingMarkdown(markdown) else markdown
        insertCjkSpacing(sanitized)  // 从 P0-T2 来的
    }

    // 新增：流式平滑处理
    val smoothed = rememberSmoothStreamContent(
        content = processed,
        isStreaming = isStreaming,
        cps = if (isStreaming) smoothingCps else Int.MAX_VALUE
    )
    
    // 后续使用 smoothed 替代 processed
    val segments = remember(smoothed) { /* ... */ }
    // ...
}
```

#### 3. 添加调速预设（可选，P1 阶段可跳过）

在 `SmoothStreamContent.kt` 中新增预设枚举：

```kotlin
enum class StreamSpeed(val cps: Int, val label: String) {
    FAST(55, "快速"),
    BALANCED(38, "平衡"),
    SMOOTH(25, "平滑")
}
```

在设置界面中添加调速选择器（留待后续 Sprint）。

### 验证标准
- 流式输出时文本平滑渐进显示，无跳变/闪烁
- 非流式模式（isStreaming=false）直接显示全文，无延迟
- 长 Markdown 文档输出时性能可接受（不阻塞主线程超过 16ms/帧）
```

### P1-T4：标题锚点 ID

| 属性 | 值 |
|------|-----|
| **依赖** | Phase 1 完成 |
| **并行组** | Phase 2 (可与 T1/T2/T3 同时执行) |
| **文件列表** | `MarkdownText.kt`（可能仅配置） |

**提示词**:

```
## 任务：Nexara Markdown 标题锚点 ID 生成

### 背景
Cherry Studio 使用 `rehypeHeadingIds` 为每个标题生成 `heading-{block.id}` 格式的锚点 ID，支持长对话中定位跳转到特定标题。

Nexara 当前无标题 ID 机制，长文档阅读体验受影响。

### 项目路径
k:/Nexara/native-ui

### 需要修改的文件

#### 1. 研究 mikepenz Markdown 的标题 ID 支持

**文件**: `app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt`

mikepenz multiplatform-markdown-renderer v0.40.2 的 `Markdown` 组件可能已支持标题 ID 生成。检查方式：

在 `MarkdownText.kt` 的 `Markdown()` 调用处（约 219-292 行），查找是否有 `headingIdPrefix` 或类似参数。

```kotlin
Markdown(
    content = segment.content,
    colors = nexaraMarkdownColors(),
    typography = nexaraMarkdownTypography(fontSize),
    components = markdownComponents(/* ... */),
    modifier = Modifier.fillMaxWidth(),
    // 如果 API 支持：
    // headingIdPrefix = "heading-"
)
```

#### 2. 如果库不支持原生标题 ID

则在 `nexaraMarkdownTypography` 中为标题添加 `Modifier.semantics`，通过 Accessibility 机制实现语义锚点：

**文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/NexaraMarkdownTheme.kt`

不需要修改 typography——标题锚点通常由上层路由/导航处理。Compose 中可通过 `Modifier.testTag` 或 `Modifier.semantics` 标记标题位置。

#### 3. 降级方案

如果 mikepenz 库不支持且无需实现完整锚点跳转，则在 `MarkdownText` 的 `markdownComponents` 中覆盖 `heading` 组件：

```kotlin
heading1 = { model ->
    Text(
        text = model.content,
        style = nexaraMarkdownTypography(fontSize).h1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .semantics { contentDescription = "标题: ${model.content}" }
    )
}
// 对 h2-h6 类似处理
```

### 验证标准
- 每个标题在 Compose 语义树中有唯一标识
- 后续可通过 Modifier.semantics 实现"跳转到标题"功能
- 不影响 Markdown 视觉效果
```

---

## Phase 3: P2 差异化竞争力

Phase 3 中 T1 和 T3 可并行，T2 依赖 T1 完成。

### P2-T1：HTML Artifacts

| 属性 | 值 |
|------|-----|
| **依赖** | Phase 2 完成 |
| **并行组** | Phase 3 (可与 T3 并行) |
| **文件列表** | `HtmlArtifactRenderer.kt`（新建）, `CodeBlockHeader.kt`（修改） |

**提示词**:

```
## 任务：Nexara HTML Artifacts —— 代码块实时预览与 PNG 导出

### 背景
Cherry Studio 的 HTML Artifacts 功能允许用户将代码块（特别是 HTML/CSS/JS）渲染为实时预览，支持分屏（代码+预览）模式，并可导出为 PNG 图片。这是 AI 客户端的高级差异化能力。

### 项目路径
k:/Nexara/native-ui

### 需要创建/修改的文件

#### 1. 新建 HtmlArtifactRenderer

**新建文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/HtmlArtifactRenderer.kt`

创建 HTML 工件渲染器，复用已有的 `RichContentWebView`：

```kotlin
package com.promenar.nexara.ui.renderer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HtmlArtifactCard(
    htmlCode: String,
    language: String?,
    fontSize: Int = 13,
    modifier: Modifier = Modifier
) {
    // 仅对 HTML/CSS/JS/SVG 启用工件预览
    val isHtmlArtifact = language in listOf("html", "htm", "svg", "xml")
    if (!isHtmlArtifact) return

    var showFullScreen by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = NexaraColors.SurfaceHigh)
    ) {
        Column {
            // 内嵌预览
            RichContentWebView(
                html = htmlCode,
                fontSize = fontSize,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),   // 预览窗口高度
                minHeight = 150
            )

            // 操作栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showFullScreen = true }) {
                    Text("全屏预览")
                }
            }
        }
    }

    // 全屏模态框
    if (showFullScreen) {
        HtmlArtifactsPopup(
            htmlCode = htmlCode,
            fontSize = fontSize,
            onDismiss = { showFullScreen = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlArtifactsPopup(
    htmlCode: String,
    fontSize: Int = 13,
    onDismiss: () -> Unit
) {
    // 分屏：左侧代码编辑器（只读） + 右侧实时预览
    // 复用 RichContentWebView
    // ...
}
```

#### 2. 集成到 CodeBlockWithHeader

**文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/CodeBlockHeader.kt`

在代码块底部位置添加 HTML Artifact 卡片：

```kotlin
Column(modifier = modifier.fillMaxWidth().clip(...).background(...)) {
    // ... 现有 Header Row ...
    // ... 现有 Code Row ...

    // 新增：如果语言是 HTML/SVG，显示实时预览
    if (language in listOf("html", "htm", "svg")) {
        HtmlArtifactCard(
            htmlCode = code,
            language = language,
            fontSize = // 需要从上层传入 fontSize
        )
    }
}
```

注意：`CodeBlockWithHeader` 目前不接收 `fontSize` 参数。需要在其签名中增加 `fontSize: Int = 13` 参数，并从 `MarkdownText.kt` 的组件注册处传入。

#### 3. 在 MarkdownText 中传递 fontSize

**文件**: `app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt`

在 `codeFence` 和 `codeBlock` 的 `CodeBlockWithHeader` 调用中添加 fontSize：

```kotlin
CodeBlockWithHeader(
    code = code,
    language = language,
    fontSize = fontSize   // ← 新增
) { ... }
```

### 验证标准
- ` ```html <h1>Hello</h1> ``` ` 代码块下方显示 WebView 实时预览
- 预览窗口约 200dp 高，可点击"全屏预览"展开
- 非 HTML 语言（Python/Java/Kotlin）不显示预览卡片
```

### P2-T2：代码块可编辑模式

| 属性 | 值 |
|------|-----|
| **依赖** | P2-T1 完成（需复用 HtmlArtifactRenderer 的 WebView 模式） |
| **并行组** | 串行（依赖 T1） |
| **文件列表** | `CodeBlockHeader.kt`（修改） |

**提示词**:

```
## 任务：Nexara 代码块可编辑模式

### 背景
Cherry Studio 的代码块支持点击编辑——用户修改代码后触发 EventEmitter 事件，通过 remark-parse 精确定位替换 AST 节点中的代码内容并重新渲染。

Nexara 可作为差异化特性实现此功能。

### 项目路径
k:/Nexara/native-ui

### 实现方案

#### 1. 在 CodeBlockHeader 中添加编辑按钮

**文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/CodeBlockHeader.kt`

在 Header Row 的复制按钮旁边添加编辑按钮：

```kotlin
// 在已有 IconButton(复制) 旁边
val isEditing = remember { mutableStateOf(false) }

IconButton(
    onClick = { isEditing.value = !isEditing.value },
    modifier = Modifier.size(28.dp)
) {
    Icon(
        imageVector = if (isEditing.value) Icons.Rounded.Check else Icons.Rounded.Edit,
        contentDescription = "Edit code",
        tint = NexaraColors.OnSurfaceVariant,
        modifier = Modifier.size(16.dp)
    )
}
```

#### 2. 切换编辑/显示模式

当 `isEditing = true` 时，代码内容区域从只读 `MarkdownHighlightedCode` 切换为可编辑的 `OutlinedTextField`：

```kotlin
if (isEditing.value) {
    var editedCode by remember { mutableStateOf(code) }
    OutlinedTextField(
        value = editedCode,
        onValueChange = { editedCode = it },
        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
        textStyle = NexaraTypography.bodySmall.copy(
            fontFamily = FontFamily.Monospace
        )
    )
    // 保存按钮触发后更新 code 并退出编辑模式
} else {
    Box(modifier = Modifier.padding(...)) {
        codeContent()  // 原 MarkdownHighlightedCode
    }
}
```

#### 3. 将编辑后的代码反馈到消息流

由于 mikepenz Markdown 基于 AST 渲染，修改后的代码需要通过 EventEmitter 或回调通知上层重新生成 Markdown 内容。

简化方案：在编辑保存时，通过 `onCodeChange` 回调通知 ChatScreen 更新 `message.content`：

```kotlin
// CodeBlockWithHeader 新增参数
fun CodeBlockWithHeader(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
    onCodeChange: ((String) -> Unit)? = null,  // 新增
    codeContent: @Composable () -> Unit
)
```

### 验证标准
- 点击代码块的编辑按钮 → 切换为可编辑文本域
- 修改代码后点击保存 → 代码块内容更新
- 退出编辑模式 → 恢复语法高亮显示
```

### P2-T3：图片灯箱增强

| 属性 | 值 |
|------|-----|
| **依赖** | 无 |
| **并行组** | Phase 3 (可与 T1 并行) |
| **文件列表** | `ImageLightbox.kt`（修改） |

**提示词**:

```
## 任务：Nexara 图片灯箱增强 —— 缩放、旋转、分享

### 背景
当前 ImageLightbox 仅支持全屏显示和关闭。对标主流图片查看器，应增加：
1. 双指缩放（pinch-to-zoom）
2. 旋转按钮
3. 分享/保存到相册按钮

### 项目路径
k:/Nexara/native-ui

### 需要修改的文件

**文件**: `app/src/main/java/com/promenar/nexara/ui/renderer/ImageLightbox.kt`

#### 1. 添加缩放手势

为 ImageLightbox 中的图片添加 `transformable` 手势：

```kotlin
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer

var scale by remember { mutableFloatStateOf(1f) }
var rotation by remember { mutableFloatStateOf(0f) }
var offset by remember { mutableStateOf(Offset.Zero) }

val transformState = rememberTransformableState { zoomChange, offsetChange, rotationChange ->
    scale = (scale * zoomChange).coerceIn(0.5f, 5f)
    rotation += rotationChange
    offset += offsetChange
}

// 在 Image composable 上
Image(
    painter = ...,
    contentDescription = ...,
    modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            rotationZ = rotation
            translationX = offset.x
            translationY = offset.y
        }
        .transformable(state = transformState)
)
```

#### 2. 添加操作栏

在灯箱底部添加半透明操作栏：

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.BottomCenter)
        .background(Color.Black.copy(alpha = 0.6f))
        .padding(16.dp),
    horizontalArrangement = Arrangement.SpaceEvenly
) {
    IconButton(onClick = { /* 重置缩放 */ }) {
        Icon(Icons.Rounded.ZoomOutMap, "重置")
    }
    IconButton(onClick = { /* 旋转 90° */ }) {
        Icon(Icons.Rounded.Rotate90DegreesCw, "旋转")
    }
    IconButton(onClick = { /* 保存到相册 */ }) {
        Icon(Icons.Rounded.SaveAlt, "保存")
    }
    IconButton(onClick = { /* 分享 */ }) {
        Icon(Icons.Rounded.Share, "分享")
    }
}
```

#### 3. 保存到相册实现

```kotlin
// 使用 Coil 的 ImageLoader 重新下载到 Bitmap
// 通过 MediaStore API 写入 Pictures/Nexara/
// 显示 Toast 提示 "已保存到相册"
```

### 验证标准
- 点击 Markdown 图片 → 全屏灯箱打开
- 双指缩放 0.5x ~ 5x 正常工作
- 旋转按钮有效
- 保存按钮可将图片写入相册
```

---

## 附录 A：各独立会话的启动清单

| 阶段 | Agent | 任务 | 并行？ | 可复制提示词 |
|------|-------|------|--------|-------------|
| P0 | A | 字号统一修复 | ✅ 与 B/C/D 并行 | 见 §P0-T1 |
| P0 | B | CJK 中西文间距 | ✅ 与 A/C/D 并行 | 见 §P0-T2 |
| P0 | C | 段落排版与断行 | ✅ 与 A/B/D 并行 | 见 §P0-T3 |
| P0 | D | WebView 字号联动 | ✅ 与 A/B/C 并行 | 见 §P0-T4 |
| P1 | E | GFM Alert 支持 | ✅ 与 F/G/H 并行 | 见 §P1-T1 |
| P1 | F | LaTeX 定界符兼容 | ✅ 与 E/G/H 并行 | 见 §P1-T2 |
| P1 | G | 流式平滑调速 | ✅ 与 E/F/H 并行 | 见 §P1-T3 |
| P1 | H | 标题锚点 ID | ✅ 与 E/F/G 并行 | 见 §P1-T4 |
| P2 | I | HTML Artifacts | ✅ 与 K 并行 | 见 §P2-T1 |
| P2 | J | 代码可编辑模式 | ❌ 依赖 I | 见 §P2-T2 |
| P2 | K | 图片灯箱增强 | ✅ 与 I 并行 | 见 §P2-T3 |

---

## 附录 B：文件修改冲突矩阵

| 文件 | P0-T1 | P0-T2 | P0-T3 | P0-T4 | P1-T1 | P1-T2 | P1-T3 | P1-T4 | P2-T1 | P2-T2 | P2-T3 |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `MarkdownText.kt` | | ✏️ | | ✏️ | ✏️ | ✏️ | ✏️ | ✏️ | ✏️ | | |
| `NexaraMarkdownTheme.kt` | ✏️ | ✏️ | ✏️ | | | | | | | | |
| `ChatScreen.kt` | ✏️ | | | | | | | | | | |
| `SessionSettingsSheet.kt` | ✏️ | | | | | | | | | | |
| `ChatInlineComponents.kt` | ✏️ | | | | | | | | | | |
| `Type.kt` | ✏️ | | | | | | | | | | |
| `EChartsRenderer.kt` | | | | ✏️ | | | | | | | |
| `PlantUmlRenderer.kt` | | | | ✏️ | | | | | | | |
| `CodeBlockHeader.kt` | | | | | | | | | ✏️ | ✏️ | |
| `ImageLightbox.kt` | | | | | | | | | | | ✏️ |
| `GfmAlertBlock.kt` (新) | | | | | ✏️ | | | | | | |
| `SmoothStreamContent.kt` (新) | | | | | | | ✏️ | | | | |
| `HtmlArtifactRenderer.kt` (新) | | | | | | | | | ✏️ | | |

**关键发现**：`MarkdownText.kt` 被 Phase 1 (T2, T4) 和 Phase 2 (T1, T2, T3, T4) 等多个 Agent 修改。但这些修改在**不同函数/区域**中，只要 Agent 各自在特定区域工作（P0-T2 在 `processed` 附近, P1-T1 在 `markdownComponents` 块, P1-T2 在 `normalizeLatexDelimiters` 函数区），合并不应产生冲突。建议每个 Phase 内严格遵循"各 Agent 修改互不重叠的文件区域"原则。

---

*实施计划完成，各 Agent 提示词已就绪*
