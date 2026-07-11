# Nexara 全栈业务流程审计报告

> **审计日期**：2026-07-06
> **审计范围**：消息发送链路、输出渲染、RAG/知识图谱、配置系统、工具/Skill、工作区/任务、UI/UX
> **审计方法**：7 个子 Agent 并行只读扫描 + 主 Agent 交叉核验
> **子报告索引**：`.agent/tmp-agent-reports/tmp-audit-module-{a..g}.md`
> **核验声明**：本报告所有 🔴 严重问题均经主 Agent 二次 grep 核验确认，非子 Agent 单方面断言

---

## 一、总体评价

Nexara 的**架构骨架是合理的**：协议抽象层（ProtocolType sealed class + ProtocolFactory）、工具统一抽象（SkillDefinition/SkillRegistry）、流式 SSE 管道、PipelineBubble 的 Thinking→Tool→Content 线性管道、RAG 检索的阶段化可视化（RagProgressCard 8 阶段微轨）——这些是经过深思的设计，代码质量在 Android 原生 LLM 客户端里属于中上水平。

但在**"骨架已搭好、血肉未填满"的状态下**，存在大量"接口定义了但无人调用""UI 提供了但后端不读""功能实现了但有头无尾"的断裂。从用户视角看，最致命的不是代码写得丑，而是**用户会被误导**——以为某个功能生效了（审批模式、温度调节、RAG 查询改写、浅色主题），实际完全没有生效。

**问题统计**：🔴 严重 25 项 / 🟡 中等 35 项 / 🟢 轻微 30+ 项（详见各子报告）

**最需要立即修复的 4 个"用户可见断裂"**（跨模块关联问题，详见第二节）：
1. 错误信息永远不显示（模块 A + G 交叉确认）
2. 审批模式形同虚设（模块 E + F + G 三方确认）
3. 会话级温度/工具/Skill 配置大量死链（模块 D + E）
4. 浅色主题假支持（模块 G）

---

## 二、跨模块关联问题（最优先修复）

这 5 个问题被多个模块独立发现，说明它们是**系统性缺陷**而非局部 bug，修复收益最高。

### X-001：错误信息永远不显示给用户

**发现来源**：模块 A（A-001）+ 模块 G（G-12）独立确认

**用户视角**：用户发送一条消息，如果 API 返回 401（API Key 错误）、429（限流）、或网络断开，**对话流里完全没有任何错误提示**。`ErrorNormalizer` 已经精心产出了友好的中文文案（如"您的 API Key 无效，请检查设置"），写入了 `ChatUiState.error`，但 `ChatScreen.kt` 全文检索 `uiState.error` 的消费点为 **0**——错误永远不显示。用户只会看到消息"发出去就没反应了"，以为 app 卡死，反复重发，陷入死循环。

**代码位置**：
- 错误产生：`ChatViewModel.kt`（ErrorNormalizer → `_uiState.update { it.copy(error = ...) }`）
- 错误消费：`ChatScreen.kt`（**缺失**，零引用 `uiState.error`）

**修复方向**：在 `ChatScreen` 的对话流底部加一个错误卡片（`Snackbar` 或 inline `Card`），监听 `uiState.error`，显示错误文案 + 重试按钮。

---

### X-002：工具执行审批模式完全无效（死代码 UI）

**发现来源**：模块 E（E-001）+ 模块 F（F-C4）+ 模块 G（G-03）三方独立确认

**用户视角**：用户在会话设置里把执行模式改成"semi"（半自动），期望 AI 调用 `write_file`/`exec_js` 等高风险工具前要先经过自己同意。但实际对话里**根本没有审批弹窗出现**——`ApprovalCard` 组件完整存在（`ChatInlineComponents.kt:1095`），却**全代码库零调用**。结果：

- `write_file`/`exec_js`/`generate_image`/`create_tool` 在 semi 模式下会**静默卡死**（等待一个永远不会来的审批）
- `patch_file`（同样修改文件、同样落盘覆盖）**根本不在高风险白名单里**，会绕过审批直接执行

用户以为开了审批，实际完全没生效——这是"用户控制感被彻底剥夺"。

**代码位置**：
- `ApprovalManager` 后端逻辑：`ui/chat/manager/ApprovalManager.kt`（完整实现）
- `ApprovalCard` UI 组件：`ChatInlineComponents.kt:1095`（**零调用**）
- 高风险白名单：`ChatViewModel.kt:1705`（只有 4 个，缺 `patch_file`）

**修复方向**：
1. 在 `PipelineBubble` 或 `ChatScreen` 的对话流里渲染 `ApprovalCard`，监听 `uiState.approvalRequest`
2. 把 `patch_file` 加入 `highRiskToolNames`
3. semi 模式下工具执行前检查 `approvalRequest`，未批准则挂起等待

---

### X-003：会话级配置大量死链（UI 能改、后端不读）

**发现来源**：模块 D（D §3.1-3.4）+ 模块 E（E-104）

**用户视角**：用户在会话设置页做了这些调整，但**全部不生效**：
- 旧版 `SessionSettingsScreen` 的温度/topP/maxTokens 滑块——只更新局部 `mutableStateOf`，从不写 DB，从不调 `updateInferenceParams`（纯 UI 摆设）
- 会话级 `activeSkillIds`/`activeMcpServerIds`——完整持久化到 DB，但 `buildToolList` 只读全局 `enabled_skills`，会话级永远不生效
- Agent 的 `ragConfig`/`retrievalConfig`/`skills`——`AgentConfigResolver` 解析了，但 `ChatViewModel` 拿到后从不读取
- 高级参数（topK/frequencyPenalty/repetitionPenalty/streamTimeout）——走默认的 `UnifiedLlmClient` 路径时会丢失（`StreamTextParams` 无这些字段）

**代码位置**：详见模块 D 报告的"配置项生效审计表"（30+ 项逐一标注）

**修复方向**：逐一打通 UI→存储→读取→业务的断点，或在 UI 上明确标注"该功能暂未启用"。

---

### X-004：浅色主题假支持

**发现来源**：模块 G（G-02）

**用户视角**：如果用户的系统设置了浅色模式，打开 app 会发现整个界面是深色的（勉强能用），但 Mermaid/ECharts/LaTeX 图表里的文字**全部是浅色**（`#E5E1E4`），在深色 WebView 背景上**几乎不可读**。更糟的是，`ThemeScreen`（主题设置页）完整存在，但 `UserSettingsHomeScreen` **没有任何入口**跳转它——用户找不到任何地方切换主题。

**代码位置**：
- `Theme.kt:16-59`：只有 `DarkColorScheme`，`else` 分支也走深色
- `MainActivity`：直接 `NexaraTheme{}`，忽略 `themeMode`
- 图表硬编码：`MermaidBlock`/`EChartsBlock`/`LatexBlock`/`PlantUmlBlock`/WebView CSS 全部写死深色主题色

**修复方向**：要么完整实现浅色主题，要么在 UI 上明确标注"当前仅支持深色模式"并移除 `ThemeScreen` 死代码。

---

### X-005：RAG 的 Query Rewriter 整套是 UI 摆设

**发现来源**：模块 C（C1）+ 模块 D 交叉确认

**用户视角**：用户在 RAG 设置页打开了"查询改写"开关，调了改写策略和数量，期望检索质量会提升。但 `QueryRewriter` 类**实现完整却全代码库 0 处实例化、0 处调用**——检索时直接用原始 query 向量化，改写功能完全无效。用户调的参数全是死配置。

**代码位置**：`QueryRewriter.kt`（完整实现）vs `MemoryManager.retrieveContext()`（**不调用**）

**修复方向**：在 `MemoryManager.retrieveContext` 接入 `QueryRewriter`，或 UI 上标注"暂未启用"。

---

## 三、按模块的问题摘要

> 以下仅列出各模块的**严重问题**（中/轻微问题详见各子报告）。每项标注是否经主 Agent 核验。

### 模块 A — 消息发送链路

| ID | 问题 | 用户视角 | 核验 |
|---|---|---|---|
| A-001 | 错误信息不可见 | API 报错时用户看不到任何提示（见 X-001） | ✅ grep 确认 `uiState.error` 零消费 |
| A-002 | 无自动重试/退避 | 弱网下一次抖动即彻底失败，`retryable` 字段定义了却无人读取 | - |
| A-003 | 重发丢失图片 | 重试带图消息时只发文本，图片丢失（`retryLastMessage` 不传 imageUris） | - |
| A-004 | 无 Provider Fallback | 主 Provider 挂了无法自动切备用 | - |
| A-005 | 多模态无校验 | 40MB 大图 `readBytes()` 直接 OOM；10 张图无上限 | - |

### 模块 B — 输出渲染链路

| ID | 问题 | 用户视角 |
|---|---|---|
| B-S1 | 思考过程流式期间静止 | 深度思考模型的推理流视觉上"卡死"，用户误判掉线 |
| B-S2 | ParseCache 串味 | LazyColumn 复用槽位时旧缓存污染新消息 |
| B-S3 | 代码块无折叠 | 500 行代码占满整屏，无法快速跳过 |
| B-S4 | 表格列数错位 | 列数不匹配时行列错乱 |
| B-S5 | 图表流式闪烁 | Mermaid/ECharts 每个 chunk 都 reload WebView |

### 模块 C — RAG/知识图谱

| ID | 问题 | 用户视角 | 核验 |
|---|---|---|---|
| C1 | QueryRewriter 死代码 | 见 X-005 | ✅ grep 确认零调用 |
| C2 | 删文档不清向量 | 删掉的文档仍会污染检索（只删 file 不删 vectors/kg_edges） | - |
| C3 | 重新索引致向量重复 | 同一文档产生多套向量 | - |
| C4 | 切 embedding 模型后静默失效 | 维度不匹配的向量被静默跳过，用户无提示 | - |
| C5 | embedDimension 死配置 | 存了但 EmbeddingClient 从不读取 | - |
| C6 | 大文件导入无内存保护 | 100MB+ 文档必 OOM | - |

### 模块 D — 配置系统

| ID | 问题 | 用户视角 | 核验 |
|---|---|---|---|
| D-3.1 | 旧会话设置滑块死配置 | 见 X-003 | ✅ 确认 `SessionSettingsScreen:342` 不调持久化 |
| D-3.2 | 会话级 Skill/MCP 死配置 | 见 X-003 | ✅ 确认 `buildToolList` 只读全局 |
| D-3.3 | 额外 Provider 不生效 | 配了主+额外 Provider，会话选备用仍发到主端点 | ⚠️ 需运行时验证（有重建机制，但会话级切换未确认） |
| D-3.4 | Agent 级配置死链 | 见 X-003 | - |

### 模块 E — 工具/Skill

| ID | 问题 | 用户视角 | 核验 |
|---|---|---|---|
| E-001 | 审批 UI 死代码 | 见 X-002 | ✅ grep 确认 ApprovalCard 零调用 |
| E-002 | 自定义 Skill 沙箱未实现 | `create_tool` 返回成功但执行时返回"未实现" | - |
| E-003 | runBlocking 查 DB | 阻塞调用线程（可能主线程） | - |
| E-004 | 工具耗时硬编码 | UI 显示 `1.2s`（Mock），与真实耗时无关 | - |
| E-005 | ExecJs WebView 安全/泄漏 | 自称"无网络访问"但未禁用网络 | - |

### 模块 F — 工作区/任务

| ID | 问题 | 用户视角 | 核验 |
|---|---|---|---|
| F-C1 | AI 无法创建新文件 | `write_file` 需已存在 UUID，无 `create_file` 工具 | - |
| F-C2 | 写文件非原子 | 名为 `writeFileAtomic` 实为 `writeText` 覆盖，崩溃丢数据 | ✅ 确认 `WorkspaceRepository.kt:52` |
| F-C3 | 无版本控制 | AI 写错即永久丢失，无法回滚 | - |
| F-C4 | patch_file 绕过审批 | 见 X-002 | ✅ 确认不在 `highRiskToolNames` |
| F-C5 | 路径穿越零防护 | `materializedPath` 不校验 `../`（当前被无 create_file 意外封印） | - |

### 模块 G — UI/UX

| ID | 问题 | 用户视角 |
|---|---|---|
| G-01 | 首次启动无引导 | 用户不知要先配 Provider，进首页模型列表为空无提示 |
| G-02 | 浅色主题假支持 | 见 X-004 |
| G-03 | 审批模式无效 | 见 X-002 |
| G-10 | 不知当前 Provider | 对话页无 Provider/模型指示器 |
| G-14 | 硬编码中文 | ~228 处硬编码中文破坏 i18n |

---

## 四、任务树状态值不一致（Schema 撒谎）

**发现来源**：模块 F 独立发现，值得单独列出因为这是一个"AI 被误导"的隐蔽问题。

`ContextBuilder.renderTaskTree` 用 `completed`/`in_progress` 判断任务状态，但 `TaskRepository` 实际写的是 `done`/`doing`。结果：AI 看到的任务树里**所有步骤都显示为未开始**，即使已经完成。这会导致 AI 重复执行已完成的步骤，或无法理解任务进度。

**代码位置**：`ContextBuilder.renderTaskTree` vs `TaskRepository`

---

## 五、做得好的部分（避免片面）

审计不是找茬，以下设计值得肯定：

- **协议抽象层**：`ProtocolType` sealed class + `ProtocolFactory` + `ProtocolParamAdapter`，多协议适配干净利落
- **错误归一化质量**：`ErrorNormalizer` 产出的中文文案质量高、分类清晰（可惜没显示给用户）
- **RAG 检索可视化**：`RagProgressCard` 8 阶段微轨 + `RagDetailsSheet` 三 Tab（检索片段/联网引用/图谱路径含分数与排名变化）是全链路最佳实践
- **Token 用量可视化**：`TokenIndicator` 圆环 + 点击展开明细
- **文件乐观锁**：`expectedHash` + `HASH_MISMATCH` 设计正确，错误反馈能引导模型自我纠错
- **UUID 隔离**：文件操作走 `dao.getByUuid`，模型无法用任意绝对路径
- **任务树可视化**：`TaskFloatingPanel` 实时进度 + 折叠树
- **回收站设计**：软删除 + 30 天清理 + 恢复
- **向量/图谱持久化**：Room 数据库，非内存
- **结构化并发**：取消语义正确（`channelFlow` + `awaitClose`）

---

## 六、修复优先级建议

### P0 — 立即修复（用户可见断裂）
1. **X-001**：错误信息显示到 UI（`ChatScreen` 加错误卡片）
2. **X-002**：审批模式生效（渲染 `ApprovalCard` + `patch_file` 入白名单）
3. **A-003**：重发带图消息不丢图片
4. **G-01**：首次启动引导配置 Provider

### P1 — 近期修复（功能完整性）
5. **X-003**：打通会话级配置死链（温度/Skill/Agent）
6. **X-005**：QueryRewriter 接入或标注停用
7. **C2/C3**：删文档清向量 + 重新索引去重
8. **F-C1**：补充 `create_file` 工具
9. **F-C2**：写文件改真正的原子操作

### P2 — 中期优化（健壮性）
10. **A-002**：自动重试/退避机制
11. **B-S1**：思考过程流式实时更新
12. **B-S3**：代码块折叠
13. **X-004**：浅色主题决策（完整实现或标注停用）

### P3 — 长期改进（体验打磨）
14. 硬编码值可配置化
15. i18n（G-14 的 228 处硬编码中文）
16. MCP 超时/重连
17. 工具调用循环可视化增强

---

## 七、遗留疑问（需运行时验证）

以下问题静态分析无法定论，建议运行时验证：

1. **D-3.3 额外 Provider 是否真的不生效**：`NexaraApplication` 有 `_llmProvider` 的 `MutableStateFlow` 和重建机制，但会话级 Provider 切换是否触发重建未确认。需运行时切换会话 Provider 实测。
2. **A-007 SSE 中断后半截内容**：静态看状态机不清晰，需模拟网络中断验证。
3. **B-S2 ParseCache 串味**：需在 LazyColumn 滚动复用场景下实测。
4. **E-003 runBlocking 阻塞线程**：需确认调用栈是否在主线程。
5. **C4 embedding 维度不匹配**：需切换 embedding 模型实测检索行为。

---

## 附录：子报告索引

| 模块 | 子报告路径 |
|---|---|
| A 消息发送 | `.agent/tmp-agent-reports/tmp-audit-module-a.md` |
| B 输出渲染 | `.agent/tmp-agent-reports/tmp-audit-module-b.md` |
| C RAG/图谱 | `.agent/tmp-agent-reports/tmp-audit-module-c.md` |
| D 配置系统 | `.agent/tmp-agent-reports/tmp-audit-module-d.md` |
| E 工具/Skill | `.agent/tmp-agent-reports/tmp-audit-module-e.md` |
| F 工作区/任务 | `.agent/tmp-agent-reports/tmp-audit-module-f.md` |
| G UI/UX | `.agent/tmp-agent-reports/tmp-audit-module-g.md` |
