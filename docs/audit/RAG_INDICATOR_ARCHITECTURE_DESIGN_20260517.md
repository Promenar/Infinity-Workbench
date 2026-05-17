# RAG 检索指示器架构审计与 UI 设计方案

> **日期**: 2026-05-17  
> **范围**: 会话生命周期全流程 + RagOmniIndicator 集成 + 图谱/压缩可视化设计  
> **状态**: 设计稿

---

## §1 会话全生命周期架构流程图

```
═══════════════════════════════════════════════════════════════════════════
                    01. 用户点击发送 (sendMessage)
═══════════════════════════════════════════════════════════════════════════
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 02. Pre-LLM Phase (generateMessage 前半段, 同步等待)                     │
│                                                                         │
│  contextBuilder.buildContext(params)                                     │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ 2a. Web Search → performClientSideSearch()                       │  │
│  │     └→ 进度: ❌ 无回调                                             │  │
│  │                                                                   │  │
│  │ 2b. RAG 检索 → performRagRetrieval()                             │  │
│  │     ├→ MemoryManager.retrieveContext()                            │  │
│  │     │  ├→ "Embedding query"      [10%] ✅ onProgress             │  │
│  │     │  ├→ "Searching memory"     [30%] ✅ onProgress             │  │
│  │     │  ├→ "Searching documents"  [50%] ✅ onProgress             │  │
│  │     │  ├→ "Hybrid fusion"        [70%] ✅ onProgress             │  │
│  │     │  ├→ "Ranking results"      [90%] ✅ onProgress             │  │
│  │     │  │  └→ rerankClient.rerank()  ❌ 无重排独立进度             │  │
│  │     │  └→ 返回: context + references + metadata                  │  │
│  │     │                                                            │  │
│  │ 2c. KG 检索 → kgProvider.extractContext()                        │  │
│  │     └→ 基于 RAG 结果召回图谱节点/边                               │  │
│  │     └→ 进度: ❌ 无回调                                            │  │
│  │                                                                   │  │
│  │ 2d. 任务计划 → taskRepository.getPlan()                          │  │
│  │                                                                   │  │
│  │ 2e. System Prompt 拼接                                            │  │
│  │     └→ buildSystemPrompt() 顺序:                                  │  │
│  │        [Time] → [Tools] → [Task Plan] → [Agent Prompt] →         │  │
│  │        [Session Prompt] → [Retrieved Context] →                  │  │
│  │        [KG Relations] → [Web Results] → [History Summary]         │  │
│  │     └→ ✅ 拼接逻辑正确                                             │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                         │
│  返回: ContextBuilderResult → ragReferences 写回 Message               │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 03. LLM Phase (流式生成)                                                 │
│                                                                         │
│  llmProvider.sendPrompt(request).collect { chunk -> ... }               │
│  ┌─ TextDelta: 正文累积 → UI 更新                                       │
│  ├─ Thinking: 思考文本累积 → UI 更新                                    │
│  ├─ ToolCallDelta: 工具调用累积 → toolExecutor                         │
│  ├─ Usage: Token 统计                                                   │
│  └─ Citations: 引用来源                                                 │
│                                                                         │
│  进度: GenerationStatus (IDLE→UPLOADING→THINKING→RECEIVING→COMPLETED)   │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ 04. Post-LLM Phase (后台协程, 不阻塞)                                    │
│                                                                         │
│  4a. Token 统计 → postProcessor.updateStats()                          │
│      └→ 写入 Session.stats (BillingUsage)                              │
│      └→ 进度: ❌ 无回调（轻量操作, 可接受）                              │
│                                                                         │
│  4b. 会话记忆归档 → archiveMessagesToRag()                             │
│      └→ 触发条件: messages.size > activeContextWindow                  │
│         AND ragOptions.enableMemory == true                             │
│      └→ 切块 → embedDocuments → 写入 vectorStore                       │
│      └→ 进度: messageManager.setVectorizationStatus("processing")      │
│              → ❌ UI 未响应此状态                                        │
│                                                                         │
│  4c. 自动摘要 → summaryManager.summarize()                              │
│      └→ 触发条件: totalTokens > maxTokens × autoSummaryThreshold       │
│      └→ 调用 LLM (同步, withContext(Dispatchers.IO))                   │
│      └→ 写入 Session.summary                                           │
│      └→ 进度: ❌ 无回调                                                │
│                                                                         │
│  4d. 会话标题生成 → titleGenerator(sessionId)                           │
│      └→ 异步（首次回复时）                                              │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## §2 可视化颗粒度审计

### 2.1 现有数据流中的进度阶段

| 阶段 | 触发时机 | 进度回调 | UI 展示 | 状态 |
|------|---------|---------|--------|------|
| **Embedding Query** | Pre-LLM | `onProgress("Embedding query", 10, null)` | RagOmniIndicator (未连线) | ⚠️ 数据存在但未展示 |
| **Searching Memory** | Pre-LLM | `onProgress("Searching memory", 30, null)` | RagOmniIndicator (未连线) | ⚠️ 同上 |
| **Searching Docs** | Pre-LLM | `onProgress("Searching documents", 50, null)` | RagOmniIndicator (未连线) | ⚠️ 同上 |
| **Hybrid Fusion** | Pre-LLM | `onProgress("Hybrid fusion", 70, null)` | RagOmniIndicator (未连线) | ⚠️ 同上 |
| **Ranking Results** | Pre-LLM | `onProgress("Ranking results", 90, null)` | RagOmniIndicator (未连线) | ⚠️ 同上 |
| **Rerank 通信** | Pre-LLM (Ranking 内部) | ❌ 无回调 | ❌ 无组件 | 🔴 缺失 |
| **KG 检索** | Pre-LLM | ❌ 无回调 | RagOmniIndicator 有 `kgPaths` 参数但未使用 | 🔴 缺失 |
| **KG 抽取** | Post-doc-import (VectorizationQueue) | ❌ 无回调 | ❌ 无组件 | 🔴 缺失 |
| **会话记忆归档** | Post-LLM | `setVectorizationStatus()` | ❌ UI 未读取此状态 | 🔴 缺失 |
| **自动摘要 (压缩)** | Post-LLM | ❌ 无回调 | ❌ 无组件 | 🔴 缺失 |
| **手动压缩** | 用户手动触发 | ❌ 未实现 | ❌ 无组件 | 🔴 未实现 |
| **RAG 结果展示** | Pre-LLM 完成后 | ragReferences 写入 Message | RagOmniIndicator 可展示参考列表 | ⚠️ 组件存在但未调用 |

### 2.2 当前 `RagOmniIndicator` 能力矩阵

```kotlin
fun RagOmniIndicator(
    progress: RagProgress?,     // ✅ 支持: 阶段文本 + 百分比 + 子阶段
    metadata: RagMetadata?,     // ✅ 支持: chunkCount, totalTokens, retrievalTimeMs
    references: List<RagReference>?, // ✅ 支持: 引用列表 LazyRow 展示
    kgPaths: List<KgPath>? = null,  // ⚠️ 参数存在但从未传入，内部只用于打开详情 Sheet
    isLoading: Boolean              // ✅ 支持: 脉冲动画 + "Active" 标签
)
```

**组件本身已实现**：
- 进度条（渐变 Primary→Tertiary，带动画）
- 阶段文本 + 百分比
- "Active" 状态标签（脉冲 Badge）
- 引用来源 LazyRow
- 详情 Sheet（RagDetailsSheet，展示引用 + KG 路径）

**缺失**：
- ❌ 从未被 ChatScreen 调用
- ❌ 没有重排阶段的独立可视化
- ❌ 没有 KG 阶段的可视化
- ❌ 没有压缩/摘要的可视化

### 2.3 总结：可视化断层全景

```
Pre-LLM                        LLM                     Post-LLM
───────                        ───                     ────────
[RAG Retrieval]  ──────────→ [Streaming] ──────────→ [Archiving]
  ✅ 10/30/50/70/90%            生成状态                  ❌ 无进度
  ✅ 有回调 + 组件                                      
                                ──────────→ [Auto-Summary]
[Rerank]                           (不阻塞)              ❌ 无进度
  ❌ 无独立回调                                          ❌ LLM 调用不可见
                                
                                ──────────→ [KG Extraction]
[KG Retrieval]                     (VectorizationQueue)  
  ❌ 无回调                          ❌ 无进度
                                    ❌ 结果不可见

[Web Search]
  ❌ 无回调
```

---

## §3 设计约束分析

### 3.1 图检索 + 图抽取 vs RAG 检索的时序差异

| 操作 | 触发时机 | 是否阻塞 | 可视化时机 |
|------|---------|---------|----------|
| RAG 检索 (Memory + Docs) | 每次用户发消息前 | ✅ 同步阻塞 | 对话前（气泡中展示进度） |
| Rerank | RAG 检索的子步骤 | ✅ 同步阻塞 | 嵌入 RAG 检索进度条中 |
| KG 检索 | RAG 检索完成后 | ✅ 同步阻塞 | 紧接 RAG 检索后 |
| KG 抽取 | 文档导入后 (后台) | ❌ 异步 | 知识库页面 / RAG 详情 |
| 会话记忆归档 | 消息完成后 (后台) | ❌ 异步 | 会话设置 / 状态栏 |
| 自动摘要 (压缩) | 消息完成后 (后台) | ❌ 异步 | 会话设置 / 状态栏 |
| 手动压缩 | 用户手动触发 | ✅ 同步阻塞 | 对话中（气泡或模态） |

**设计结论**：
- **Pre-LLM 同步操作**（RAG 检索 + Rerank + KG 检索）→ 适合嵌入 `RagOmniIndicator` 中，作为"发送前预处理"的统一进度展示
- **Post-LLM 异步操作**（记忆归档 + 自动摘要 + KG 抽取）→ 不适合嵌入对话气泡中的指示器（因为消息已经发出），适合在**会话底部状态栏**或**会话设置面板**中展示
- **手动压缩**（用户主动触发）→ 适合对话气泡中的独立卡片，类似当前 `ThinkingBlock` 的展示方式

### 3.2 指示器 UI 架构设计

```
┌──────────────────────────────────────────────────────┐
│                   ChatScreen                          │
│                                                      │
│  [Bubble: User Message]                              │
│                                                      │
│  ┌────────────────────────────────────────────────┐  │
│  │ RagOmniIndicator (Pre-LLM 预处理进度)           │  │
│  │ ┌──────────────────────────────────────────┐   │  │
│  │ │ Phase 1: RAG 检索            [Active]    │   │  │
│  │ │ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░  85%          │   │  │
│  │ │  Searching documents...                  │   │  │
│  │ ├──────────────────────────────────────────┤   │  │
│  │ │ Phase 2: Rerank 重排序        [Done]     │   │  │
│  │ │  ✓ 12 candidates → 8 finalized (0.3s)    │   │  │
│  │ ├──────────────────────────────────────────┤   │  │
│  │ │ Phase 3: KG 图谱检索          [Done]     │   │  │
│  │ │  ✓ 5 nodes, 3 edges retrieved            │   │  │
│  │ ├──────────────────────────────────────────┤   │  │
│  │ │ References: [doc1] [doc2] [mem1]         │   │  │
│  │ └──────────────────────────────────────────┘   │  │
│  └────────────────────────────────────────────────┘  │
│                                                      │
│  [Bubble: Assistant Message (含检索上下文)]           │
│                                                      │
│  ┌────────────────────────────────────────────────┐  │
│  │ 底部状态栏 (Post-LLM 后处理进度)                 │  │
│  │ ┌──────────────────────────────────────────┐   │  │
│  │ │ 🔄 正在压缩对话摘要...         [12:34]    │   │  │
│  │ │ 📦 本轮记忆归档: 3/5 chunks vectorized   │   │  │
│  │ │ 🧠 KG 抽取排队中...                      │   │  │
│  │ └──────────────────────────────────────────┘   │  │
│  └────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

---

## §4 新 UI 设计方案

### 4.1 方案总览：三区模型

```
ChatScreen 会话界面
├── 区 1: Inline Indicator (消息流内)
│   ├── RagProgressCard: Pre-LLM 预处理统一卡片
│   │   ├── PhaseGroup: 向量检索 (Embedding → Search → Fusion)
│   │   ├── PhaseGroup: 重排序 (Rerank)
│   │   └── PhaseGroup: 图谱检索 (KG Query)
│   ├── PostProcessCard: Post-LLM 后处理卡片
│   │   └── 手动压缩进度
│   └── SummaryCard: 摘要/压缩卡片
│       └── "对话摘要已更新" 折叠卡片
│
├── 区 2: Session Status Bar (会话底部)
│   └── PostProcessBar: 后台异步后处理状态
│       ├── MemoryArchiveChip: "记忆归档..."
│       ├── AutoSummaryChip: "自动摘要..."
│       └── KgExtractionChip: "图谱抽取..."
│
└── 区 3: Detail Sheet (点击展开)
    └── RagDetailsSheet: 已有,需增强
        ├── 检索结果列表
        ├── KG 节点/边关系图
        └── 摘要历史版本
```

### 4.2 区 1: Inline Pre-LLM Indicator (核心改造)

改造现有 `RagOmniIndicator` → `RagProgressCard`，支持多阶段管道式展示。

#### 组件签名:

```kotlin
@Composable
fun RagProgressCard(
    phases: List<RagPhase>,          // 多阶段列表
    references: List<RagReference>?,
    kgPaths: List<KgPath>?,
    isComplete: Boolean,
    onViewDetails: () -> Unit,
    modifier: Modifier = Modifier
)

data class RagPhase(
    val id: String,                  // "embedding", "search", "rerank", "kg"
    val label: String,               // 显示名称
    val icon: ImageVector,
    val status: PhaseStatus,         // PENDING, ACTIVE, DONE, ERROR
    val progress: Int,               // 0-100
    val subStage: String?,           // 子阶段描述
    val detail: String? = null       // 完成后的统计信息
)

enum class PhaseStatus { PENDING, ACTIVE, DONE, ERROR }
```

#### 视觉设计:

```
┌─────────────────────────────────────────────────────┐
│ 🔍 Knowledge Retrieval                     [Active] │
│                                                     │
│  ● Embedding Query ─────────────────── Done (0.2s)  │
│  ● Searching Vectors ───────────────── Done (0.5s)  │
│  ● Hybrid Fusion ────────────────────── Done         │
│                                                     │
│  ⚡ Rerank ───────────────────────────── Done (0.3s) │
│     ✓ 12 → 8 candidates re-ranked                   │
│                                                     │
│  🧠 Knowledge Graph ─────────────────── Done (0.1s) │
│     ✓ 5 entities, 3 relations                       │
│                                                     │
│  ───────────────────────────────────────────────    │
│  Sources: [全书大纲...] [规划初稿...] [conversation]  │
└─────────────────────────────────────────────────────┘
```

#### 调用位置:

在 `ChatScreen` 中，Assistant 消息气泡之前渲染：

```kotlin
// ChatScreen.kt 中消息列表的 item
if (message.role == MessageRole.ASSISTANT && message.ragProgress != null) {
    item {
        RagProgressCard(
            phases = buildPhasesFromProgress(message.ragProgress, message.ragMetadata),
            references = message.ragReferences,
            kgPaths = message.kgPaths,
            isComplete = message.ragProgress?.percentage == 100,
            onViewDetails = { /* open RagDetailsSheet */ }
        )
    }
}
```

### 4.3 区 2: Post-Process Status Bar (新增)

会话底部常驻的轻量级状态条，用于展示后台异步操作。

#### 组件签名:

```kotlin
@Composable
fun PostProcessBar(
    tasks: List<PostProcessTask>,
    modifier: Modifier = Modifier
)

data class PostProcessTask(
    val id: String,
    val type: PostProcessType,       // MEMORY_ARCHIVE, AUTO_SUMMARY, KG_EXTRACTION
    val status: PostProcessStatus,   // IDLE, RUNNING, DONE, ERROR
    val progress: Int = 0,
    val detail: String = ""
)

enum class PostProcessType { MEMORY_ARCHIVE, AUTO_SUMMARY, KG_EXTRACTION }
enum class PostProcessStatus { IDLE, RUNNING, DONE, ERROR }
```

#### 视觉设计:

```
┌─────────────────────────────────────────────────────┐
│ 📦 记忆归档: 3/5 · ⚡ 自动摘要: 处理中...          │
└─────────────────────────────────────────────────────┘
```

每个任务以 Chip 形式展示，可水平滚动。完成 3 秒后自动淡出。错误状态显示红色并持久化。

### 4.4 区 3: Detail Sheet 增强 (已有基础)

增强现有 `RagDetailsSheet`：

```
┌─────────────────── Rag Details ────────────────────┐
│  [检索结果] [知识图谱] [摘要历史]                    │
│                                                    │
│  ┌─ 知识图谱 ──────────────────────────────────┐   │
│  │  Nodes (5):                                │   │
│  │  ● 全书大纲 (document)                      │   │
│  │  ● 规划初稿 (document)                      │   │
│  │  ├── relates_to ── ● 主题设计                │   │
│  │  ├── contains ──── ● 章节规划                │   │
│  │  └── references ── ● 参考书目                │   │
│  │                                            │   │
│  │  [可视化图] (ECharts/Canvas)                │   │
│  └────────────────────────────────────────────┘   │
│                                                    │
│  ┌─ 摘要历史 ─────────────────────────────────┐   │
│  │  v3  2026-05-17 08:15  (auto)              │   │
│  │  "对话涉及全书大纲的规划..."                │   │
│  │  v2  2026-05-17 08:10  (auto)              │   │
│  │  v1  2026-05-17 08:05  (manual)            │   │
│  └────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────┘
```

### 4.5 手动压缩 UI

用户在会话设置中点击"手动压缩"时，在当前消息流末尾插入 `SummaryCard`：

```
┌─────────────────────────────────────────────────────┐
│ 📝 Context Compression                              │
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░  90%                  │
│ Summarizing 24 messages into compact context...      │
│                                                     │
│ Result:                                             │
│ "对话涉及全书大纲的规划，用户要求..."  (压缩比 15:1) │
│ [Expand] [Apply]                                    │
└─────────────────────────────────────────────────────┘
```

### 4.6 KG 抽取的可视化（特别设计）

**问题**: KG 抽取发生在文档导入后（异步后台），不在对话流中。

**方案**: 双层可视化

1. **知识库层级**（RagHomeScreen）:
   - FilesPanel 中每个文档旁边显示 KG 抽取状态图标
   - `○ pending → ⟳ extracting → ✓ completed → ✕ failed`
   - 已完成的可点击展开节点/边预览

2. **会话层级**（PostProcessBar）:
   - 如果最近导入的文档 KG 抽取完成 → PostProcessBar 弹出 "🧠 新知识图谱可用"
   - 点击跳转到 RagDetailsSheet 的知识图谱 Tab

---

## §5 实施计划

### Phase 1: RagOmniIndicator 连线 (P0, ~2h)

1. 在 `ChatScreen.kt` 消息渲染中，ASSISTANT 消息前插入 `RagOmniIndicator` 调用
2. 从 `message.ragProgress` / `message.ragMetadata` / `message.ragReferences` 读取数据
3. 在 `ContextBuilder.performRagRetrieval()` 中补充 KG 检索的 `onProgress` 回调
4. 在 `MemoryManager.retrieveContext()` 中拆分 Rerank 为独立进度阶段

### Phase 2: 多阶段管道改造 (P1, ~3h)

5. 创建 `RagPhase` / `PhaseStatus` 数据模型
6. 重构 `RagOmniIndicator` → `RagProgressCard`，支持多阶段列表渲染
7. ContextBuilder 构建 Phase 列表传给 ChatViewModel

### Phase 3: PostProcessBar (P1, ~2h)

8. 创建 `PostProcessBar` 组件 + `PostProcessTask` 模型
9. ChatViewModel 维护 `postProcessTasks: StateFlow<List<PostProcessTask>>`
10. postProcessor + summaryManager 更新任务状态
11. ChatScreen 底部添加 PostProcessBar

### Phase 4: 手动压缩 (P2, ~1.5h)

12. ChatViewModel 新增 `compressContext()` 方法
13. SessionSettingsSheet 添加"手动压缩"按钮
14. ChatScreen 支持 SummaryCard 渲染

### Phase 5: KG 可视化增强 (P2, ~2h)

15. RagDetailsSheet 增加 KG Tab（节点/边表格 + 简单关系图）
16. FilesPanel 增加 KG 状态图标
17. PostProcessBar 集成 KG 抽取完成通知

---

## §6 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| RAG+Rerank+KG检索在同一个指示器 | ✅ 是 | 三者都是 Pre-LLM 同步操作，统一组成"发送前预处理管道" |
| 记忆归档+自动摘要放在独立位置 | ✅ 是 | Post-LLM 异步，不适合气泡内展示；应放在底部状态栏 |
| KG 抽取双位置展示 | ✅ 是 | 知识库页面（源头）+ 会话状态栏（通知），各司其职 |
| 手动压缩在消息流内 | ✅ 是 | 用户主动操作，应在对话中有明确的"发生位置" |
| RagOmniIndicator 改名 | ✅ RagProgressCard | 更准确地表达"预处理阶段管道卡片"的含义 |

---

*设计文档存档至 `docs/audit/RAG_INDICATOR_ARCHITECTURE_DESIGN_20260517.md`。*
