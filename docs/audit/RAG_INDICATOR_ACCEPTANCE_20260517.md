# RAG 指示器多会话并行执行 — 验收报告

> **验收日期**: 2026-05-17 10:00  
> **审计范围**: Session A-E2 全部产出 + 全链路集成验证  
> **验收方法**: 逐文件代码审查 + 数据流端点追踪 + 零 lint 验证

---

## 一、产出清单

| 会话 | 阶段 | 状态 | 产出文件 |
|------|------|------|----------|
| A | RagOmniIndicator 连线 | ✅ 通过 | `ChatScreen.kt`, `ContextBuilder.kt` |
| B | RagProgressCard 管道改造 | ✅ 通过 | `ChatInlineComponents.kt`, `ChatModels.kt`, `ChatViewModel.kt`, `MemoryManager.kt` |
| C | PostProcessBar 状态栏 | ✅ 通过 | `ChatInlineComponents.kt`, `ChatModels.kt`, `ChatViewModel.kt` |
| D | 手动压缩 + SummaryCard | ✅ 通过 | `ChatViewModel.kt`, `SessionSettingsSheet.kt`, `ChatInlineComponents.kt` |
| E1 | KG Detail Sheet 增强 | ✅ 通过 | `RagDetailsSheet.kt` |
| E2 | FilesPanel KG 图标 | ✅ 通过 | `IndexStatusBadge.kt`, `RagViewModel.kt`, `FilesPanel.kt`, `RagHomeScreen.kt` |
| — | 字符串资源 | ✅ 通过 | `values/strings.xml`, `values-zh-rCN/strings.xml` |

---

## 二、逐项验收

### 2.1 Session A: RagOmniIndicator → RagProgressCard 连线

| 检查项 | 状态 | 详情 |
|--------|------|------|
| ChatScreen 中渲染检索指示器 | ✅ | `ChatScreen.kt:321-330`，在 PipelineBubble **之前** 插入 RagProgressCard |
| 显示条件正确 | ✅ | `ragPhases.isNotEmpty() || !ragReferences.isNullOrEmpty()` |
| 加载态判断正确 | ✅ | `isGeneratingGroup && ragPhases.any { it.status == PhaseStatus.ACTIVE }` |
| 完成态判断正确 | ✅ | `all { it.status == PhaseStatus.DONE }` |
| KG 检索进度回调 | ✅ | `ContextBuilder.kt:67-69`，`onRagProgress("KG retrieval", 95)` → `("Context ready", 100)` |
| Rerank 独立进度 | ✅ | `MemoryManager.kt:150-159`，`onProgress("Reranking", 92)` 和 `"Rerank complete", 95` |

### 2.2 Session B: RagProgressCard 多阶段管道

| 检查项 | 状态 | 详情 |
|--------|------|------|
| RagPhase 数据模型 | ✅ | `ChatModels.kt:369-376`，字段: id, name, status, progress, detail, durationMs |
| PhaseStatus 枚举 | ✅ | `ChatModels.kt:365-367`，PENDING/ACTIVE/DONE |
| RagProgressCard 组件 | ✅ | `ChatInlineComponents.kt:361-469`，输入栏一致的 24dp 圆角 NexaraGlassCard |
| PhaseRow 子组件 | ✅ | `ChatInlineComponents.kt:472+`，ACTIVE 态脉冲动画，DONE 态对勾图标 |
| 阶段列表构建 | ✅ | `ChatViewModel.kt:322-387`，预填充 6 阶段 → onRagProgress 动态更新 |
| 错误兜底 | ✅ | 上下文构建失败时将所有 ACTIVE 阶段标记为 DONE |

### 2.3 Session C: PostProcessBar

| 检查项 | 状态 | 详情 |
|--------|------|------|
| PostProcessTask 模型 | ✅ | `ChatModels.kt:16-23`，id/type/status/progress/detail/createdAt |
| PostProcessType 枚举 | ✅ | `ChatModels.kt:5-8`，ARCHIVE_TO_RAG/AUTO_SUMMARY |
| PostProcessStatus 枚举 | ✅ | `ChatModels.kt:10-14`，RUNNING/DONE/ERROR |
| PostProcessBar 组件 | ✅ | `ChatInlineComponents.kt:760-780`，水平 Chip 药丸形 Row |
| PostProcessChip 子组件 | ✅ | `ChatInlineComponents.kt:782-855`，RUNNING 脉冲动画，DONE 3s 后自动淡出 |
| ChatViewModel 状态管理 | ✅ | `_postProcessTasks` StateFlow + add/update/remove 方法 |
| 记忆归档完成后更新 | ✅ | `ChatViewModel.kt:661-665`，DONE→delay 3s→remove |
| 自动摘要完成后更新 | ✅ | `ChatViewModel.kt:705-709`，DONE→delay 3s→remove |
| ChatScreen 集成 | ✅ | `ChatScreen.kt:422-426`，输入栏上方 PostProcessBar |

### 2.4 Session D: 手动压缩

| 检查项 | 状态 | 详情 |
|--------|------|------|
| compressContext() 方法 | ✅ | `ChatViewModel.kt:921-967`，带完整进度更新 |
| CompressionState 数据 | ✅ | `ChatViewModel.kt:153-158`，isCompressing/progress/detail/result |
| SummaryCard 组件 | ✅ | `ChatInlineComponents.kt:858+`，24dp 圆角 + 进度条 + 结果折叠展开 |
| SessionSettingsSheet 按钮 | ✅ | `SessionSettingsSheet.kt:898-899`，调用 compressContext() |
| ChatScreen SummaryCard 渲染 | ✅ | `ChatScreen.kt:347-356`，compressionState 驱动 |
| ChatInputTopBar 手动摘要 | ⚠️ | `ChatScreen.kt:419` → `summarizeHistory()`，**无声摘要无反馈** |
| SummaryManager onProgress | ✅ | `SummaryManager.kt:24`，新增 onProgress 参数，3 阶段回调 |

### 2.5 Session E1: KG Detail Sheet

| 检查项 | 状态 | 详情 |
|--------|------|------|
| Tab 切换 | ✅ | `RagDetailsSheet.kt:32-35`，"检索结果" / "知识图谱" 双 Tab |
| 检索结果 Tab | ✅ | 保持原有逻辑，展示引用列表 + 分数排名 |
| 知识图谱 Tab | ✅ | `KgPathsTab` → `KgPathSection`，节点卡片 + 边关系 |
| 节点卡片设计 | ✅ | `KgNodeCard`，Primary 色左侧竖线 accent bar |
| 边关系展示 | ✅ | `KgEdgeRow`，源节点 → 关系 Badge → 目标节点 |
| 空状态处理 | ✅ | 无 KG 数据时显示 EmptyStateText |

### 2.6 Session E2: FilesPanel KG 图标

| 检查项 | 状态 | 详情 |
|--------|------|------|
| KgStatus 枚举 | ✅ | `IndexStatusBadge.kt:115-120`，COMPLETED/IN_PROGRESS/FAILED/NOT_STARTED |
| KgStatusIcon 组件 | ✅ | `IndexStatusBadge.kt:123-171`，4 态图标 + IN_PROGRESS 脉冲 |
| RagViewModel 状态管理 | ✅ | `_kgExtractionStates` StateFlow + `_kgExtractingIds` |
| KG 状态追踪 | ✅ | `observeQueue()` 中追踪 extracting→completed/failed |
| extractKG 触发 | ✅ | `RagViewModel.kt:613-627`，kgStrategy="full" |
| RagHomeScreen 透传 | ✅ | `kgExtractionStates` → FilesPanel |
| FilesPanel 参数链 | ✅ | FilesPanel → FileTreeNode → FileRow 逐层透传 |
| FileRow 渲染 | ✅ | `FilesPanel.kt:438`，非目录文件右侧显示 KgStatusIcon |
| resolveKgStatus 逻辑 | ✅ | `FilesPanel.kt:550-554`，优先状态 Map，回退 file.kgExtractedAt |

---

## 三、数据流端点追踪

### Pre-LLM 检索 → 指示器

```
用户发送消息
  → ChatViewModel.generateMessage()
    → 构建 defaultPhases (6 阶段, 全 PENDING)
    → _ragPhases.update { defaultPhases }
    → ContextBuilder.buildContext()
      → onRagProgress 回调触达 _ragPhases 更新  ✅
      → MemoryManager.retrieveContext() onProgress  ✅
        → "Embedding query"(10) → "Searching memory"(30) → "Searching documents"(50)
        → "Hybrid fusion"(70) → "Reranking"(92) → "Rerank complete"(95)  ✅ 新增!
      → KG retrieval → "KG retrieval"(95) → "Context ready"(100)  ✅ 新增!
    → _ragPhases DONE  ✅
    → ragReferences 写入 Message  ✅
  → ChatScreen:
    → ragPhases.collectAsState() 驱动 RagProgressCard  ✅
    → references + kgPaths 透传  ✅
```

### Post-LLM 后处理 → PostProcessBar

```
流式生成完成
  → postProcessor.updateStats()  ✅
  → archiveMessagesToRag()
    → addPostProcessTask(ARCHIVE_TO_RAG, RUNNING)  ✅
    → updatePostProcessTask(...DONE) → delay 3s → remove  ✅
  → auto-summary (token 阈值触发)
    → addPostProcessTask(AUTO_SUMMARY, RUNNING)  ✅
    → updatePostProcessTask(...DONE) → delay 3s → remove  ✅
  → ChatScreen:
    → postProcessTasks.collectAsState() 驱动 PostProcessBar  ✅
```

### 手动压缩 → SummaryCard

```
SessionSettingsSheet "压缩" 按钮
  → chatViewModel.compressContext()
    → _compressionState → isCompressing=true  ✅
    → summaryManager.summarize(onProgress=...)  ✅
    → _compressionState → result=newSummary  ✅
  → ChatScreen:
    → compressionState.collectAsState() 驱动 SummaryCard  ✅
```

### KG 抽取 → FilesPanel 图标

```
用户点击文档"抽取 KG"
  → ragViewModel.extractKG(uuid)
    → _kgExtractingIds.add(uuid)
    → _kgExtractionStates[uuid] = IN_PROGRESS  ✅
    → vectorizationQueue.enqueueDocument(kgStrategy="full")
      → GraphExtractor.extractAndSave() (异步)
    → observeQueue(): completed → _kgExtractingIds.remove + COMPLETED  ✅
  → RagHomeScreen → FilesPanel:
    → kgExtractionStates 驱动 KgStatusIcon  ✅
```

---

## 四、发现的问题

### 🟡 P2-1: summarizeHistory() 无用户反馈

**位置**: `ChatViewModel.kt:889-919`  
**症状**: 用户点击 ChatInputTopBar 的摘要按钮后，摘要静默在后台生成，无任何 UI 反馈。  
**根因**: `summarizeHistory()` 不使用 `compressionState`，也不显示 SummaryCard。  
**建议**: 将 `summarizeHistory()` 改为调用 `compressContext()`，或至少添加 PostProcessTask 通知。

### 🟢 P3-1: PostProcessType 缺少 MANUAL_SUMMARY/KG_EXTRACTION

**位置**: `ChatModels.kt:5-8`  
**症状**: `PostProcessType` 只有 `ARCHIVE_TO_RAG` 和 `AUTO_SUMMARY`，设计文档中规划的 `MANUAL_SUMMARY` 和 `KG_EXTRACTION` 未实现。  
**影响**: 低。手动摘要通过 `CompressionState + SummaryCard` 展示（不同的 UI 路径），KG 抽取通过 FilesPanel 图标展示。两种类型的 PostProcessTask 暂无实际使用场景。

### 🟢 P3-2: RagOmniIndicator 未标记 @Deprecated

**位置**: `ChatInlineComponents.kt:197`  
**症状**: 旧的 `RagOmniIndicator` 组件仍存在但不再被 ChatScreen 调用。  
**影响**: 无运行时影响（死代码），但建议标记 `@Deprecated` 以便未来清理。

---

## 五、总体评分

| 维度 | 评分 | 说明 |
|------|------|------|
| **功能完整性** | 95/100 | 6 个阶段全部交付，核心流程闭环 |
| **视觉一致性** | 98/100 | 统一 24dp NexaraGlassCard + Primary→Tertiary 渐变 + 药丸 Chip，与输入栏高度一致 |
| **数据流正确性** | 100/100 | 4 条数据流端点全部追踪验证，无断链 |
| **编译质量** | 100/100 | 全部修改文件零 lint 错误 |
| **文档完整性** | 100/100 | 字符串资源双语齐全 |

**等级**: A （优秀，1 个 P2 建议优化 + 2 个 P3 非阻塞项）

---

*验收报告存档至 `docs/audit/RAG_INDICATOR_ACCEPTANCE_20260517.md`。*
