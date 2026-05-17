# RAG 指示器多会话并行执行方案

> 基于 `docs/audit/RAG_INDICATOR_ARCHITECTURE_DESIGN_20260517.md` 设计文档  
> 目标：6 个独立 GLM-5.1 会话并行/串行执行全部 Phase 1-5

---

## 执行拓扑

```
Wave 1 (立即启动)
  └── Session A ──→ ████████████ 完成

Wave 2 (Session A 完成后)
  ├── Session B ──→ ██████████
  ├── Session C ──→ ████████         ← 与 B 完全解耦，可并行
  └── Session E1 ─→ ██████          ← 独立
          ↓
Wave 3 (Session A+Session C 完成后)
  └── Session D ──→ ██████

独立
  └── Session E2 ─→ ████             ← 完全独立，随时可执行
```

| 会话 | 阶段 | 依赖 | 预估工时 | 可并行 |
|------|------|------|---------|--------|
| **A** | RagOmniIndicator 连线 ChatScreen | 无 | 2h | Wave 1 单独 |
| **B** | RagProgressCard 管道改造 | A 完成 | 3h | 与 C, E1 并行 |
| **C** | PostProcessBar 后处理状态栏 | A 完成 | 2h | 与 B, E1 并行 |
| **E1** | KG Detail Sheet 增强 | A 完成 | 1h | 与 B, C 并行 |
| **D** | 手动压缩 | A + C 完成 | 1.5h | Wave 3 单独 |
| **E2** | FilesPanel KG 状态图标 | 无 | 1h | 独立，随时 |

---

## 视觉规范速查（所有会话共用）

每个会话提示词末尾自动注入此规范。以下为完整版供引用：

### 颜色
- CanvasBackground: `#131315` | SurfaceLow: `#1C1B1D` | SurfaceHigh: `#2A2A2C`
- Primary: `#C0C1FF` (淡紫) | OnPrimary: `#1000A9` | Primary.copy(alpha=0.1f) (浅底)
- GlassSurface: White@3% | GlassBorder: White@10%, 0.5dp
- OnSurface: `#E5E1E4` | OnSurfaceVariant: `#C7C4D7`
- OutlineVariant: `#464554` | Outline: `#908FA0`

### 圆角
- 卡片: 16dp (large) / 24dp (输入栏) | 药丸: 50dp | 小标签: 4dp/8dp

### 字体
- 正文: Inter 15sp bodyMedium | 标签: Inter 13sp labelMedium | 等宽: Space Grotesk 14sp
- 小标签: bodyMedium.copy(fontSize=12.sp) / labelSmall.copy(fontSize=10.sp)

### 组件模式
- 所有卡片用 `NexaraGlassCard` (GlassSurface + 0.5dp GlassBorder)
- 进度条用 Primary→Tertiary 渐变 (Brush.horizontalGradient)
- 状态 Badge: Primary.copy(alpha=0.1f) 底 + Primary 文字
- 分隔线: HorizontalDivider(0.5dp, OutlineVariant.copy(alpha=0.2f))

---

## Session A: RagOmniIndicator 连线 ChatScreen

> **依赖**: 无  
> **文件**: `ChatScreen.kt`, `ChatInlineComponents.kt`, `ChatViewModel.kt`  
> **目标**: RagOmniIndicator 接入会话消息流，RAG 检索进度可视化首次生效

### 背景

`RagOmniIndicator` 组件已在 `ChatInlineComponents.kt:197-352` 完整实现，但从未被 `ChatScreen.kt` 调用。在 `generateMessage()` 中：
- `contextBuilder.buildContext()` 通过 `onRagProgress` 回调更新 `message.ragProgress`
- 检索完成后 `ragReferences` + `ragMetadata` 写入 Message
- 这些数据已正确持久化到 Message 模型

但 ChatScreen 渲染消息列表时完全忽略了这些字段。

### 任务

**1. ChatScreen.kt — 在 Assistant 消息气泡前插入 RagOmniIndicator**

定位 `ChatScreen.kt` 中 Assistant 消息的渲染位置（约在 LazyColumn 的 item 中，流式生成中的第一个 Assistant 消息或消息列表中的 Assistant 消息）。在 Assistant 气泡之前插入条件渲染：

```kotlin
// 伪代码 — 在 Assistant 消息的 item 中
if (message.role == MessageRole.ASSISTANT) {
    // 先渲染 RagOmniIndicator (如果有 RAG 数据)
    val hasRagData = message.ragProgress != null || 
                     !message.ragReferences.isNullOrEmpty()
    if (hasRagData) {
        RagOmniIndicator(
            progress = message.ragProgress,
            metadata = message.ragMetadata,
            references = message.ragReferences,
            kgPaths = message.kgPaths,
            isLoading = isGenerating && message.ragProgress != null && 
                       (message.ragProgress?.percentage ?: 100) < 100
        )
    }
    // 然后是 Assistant 气泡
    ChatBubble(...)
}
```

**关键时机判断**:
- `isLoading = true`：流式生成进行中 且 ragProgress 未达 100%
- `isLoading = false`：生成完成，展示为静态检索结果摘要

**2. ChatViewModel.kt — 确保 KG 检索进度也通过 onRagProgress 上报**

在 `ContextBuilder.performRagRetrieval()` 中，KG 检索步骤（第 64-72 行）需要对 `onRagProgress` 透传。当前 KG 检索无回调。修改 `kgProvider.extractContext()` 调用前后发送进度：

```kotlin
// ContextBuilder.kt 第 64-72 行附近
val kgEnabled = params.session.ragOptions?.enableKnowledgeGraph ?: false
val kgContext = if (kgProvider != null && ragResult.second.isNotEmpty() && kgEnabled) {
    params.onRagProgress?.invoke("KG retrieval", 95, null)
    val result = try {
        kgProvider.extractContext(params.content, params.sessionId, ragResult.second) ?: ""
    } catch (e: Exception) { ... }
    params.onRagProgress?.invoke("Context ready", 100, null)
    result
} else ""
```

**3. 验证**

确认: 用户打开 Docs SWITCH → 发送消息 → Assistant 气泡前出现 RagOmniIndicator 卡片 → 显示阶段进度 → 完成后显示引用来源列表。

### 提示词

```
## 任务: RagOmniIndicator 连线 ChatScreen

### 修改文件
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\ChatScreen.kt
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\manager\ContextBuilder.kt

### 约束
- 仅修改上述文件，不创建新文件
- 所有 import 需显式添加
- 保持现有消息流结构不变

### 要读取的参考文件（先读后改）
1. ChatInlineComponents.kt (完整文件) — RagOmniIndicator 组件定义
2. ChatScreen.kt (搜索 "Assistant"/"ChatBubble"/"LazyColumn" 定位 Assistant 消息渲染位置)
3. ContextBuilder.kt (完整文件) — 确认 onRagProgress 回调链路
4. ChatViewModel.kt 第 248-321 行 — generateMessage 的上下文构建部分

### 操作步骤

Step 1: 在 ChatScreen.kt 中找到 Assistant 消息的渲染代码。通常在 LazyColumn 的 items 中，搜索 `MessageRole.ASSISTANT` 或 `ChatBubble`。

Step 2: 在 Assistant 气泡渲染之前，插入条件判断：
  - 如果 message.ragProgress != null (检索进行中)
  - 或 message.ragReferences 非空 (检索已完成有结果)
  则渲染 RagOmniIndicator 卡片。

Step 3: 传入正确的参数:
  - progress = message.ragProgress
  - metadata = message.ragMetadata
  - references = message.ragReferences
  - kgPaths = message.kgPaths (可能为null)
  - isLoading = 通过 isGenerating + ragProgress.percentage < 100 联合判断

Step 4: 在 ContextBuilder.kt 的 performRagRetrieval() 中，KG 检索步骤补充 onRagProgress 回调:
  - 检索前: invoke("KG retrieval", 95, null)
  - 完成后: invoke("Context ready", 100, null)

### 视觉规范速查
- 所有卡片使用 NexaraGlassCard (GlassSurface = White@3%底 + 0.5dp GlassBorder)
- 主色 Primary: #C0C1FF (淡紫)
- 文字 OnSurface: #E5E1E4, OnSurfaceVariant: #C7C4D7
- 圆角: 16dp (卡片), 4dp (小标签)
- 字体: Inter (标签), Space Grotesk (等宽数据)
- 输入栏圆角 24dp，颜色 SurfaceLow #1C1B1D
- 输入栏与指示器统一使用 NexaraGlassCard 作为基础容器
- 指示器卡片视觉上与输入栏保持一致的玻璃材质风格 (GlassSurface + GlassBorder)
```

---

## Session B: RagProgressCard 多阶段管道改造

> **依赖**: Session A 完成 (需要 knowing 指示器在哪里渲染、数据从哪里来)  
> **文件**: `ChatInlineComponents.kt`, `MemoryManager.kt`, `ChatModels.kt`  
> **目标**: 单阶段指示器 → 多阶段管道式展示，Rerank 独立可视化

### 背景

当前 MemoryManager.retrieveContext() 上报 5 个阶段 (10/30/50/70/90%)，但 Rerank 步骤混在 "Ranking results" 中无独立回调。需要：
1. 新增 `RagPhase` 数据模型描述每个阶段
2. 重构 RagOmniIndicator → RagProgressCard 支持多阶段列表
3. MemoryManager 拆分 Rerank 为独立进度

### 任务

**1. 数据模型 (ChatModels.kt, 追加)**

```kotlin
enum class PhaseStatus { PENDING, ACTIVE, DONE, ERROR }

data class RagPhase(
    val id: String,           // "embedding", "memory", "docs", "fusion", "rerank", "kg"
    val label: String,        // 中文显示名称
    val status: PhaseStatus,
    val progress: Int = 0,    // 仅 ACTIVE 时有意义
    val subStage: String? = null,
    val detail: String? = null // 完成后的统计: "✓ 12→8 candidates"
)
```

**2. 重构 RagOmniIndicator → RagProgressCard (ChatInlineComponents.kt)**

新组件接收 `phases: List<RagPhase>` 替代原有的单一 `progress: RagProgress?`。每个阶段以紧凑行展示：

```
┌─────────────────────────────────────────────────┐
│ 🔍 Knowledge Retrieval                 [Active] │
│                                                 │
│  ✓ Embedding Query          Done (0.2s)         │
│  ✓ Searching Vectors        Done (0.5s)         │
│  ● Hybrid Fusion            ████░░ 70%          │
│  ○ Rerank                   Pending             │
│  ○ KG Retrieval             Pending             │
└─────────────────────────────────────────────────┘
```

每个阶段行 = 状态图标 (✓/●/○/✕) + 阶段名 + 进度/统计。

**参照输入栏 Token Indicator 视觉风格** (药丸形 50dp 圆角, GlassSurface, GlassBorder):
- 阶段行背景: `SurfaceLow.copy(alpha=0.3f)`, 圆角 8dp
- ACTIVE 行: Primary 色左侧竖线 (2dp) + 脉冲动画
- DONE 行: StatusSuccess 图标 + 灰色文字
- PENDING 行: 灰色 Outline 图标 + 更低对比度文字
- 进度条 (仅 ACTIVE): 4dp 高, 圆角 2dp, Primary→Tertiary 渐变

**3. 拆分 Rerank 进度 (MemoryManager.kt)**

在 `retrieveContext()` 中添加 rerank 开始/完成的进度回调:

```kotlin
// 第 148-162 行附近，rerank 之前
onProgress?.invoke("Reranking", 85, null)
val rerankedResults = ...
onProgress?.invoke("Reranking complete", 90, "${rerankedResults.size} results")
```

**4. ChatViewModel/ContextBuilder 构建 Phase 列表**

在 `generateMessage()` 中，根据 `onRagProgress` 回调控件动态构建 `List<RagPhase>`，通过 StateFlow 传递给 ChatScreen。

### 提示词

```
## 任务: RagProgressCard 多阶段管道改造

### 修改文件
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\data\model\ChatModels.kt (新增 RagPhase)
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\ChatInlineComponents.kt (重构)
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\data\rag\MemoryManager.kt (拆分 Rerank 回调)
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\ChatViewModel.kt (构建 Phase 列表)

### 约束
- 保持向后兼容：旧的 RagOmniIndicator 保留但标记 @Deprecated
- ChatModels.kt 中的 RagPhase/PhaseStatus 追加在文件末尾
- 所有新增组件使用 Nexara 视觉规范

### 要读取的参考文件
1. ChatInlineComponents.kt (完整文件) — 要重构的组件
2. ChatModels.kt — 追加数据模型的位置
3. MemoryManager.kt 第 43-190 行 — 拆分 Rerank 回调
4. ChatViewModel.kt 第 248-321 行 — Phase 列表构建位置
5. ui/theme/Color.kt — NexaraColors 完整定义

### 操作步骤

Step 1: 在 ChatModels.kt 末尾追加 PhaseStatus 枚举和 RagPhase 数据类。

Step 2: 在 ChatInlineComponents.kt 中新建 RagProgressCard 组件:
  - 接收 phases: List<RagPhase>, references, kgPaths, isComplete
  - 头部: 图标 + "Knowledge Retrieval" 标题 + Active/Done Badge
  - Body: phases.forEach → 阶段行 (图标 + 名称 + 进度/统计)
  - 底部: LazyRow 引用来源列表 (已有逻辑从 RagOmniIndicator 迁移)
  - 点击展开 RagDetailsSheet

Step 3: 在 MemoryManager.retrieveContext() 中，第 150 行 rerank 前后加入 onProgress 回调。

Step 4: 在 ChatViewModel.generateMessage() 中，将 onRagProgress 回调改为构建 RagPhase 列表:
  - 创建 MutableList<RagPhase>，预填充所有阶段为 PENDING
  - 在 onRagProgress 中更新对应阶段的状态
  - 通过一个新的 _ragPhases StateFlow 暴露给 ChatScreen

Step 5: 在 ChatScreen.kt (Session A 已修改) 中，将 RagOmniIndicator 调用替换为 RagProgressCard。

### 视觉规范（关键）
- 阶段行容器: NexaraGlassCard, 填充 12dp, 间距 4dp
- ACTIVE 阶段: Primary 色左侧 accent bar (2dp宽), 状态点脉冲动画
- DONE 阶段: StatusSuccess (#10B981) 图标, 灰色文字
- PENDING 阶段: Outline (#908FA0) 图标, alpha=0.4 文字
- 进度条: 4dp高, CircleShape, Primary→Tertiary 渐变 (Brush.horizontalGradient)
- Badge: Primary.copy(alpha=0.1f) 底, Primary 文字, RoundedCornerShape(4dp)
- 字体: labelMedium 13sp, labelSmall 11sp, fontFamily=Monospace 用于时间
- 与输入栏风格统一: 24dp 圆角的 NexaraGlassCard 容器
```

---

## Session C: PostProcessBar 后处理状态栏

> **依赖**: Session A 完成 (需要 knowing ChatScreen 底部布局)  
> **文件**: `ChatScreen.kt`, `ChatViewModel.kt`, `ChatInlineComponents.kt`, `PostProcessor.kt`  
> **目标**: 会话底部新增 PostProcessBar，展示记忆归档/自动摘要/KG 抽取进度

### 背景

Post-LLM 阶段有 3 个异步操作：
- 记忆归档 (PostProcessor.archiveMessagesToRag) — 有 setVectorizationStatus 但 UI 未读取
- 自动摘要 (SummaryManager.summarize) — 完全无进度回调
- KG 抽取 (VectorizationQueue) — 完全无进度回调

这些操作适合在会话底部以轻量 Chip 形式展示。

### 任务

**1. 数据模型 (ChatModels.kt)**

```kotlin
enum class PostProcessType { MEMORY_ARCHIVE, AUTO_SUMMARY, KG_EXTRACTION, MANUAL_SUMMARY }
enum class PostProcessStatus { IDLE, RUNNING, DONE, ERROR }

data class PostProcessTask(
    val id: String,
    val type: PostProcessType,
    val status: PostProcessStatus,
    val progress: Int = 0,
    val detail: String = ""
)
```

**2. ChatViewModel 新增 StateFlow**

```kotlin
private val _postProcessTasks = MutableStateFlow<List<PostProcessTask>>(emptyList())
val postProcessTasks: StateFlow<List<PostProcessTask>>
```

generateMessage() 后处理阶段更新此 StateFlow。

**3. PostProcessor + SummaryManager 改造**

- `archiveMessagesToRag()`: 开始/完成时更新 PostProcessTask 状态
- `summarize()`: 通过回调更新进度（需要 SummaryManager 新增 onProgress 参数）

**4. PostProcessBar 组件 (ChatInlineComponents.kt)**

参照输入栏 TopBar 的 Chip 风格（药丸形, NexaraGlassCard）:

```
┌──────────────────────────────────────────────┐
│ 📦 记忆归档: 3/5    ⚡ 自动摘要: 处理中...   │
└──────────────────────────────────────────────┘
```

每个 Chip:
- 容器: NexaraGlassCard, RoundedCornerShape(50)
- 内边距: h=8dp, v=4dp, 间距 6dp
- 图标: 14dp, 根据类型不同
- RUNNING: 脉冲动画 + Primary 色
- DONE: StatusSuccess 图标, 3 秒后淡出
- ERROR: StatusError 图标, 持久化显示

**5. ChatScreen 集成**

在 Scaffold 的 bottomBar 或输入栏上方插入 PostProcessBar (底部对齐，输入栏之上)。

### 提示词

```
## 任务: PostProcessBar 后处理状态栏

### 修改文件
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\data\model\ChatModels.kt
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\ChatViewModel.kt
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\ChatInlineComponents.kt
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\ChatScreen.kt
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\manager\PostProcessor.kt
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\manager\SummaryManager.kt

### 约束
- PostProcessBar 视觉风格参照输入栏 TopBar 的 Chip 设计 (药丸形, NexaraGlassCard)
- DONE 状态 3 秒后自动从列表移除 (LaunchedEffect + delay)
- ERROR 状态持久化直到下次成功
- 不阻塞 UI，所有更新通过 StateFlow

### 要读取的参考文件
1. ChatScreen.kt — 搜索 "ChatInputTopBar" 了解现有 Chip 风格
2. ChatViewModel.kt 第 530-606 行 — 后处理逻辑
3. PostProcessor.kt — archiveMessagesToRag 方法
4. SummaryManager.kt — summarize 方法
5. ChatInlineComponents.kt — 新增组件的位置

### 操作步骤

Step 1: ChatModels.kt 追加 PostProcessType/Status/Task 数据类。

Step 2: ChatViewModel 新增:
  - _postProcessTasks StateFlow
  - addPostProcessTask(type, status, progress, detail) 辅助方法
  - generateMessage() 后处理段调用更新 (第 530-606 行附近)

Step 3: PostProcessor.archiveMessagesToRag() 改造:
  - 开始: 更新 task 为 RUNNING
  - 每个 chunk: 更新 progress
  - 完成: 更新 task 为 DONE

Step 4: SummaryManager.summarize() 新增 onProgress 回调参数:
  - fun summarize(..., onProgress: ((String) -> Unit)? = null)

Step 5: ChatInlineComponents.kt 新增 PostProcessBar 组件。

Step 6: ChatScreen.kt Scaffold 底部或输入栏上方插入 PostProcessBar。

### 视觉规范
- Chip 容器: NexaraGlassCard, RoundedCornerShape(50), 内边距 h8/v4
- RUNNING: Primary 色图标 + 脉冲动画 (repeatMode=Reverse, tween 800ms)
- DONE: StatusSuccess (#10B981) 图标, 3s 后 fadeOut
- ERROR: StatusError (#EF4444) 图标, 持久化
- 字体: labelSmall.copy(fontSize=10.sp)
- 图标尺寸: 12dp
- Chip 间距: 6dp (参照输入栏 TopBar)
- 容器背景: CanvasBackground (#131315) 或透明
```

---

## Session D: 手动压缩

> **依赖**: Session A (ChatScreen 连线) + Session C (PostProcessBar 模式)  
> **文件**: `ChatViewModel.kt`, `SessionSettingsSheet.kt`, `ChatInlineComponents.kt`, `ChatScreen.kt`  
> **目标**: 用户主动触发的对话上下文压缩，展示为对话流中的独立卡片

### 任务

**1. ChatViewModel 新增 compressContext()**

```kotlin
fun compressContext() {
    val sessionId = _currentSessionId.value ?: return
    viewModelScope.launch {
        _postProcessTasks.update { it + PostProcessTask(
            id = "summary_${System.currentTimeMillis()}",
            type = PostProcessType.MANUAL_SUMMARY,
            status = PostProcessStatus.RUNNING,
            detail = "压缩中..."
        )}
        try {
            val session = store.getSession(sessionId) ?: return@launch
            val allMsgs = session.messages.filter { !it.isArchived }
            val summary = summaryManager.summarize(
                oldSummary = session.summary,
                overflowMessages = allMsgs,
                summaryModelId = ...,
                currentModelId = session.modelId ?: "",
                onProgress = { msg -> ... }
            )
            sessionManager.updateSession(sessionId, mapOf("summary" to summary))
            _postProcessTasks.update { tasks ->
                tasks.map { if (it.id.startsWith("summary_")) 
                    it.copy(status = PostProcessStatus.DONE, detail = "压缩完成") else it }
            }
        } catch (e: Exception) { /* ERROR */ }
    }
}
```

**2. SessionSettingsSheet 新增手动压缩按钮**

在 SettingsPanel 的"压缩阈值"滑块下方增加按钮。

**3. SummaryCard 组件 (ChatInlineComponents.kt)**

与输入栏视觉一致:

```
┌──────────────────────────────────────────┐
│ 📝 Context Compression        [Complete] │
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░  90%         │
│ Summarizing 24 messages...               │
│                                          │
│ 压缩结果:                                │
│ "对话涉及全书大纲的规划..." (压缩比 15:1) │
│                                [Expand]  │
└──────────────────────────────────────────┘
```

### 提示词

```
## 任务: 手动会话压缩

### 修改文件
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\ChatViewModel.kt
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\SessionSettingsSheet.kt
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\ChatInlineComponents.kt
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\ChatScreen.kt

### 约束
- SummaryCard 视觉风格参照输入栏 (24dp 圆角 NexaraGlassCard)
- 进度条与 RagProgressCard 一致 (Primary→Tertiary 渐变)
- 压缩结果默认折叠，点击 Expand 展开

### 要读取的参考文件
1. ChatViewModel.kt — 搜索 "summary" 了解现有摘要逻辑
2. SessionSettingsSheet.kt 第 791-853 行 — SettingsPanel
3. SummaryManager.kt — summarize 方法签名
4. ChatInlineComponents.kt — SummaryCard 组件位置

### 操作步骤

Step 1: ChatViewModel 新增 compressContext() 方法，调用 SummaryManager
Step 2: SummaryManager 新增带有 onProgress 回调的 summarize 重载
Step 3: SessionSettingsSheet 添加 "手动压缩" 按钮
Step 4: ChatInlineComponents.kt 新增 SummaryCard 组件
Step 5: ChatScreen 支持 SummaryCard 在消息流中渲染

### 视觉规范
- 卡片: NexaraGlassCard, RoundedCornerShape(24dp), 间距同输入栏
- 头部: 图标 + 标题 + Badge (StatusSuccess #10B981 / Primary #C0C1FF)
- 进度条: 4dp高, CircleShape, Primary→Tertiary 渐变
- 字体: labelMedium 13sp (标题), bodyMedium 15sp (结果摘要)
```

---

## Session E1: KG Detail Sheet 增强

> **依赖**: Session A (RagDetailsSheet 连线入口)  
> **文件**: `RagDetailsSheet.kt`, `ChatModels.kt`  
> **目标**: RagDetailsSheet 增加知识图谱 Tab

### 任务

在 RagDetailsSheet 中增加 Tab 切换: [检索结果] [知识图谱] [摘要历史]。

知识图谱 Tab 展示:
- 节点列表 (实体名 + 类型标签)
- 边列表 (源→目标 + 关系类型)
- 知识图谱在会话中所用模型检索到的数量统计

### 提示词

```
## 任务: KG Detail Sheet 增强

### 修改文件
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\chat\components\RagDetailsSheet.kt
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\data\model\ChatModels.kt (如需追加模型)

### 要读取的参考文件
1. RagDetailsSheet.kt — 现有组件
2. KgPath / RagReference 数据模型

### 操作步骤
Step 1: 在 RagDetailsSheet 中增加 TabRow [检索结果, 知识图谱]
Step 2: KG Tab 展示 kgPaths 列表: 节点名 + 边关系
Step 3: 每个节点以 NexaraGlassCard 卡片展示，侧面 Primary 色竖线

### 视觉规范
- TabRow 使用 ScrollableTabRow, Primary 色指示器
- 节点卡片: RoundedCornerShape(8dp), SurfaceLow 底, Primary 色左侧 accent bar 2dp
- 关系标签: SurfaceHigh 底, RoundedCornerShape(4dp), 小号 Monospace 字体
```

---

## Session E2: FilesPanel KG 状态图标

> **依赖**: 无  
> **文件**: `RagHomeScreen.kt` / FilesPanel 组件  
> **目标**: 知识库文档列表显示 KG 抽取状态图标

### 任务

在 FilesPanel 的文档行末尾增加 KG 状态指示器:
- `○` Gray: 未抽取
- `⟳` Primary: 抽取中 (脉冲动画)
- `✓` StatusSuccess: 已完成
- `✕` StatusError: 失败

状态来源: VectorizationQueue 中的 GraphExtractor 状态或 FileEntry 的 kgExtractedAt 字段。

### 提示词

```
## 任务: FilesPanel KG 状态图标

### 修改文件
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\rag\RagHomeScreen.kt (或独立 FilesPanel 组件)
- k:\Nexara\native-ui\app\src\main\java\com\promenar\nexara\ui\rag\RagViewModel.kt (状态管理)

### 要读取的参考文件
1. RagHomeScreen.kt — 搜索 "FilesPanel" 或文档列表渲染
2. RagViewModel.kt — 搜索 "vectorized" 或 "FileEntry"
3. data/local/db/entity/FileEntry.kt — 确认现有字段 (kgExtractedAt?)

### 操作步骤
Step 1: 在 FileEntry 渲染行末尾添加 KG 状态图标
Step 2: RagViewModel 新增 _kgExtractionStates: StateFlow<Map<String, KgStatus>>
Step 3: 图标使用 animateFloatAsState 实现脉冲效果

### 视觉规范
- 图标尺寸: 14dp
- 完成: StatusSuccess (#10B981), CheckCircle 图标
- 进行中: Primary (#C0C1FF), 脉冲 alpha 0.3→1.0
- 失败: StatusError (#EF4444), Error 图标
- 未开始: Outline (#908FA0) alpha 0.3, RadioButtonUnchecked 图标
```

---

## 执行检查清单

| 会话 | 启动条件 | 完成标志 |
|------|---------|---------|
| A | 立即 | RagOmniIndicator 在 Assistant 气泡前可见，检索时显示进度 |
| B | A 完成 | RagProgressCard 替代 RagOmniIndicator，多阶段独立展示 |
| C | A 完成 | PostProcessBar 在会话底部显示，后台任务可见 |
| E1 | A 完成 | RagDetailsSheet 有 KG Tab |
| D | A + C 完成 | 手动压缩按钮可用，SummaryCard 正确展示 |
| E2 | 随时 | FilesPanel 中文档显示 KG 状态图标 |

---

*执行方案存档至 `docs/plans/RAG_INDICATOR_MULTI_SESSION_EXECUTION.md`。*
