# RAG 检索 + 知识图谱全链路审计报告

> **审计日期**: 2026-05-17  
> **审计范围**: RAG 检索管线 + 知识图谱管线 + 可视化指示器  
> **审计方法**: 从用户操作入口到数据存储，逐颗粒度追踪完整数据流  
> **触发原因**: 真机测试发现：(1) 文档已入库但聊天检索无效，指示器 1s 假完成；(2) 图谱页面为空，KG 抽取无失败反馈  

---

## 一、审计结论摘要

| # | 严重度 | 问题描述 | 根因位置 | 影响 |
|---|--------|---------|---------|------|
| F-1 | **P0 致命** | RAG 文档检索永远不执行 | `ChatModels.kt:246` | 用户导入文档后，聊天永远无法引用文档内容 |
| F-2 | **P0 致命** | KG 图谱抽取依赖向量化成功 | `RagViewModel.kt:621` | 若 embedding 有问题，KG 抽取代码永远不执行且无失败提示 |
| F-3 | **P1 重要** | 文档向量化记录缺失 docId | `VectorizationQueue.kt:291` | vectors 表 doc_id 列为 NULL，按文档过滤的检索永远匹配不到 |
| F-4 | **P1 重要** | 指示器假完成：跳过的步骤显示为完成 | `ChatViewModel.kt:403` | 用户无法判断哪些步骤真的执行了 |
| F-5 | **P2 一般** | KG 抽取失败静默吞错 | `RagViewModel.kt:628` | 抽取失败后 UI 仍显示 IN_PROGRESS，永远不会变成 FAILED |
| F-6 | **P2 一般** | importDocuments 不传 kgStrategy | `RagViewModel.kt:548` | 导入文档时不会自动触发 KG 抽取（仅向量化） |

---

## 二、RAG 检索管线完整数据流审计

### 2.1 数据流路径

```
用户发送消息
  → ChatViewModel.sendMessage()
    → ChatViewModel.generateMessage()
      → ContextBuilder.buildContext(params)
        → ContextBuilder.performRagRetrieval(params)
          → RagProvider.retrieveContext()  [MemoryManagerRagAdapter]
            → MemoryManager.retrieveContext(query, sessionId, options)
              → EmbeddingClient.embedQuery(query)           // 生成查询向量
              → VectorStore.search(queryEmbedding, ...)       // 向量检索
              → KeywordSearcher.search(query, ...)            // 关键词检索
              → rrfFusion(vectorResults, keywordResults)      // RRF 融合
              → RerankClient.rerank(query, results)           // 重排序
            → 返回 (context, references, usage)
          → 返回 Triple(context, references, usage)
        → kgProvider.extractContext(...) [可选]
      → buildSystemPrompt(context, references, kgContext)
    → llmProvider.sendPrompt(request)
  → 流式输出到 UI
```

### 2.2 致命缺陷 F-1：文档检索永远不执行

**位置**: `ChatModels.kt:246`

```kotlin
data class RagOptions(
    val enableMemory: Boolean = true,
    val enableDocs: Boolean = false,  // ← 致命：默认关闭
    ...
)
```

**影响链路**:

1. 用户在知识库页面导入文档 → `VectorizationQueue.enqueueDocument()` → 向量化成功 → vectors 表中有数据 ✅
2. 用户在聊天界面发消息 → `generateMessage()` → `_currentRagOptions` 值为 `RagOptions()` 默认实例
3. `ContextBuilder.performRagRetrieval()`:
   ```kotlin
   val sessionRagOptions = params.session.ragOptions ?: RagOptions()  // enableDocs=false
   val tempRagOptions = params.ragOptions ?: RagOptions()              // enableDocs=false
   val finalRagOptions = RagOptions(
       enableDocs = tempRagOptions.enableDocs && sessionRagOptions.enableDocs  // false && false = false
   )
   val isRagEnabled = finalRagOptions.enableMemory || finalRagOptions.enableDocs  // true || false = true
   ```
4. `MemoryManager.retrieveContext()`:
   ```kotlin
   val canSearchDocs = options.enableDocs && (options.isGlobal || !hasSpecificDocs)  // false && ... = false
   ```
5. 文档搜索分支 `if (canSearchDocs)` **永远不进入** → 只有 memory 搜索生效

**结果**: 用户导入的文档向量虽然存在数据库中，但检索时被静默跳过。LLM 收到的上下文中永远不包含文档内容。

**验证方法**: 在 `ContextBuilder.performRagRetrieval()` 中添加日志 `finalRagOptions.enableDocs`，真机运行时必然为 `false`。

### 2.3 缺陷 F-4：指示器假完成

**位置**: `ChatViewModel.kt:403`

```kotlin
_ragPhases.update { phases -> 
    phases.map { p -> if (p.status != PhaseStatus.DONE) p.copy(status = PhaseStatus.DONE) else p } 
}
```

**问题**: 8 个 phase（embed、memory、docs、hybrid、rank、rerank、kg、ready）初始为 `PENDING`。由于 F-1 导致文档检索不执行：
- `onRagProgress` 回调可能只触发了 embed 和 memory 阶段
- 其余 phase 从未被激活（始终 PENDING）
- 上述代码将所有 PENDING 批量标记为 DONE

**用户看到**: 所有 8 个步骤 1s 内全部显示绿色对勾 → 假完成视觉

### 2.4 缺陷 F-3：文档向量化记录缺失 docId

**位置**: `VectorizationQueue.kt:290-298`

```kotlin
val records = chunks.mapIndexed { i, chunk ->
    VectorStore.NewVectorRecord(
        sessionId = null,       // ← 文档没有 sessionId，正确
        content = chunk,
        embedding = embeddingResult.embeddings[i],
        metadata = """{"type":"document","fileUuid":"$docId","chunkIndex":$i}""",
        startMessageId = docId,
        endMessageId = docId
    )
}
```

**问题**: `NewVectorRecord` 有 `docId` 参数，但这里没有传入。对比 `VectorStore.addVectorRecords()`:

```kotlin
val entities = vectors.map { vec ->
    VectorEntity(
        id = UUID.randomUUID().toString(),
        docId = vec.docId,        // ← null（因为没传入）
        ...
    )
}
```

**影响**: `vectors` 表中所有文档类型的记录 `doc_id` 列为 NULL。以下查询全部失效：
- `VectorDao.getByDocId(docId)` — 空
- `VectorDao.getByDocIds(docIds)` — 空
- `VectorDao.getByTypeAndDocIds("document", docIds)` — 空

**但实际检索仍可能工作**，因为 `MemoryManager.retrieveContext()` 在 `canSearchDocs` 为 true 时调用:
```kotlin
vectorStore.search(
    queryEmbedding = queryEmbedding,
    filter = VectorStore.SearchFilter(type = "document", docIds = null)  // authorizedDocIds 在全局模式下为 null
)
```
→ 走 `filter.type != null` 分支 → `vectorDao.getByType("document")` → 返回所有文档向量 → **检索不受影响**

**但当用户指定了 activeDocIds 时**（非全局模式）:
```kotlin
filter = VectorStore.SearchFilter(type = "document", docIds = authorizedDocIds.toList())
```
→ 走 `filter.docIds != null` → `vectorDao.getByTypeAndDocIds("document", docIds)` → **匹配 doc_id 列** → 空（因为 doc_id 为 NULL）

**结论**: 全局文档检索可能侥幸工作，但指定文档检索必然失败。

---

## 三、知识图谱管线完整数据流审计

### 3.1 数据流路径

```
路径 A：文档导入时自动触发（不触发 KG）
  RagViewModel.importDocuments(uris)
    → vectorizationQueue.enqueueDocument(docId, docTitle, content)  // kgStrategy=null
    → VectorizationQueue.processDocumentTask()
      → 向量化成功
      → if (kgStrategy != null) → false → KG 抽取跳过

路径 B：手动触发 KG 抽取
  RagViewModel.extractKG(uuid)
    → vectorizationQueue.enqueueDocument(docId, docTitle, content, kgStrategy="full")
    → VectorizationQueue.processDocumentTask()
      → 向量化 ← 必须先成功
      → if (kgStrategy != null) → true
        → GraphExtractor.extractAndSave(content, docId)
          → LlmProvider.protocol.sendPromptSync(request)  // 调用 LLM 抽取实体
          → parseExtractionResult(content)                  // 解析 JSON
          → graphStore.upsertNode(...)                      // 写入节点
          → graphStore.createEdge(...)                       // 写入边

路径 C：图谱页面查看
  KnowledgeGraphScreen (Composable)
    → KnowledgeGraphViewModel.loadGraph()
      → graphStore.getGraphData()  // 查询 kg_nodes + kg_edges
      → renderFromCache()
        → 读取 kg_template.html 模板
        → 替换 __GRAPH_DATA__ 占位符
        → WebView 加载 HTML+ECharts
```

### 3.2 致命缺陷 F-2：KG 抽取依赖向量化成功

**位置**: `RagViewModel.kt:613-630`

```kotlin
fun extractKG(uuid: String) {
    viewModelScope.launch {
        try {
            val entry = workspaceRepository.getByUuid(uuid) ?: return@launch
            val content = fileOperationRepository.readFileRange(uuid).content
            if (content.isNotBlank()) {
                _kgExtractingIds.add(uuid)
                _kgExtractionStates.value = ... + (uuid to KgStatus.IN_PROGRESS)
                app.vectorizationQueue.enqueueDocument(   // ← 走完整向量化管线
                    docId = uuid,
                    docTitle = entry.name,
                    content = content,
                    kgStrategy = "full"
                )
            }
        } catch (_: Exception) { }   // ← F-5：异常静默吞掉
    }
}
```

**问题链路**:

1. `enqueueDocument()` 将任务放入 `VectorizationQueue`
2. `processDocumentTask()` 先执行切块 → embedding → 保存向量
3. **只有向量化全部成功后**，才执行 `if (graphExtractor != null && task.kgStrategy != null)` 中的 KG 抽取
4. 如果 embedding 配置有问题（baseUrl 为空、API key 缺失等），向量化步骤就会抛异常
5. 异常被 `processNext()` 的 `catch` 捕获，任务标记为 `failed`
6. KG 抽取代码 **永远不会被执行到**

**影响**: 用户点击"提取知识图谱"后，如果 embedding 有任何问题，KG 抽取根本不会发生，但 UI 上：
- `_kgExtractionStates` 设为了 `IN_PROGRESS`
- `_kgExtractingIds` 中加入了 uuid
- `observeQueue()` 中只在 `completed`/`warning`/`failed` 时更新状态
- 但 `enqueueDocument()` 会重新走完整的向量化流程（包括删除旧向量），如果失败 → `currentTask.status = "failed"` → 应该触发 `KgStatus.FAILED`
- **但** `catch (_: Exception) { }` 在 `extractKG()` 中吞掉了所有异常

**真机场景**:
- 向量化本身可能成功（因为 embedding 配置已经修好了）
- 但 `extractKG()` 重新把整个文档走一遍向量化 → **浪费时间和 API 调用**
- KG 抽取使用 `GraphExtractor`，它需要 LLM 调用 → 如果 LLM 配置有问题也会失败
- 失败后 `task.subStatus = "知识图谱提取跳过（非致命）"` → 整个任务仍标记为 `completed` → **假成功**

### 3.3 缺陷 F-5：KG 抽取异常被静默吞掉

**位置**: `RagViewModel.kt:628`

```kotlin
} catch (_: Exception) { }   // 完全静默
```

**影响**: 
- 如果 `workspaceRepository.getByUuid(uuid)` 抛异常 → 无反馈
- 如果 `fileOperationRepository.readFileRange(uuid)` 抛异常 → 无反馈
- 如果 `vectorizationQueue.enqueueDocument()` 抛异常 → 无反馈
- UI 状态永远是 `IN_PROGRESS`，永远不会变成 `FAILED` 或 `COMPLETED`

### 3.4 缺陷 F-6：importDocuments 不触发 KG

**位置**: `RagViewModel.kt:548`

```kotlin
app.vectorizationQueue.enqueueDocument(
    docId = uuid,
    docTitle = fileName!!,
    content = content
    // kgStrategy 未传递 → 默认 null
)
```

**影响**: 即使在 RagAdvancedScreen 中开启了知识图谱开关，文档导入时也不会自动触发 KG 抽取。用户必须手动逐个文件点击"提取知识图谱"。

---

## 四、可视化指示器审计

### 4.1 数据流路径

```
ChatViewModel.generateMessage()
  → 初始化 _ragPhases = 8 个 PENDING phase
  → ContextBuilder.buildContext(params)
    → onRagProgress 回调被调用时:
      → _ragPhases.update { ... }  // 匹配阶段名 → 设为 ACTIVE
      → 之前的 ACTIVE → 设为 DONE
    → 回调未被调用时:
      → phase 保持 PENDING
  → buildContext 返回后:
    → _ragPhases 全部非 DONE 的标记为 DONE  ← F-4

ChatScreen (Composable)
  → val ragPhases by chatViewModel.ragPhases.collectAsState()
  → RagProgressCard(phases = ragPhases, references = ..., isComplete = ...)
    → PhasePipeline(phases)
      → 每个 phase 根据 status 渲染:
        - ACTIVE: 蓝色脉冲点 + 加粗文字
        - DONE: 绿色对勾 + 普通文字
        - PENDING: 灰色圆点 + 弱化文字
      → 完成摘要: "✓ N stages completed"
```

### 4.2 F-4 详细分析：假完成的时间线

| 时间 | 事件 | Phase 状态 |
|------|------|-----------|
| T+0ms | `generateMessage()` 初始化 | 8 × PENDING |
| T+10ms | `buildContext()` → `performRagRetrieval()` | ragProvider != null |
| T+10ms | `MemoryManagerRagAdapter.retrieveContext()` | |
| T+10ms | `MemoryManager.retrieveContext()` | |
| T+10ms | `onProgress("Embedding query", 10)` | embed → ACTIVE |
| T+200ms | embedding 完成 | |
| T+200ms | `onProgress("Searching memory", 30)` | embed → DONE, memory → ACTIVE |
| T+300ms | memory 搜索完成 | |
| T+300ms | `canSearchDocs = false` (F-1) → **跳过文档搜索** | |
| T+300ms | `onProgress("Hybrid fusion", 70)` | memory → DONE, hybrid → ACTIVE |
| T+400ms | 融合完成 | |
| T+400ms | `onProgress("Ranking results", 90)` | hybrid → DONE, rank → ACTIVE |
| T+400ms | rerank 可能为空（无 rerankClient 或未开启）| |
| T+500ms | 返回结果 | |
| T+500ms | `buildContext()` 返回 | |
| T+500ms | `_ragPhases` 全部标记 DONE | **docs/rerank/kg/ready 从 PENDING 直接变成 DONE** |

**用户看到**: 8 个绿色对勾在 ~500ms 内全部亮起 → **不可能这么快完成了所有步骤**

### 4.3 RagProgressCard 显示条件审计

**位置**: `ChatInlineComponents.kt:374-376`

```kotlin
val anyActive = phases.any { it.status == PhaseStatus.ACTIVE }
if (anyActive == false && isComplete == false && hasReferences == false) return
```

**问题**: 
- 如果 `isComplete = false` 且 `anyActive = false`（所有 phase 都是 PENDING → 被 F-4 标记为 DONE），但 `hasReferences = true`（有检索结果），卡片会显示
- 如果没有检索结果，卡片不显示 → 用户完全看不到 RAG 检索过程

### 4.4 ChatScreen 中 RagProgressCard 的触发条件

**位置**: `ChatScreen.kt:321-329`

```kotlin
if (lastAssistantMsg != null && (ragPhases.isNotEmpty() || !lastAssistantMsg.ragReferences.isNullOrEmpty())) {
    val ragLoading = isGeneratingGroup && ragPhases.any { it.status == PhaseStatus.ACTIVE }
    val ragComplete = ragPhases.isNotEmpty() && ragPhases.all { it.status == PhaseStatus.DONE }
    RagProgressCard(
        phases = ragPhases,
        references = lastAssistantMsg.ragReferences,
        kgPaths = lastAssistantMsg.kgPaths,
        isComplete = ragComplete || !ragLoading
    )
}
```

**问题**: `ragComplete` 检查 `all { it.status == PhaseStatus.DONE }`，这在 F-4 的影响下永远为 true（因为 PENDING 被批量标记为 DONE）。

---

## 五、知识图谱页面审计

### 5.1 KnowledgeGraphScreen 数据加载

**位置**: `KnowledgeGraphViewModel.kt:78-80`

```kotlin
fun loadGraph() {
    loadGraphInternal(null)
}
```

`loadGraphInternal(null)` 调用 `graphStore.getGraphData(docIds = null)`:

```kotlin
// GraphStore.kt:163
suspend fun getGraphData(docIds: List<String>? = null, ...): GraphData {
    val edges = when {
        docIds != null && docIds.isNotEmpty() -> kgEdgeDao.getByDocIds(docIds)
        sessionId != null -> kgEdgeDao.getBySessionId(sessionId)
        else -> kgEdgeDao.getAllDocEdges()  // ← 全局模式：获取所有有 doc_id 的边
    }
    ...
}
```

**全局模式** 查询 `kgEdgeDao.getAllDocEdges()`:
```sql
SELECT * FROM kg_edges WHERE doc_id IS NOT NULL
```

**问题**: 如果 KG 抽取从未成功执行（F-2），或者 `GraphExtractor.extractAndSave()` 虽然执行但 LLM 返回空结果，则 `kg_edges` 表为空 → 图谱页为空 → **这是预期行为，不是 Bug**

**真正的 Bug 是**: 用户点击"提取知识图谱"后，由于 F-2 的存在，抽取可能根本没执行，但 UI 没有提示失败。

### 5.2 ECharts 渲染链路

```
KnowledgeGraphViewModel.renderFromCache()
  → buildGraphJson(filteredData)  // 构建 JSON
  → 读取 assets/kg_template.html
  → 替换 __GRAPH_DATA__ 占位符
  → _graphHtml.value = html
  
KnowledgeGraphScreen
  → if (graphHtml != null) → AndroidView(WebView) → loadDataWithBaseURL(html)
  → else if (isLoading) → CircularProgressIndicator
  → else → Text("图谱为空")
```

**渲染链路本身无 Bug**，空图谱是因为数据不存在（根因在 F-2）。

---

## 六、问题优先级与修复建议

### P0 致命（功能完全不可用）

| # | 问题 | 修复建议 | 影响文件 |
|---|------|---------|---------|
| F-1 | `RagOptions.enableDocs` 默认 `false` | 改为 `true`；或在文档导入成功后自动开启 | `ChatModels.kt` |
| F-2 | `extractKG()` 走 VectorizationQueue 完整管线 | 改为直接调用 `graphExtractor.extractAndSave()`，独立于向量化 | `RagViewModel.kt` |

### P1 重要（部分场景失效 / 用户误导）

| # | 问题 | 修复建议 | 影响文件 |
|---|------|---------|---------|
| F-3 | `NewVectorRecord` 缺少 `docId` | 补充 `docId = docId` | `VectorizationQueue.kt` |
| F-4 | 指示器将跳过步骤标记为 DONE | 新增 `PhaseStatus.SKIPPED`，区分"执行完成"和"从未执行" | `ChatModels.kt`, `ChatViewModel.kt`, `ChatInlineComponents.kt`, `ChatScreen.kt` |

### P2 一般（体验问题）

| # | 问题 | 修复建议 | 影响文件 |
|---|------|---------|---------|
| F-5 | `extractKG()` 异常被 `catch (_: Exception) { }` 吞掉 | 捕获异常后设置 `KgStatus.FAILED` | `RagViewModel.kt` |
| F-6 | `importDocuments()` 不传 `kgStrategy` | 根据用户配置决定是否自动触发 KG | `RagViewModel.kt` |

---

## 七、修复方案设计

### F-1 修复方案

**方案 A（推荐）**: 修改 `RagOptions.enableDocs` 默认值为 `true`。理由：用户导入文档的意图就是检索，不应要求手动开启。

**方案 B**: 在 `RagViewModel.importDocuments()` 成功后，自动调用 `updateConfig { it.copy(enableDocs = true) }` 并同步到当前会话。但此方案仅影响知识库页面，不影响已有会话。

### F-2 修复方案

**推荐**: 将 `extractKG()` 改为直接调用 `app.graphExtractor.extractAndSave(content, uuid)`，不走 `VectorizationQueue`。理由：
- KG 抽取与向量化是完全独立的操作（一个调用 LLM 提取实体，一个调用 Embedding 生成向量）
- 用户触发 KG 抽取时不应重新走一遍向量化流程
- 直接调用可以精确反馈成功/失败/空结果

### F-3 修复方案

补充 `docId = docId` 到 `NewVectorRecord` 构造参数。

### F-4 修复方案

1. `PhaseStatus` 新增 `SKIPPED` 枚举值
2. `ChatViewModel.generateMessage()` 中将未执行 phase 标记为 `SKIPPED`
3. `PhasePipeline` 组件渲染 `SKIPPED` 状态（灰色 + 横线图标）
4. 完成摘要行显示 "N done · M skipped"

### F-5 修复方案

`catch` 块中设置 `_kgExtractionStates.value = ... + (uuid to KgStatus.FAILED)`，并添加 `NexaraLogger` 日志。

### F-6 修复方案（可选）

根据 `RagConfiguration.enableKnowledgeGraph` 配置，在 `importDocuments()` 中有条件传递 `kgStrategy = "full"`。

---

## 八、端到端验证清单

修复后应在真机上执行以下验证：

- [ ] **E2E-1**: 全新安装 → 配置提供商 → 同步模型 → 选择嵌入模型 → 导入文档 → 向量化成功 → 进入聊天 → 发送文档相关关键词 → LLM 回答包含文档内容 → 指示器显示正确的阶段进度
- [ ] **E2E-2**: 知识库页面 → 右键文件 → 提取知识图谱 → 等待完成 → 进入图谱页面 → 图谱不为空 → 有节点和边
- [ ] **E2E-3**: 知识库页面 → 右键文件 → 提取知识图谱（故意配置错误的 LLM） → UI 应显示 FAILED 状态 → 图谱页面应提示错误
- [ ] **E2E-4**: 聊天界面 → 关闭文档检索开关 → 发消息 → 指示器应显示文档相关阶段为 SKIPPED
- [ ] **E2E-5**: 聊天界面 → 开启文档检索 → 指定 activeDocIds → 发消息 → 仅搜索指定文档

---

*审计结束。所有发现的问题均未修改，等待确认后进入修复阶段。*

---

## 九、交叉核验补充 (2026-05-17 14:01)

IDEA 会话（同为 GLM-5.1）已完成了一轮修复，共变更 38 个文件。交叉核验结论：

### 已被 IDEA 修复的问题
- **F-1 部分**: IDEA 没有改 `enableDocs` 默认值，而是修复了 MemoryManager 的 `canSearchDocs` 逻辑 + ragOptions 传递断裂 + 设置面板互覆盖，方案更优
- **F-4 部分**: 通过使检索真正执行，间接解决了假完成

### 仍未修复的问题（需后续处理）
- **F-2 (P0)**: `extractKG()` 仍走 VectorizationQueue，依赖向量化成功
- **F-3 (P1)**: `NewVectorRecord` 仍缺少 `docId`
- **F-5 (P2)**: `catch (_: Exception) { }` 仍静默吞错
- **F-6 (P2)**: `importDocuments()` 仍不传 `kgStrategy`

### IDEA 发现但我遗漏的 8 项问题
详见 `docs/audit/IDEA_CROSS_VERIFICATION_20260517.md` 第三节
