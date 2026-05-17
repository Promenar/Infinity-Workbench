# 高级检索配置架构缺口全链路审计报告

> **审计日期**: 2026-05-17 17:31  
> **审计范围**: AdvancedRetrievalScreen + RagAdvancedScreen 全部设置项 ↔ 实际管线消费  
> **触发原因**: 页面缺少垂直滚动导致底部开关不可见 → 追问"还有多少暗病"

---

## 一、P0 致命发现：高级检索所有滑块参数完全不生效

### 1.1 根因

用户通过 `AdvancedRetrievalScreen` 修改的参数写入 SharedPreferences ✅，但 `MemoryManager` 始终使用 `NexaraApplication` 中 `RagConfiguration()` 硬编码默认值。两者之间没有任何代码桥接。

### 1.2 断链示意图

```
AdvancedRetrievalScreen (UI)
  │ 用户调 memoryThreshold=0.5, docLimit=20, rerankTopK=50
  ▼
RagViewModel.updateConfig() → saveConfig()
  │
  ▼
SharedPreferences "rag_settings"  ✅ 已持久化
  │
  │  ⚡ 断链！NexaraApplication 从不读取 SharedPreferences 的 RAG 配置
  │
  ▼
NexaraApplication.memoryManager (L431)
  MemoryManager(
    ragConfig = RagConfiguration()   ← 永远是硬编码默认值
  )
  │
  ▼
MemoryManager.retrieveContext() (L57)
  effectiveConfig = ragConfig        ← 永远默认
  effectiveConfig.memoryThreshold  = 0.7   (永远默认)
  effectiveConfig.docThreshold     = 0.45  (永远默认)
  effectiveConfig.rerankTopK       = 30    (永远默认)
  effectiveConfig.hybridAlpha      = 0.6   (永远默认)
```

### 1.3 影响范围

`MemoryManager.retrieveContext()` 和 `addTurnToMemory()` 中所有从 `ragConfig` 读取的参数全部使用默认值，用户界面上的滑块调整无任何效果。

---

## 二、字段消费状态完整矩阵

### 2.1 🟡 实际消费但值永远为默认

| 字段 | 默认值 | 消费位置 | 消费方法 |
|------|-------|---------|---------|
| `memoryThreshold` | 0.7f | MemoryManager.kt:112 | `vectorStore.search(threshold=)` |
| `docThreshold` | 0.45f | MemoryManager.kt:151 | `vectorStore.search(threshold=)` |
| `memoryLimit` | 5 | MemoryManager.kt:221 | `topMemories.take(memoryLimit)` |
| `docLimit` | 8 | MemoryManager.kt:224 | `topDocs.take(docLimit)` |
| `rerankTopK` | 30 | MemoryManager.kt:111,150 | `vectorStore.search(limit=)` |
| `hybridAlpha` | 0.6f | MemoryManager.kt:327 | `rrfFusion()` 向量权重 |
| `hybridBM25Boost` | 1.0f | MemoryManager.kt:327 | `rrfFusion()` BM25 增强 |
| `memoryChunkSize` | 1000 | MemoryManager.kt:289 | `addTurnToMemory()` TrigramTextSplitter |
| `chunkOverlap` | 100 | MemoryManager.kt:290 | `addTurnToMemory()` TrigramTextSplitter |
| `docChunkSize` | 800 | VectorizationQueue.kt:239 | `processDocumentTask()` TrigramTextSplitter |
| `enableRerank` | true | MemoryManager.kt:70 | `canRerank` 决策 |

### 2.2 🔴 死字段——定义、UI 可操作、持久化，但零管线消费

| # | 字段 | UI 中文字符串 | UI 英文字符串 | UI 位置 | 说明 |
|---|------|------------|------------|--------|------|
| 1 | `enableIncrementalHash` | 增量哈希 | Incremental Hash | RagAdvancedScreen 本地优化 | 无任何消费者 |
| 2 | `enableLocalPreprocess` | 规则预过滤 | Rule Prefilter | RagAdvancedScreen 本地优化 | 无任何消费者 |
| 3 | `kgDomainAuto` | 域名自动检测 | Domain Auto-Detection | RagAdvancedScreen JIT 微图 | GraphExtractor/MicroGraphExtractor 不按域名过滤 |
| 4 | `kgFreeMode` | 免费模式 | Free Mode | RagAdvancedScreen JIT 微图 | 无任何消费者 |
| 5 | `costStrategy` | 成本策略(摘要优先/按需/全扫描) | Cost Strategy | RagAdvancedScreen 成本策略 | 3 RadioButton 全零消费 |
| 6 | `kgExtractionPrompt` | 抽取提示词 | Extraction Prompt | RagAdvancedScreen 抽取提示词 | GraphExtractor 用硬编码 DEFAULT_KG_PROMPT |
| 7 | `kgExtractionModel` | 抽取模型 | Extraction Model | RagAdvancedScreen 抽取配置 | GraphExtractor 用构造函数 modelId |
| 8 | `rerankFinalK` | 最终结果数 | Final Results | AdvancedRetrievalScreen 重排序 | RerankClient 不读取 |
| 9 | `rerankMaxPerCall` | 单次最多重排文档数 | Max Docs Per Call | AdvancedRetrievalScreen 重排序 | RerankClient 不读取 |
| 10 | `queryRewriteModel` | — (无独立UI) | — | 持久化但无 UI | QueryRewriter 未接入 MemoryManager |
| 11 | `queryRewriteCount` | — (无独立UI) | — | 持久化但无 UI | QueryRewriter 未接入 MemoryManager |
| 12 | `showRetrievalProgress` | 显示检索进度 | Show Progress | AdvancedRetrievalScreen 可观测性 | RagProgressCard 不检查 |
| 13 | `showRetrievalDetails` | 显示检索详情 | Show Details | AdvancedRetrievalScreen 可观测性 | 无消费者 |
| 14 | `trackRetrievalMetrics` | 追踪检索指标 | Track Metrics | AdvancedRetrievalScreen 可观测性 | 无消费者 |
| 15 | `jitTimeoutMs` | — (无独立UI) | — | 仅数据类定义 | 零消费 |
| 16 | `jitMaxCharsPerChunk` | — (无独立UI) | — | 仅数据类定义 | 零消费 |
| 17 | `jitCacheTTL` | — (无独立UI) | — | 仅数据类定义 | 零消费 |
| 18 | `kgEntityTypes` | — (无独立UI) | — | 仅数据类定义 | 零消费 |
| 19 | `kgDomainHint` | — (无独立UI) | — | 仅数据类定义 | 零消费 |
| 20 | `embedDimension` | — (无独立UI) | — | 仅数据类定义 | EmbeddingClient 不读取 |
| 21 | `maxEmbedTokensPerCall` | — (无独立UI) | — | 仅数据类定义 | EmbeddingClient 不读取 |
| 22 | `summaryTemplate` | — (无独立UI) | — | 仅数据类定义 | 零消费 |

### 2.3 统计

| 类别 | 数量 | 占比 |
|------|------|------|
| RagConfiguration 总字段 | 50 | 100% |
| 实际消费（但值固定默认） | 11 | 22% |
| 死字段（零消费） | 22 | 44% |
| 正常工作（enableMemory/enableDocs 等流程字段） | 11 | 22% |
| 其他（queryRewriteStrategy 等部分接入） | 6 | 12% |

---

## 三、修复方案

### 3.1 P0：打通 RagConfiguration 链路

**目标**: 让 AdvancedRetrievalScreen 的滑块调整真正生效。

**方案一（推荐）**: `NexaraApplication.memoryManager` getter 从 `RagConfigPersistence` 读取用户保存的配置：

```kotlin
// NexaraApplication.kt
private val ragConfigPersistence: RagConfigPersistence by lazy {
    RagConfigPersistence(getSharedPreferences("rag_settings", MODE_PRIVATE))
}

val memoryManager: MemoryManager
    get() = _memoryManager ?: MemoryManager(
        vectorStore = vectorStore,
        keywordSearcher = keywordSearcher,
        graphStore = graphStore,
        embeddingClient = embeddingClient,
        rerankClient = rerankClient,
        ragConfig = ragConfigPersistence.loadFullConfig()  // 从 SP 读取
    ).also { _memoryManager = it }
```

**方案二（补充）**: `RagViewModel.saveConfig()` 中增加 `(application as NexaraApplication).rebuildMemoryManager()` 使修改即时生效。

**推荐组合方案**: 方案一保证重启后生效，方案二保证运行时即时生效。

### 3.2 P1：清理死字段

22 个死字段分三类处理：

| 类别 | 字段 | 处理方式 |
|------|------|---------|
| **UI 已暴露但不生效** (9个) | enableIncrementalHash, enableLocalPreprocess, kgDomainAuto, kgFreeMode, costStrategy, kgExtractionPrompt, rerankFinalK, rerankMaxPerCall, showRetrievalProgress/showRetrievalDetails/trackRetrievalMetrics | ①连接管线 或 ②UI 增加 "Coming Soon" 标注 或 ③暂时隐藏 |
| **有模型选择器但未接入** (1个) | kgExtractionModel | 接入 GraphExtractor 构造函数 |
| **纯数据定义无 UI** (12个) | jitTimeoutMs, jitMaxCharsPerChunk, jitCacheTTL, kgEntityTypes, kgDomainHint, embedDimension, maxEmbedTokensPerCall, summaryTemplate, queryRewriteModel, queryRewriteCount | 保留供未来使用，或清理 |

### 3.3 需要新增的方法

`RagConfigPersistence` 需新增 `loadFullConfig(): RagConfiguration` 方法，合并 `loadRagConfig()` 和 `loadRetrievalConfig()` 的结果，同时补全 `RagConfigPersistence` 中 `loadRagConfig()` 缺失的少量字段读取。

---

## 四、变更预估

| 阶段 | 文件 | 变更量 | 工时 |
|------|------|--------|------|
| Phase 1: 配置链路打通 | NexaraApplication.kt, RagConfigPersistence.kt, RagViewModel.kt | ~30 行 | 0.5h |
| Phase 2: 死字段清理 | AdvancedRetrievalScreen.kt, RagAdvancedScreen.kt | ~20 行 | 0.5h |
| Phase 3: 编译验证 | — | — | 0.2h |

---

*审计结束。Phase 1 实施后，用户在高级检索页面调整的每个滑块都将实际影响检索行为。*
