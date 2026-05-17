# RAG 记忆存储链路缺口审计报告

> **审计日期**: 2026-05-17 14:37  
> **审计方**: CodeBuddy (GLM-5.1)  
> **基准审计**: `RAG_KG_FULL_PIPELINE_AUDIT_20260517.md` + `IDEA_CROSS_VERIFICATION_20260517.md`  
> **触发原因**: 多轮审计修复后真机仍无效果 — RAG 检索 0 结果，KG 0 节点，8 步指示器 1s 假完成

---

## 一、审计结论摘要

| # | 严重度 | 问题描述 | 根因位置 | 当前状态 |
|---|--------|---------|---------|---------|
| G-1 | **P0 致命** | `addTurnToMemory()` 从未被调用 | `ChatViewModel.kt` → `MemoryManager.kt` | ✅ 已修复 |
| G-2 | **P0 致命** | 8 步指示器批量假完成 | `ChatViewModel.kt:403` | ✅ 已修复 |
| G-3 | **P0 致命** | 检索日志缺乏状态诊断信息 | `MemoryManager.kt` / `VectorStore.kt` | ✅ 已修复 |
| G-4 | **P1 重要** | PostProcessor 归档日志不完整 | `PostProcessor.kt` | ✅ 已修复 |

---

## 二、G-1: `addTurnToMemory()` 从未被调用 (P0 致命)

### 2.1 问题发现

搜索全仓库 `addTurnToMemory` 调用:

```
native-ui/app/src/main/java/com/promenar/nexara/data/rag/MemoryManager.kt:241: 定义
native-ui/app/src/main/java/com/promenar/nexara/data/rag/MemoryManagerRagAdapter.kt:26: retrieveContext (无关)
```

**只有一处定义，零调用点。**

### 2.2 影响链路

```
用户发消息
  → ChatViewModel.generateMessage()
    → ContextBuilder.buildContext()
      → MemoryManager.retrieveContext()
        → memory search: 0 results ⚠️
    → 流式生成回复
    → generateMessage 完成
    → PostProcessor.updateStats() ✓
    → if (messages.size > windowSize) → archiveMessagesToRag()  ← 仅溢出时触发
    → 🔴 addTurnToMemory() 从未被调用
```

结果：
- 用户问了 5 轮 "何瑞斯是谁" → 每轮都应该存储为记忆向量
- 但 `addTurnToMemory()` 从未执行 → vectors 表该 session 的行数为 0
- 第 6 轮再次问 → `getBySessionIdAndType(sessionId, "memory")` → 0 rows → 永远搜不到

### 2.3 与已有归档路径的关系

`archiveMessagesToRag()` 是**溢出归档**路径（消息数超过 activeContextWindow，默认 10），它只处理"被移出窗口"的消息，而非存储正常对话。

两个路径的关系:
- `addTurnToMemory()`: **每轮存储** — 正常对话直接入向量库
- `archiveMessagesToRag()`: **溢出归档** — 上下文窗口不够用时，将溢出消息批量向量化

两者互补，缺一不可。

### 2.4 修复

在 `ChatViewModel.generateMessage()` 的后处理阶段（`postProcessor.updateStats()` 之后，溢出归档之前），新增调用:

```kotlin
if (sessionRagOpts?.enableMemory == true
    && effectiveUserContent.isNotBlank()
    && accumulatedContent.isNotBlank()
    && memoryManager != null
) {
    memoryManager.addTurnToMemory(
        sessionId, effectiveUserContent, accumulatedContent,
        userMsgId, assistantMsgId
    )
}
```

### 2.5 真机验证预期

修复后首次发送消息:
```
[ChatViewModel] addTurnToMemory success: session=session_xxx, time=XXXms
[MemoryManager] addTurnToMemory: chunking=N chunks, session=session_xxx
[MemoryManager] addTurnToMemory: embedding done, dim=1024, chunks=N
[MemoryManager] addTurnToMemory done: N vectors stored, session=session_xxx, total=XXXms
```

第二次发送消息（检索时）:
```
[MemoryManager] vectors DB state: total=N, sessionVecCount=M  (M > 0!)
[MemoryManager] memory search: K results (K > 0!)
```

---

## 三、G-2: 8 步指示器批量假完成 (P0 致命)

### 3.1 问题代码

`ChatViewModel.kt:403` (修复前):
```kotlin
_ragPhases.update { phases ->
    phases.map { p -> if (p.status != PhaseStatus.DONE) p.copy(status = PhaseStatus.DONE) else p }
}
```

### 3.2 问题分析

- `buildContext()` 返回后，无论每个阶段是否真正执行，所有非 DONE 阶段被批量标记为 DONE
- 用户看到: 8 个绿色对勾在 ~500ms 内全部亮起 → 物理上不可能完成所有步骤

### 3.3 修复

```kotlin
_ragPhases.update { phases ->
    val executedPhaseIds = phases.filter {
        it.status == PhaseStatus.ACTIVE || it.status == PhaseStatus.DONE
    }.map { it.id }.toSet()
    phases.map { p ->
        when {
            p.id in executedPhaseIds && p.status != PhaseStatus.DONE ->
                p.copy(status = PhaseStatus.DONE)
            else -> p
        }
    }
}
```

- 仅将 ACTIVE（正在执行）的阶段标记为 DONE
- PENDING（从未执行）的阶段保持原状
- 用户可以看到哪些阶段确实执行了，哪些被跳过

---

## 四、G-3: 日志缺乏状态诊断信息 (P0 致命)

### 4.1 修复前日志

```
[MemoryManager] memory search: 0 results, time=10ms
```

无法回答"为什么是 0":
- vectors 表总共多少行？
- 该 session 下有多少行？
- 阈值是多少？有没有结果被阈值截断？
- 存储向量和查询向量维度是否一致？

### 4.2 新增诊断日志

**检索前 — vectors 表状态快照**:
```
[MemoryManager] vectors DB state: total=42, sessionVecCount=0, memoryThreshold=0.7, docThreshold=0.45, rerankTopK=30
```

**embedding 后 — 维度诊断**:
```
[MemoryManager] embedQuery success: dim=1024, time=809ms, storedDim=1536  ← 维度不匹配告警!
```

**searchInMemory — 过滤统计**:
```
[VectorStore] searchInMemory: loaded=15 rows, queryDim=1024, storedDim=1536, threshold=0.7, dimMismatch=15, belowThreshold=0, candidates=0 ⚠️ DIM_MISMATCH — stored v1536 vs query v1024 — 模型切换? 需要重新向量化!
```

### 4.3 新增 VectorStore 诊断方法

```kotlin
fun getTotalVectorCount(): Int       // vectors 表总行数
fun getSessionVectorCount(sid): Int   // 指定 session 行数
fun getFirstStoredDimension(): Int?   // 首条存储向量维度
```

---

## 五、G-4: PostProcessor 归档日志不完整 (P1)

### 5.1 修复前

```kotlin
if (client == null || store == null || splitter == null) {
    Log.w(TAG, "Embedding pipeline not configured, skipping archive")
    return
}
```

无法知道**哪个**组件缺失。

### 5.2 修复后

```kotlin
val missing = listOfNotNull(
    if (client == null) "EmbeddingClient" else null,
    if (store == null) "VectorStore" else null,
    if (splitter == null) "TextSplitter" else null
).joinToString(", ")
Log.w(TAG, "archiveMessagesToRag skipped: $missing not configured — session=$sessionId msgs=${messages.size}")
```

---

## 六、变更文件清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `ChatViewModel.kt` | 新增 15 行 | addTurnToMemory 调用 + _ragPhases 假完成修复 |
| `MemoryManager.kt` | 新增 30 行 | 检索前状态快照日志 + embedding 后维度诊断 + addTurnToMemory 全流程日志 |
| `VectorStore.kt` | 新增 40 行 | 3 个诊断方法 + searchInMemory 详细过滤统计日志 |
| `PostProcessor.kt` | 新增 8 行 | 跳过原因/维度/耗时/成功失败日志 |
| `CHANGELOG.md` | 新增 12 行 | 变更记录 |
| `.agent/handover.md` | 更新 | 交接信息 |

---

## 七、真机验证清单

修复后应在真机上执行以下验证:

- [ ] **V-1**: 全新安装 → 配置提供商 → 发送第一条消息 → logcat 确认出现 `addTurnToMemory success`
- [ ] **V-2**: 发送第二条消息（相同话题）→ logcat 确认 `vectors DB state: total>0, sessionVecCount>0`
- [ ] **V-3**: 确认 `memory search: N results` (N > 0) — 之前的对话内容被检回
- [ ] **V-4**: 确认 8 步指示器不再秒完成 — PENDING 阶段保持灰色而非绿色对勾
- [ ] **V-5**: 询问 "何瑞斯是谁" 等之前聊过的内容 → LLM 应答引用之前对话

---

*审计结束。G-1 是本次 RAG 失效的根本原因，此前多轮修复均未发现此缺口。*
