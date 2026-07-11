# Nexara APP 业务流程终版交叉审计报告

- 审计时间：2026-07-06T00:34:34+08:00
- 报告性质：GLM-5.2 并行审计报告 + Codex 原审计报告的终版交叉整合
- 结论用途：后续修复排期、交叉验证、回归测试与交付验收的主入口
- 审计边界：静态代码证据为主，未做真实设备、真实模型服务商、弱网、大文件和多 Provider 运行时压测

## 1. 输入材料

本报告已完整阅读并交叉比对以下材料：

- `docs/audit/20260706-fullstack-business-audit.md`：GLM-5.2 主整合报告。
- `.agent/tmp-agent-reports/tmp-audit-module-a.md`：消息发送链路。
- `.agent/tmp-agent-reports/tmp-audit-module-b.md`：输出渲染链路。
- `.agent/tmp-agent-reports/tmp-audit-module-c.md`：RAG / 知识图谱。
- `.agent/tmp-agent-reports/tmp-audit-module-d.md`：配置系统。
- `.agent/tmp-agent-reports/tmp-audit-module-e.md`：工具 / Skill / MCP。
- `.agent/tmp-agent-reports/tmp-audit-module-f.md`：工作区 / 任务管理。
- `.agent/tmp-agent-reports/tmp-audit-module-g.md`：UI/UX 与黑盒程度矩阵。
- `docs/audit/20260706-business-flow-full-code-audit.md`：Codex 原业务流程完整代码审计报告。

交叉审计原则：

- 同一问题被两边独立发现且代码证据一致，直接采纳并提升优先级。
- 一边发现、另一边未覆盖，但代码 grep 能确认，采纳并纳入终版。
- 静态证据不足、依赖运行时复现、或结论表述过满的，降级为“需验证”。
- 发现描述中有事实偏差的，不照抄原结论，保留可用部分后重写用户影响。

## 2. 总体判断

Nexara 的架构骨架是成立的：对话编排、协议抽象、RAG 进度、知识库导入、工具注册、工作区文件、任务计划和渲染组件都已成体系，不是简单样板工程。

但终版交叉审计后，最核心的问题可以概括为一句话：

> 当前产品在多个关键链路上“给了用户可配置、可点击、可选择、可审批、可发送的入口”，但这些入口并不总能稳定进入真实业务链路；用户会被 UI 暗示某个能力已生效，实际请求、Prompt、检索、工具执行或渲染层并没有照做。

这类问题比普通崩溃更危险，因为用户无法从界面判断真实状态。它会直接破坏对 AI 客户端最重要的信任感：我到底发了什么、查了什么、用的是哪个模型、哪些工具执行了、哪些配置生效了、哪些文件被改了。

## 3. 终版 P0 队列

P0 定义：修复前会持续误导用户，或在核心业务链路中造成静默失败、上下文丢失、数据损坏、权限/审批错觉。

### FA-01 错误状态没有进入用户可见恢复链路

采纳来源：GLM A-001 / G-12 + Codex BF-12。

用户影响：

- API Key 错、模型不存在、429 限流、网络断开、RAG 检索失败、SSE chunk 解析失败时，用户看到的经常是空气泡、半截输出或底部小红字。
- `ChatUiState.error` 已存在，`clearError()` 也存在，但 `ChatScreen` 未消费这条错误流；`SnackbarHost` 当前主要用于撤销删除。
- 用户不知道是配置错、网络错、限流、超时，还是应用自身 bug。

关键证据：

- `ChatViewModel.kt` 多处 `_error.update { ... }`。
- `ChatScreen.kt` 未绑定 `uiState.error` 到 snackbar / error card。
- `PipelineBubble.kt` 只在消息内显示 `errorMessage`，无分类、无重试、无跳转设置。

终版建议：

- 对 `ChatUiState.error` 建立统一错误卡片或 snackbar。
- 错误分类至少覆盖：鉴权、模型不存在、限流、网络、超时、上下文过长、RAG/Embedding 未配置。
- 每类错误给一个恢复入口：重试、检查 Provider、切换模型、关闭联网/RAG、重新索引。

### FA-02 审批机制后端存在但 UI 未接线，且高风险工具名单过窄

采纳来源：GLM X-002 / E-001 / F-C4 / G-03 + Codex BF-25。

纠偏说明：

- GLM 报告中“manual/semi 下工具直接执行”的表述不完全准确。
- 代码实际会在 `semi/manual` 下为部分工具设置 `approvalRequest` 并进入 `WAITING_FOR_APPROVAL`。
- 真正的问题是：审批请求没有被 UI 渲染，用户无法点击同意/拒绝；同时 `patch_file` 等高风险工具没有被纳入审批。

用户影响：

- 默认半自动模式下，高风险工具可能让对话静默停住。
- 用户看不到模型想写哪个文件、执行什么 JS、生成什么图片。
- `patch_file` 同样会修改磁盘文件，却绕过 `write_file` 的审批预期。

关键证据：

- `ChatViewModel.kt:636-649` 创建 `ApprovalRequest`。
- `ChatViewModel.kt:970-983` 有 `approveRequest()` / `rejectRequest()`。
- `ChatInlineComponents.kt` 定义 `ApprovalCard`，但全代码库未调用。
- `highRiskToolNames` 只有 `write_file`、`exec_js`、`generate_image`、`create_tool`。

终版建议：

- 在对话流或工具执行块中接入 `ApprovalCard`。
- `patch_file`、`drop_plan`、`web_fetch`、未来 `create_file` 按风险级别纳入审批。
- 审批 UI 必须把参数翻译成人能理解的摘要，例如“将修改文件 X 的 12-18 行”。

### FA-03 RAG 检索范围和 Prompt 注入存在信任断点

采纳来源：Codex BF-01 / BF-02 / BF-03，GLM C 报告部分补充。

用户影响：

- 用户选择特定文档后，系统可能不检索这些文档。
- RAG 层构造出的完整 `contextBlock` 没有进入最终 system prompt，最终只拼接每条引用前 400 字。
- 旧的 `SessionSettingsScreen` 里“附加文档”使用硬编码示例名，只改变 UI 列表，不写入真实 `activeDocIds`。

关键证据：

- `MemoryManager.kt:61-68`：`activeDocIds` 非空且 `isGlobal=false` 时，`authorizedDocIds` 被设置，但 `canSearchDocs = canSearchAllDocs`，导致文档搜索路径可被关闭。
- `ContextBuilder.kt:258` 接收到 `context`，但 `buildSystemPrompt()` 只接收 `ragReferences`。
- `ContextBuilder.kt:339-344` 每条引用 `take(400)`。
- `SessionSettingsScreen.kt:81-89` 默认 `Q3_Report_Final.pdf`、`Revenue_Data.csv`。

终版建议：

- 分离“全局文档检索”和“指定文档检索”两个分支。
- 把 `ragProvider.retrieveContext()` 返回的完整 `context` 注入 Prompt，引用列表只用于 UI。
- 删除演示型附加文档 UI，改为真实知识库文档选择器，写入 `activeDocIds` / `activeFolderIds`。

### FA-04 RAG 配置和生命周期管理存在多处死链

采纳来源：GLM C1-C6 / D-3.4 + Codex BF-12 / BF-27 / BF-28。

用户影响：

- 用户打开 Query Rewriter、调整改写策略和数量，检索结果不变。
- 用户删除文档后，旧向量和图谱边可能继续污染检索。
- 用户重新索引后，旧向量未清理，新旧向量可能重复命中。
- 用户切换 Embedding 模型后，维度不匹配向量被静默跳过，知识库看似“突然失效”。
- 大 PDF / 文档导入使用 `readBytes()` 和 PDFBox 全量加载，存在 OOM 风险。
- Agent 级 `ragConfig` / `retrievalConfig` 已保存但未进入对话检索链路。

关键证据：

- `QueryRewriter` 类存在，但全仓库无实例化/调用。
- `RagViewModel.deleteDocuments()` 只调用 `workspaceRepository.permanentDelete()`。
- `VectorizationQueue.processDocumentTask()` 写入前未看到按 docId 清理旧向量。
- `EmbeddingClient` 请求体未使用 `embedDimension`，批大小硬编码。
- `AgentConfigResolver` 解析 `ragConfig` / `retrievalConfig`，`ChatViewModel.generateMessage()` 只使用 systemPrompt / model / params。

终版建议：

- Query Rewriter 要么接入 `MemoryManager.retrieveContext()`，要么 UI 标注“暂未启用”。
- 删除文档时清理 vector、KG edge、孤儿 node、tag / task。
- 重新索引必须先清理该 docId 的旧向量，再写新向量。
- 检测向量维度不匹配，给用户“需要重新索引”提示。
- Agent 级 RAG 配置纳入最终配置合并链路。

### FA-05 多模态链路从发送、重试、本地协议到工具产物均有断点

采纳来源：Codex BF-05~BF-08 / BF-30 + GLM A-003 / A-005。

用户影响：

- 发送大图时主线程读 bytes + Base64，可能卡顿或 OOM。
- 图片读取失败时可能继续发送纯文本，用户误以为图已经发出。
- 带图消息重发 / 重新生成会丢图。
- 本地 VL 模型路径只把图片转为 `[Image: mime]` 文本标记，没有真实视觉输入。
- 工具生成图片写进 TOOL 消息 `images`，但 PipelineBubble 跳过 TOOL 正文，用户看不到图。
- 主输入只支持 `image/*`，数据模型却有 audio / file 字段，能力边界不清晰。

关键证据：

- `ChatViewModel.sendMessage()` 在 launch 前读取 URI bytes。
- `retryLastMessage()` 调 `sendMessage(lastUserMsg.content)`。
- `LocalProtocol` 只拼文本图片标记。
- `ToolExecutor` 写 TOOL message images；`PipelineBubble` 跳过 TOOL 消息，用户图片展示只消费 `userImages`。

终版建议：

- 附件预处理独立到 `Dispatchers.IO`，限制单图大小、数量、MIME，并压缩。
- 重发 / 重新生成复用原用户消息的 `userImages`。
- 本地视觉未接通时禁用图片发送或明确提示“本地模型暂不支持图片理解”。
- 工具产物改为统一 artifact，主气泡和工具步骤都能预览。

### FA-06 协议层与配置透传不可信

采纳来源：Codex BF-04 / BF-09 / BF-10 / BF-11 + GLM D-3.5 / D-3.7。

用户影响：

- OpenAI Responses 入口实际走 Chat Completions。
- OpenAI / Generic OpenAI-compatible 对每个文本 delta 做 trim，破坏 Markdown 空白。
- `UnifiedLlmClient` 路径丢失 topK、frequencyPenalty、presencePenalty、repetitionPenalty、streamTimeout、enableGeminiSearch 等设置。
- Gemini 搜索关闭开关在 unified 路径丢失，可能仍注入联网检索。
- OpenAI-compatible 参数适配过宽，可能向不支持字段的服务商发送 `top_k` / `repetition_penalty`。

关键证据：

- `ProtocolFactory.kt` / `LlmProvider.kt` 将 `OpenAI_Responses` 映射到 `OpenAIProtocol`。
- `OpenAIProtocol.buildUrl()` 固定 `/chat/completions`。
- `OpenAIProtocol` / `GenericOpenAICompatProtocol` 的 `cleanSpecialTokens()` 末尾 `.trim()`。
- `ChatViewModel.kt:477-490` 走 `StreamTextParams`，字段少于 `PromptRequest`。
- `GenericOpenAICompatProtocol.kt` 根据 `request.enableGeminiSearch != false` 决定 Gemini 搜索。

终版建议：

- 实现真实 `OpenAIResponsesProtocol` 或隐藏入口。
- delta 清理只移除明确特殊 token，不 trim 普通空白。
- 合并请求模型，避免 `PromptRequest -> StreamTextParams -> PromptRequest` 丢字段。
- 参数按服务商白名单映射，不支持时 UI 置灰。

### FA-07 工作区数据安全未达到“类 IDE 文件系统”的预期

采纳来源：GLM F-C1~F-C5 + Codex BF-16~BF-19 / BF-26。

用户影响：

- AI 无法创建新文件，只有 `write_file` / `patch_file` 修改已有 UUID。
- `writeFileAtomic` 名称暗示原子写，实际使用 `writeText()` 直接覆盖。
- 写入 / patch 前没有版本快照，AI 写错无法撤销。
- `materializedPath` 缺少规范化和 root 边界校验，补 `create_file` 后会立即放大路径穿越风险。
- `renameTo()` 返回值被忽略，DB 和磁盘可不一致。
- `diff_file` 在无法重建 basisHash 时用空字符串产生全量假 diff。
- 写入 / patch 后未触发重新索引，AI 改过文件后 RAG/搜索可能不知道。
- `search_files(mode="fts")` schema 声称支持全文，但实现只搜文件名。

终版建议：

- 新增 `create_file` 前必须先完成路径规范化和 root containment 校验。
- 写文件改为临时文件 + fsync + 原子 rename，成功后再更新 DB。
- 增加 `file_versions` 或至少 N 版快照。
- `diff_file` 无法还原历史基线时返回明确错误。
- 文件改动后发布索引失效 / 重建事件。

### FA-08 任务管理对 UI 和 AI 的状态表达不一致

采纳来源：GLM F-L5 + Codex BF-20~BF-22。

用户影响：

- UI 面板使用 `done` / `doing` / `dropped`，显示基本可用。
- 但 `ContextBuilder.renderTaskTree()` 判断的是 `completed` / `in_progress` / `failed`，注入给模型的任务树可能全部显示为未开始。
- `move_step` 可制造父子环，树构建无 visited set。
- 批量 `updatePlan` 没事务，中途失败会半更新。
- `partial_dropped` 与 UI 的 `partial-dropped` 不一致。

关键证据：

- `TaskRepository` 写入 `todo` / `doing` / `done`。
- `ContextBuilder.kt:441-445` 判断 `completed` / `in_progress` / `failed`。
- `TaskRepository.updatePlan()` 逐个 operation 执行。
- `TaskRepository.buildTree()` 递归无防环。

终版建议：

- 任务状态统一为 enum / sealed class，并集中做协议映射。
- `ContextBuilder` 与 UI 使用同一状态体系。
- `move_step` 加祖先链校验，树构建加 cycle detection。
- `updatePlan` 批处理事务化。

## 4. 终版 P1 队列

### FA-09 Tool / Skill 系统需要去“假成功”和补幂等

合并来源：GLM E-002~E-108 + Codex BF-14 / BF-15 / BF-23。

保留结论：

- `create_tool` 返回“可以使用”，但自定义 Skill 沙箱未实现。
- `UserSkillRegistry` 用 `runBlocking` 桥接 Room。
- 工具执行缺少已执行 tool call 幂等状态，重复片段可能重复执行。
- 工具耗时 `1.2s` 是假数据，应从执行层记录真实 duration。
- 给模型的 SYSTEM NOTE 混入 UI 可见工具结果，用户会看到不该看的提示词。
- MCP 工具列表只增不删，server 工具移除后可能陈旧。
- MCP 大结果 / 多模态产物未统一走 ResultSizeOptimizer。

降级说明：

- GLM 关于 MCP schema “双重序列化”的结论需要补协议层测试。当前协议层会 `parseToJsonElement(tool.function.parameters)`，并非必然错误；保留为 P2 验证项，不列 P0。

### FA-10 流式连接与重试策略不完整

合并来源：GLM A-002 / A-007 / A-008 / A-012 + Codex BF-13。

保留结论：

- `StreamChunk.Error.retryable` 存在，但业务链路没有自动重试 / 指数退避。
- 流式中断和正常结束没有清晰区分，半截内容可能被当成完成。
- 删除生成中的消息没有清理 pending update 的强证据，可能“删了又回写”。
- 切换会话时是否显式取消生成需要补运行时验证。

降级说明：

- Provider fallback 缺失不应直接列 P0，因为产品未明确承诺自动切备用 Provider。终版列为 P2 能力增强，除非后续产品定义要求。

### FA-11 UI/UX 的关键控制状态仍然黑盒

合并来源：GLM G-01~G-16 + Codex BF-27 / BF-31。

保留结论：

- 首次启动缺 Provider 配置引导。
- 对话页只显示模型，不显示 Provider / 本地或云端来源。
- 发送前看不到本轮 RAG 会检索哪些源。
- 参数变更后没有“已生效 / 下条消息生效”反馈。
- loop limit 达到后静默停止。
- ThemeScreen 存在但浅色主题未实现，入口/行为不一致。
- i18n 和 contentDescription 覆盖不完整。

优先建议：

- Provider onboarding、错误恢复卡片、Provider+模型 chip、RAG 源徽章、审批卡片，优先级高于装饰性视觉优化。

### FA-12 输出渲染链路需要提升长内容和流式稳定性

合并来源：GLM B-S1~B-S5 + Codex BF-29 / BF-32。

保留结论：

- 思考过程流式体验可能静止，需实测并让 reasoning 与内容流分开驱动。
- 长代码块没有折叠 / 高度上限。
- 表格列数不一致时需要补齐 / 截断，避免错位。
- Mermaid / ECharts 流式半截代码不应反复 WebView reload。
- HTML block 当前当普通文本处理，需要明确安全策略。
- 8ms 自动滚动轮询需降频或与用户手势更好协调。

降级说明：

- `ParseCache` 串味是合理怀疑，但需要 Compose LazyColumn 复用复现；终版列 P2 验证。
- `repairCompressedMarkdownBoundaries` 误伤正常 Markdown 的风险存在，但前序已有测试覆盖一部分，终版列为补测试和边界收紧，不作为 P0。

### FA-13 安全边界需按“Agent 可执行环境”重新梳理

合并来源：GLM E-005 / E-006 / F-C5 + Codex BF-26。

保留结论：

- `exec_js` 使用 WebView，描述称无网络访问，但需确认并强制阻断网络。
- `web_fetch` 缺少私网 / localhost / file scheme / metadata 地址保护。
- 工作区路径缺少 canonical root 校验。
- 高风险工具需要从硬编码名单升级为 skill metadata。

## 5. 需要降级或剔除的结论

以下结论不作为终版高优先级事实直接推进：

| 原结论 | 终版处置 | 理由 |
|---|---|---|
| manual/semi 模式下工具直接执行 | 重写为“审批请求会产生，但 UI 未接线；patch_file 绕过审批” | 代码里 `approvalRequest` 和 `WAITING_FOR_APPROVAL` 确实存在。 |
| 额外 Provider 完全不生效 | 降级为 P1/P2 运行时验证项 | Chat 链路看起来仍绑定主 provider config，但应用层存在 extra provider listener 和 `getProviderConfigByModelId`，需抓请求确认。 |
| MCP schema 双重序列化必然损坏 | 降级为 P2 协议测试项 | 协议层对 parameters 有 `parseToJsonElement`，需用对象 schema 和字符串 schema 分别验证。 |
| Provider fallback 缺失是严重缺陷 | 降级为 P2 能力缺口 | 没有产品承诺“自动切备用”，但可作为增强项。 |
| 浅色主题假支持是核心 P0 | 降为 P1/P2 | 若当前产品定位仅深色，则应清理死入口或明确仅深色；不是对话主链路阻断。 |
| 工具耗时硬编码 1.2s 是 P0 | 降为 P1/P2 | 误导可观测性，但不直接破坏核心生成。 |
| ParseCache 串味已确认 | 降为 P2 需复现 | 静态分析合理，但需要 LazyColumn 槽位复用复现。 |

## 6. 建议修复顺序

### 第一阶段：先修“用户信任链”

目标：用户能知道错误、审批、配置、检索和发送到底有没有生效。

1. 接入错误卡片 / snackbar，覆盖 `ChatUiState.error` 与消息级错误。
2. 接入 `ApprovalCard`，补 `patch_file` 等高风险工具审批。
3. 修 RAG `contextBlock` 注入、指定文档检索、附加文档真实绑定。
4. 修带图重发、图片 IO、工具图片可见。
5. 修协议 delta trim、UnifiedLlmClient 参数丢失、OpenAI Responses 入口。

### 第二阶段：修“数据安全链”

目标：AI 改文件、改任务、重建知识库时不会造成静默损坏。

1. 工作区写入原子化和版本快照。
2. `create_file` 工具与路径 root 校验一起实现。
3. `renameTo` 结果校验和移动/回收站事务补偿。
4. `diff_file` basisHash 无法还原时返回错误。
5. 任务状态枚举化、事务化、防环。

### 第三阶段：修“知识库生命周期”

目标：RAG 不是只在 UI 上可见，而是检索质量和数据生命周期可信。

1. 删除文档清理向量 / 图谱 / 标签。
2. 重新索引先删旧向量。
3. Embedding 维度变更提示重建索引。
4. QueryRewriter 接入或标注停用。
5. 引用来源显示真实文件名而不是 UUID 前 8 位。

### 第四阶段：修“长期体验和可维护性”

目标：减少黑盒、死代码、重复组件和误导文案。

1. 首次启动 Provider 引导。
2. Provider + 模型 chip、RAG 源徽章、参数生效反馈。
3. 代码块折叠、表格规范化、图表流式占位。
4. 主题策略明确：仅深色或完整浅色。
5. i18n / contentDescription 基线治理。

## 7. 交叉验证用例

修复前后都建议执行这些用例：

- API Key 错误、模型不存在、429、断网：界面必须显示明确错误和恢复按钮。
- 半自动模式调用 `write_file`、`patch_file`、`exec_js`：必须出现审批卡片；拒绝后不能执行。
- 选择单个知识库文档并关闭全局：问题答案在该文档第 800 字后，模型仍能命中。
- RAG 返回 contextBlock 与 references：抓最终 prompt，确认完整 context 进入系统提示词。
- 删除文档后提问旧内容：旧向量和 KG 不再命中。
- 重新索引同一文档：向量数量不重复膨胀。
- 切换 embedding 模型：出现重新索引提示，而不是静默 0 结果。
- 带图消息失败后重发：请求仍包含 image input。
- 本地文本模型发图：UI 明确提示不支持图片理解。
- image_generation 返回图片：工具步骤和最终气泡均可预览。
- OpenAI Responses 协议：请求 URL 不是 `/chat/completions`。
- SSE delta 以空格/换行开头：最终 Markdown 不丢空白。
- `search_files(mode="fts")`：要么真搜正文，要么 schema 不再暴露该模式。
- 工作区写入时强制崩溃 / 磁盘异常：文件不应变空，DB/hash 不应假成功。
- `move_step` 把父节点移动到子节点下：应被拒绝，任务树不崩。
- `ContextBuilder` 注入任务树：已完成步骤必须显示为完成，而不是全部未开始。

## 8. 最终取舍结论

GLM-5.2 报告的最大价值是补足了我原报告中未展开的 UI/UX、配置死链、审批死链、RAG 生命周期、工作区文件安全和输出渲染细节；我原报告的最大价值是抓住了 RAG Prompt 注入、指定文档检索、协议 delta trim、Responses API 映射、多模态真实链路、工具图片不可见、任务事务/防环等主链路断点。

终版报告采用两者交集作为优先级核心，并保留单边但代码证据明确的问题。对于静态分析无法闭环的断言，终版全部降级为运行时验证项，避免后续修复被不确定结论带偏。

一句话收口：

> Nexara 不是缺功能入口，而是太多入口没有形成可信的“UI 意图 -> 配置持久化 -> 请求/Prompt/工具/检索/渲染生效 -> 用户可见反馈”闭环。后续修复应优先补信任链，再补功能面。

## 9. 文档治理

DIA: 已同步 `docs/audit/20260706-final-business-flow-cross-audit.md`、`.agent/registry.md`、`CHANGELOG.md` 与 `.agent/handover.md`。

HLG: 本报告作为后续修复排期主入口；原始 GLM 主报告、A-G 模块报告和 Codex 原审计报告均保留，不覆盖证据链。
