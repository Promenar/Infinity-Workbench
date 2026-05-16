# RAG 设置四页全量审计报告

> **日期**: 2026-05-16
> **范围**: GlobalRagConfigScreen / RagAdvancedScreen / AdvancedRetrievalScreen
> **涉及文件**: 3 个 Screen + RagViewModel + RagConfigPersistence + RagModels

---

## 一、页面导航与项数统计

```
GlobalRagConfigScreen (RAG配置) — 16 个可交互项
  ├── → onNavigateToAdvanced → RagAdvancedScreen (高级) — 11 个可交互项
  └── → onNavigateToDebug → RagDebugScreen

AdvancedRetrievalScreen (高级检索) — 14 个可交互项
  └── → onNavigateToRagAdvanced → RagAdvancedScreen (高级)

KnowledgeGraphScreen — 可视化查看器（非设置页，不计入）
```

**合计：39 个可交互项，分布在 3 个设置页**

---

## 二、🔴 P0 Bug（3 个）

### Bug 1 — Preset 选中检测仅匹配 Balanced

**文件**: `GlobalRagConfigScreen.kt:105`
```kotlin
val isSelected = config.docChunkSize == 800 && config.chunkOverlap == 100
```
**问题**: 三个预设中只有 Balanced 参数 (800/100) 能命中此条件。Writing (1200/200) 和 Coding (500/50) **永远无法显示为选中状态**。

**修复**: 引入 `currentPreset` 状态，`applyPreset()` 时记录预设 ID，基于 ID 而非参数反推选中态。

---

### Bug 2 — 摘要模板编辑器不持久化

**文件**: `GlobalRagConfigScreen.kt:537-576`
```kotlin
// BottomSheet 保存按钮：
.clickable { showPromptEditor = false }  // ← 仅关闭Sheet，未调用 viewModel.updateConfig
```
**问题**: `showPromptEditor` 用 `remember { mutableStateOf(...) }` 本地状态，保存时只关闭 Sheet，**修改的模板文本完全丢失**。

**修复**: 保存时调用 `viewModel.updateConfig { copy(summaryTemplate = promptText) }`，并在 `RagConfiguration` 中新增 `summaryTemplate` 字段以支持持久化。

---

### Bug 3 — 查询改写策略 UI 初始值与配置不同步

**文件**: `AdvancedRetrievalScreen.kt:62`
```kotlin
var queryRewriteStrategy by remember { mutableStateOf("hyde") }
```
**问题**: 
- 本地 `remember` 初始化为 `"hyde"`
- `RagConfiguration.queryRewriteStrategy` 默认值是 `"multi-query"`
- `remember` 只在首次组合时初始化，后续 `config` 变化不会同步本地状态
- **UI 始终显示 HyDE 为选中，即使用户之前选的是 Multi-Query**

**修复**: 删除本地 `queryRewriteStrategy` 状态，直接读取 `config.queryRewriteStrategy`。

---

### Bug 4 — 清除孤立向量按钮为空操作

**文件**: `GlobalRagConfigScreen.kt:447-466`
```kotlin
.clickable { }  // ← 空闭包
```

**修复**: 接入 `viewModel` 中的清除逻辑或暂时隐藏按钮。

---

## 三、🟡 跨页重复项（3 组）

| 配置项 | 出现位置 | 分析 |
|--------|---------|------|
| **enableRerank** | Page1 底部 "Reranker" 卡片 + Page3 "Rerank" 区块 | **三处影响**（Page3 中 memory/docLimit 被 rerank 间接禁用）。Page1 应移除，Rerank 统一在 Page3 控制 |
| **memoryThreshold** | Page1 "检索参数" 卡 (30-95%) + Page3 "Memory" 区 (0-1) | 同一字段两套 UI，范围显示不一致（% vs 小数），造成困惑 |
| **docThreshold** | Page1 "检索参数" 卡 (20-90%) + Page3 "Document" 区 (0-1) | 同上 |

---

## 四、🟠 放置不当（5 项）

| 配置项 | 当前位置 | 建议位置 | 理由 |
|--------|---------|---------|------|
| `memoryThreshold` | Page1 | Page3 | 属于检索阈值，不是分块参数 |
| `docThreshold` | Page1 | Page3 | 同上 |
| `contextWindow` | Page1 | Page2 | 属于上下文管理，不是分块参数 |
| `summaryThreshold` | Page1 | Page2 | 同上 |
| `rerankMaxPerCall` | Page1 | Page3 | 与 Rerank 参数合并 |

---

## 五、🔵 缺失 UI 的配置项（10 项）

| 配置项 | 默认值 | 当前可见范围 |
|--------|--------|------------|
| `enableMemory` | `true` | **无任何页面可配置** |
| `enableDocs` | `true` | **无任何页面可配置** |
| `memoryChunkSize` | `1000` | 仅 `AgentRagConfigScreen` |
| `queryRewriteModel` | `null` | 无模型选择器 |
| `queryRewriteCount` | `3` | 仅 `AgentAdvancedRetrievalScreen` |
| `jitTimeoutMs` | `5000` | 无 UI |
| `jitMaxCharsPerChunk` | `2000` | 无 UI |
| `jitCacheTTL` | `3600` | 无 UI |
| `kgEntityTypes` | `[]` | 无 UI |
| `kgDomainHint` | `null` | 无 UI |

---

## 六、⚠️ 已配置但未接入管线的项（5 项）

| 配置项 | 持久化 | 管线使用状态 |
|--------|--------|------------|
| `contextWindow` | ✅ | ❌ 业务代码中未消费此字段 |
| `summaryThreshold` | ✅ | ❌ 同上 |
| `embedDimension` | ✅ | ❌ `EmbeddingClient` 未接收此参数 |
| `maxEmbedTokensPerCall` | ✅ | ❌ `EmbeddingClient.embedBatch` 未分批 |
| `rerankMaxPerCall` | ✅ | ❌ `RerankClient` 未分批 |
| `costStrategy` | ✅ | ❌ KG 提取未按策略切换 |

---

## 七、逐项完整审计表

| 项 | 页面 | 数据流 | 持久化 | 后端使用 | 判定 |
|----|------|--------|--------|----------|------|
| Presets (3个) | Page1 | `viewModel.applyPreset()` → `updateConfig()` | ✅ | `VectorizationQueue.processDocumentTask` chunk 参数 | ✅ (含 Bug) |
| `docChunkSize` | Page1 | `updateConfig { copy(docChunkSize=...) }` | ✅ `RagConfigPersistence` | `VectorizationQueue` 分块 | ✅ |
| `chunkOverlap` | Page1 | `updateConfig { copy(chunkOverlap=...) }` | ✅ | `VectorizationQueue` 分块 | ✅ |
| `contextWindow` | Page1 | `updateConfig { copy(contextWindow=...) }` | ✅ | 未接入 | ⚠️ |
| `summaryThreshold` | Page1 | `updateConfig { copy(summaryThreshold=...) }` | ✅ | 未接入 | ⚠️ |
| `memoryThreshold` (Page1) | Page1 | `updateConfig { copy(memoryThreshold=...) }` | ✅ | `MemoryManager.retrieveContext` | ✅ 重复 |
| `docThreshold` (Page1) | Page1 | `updateConfig { copy(docThreshold=...) }` | ✅ | `MemoryManager.retrieveContext` | ✅ 重复 |
| Summary Template | Page1 | 无持久化 | ❌ | 硬编码 | ❌ Bug |
| Reranker toggle (Page1) | Page1 | `updateConfig { copy(enableRerank=...) }` | ✅ | `MemoryManager.retrieveContext` | ✅ 重复 |
| `embedDimension` | Page1 | `updateConfig { copy(embedDimension=...) }` | ✅ `prefs` | `EmbeddingClient` 未接收 | ⚠️ |
| `maxEmbedTokensPerCall` | Page1 | `updateConfig { copy(maxEmbedTokensPerCall=...) }` | ✅ `prefs` | `EmbeddingClient` 未分批 | ⚠️ |
| `rerankMaxPerCall` | Page1 | `updateConfig { copy(rerankMaxPerCall=...) }` | ✅ `prefs` | `RerankClient` 未分批 | ⚠️ |
| Clear Vectors | Page1 | BottomSheet → 无实际操作 | ❌ | 无 | ❌ |
| Clean Orphans | Page1 | 空闭包 | ❌ | 无 | ❌ 死按钮 |
| KG Enable | Page2 | `updateConfig { copy(enableKnowledgeGraph=...) }` | ✅ | `ContextBuilder` KG 开关 | ✅ |
| KG Extract Model | Page2 | ModelPicker → `updateConfig { copy(kgExtractionModel=...) }` | ✅ | `GraphExtractor.modelId` | ✅ |
| JIT Enable | Page2 | toggle → `jitMaxChunks = 128/0` | ✅ | `MicroGraphExtractor` JIT | ✅ |
| JIT Max Blocks | Page2 | slider 16-512 | ✅ | JIT 块数上限 | ✅ |
| `kgFreeMode` | Page2 | toggle | ✅ | KG 自由模式 | ✅ |
| `kgDomainAuto` | Page2 | toggle | ✅ | KG 域推断 | ✅ |
| Cost Strategy | Page2 | RadioButton 三选一 | ✅ | 未接入 | ⚠️ |
| `enableIncrementalHash` | Page2 | toggle | ✅ | 增量哈希 | ✅ |
| `enableLocalPreprocess` | Page2 | toggle | ✅ | 本地预过滤 | ✅ |
| KG Prompt | Page2 | BottomSheet → `updateConfig` | ✅ | `GraphExtractor.systemPrompt` | ✅ |
| `memoryLimit` | Page3 | slider 1-50 | ✅ | `MemoryManager` top-K | ✅ |
| `memoryThreshold` (Page3) | Page3 | slider 0-1 | ✅ | 同 Page1 | ✅ 重复 |
| `docLimit` | Page3 | slider 1-50 | ✅ | `MemoryManager` top-K | ✅ |
| `docThreshold` (Page3) | Page3 | slider 0-1 | ✅ | 同 Page1 | ✅ 重复 |
| `enableHybridSearch` | Page3 | toggle | ✅ | `MemoryManager.rrfFusion` | ✅ |
| `hybridAlpha` | Page3 | slider 0-1 | ✅ | RRF 向量权重 | ✅ |
| `hybridBM25Boost` | Page3 | slider 0.5-2.0 | ✅ | RRF BM25 增强 | ✅ |
| `enableRerank` (Page3) | Page3 | toggle | ✅ | 同 Page1 | ✅ 重复 |
| `rerankTopK` | Page3 | slider 5-100 | ✅ | Rerank 候选数 | ✅ |
| `rerankFinalK` | Page3 | slider 1-20 | ✅ | Rerank 输出数 | ✅ |
| `enableQueryRewrite` | Page3 | toggle | ✅ | 查询改写开关 | ✅ |
| `queryRewriteStrategy` | Page3 | 3 芯片 | ✅ (含 Bug) | 改写策略 | ✅ |
| `showRetrievalProgress` | Page3 | toggle | ✅ | 进度可见 | ✅ |
| `showRetrievalDetails` | Page3 | toggle | ✅ | 详情可见 | ✅ |
| `trackRetrievalMetrics` | Page3 | toggle | ✅ | 指标追踪 | ✅ |

---

## 八、推荐重构方案（3 页结构）

```
┌─────────────────────────────────────────────────────────────┐
│ 📄 分块与向量化 (Chunk & Vectorize)                          │
│    [原 GlobalRagConfigScreen 核心部分]                       │
├─────────────────────────────────────────────────────────────┤
│  预设方案            Balanced / Writing / Coding    [修复选中检测]
│  ──────────────────────────────────────────────────────────┤
│  文档分块大小         docChunkSize           100-2000 (800) │
│  分块重叠             chunkOverlap           0-500 (100)    │
│  记忆分块大小         memoryChunkSize       500-3000 (1000) │[从Agent提升]
│  ──────────────────────────────────────────────────────────┤
│  Embedding 模型       embedDimension         0-4096 (0=自动) │
│                        maxEmbedTokensPerCall 256-16384      │
│  ──────────────────────────────────────────────────────────┤
│  向量统计 (只读)      Docs/Vectors/Storage                  │
│  操作                 [清除向量] [清除孤立]   [修复实现]      │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ 📄 检索策略 (Retrieval Strategy)                              │
│    [原 AdvancedRetrievalScreen + 合并 Page1 分散项]          │
├─────────────────────────────────────────────────────────────┤
│  检索来源             enableMemory / enableDocs     [🆕 新增]│
│  ──────────────────────────────────────────────────────────┤
│  记忆检索             memoryLimit (1-50)                     │
│                        memoryThreshold (0-1)       [从Page1迁]│
│  文档检索             docLimit (1-50)                        │
│                        docThreshold (0-1)          [从Page1迁]│
│  ──────────────────────────────────────────────────────────┤
│  混合检索             enableHybridSearch (开/关)             │
│                        hybridAlpha (0-1)                     │
│                        hybridBM25Boost (0.5-2.0)             │
│  ──────────────────────────────────────────────────────────┤
│  重排序               enableRerank (开/关)      [Page1+3合并]│
│                        rerankTopK (5-100)                    │
│                        rerankFinalK (1-20)                   │
│                        rerankMaxPerCall (8-200) [从Page1迁]  │
│  ──────────────────────────────────────────────────────────┤
│  查询改写             enableQueryRewrite (开/关)             │
│                        strategy (HyDE/Multi-Query/Expansion) │
│                        model + count               [🆕 新增]│
│  ──────────────────────────────────────────────────────────┤
│  可观测性             showProgress / showDetails / metrics   │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ 📄 知识图谱与上下文 (KG & Context)                            │
│    [原 RagAdvancedScreen + contextWindow/summary 等]        │
├─────────────────────────────────────────────────────────────┤
│  启用知识图谱         enableKnowledgeGraph (开/关)           │
│  提取模型选择         kgExtractionModel (ModelPicker)        │
│  ──────────────────────────────────────────────────────────┤
│  JIT 即时提取         jitMaxChunks (开/关 + 16-512)          │
│                        kgFreeMode / kgDomainAuto (开/关)     │
│  ──────────────────────────────────────────────────────────┤
│  成本策略             summary / on-demand / full-scan       │
│  ──────────────────────────────────────────────────────────┤
│  上下文窗口           contextWindow            4-128 (20)   │[从Page1迁]
│  摘要阈值             summaryThreshold         0-50 (10)    │[从Page1迁]
│  摘要模板             可编辑模板                 [修复持久化] │
│  ──────────────────────────────────────────────────────────┤
│  提取提示词           可编辑 + 重置默认                     │
│  ──────────────────────────────────────────────────────────┤
│  优化选项             incrementalHash / localPreprocess      │
│  ──────────────────────────────────────────────────────────┤
│  → 查看知识图谱                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 九、实施建议优先级

| 优先级 | 任务 | 预估工时 |
|--------|------|---------|
| **P0** | 修复 3 个 Bug（Preset 选中、摘要模板丢失、策略初始值） | 1h |
| **P0** | 消除 3 组重复项（enableRerank/memoryThreshold/docThreshold） | 0.5h |
| **P1** | 执行 3 页重构方案（项迁移 + 新增 UI 项） | 3h |
| **P1** | 接入未使用配置（contextWindow/summary/embedDim 等） | 2h |
| **P2** | 补充缺失 UI（enableMemory/enableDocs/queryRewriteModel 等） | 1.5h |
| **P2** | 修复无关项（Clear Vectors 实现、移除 Clean Orphans 死按钮） | 1h |

---

*文档结束*
