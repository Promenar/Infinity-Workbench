# IDEA 会话变更交叉核验报告

> **核验日期**: 2026-05-17  
> **核验方**: CodeBuddy (GLM-5.1)  
> **变更方**: JetBrains IDEA (GLM-5.1)  
> **基准审计文档**: `docs/audit/RAG_KG_FULL_PIPELINE_AUDIT_20260517.md`

---

## 一、IDEA 变更全景

IDEA 会话共变更 **38 个文件**，+2281/-603 行。从 handover.md 记录来看，这是 **5 月 16~17 日多个会话的累积变更**，涵盖：

1. Embedding 配置解析三座冰山系统性修复 (RC-2/RC-5/RC-6)
2. MemoryManager 持有过期 EmbeddingClient 修复
3. RAG 指示器 6 会话全量交付
4. RAG 检索业务管线 4 项致命 Bug 修复
5. RAG 检索 opts 传递断裂修复
6. 会话设置面板 5 开关互相覆盖修复
7. 全站 by lazy 审计 + 6 项危害修复

---

## 二、与我的审计发现的交叉对比

### F-1: `RagOptions.enableDocs` 默认 `false`

| 维度 | 我的审计 | IDEA 实际修复 |
|------|---------|-------------|
| 是否发现 | ✅ 发现，标记 P0 致命 | ✅ 同样发现 |
| 根因定位 | `ChatModels.kt:246` 默认值 `false` | **未改默认值**，而是修复了两个上下游问题 |
| 修复方式 | 建议改默认值为 `true` | **采用更精细的方案**： |

**IDEA 的修复方案更优**：IDEA 没有简单地改默认值（这可能影响不想启用文档检索的用户），而是修复了两个关键问题：

1. **MemoryManager Q1 修复** (`MemoryManager.kt:58-66`): 当 `enableDocs=true` 且 `isGlobal=false`、`activeDocIds` 为空时，原逻辑静默跳过文档检索。修复后：`canSearchAllDocs = enableDocs && (isGlobal || !hasSpecificDocs)` — 未指定特定文档时等同全局搜索。

2. **ragOptions 传递断裂修复** (`ChatViewModel.kt`): 新增 `_currentRagOptions` StateFlow 缓存机制，确保用户切换开关后立即生效，绕过 store 异步延迟。

3. **设置面板互覆盖修复** (`SessionSettingsSheet.kt`): 5 个 toggle 改用 `chatViewModel.currentRagOptions.value.copy(...)` — 每次读取最新缓存值。

**核验结论**: IDEA 的修复比我提出的"改默认值"方案更精确。根因不在默认值本身，而在于：(a) 传递链路断裂导致用户即使手动开启也不生效；(b) MemoryManager 的 `canSearchDocs` 逻辑过于严格。

### F-2: KG 抽取依赖向量化成功

| 维度 | 我的审计 | IDEA 实际修复 |
|------|---------|-------------|
| 是否发现 | ✅ 发现，标记 P0 致命 | ⚠️ 未直接修复此问题 |
| 修复方式 | 建议直接调用 `graphExtractor.extractAndSave()` | **未改动 `extractKG()` 的实现** |

**核验结论**: IDEA 会话的 `extractKG()` 仍然走 `VectorizationQueue.enqueueDocument()` 路径。此问题 **尚未修复**。如果向量化失败，KG 抽取仍不会执行。但 IDEA 修复了上游的 embedding 配置问题（RC-2/RC-5/RC-6），使得向量化更可能成功，从而间接降低了此问题的触发概率。

### F-3: `NewVectorRecord` 缺少 `docId`

| 维度 | 我的审计 | IDEA 实际修复 |
|------|---------|-------------|
| 是否发现 | ✅ 发现，标记 P1 | ❌ **未发现** |

**核验结论**: `VectorizationQueue.kt:291` 的 `NewVectorRecord` 仍然缺少 `docId = docId`。此问题 **尚未修复**。但由于 MemoryManager 的全局搜索走 `getByType("document")` 而非 `getByDocIds`，在全局模式下不受影响。

### F-4: 指示器假完成

| 维度 | 我的审计 | IDEA 实际修复 |
|------|---------|-------------|
| 是否发现 | ✅ 发现，标记 P1 | ✅ 部分修复 |
| 修复方式 | 新增 SKIPPED 状态 | **不同方案** |

**IDEA 的方案**: 通过修复 F-1 的根因（MemoryManager + ragOptions 传递），使文档检索真正执行，从而 onRagProgress 回调能正确触发所有阶段。这样新增的 PhasePipeline 就能显示真实的进度。未执行的阶段（如 KG）保持 PENDING 而非标记 DONE。

**核验结论**: IDEA 没有引入 SKIPPED 状态，但通过修复上游问题使大多数阶段真正执行，间接解决了"假完成"。但 KG 阶段（当未开启时）仍会被批量标记为 DONE — 这是一个小瑕疵。

### F-5: `extractKG()` 异常被吞掉

| 维度 | 我的审计 | IDEA 实际修复 |
|------|---------|-------------|
| 是否发现 | ✅ 发现，标记 P2 | ❌ **未修复** |

`catch (_: Exception) { }` 仍然存在。

### F-6: importDocuments 不触发 KG

| 维度 | 我的审计 | IDEA 实际修复 |
|------|---------|-------------|
| 是否发现 | ✅ 发现，标记 P2 | ❌ **未修复** |

`importDocuments()` 仍不传 `kgStrategy`。

---

## 三、IDEA 发现但我未发现的问题

### 3.1 我遗漏的问题（共 8 项）

| # | 问题 | 位置 | 严重度 | 说明 |
|---|------|------|--------|------|
| E-1 | `getProviderConfigByModelId()` 方法完全不存在 | `ProviderManager.kt` | P0 | 编译不应通过但实际通过了（可能是因为无调用点） |
| E-2 | `persistModels()` 未保存 `provider_id` | `ProviderManager.kt` | P0 | 每次重启丢失 providerId |
| E-3 | `memoryManager` 使用 `by lazy` 持有过期 `embeddingClient` | `NexaraApplication.kt` | P0 | rebuildEmbeddingClient() 后 MemoryManager 仍持旧引用 |
| E-4 | 全新安装下 `NavGraph.kt` 无主提供商创建路径 | `NavGraph.kt` | P0 | 首次配置崩溃 |
| E-5 | 会话设置面板 5 开关互相覆盖 | `SessionSettingsSheet.kt` | P1 | 静态 val snapshot 问题 |
| E-6 | 全站 `by lazy` 过期引用（6 处危害） | `NexaraApplication.kt` | P1 | graphExtractor/vectorRepository/imageService/microGraphExtractor/kgProvider |
| E-7 | `buildEmbeddingClient()` 缺乏跨提供商兜底 | `NexaraApplication.kt` | P1 | 非主提供商的 embedding 模型无法解析 |
| E-8 | `settingsListener` 未注册 + 额外提供商变更无响应 | `NexaraApplication.kt` | P1 | 切换预设模型后 embedding client 不更新 |

### 3.2 为什么 IDEA 发现了更多问题

分析原因：

1. **上下文窗口利用差异**：
   - IDEA 会话是**连续多轮累积**（从 5 月 16 日 22:21 到 5 月 17 日），共经历 7+ 个修复阶段，逐步深入
   - 我的审计是**单轮一次性**完成，虽然覆盖了核心管线，但未深入基础设施层（ProviderManager、NexaraApplication）

2. **审计起点不同**：
   - IDEA 从"向量化配置诊断"切入 → 追踪到 ProviderManager → 发现 RC-5/RC-6 → 连锁发现 by lazy 问题
   - 我从"RAG 检索不工作"切入 → 追踪到 MemoryManager → ContextBuilder → ChatViewModel → 到此为止
   - **关键差异**: 我没有继续往上追踪到 `NexaraApplication.buildEmbeddingClient()` 和 `ProviderManager` 层

3. **代码阅读范围**：
   - 我读了 12 个核心文件（MemoryManager, ContextBuilder, ChatViewModel, RagViewModel, GraphExtractor, MicroGraphExtractor, KnowledgeGraphScreen 等）
   - IDEA 额外深入了 `NexaraApplication.kt`（190 行变更）、`ProviderManager.kt`（102 行变更）、`SettingsViewModel.kt`（168 行变更）、`NavGraph.kt`
   - **这些文件在 RAG 管线的上游**，是配置基础设施层

4. **模型本身的差异**：
   - 两个会话都用 GLM-5.1，模型能力相同
   - 但 IDEA 会话中的**系统提示和上下文注入**可能更完整（IDEA 插件会自动注入项目结构、文件索引等）
   - CodeBuddy 的上下文更多依赖我主动读取文件

---

## 四、尚未修复的问题清单

基于交叉核验，以下问题仍然存在：

| # | 问题 | 严重度 | 来源 |
|---|------|--------|------|
| F-2 | `extractKG()` 依赖向量化成功 | P0 | 我的审计 |
| F-3 | `NewVectorRecord` 缺少 `docId` | P1 | 我的审计 |
| F-5 | `extractKG()` 异常被 `catch(_){}` 吞掉 | P2 | 我的审计 |
| F-6 | `importDocuments()` 不传 `kgStrategy` | P2 | 我的审计 |

---

## 五、结论

1. IDEA 会话的修复质量**整体优于**我的单轮审计方案，尤其在基础设施层（ProviderManager、NexaraApplication）的修复更深入
2. 我的审计在 **RAG 检索管线的中间层**（MemoryManager → ContextBuilder → ChatViewModel）发现问题与 IDEA 重叠，且独立发现了 F-2/F-3/F-5/F-6 四个 IDEA 未涉及的问题
3. 两个会话**互补**：IDEA 修复了上游配置基础设施，我发现了下游 KG 管线的残留缺陷
4. 尚未修复的 4 个问题（F-2/F-3/F-5/F-6）需要在下一轮修复中处理
