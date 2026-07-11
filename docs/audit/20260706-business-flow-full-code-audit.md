# Nexara APP 业务流程完整代码审计报告

- 审计时间：2026-07-06T00:18:47+08:00
- 审计对象：Android 原生 Compose APP 主业务链路
- 审计方式：静态代码审计 + 子代理并行扫描 + 主控复核关键证据
- 报告定位：供后续交叉审计、修复排期和真机验证使用

## 1. 审计范围

本轮覆盖用户要求的主业务链路：

- 基础文本消息发送、流式生成、停止、删除、重发、重新生成。
- 多模态发送、图片上下文保存、重试、返回渲染、工具生成图片。
- Prompt 拼接、系统提示词、RAG、联网搜索、知识图谱、任务计划上下文。
- API 协议层、参数透传、连接保持、错误捕获、fallback 解析。
- RAG 系统、知识库文档系统、图谱系统在生成链路中的启用条件。
- 可配置项是否从 UI / SharedPreferences / Session 设置进入真实业务链。
- 工具调用系统、Skill 系统、MCP 工具、工作区文件操作、任务管理模块。
- UI/UX、可视化、黑盒程度、硬编码和用户可理解性。

本轮没有进行真机 UI 自动化、真实服务商 API 付费调用或大文件压力测试。因此本报告的结论是“代码证据级审计结论”，需要后续用真机和真实模型做端到端复验。

## 2. 执行说明

已授权使用子代理后，本轮分派了 5 个方向：

- A：核心对话 / Prompt / API 链路，已完成。
- B：多模态 / 渲染链路，已完成。
- C：RAG / KG 链路，因 Spark 子代理额度限制中断，主控补做复核。
- D：工具 / Skill / 工作区 / 任务链路，已完成。
- E：配置 / UI / 可观测性，因 Spark 子代理额度限制中断，主控补做复核。

子代理输出只作为输入，最终问题分级和取舍由主控结合代码证据复核后给出。

本轮补充模块报告已保留在 `.agent/tmp-agent-reports/tmp-audit-module-a.md` 至 `.agent/tmp-agent-reports/tmp-audit-module-g.md`，作为交叉审计时的原始证据材料；主报告以本文件的 BF 编号为准。

## 3. 总体结论

Nexara 当前主对话链路已经具备较完整的骨架：消息入库、RAG 阶段卡片、工具执行步骤、流式输出、知识库导入、工作区文件、任务计划等模块都存在，并非停留在空壳。

但从用户交互体验看，存在一类更危险的问题：用户在 UI 中开启、选择或执行了某项能力后，系统有时只改变了界面状态或局部数据，并没有把这个选择稳定传递到最终 Prompt、协议请求、检索过滤、工具执行或渲染层。用户会感觉“我明明打开了知识库 / 选了文档 / 发了图片 / 让工具生成了图片 / 调了参数，但回答像没发生过一样”，这类失真比显式报错更难排查。

最高优先级建议先处理 4 条主线：

1. RAG 文档选择、Prompt 拼接和知识图谱启用条件。
2. 协议层对流式文本的逐块 trim、Responses API 映射和参数透传。
3. 多模态上下文在发送、重试、重新生成、本地模型和工具产物渲染中的断链。
4. 工作区和任务计划的持久化一致性，避免工具显示成功但文件树或任务树已经失真。

## 4. 优先级定义

- P0：用户主流程会直接失真，且很难从 UI 判断真实原因。
- P1：功能可用性或数据一致性有明显风险，常规使用中可能触发。
- P2：局部体验、可观测性、兼容性或性能问题，会放大排障成本。
- P3：设计债务或边缘问题，建议纳入后续整理。

## 5. 高优先级问题

### BF-01 P0：选择特定知识库文档后，可能完全没有检索这些文档

用户视角：用户在会话里以为“我只让它查这几个文档”，但实际回答可能没有查这些文档。最糟糕的是界面仍然会展示 RAG 相关状态，用户难以判断到底是没搜到、没开启，还是被代码跳过。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/rag/MemoryManager.kt:59-64`
- `native-ui/app/src/main/java/com/promenar/nexara/data/rag/MemoryManagerRagAdapter.kt:12-27`

问题说明：

- `hasSpecificDocs = activeDocIds.isNotEmpty()` 时，如果 `isGlobal=false`，代码设置了 `authorizedDocIds`。
- 但真正决定是否搜索文档的是 `canSearchAllDocs = enableDocs && (isGlobal || !hasSpecificDocs)`。
- 这意味着“启用文档 + 指定文档 + 非全局”会让 `canSearchAllDocs=false`，后续很可能不走文档检索。
- `MemoryManagerRagAdapter` 只传递 `activeDocIds`，没有传递 `activeFolderIds`，文件夹级选择也无法进入检索过滤。

建议：

- 把“搜索全部文档”和“搜索指定文档”拆成两个明确分支。
- `activeDocIds` 不为空时应按 ID 过滤检索，而不是关闭文档检索。
- 为“指定文档检索”和“指定文件夹检索”补单元测试与端到端测试。

### BF-02 P0：RAG 生成的完整上下文被丢弃，最终 Prompt 只拼接截断片段

用户视角：用户看到 RAG 卡片似乎完成了检索，但模型实际拿到的上下文可能只有每条引用前 400 字，且没有使用 RAG 层生成的完整模板。长文档、表格、规章制度、跨段落信息会明显答不准。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/ContextBuilder.kt:258`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/ContextBuilder.kt:339-351`
- `native-ui/app/src/main/java/com/promenar/nexara/data/rag/MemoryManager.kt:238-239`

问题说明：

- `ragProvider.retrieveContext()` 返回的是 `(context, references, usage)`。
- `MemoryManager` 会基于 `summaryTemplate` 构造完整 `contextBlock`。
- 但 `ContextBuilder.buildSystemPrompt()` 没有使用返回的 `context`，而是重新遍历 `ragReferences`，每条只取 `ref.content.take(400)`。

建议：

- `ContextBuilderResult` 或内部变量应保留并注入 `ragResult.first`。
- UI 引用列表可以继续使用 `ragReferences`，但模型上下文应使用 RAG 层已排好序、已模板化、可控长度的 `contextBlock`。
- 对“长文档关键答案在 400 字之后”的用例补回归测试。

### BF-03 P0：会话设置里的“附加文档”更像演示 UI，没有把真实文档 ID 写入 RAG 配置

用户视角：用户在设置里看到两个默认文档，还能添加文件名，容易相信这些文档已经绑定到会话；但回答时并不会按这些文件名检索，形成强烈的信任落差。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/SessionSettingsScreen.kt:81-89`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/SessionSettingsScreen.kt:401-417`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/SessionSettingsScreen.kt:373-396`

问题说明：

- `attachedDocs` 默认硬编码为 `Q3_Report_Final.pdf`、`Revenue_Data.csv`。
- 文件选择后只追加显示名，没有映射到知识库文档 ID。
- `updateRagOptions()` 只更新 `enableKnowledgeGraph`、`enableDocs` 等布尔值，没有更新 `activeDocIds`。

建议：

- 移除演示文档硬编码。
- 改为从知识库文档表选择真实文档，保存 `activeDocIds`。
- 附加文档 UI 应展示索引状态、向量化状态、是否可检索。

### BF-04 P0：协议层对每个流式 delta 做 trim，会破坏 Markdown 与自然语言空白

用户视角：本地模型或 OpenAI-Compatible 模型输出中明明有 Markdown 标记，但界面里仍可能压成一坨，或者中英文、列表、代码块空格异常。此前渲染层已做补救，但上游协议层仍在制造数据损伤。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/OpenAIProtocol.kt:93`
- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/OpenAIProtocol.kt:418-429`
- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/GenericOpenAICompatProtocol.kt:102`
- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/GenericOpenAICompatProtocol.kt:444-455`

问题说明：

- SSE 行解析阶段使用 `trim()` 可以理解。
- 但 `cleanSpecialTokens()` 最后也 `.trim()`，且它被用于每个流式文本片段。
- 对 delta 逐块 trim 会删除片段边缘的空格和换行，导致列表、标题、代码围栏和自然语言空格被拼坏。

建议：

- delta 级清理只能移除明确的特殊 token，不能 trim 普通空白。
- 只在完整消息完成后做必要的首尾清理。
- 新增测试：模拟 `["# 标题", "\n\n", "- 项目", " 续"]` 这类 chunk 拼接，不允许丢失换行和空格。

### BF-05 P0：图片发送在 ViewModel 同步读全量 Base64，可能卡 UI，失败还会静默降级成纯文本

用户视角：用户选择大图后点击发送，界面可能卡顿；如果读取失败，消息仍发出去，但模型看不到图片，用户只会觉得“多模态不准”。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt:247-266`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt:422-472`

问题说明：

- `sendMessage()` 进入协程前就遍历 URI、读取 bytes、Base64 编码。
- 异常被捕获后只打印堆栈，没有给用户提示，也没有阻止继续发送。
- 没有图片尺寸、文件大小、MIME、压缩或采样策略。

建议：

- 图片读取和编码放入 `Dispatchers.IO`。
- 失败时应在输入区或消息旁明确提示“图片读取失败，未发送图片”。
- 加入大小限制、压缩策略和发送前预估。

### BF-06 P0：带图消息重发 / 重新生成会丢失图片上下文

用户视角：用户第一次问图像问题能得到回答，但长按重发或重新生成后，模型只收到文字，不再收到图片，回答质量会突然变差。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt:247-289`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt:1365-1387`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt:1228-1277`

问题说明：

- 原始发送会把图片保存到 `userImages` 并在协议消息中映射到 `imageUrls`。
- `regenerateMessage()` 只基于 `message.content` 创建新生成，没有重新带上 `userImages`。

建议：

- 重发用户消息时复用原消息的 `userImages`。
- 重新生成 AI 消息时回溯对应用户消息，并把文本与图片一并放入请求。
- 增加“带图消息重发仍包含 imageUrls”的单元测试。

### BF-07 P0：本地协议不是真正多模态，只把图片写成文本标记

用户视角：用户选择本地 VL 模型并发送图片，会以为图片进入了模型。但实际本地协议只生成 `[Image: image/png]` 这类文本提示，模型无法看见图像内容。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/LocalProtocol.kt:19-35`
- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/LocalProtocol.kt:43-60`

问题说明：

- `LocalProtocol` 将 `imageUrls` 转成纯文本标记。
- 没有把图片 bytes、tensor、vision encoder 输入传入本地推理引擎。

建议：

- UI 上区分“本地文本模型”和“本地视觉模型是否已接入真实图像输入”。
- 未实现真实视觉输入时，应禁用图片按钮或显示明确说明。
- 如果要支持 VL，本地推理接口需要扩展为结构化多模态输入。

### BF-08 P0：工具生成的图片和多模态工具产物不会在主气泡中可见

用户视角：用户让工具生成图片，工具可能返回成功，但聊天正文不显示图；用户需要猜测图片是否生成、在哪里看。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/ToolExecutor.kt:129-136`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt:372`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt:799-805`

问题说明：

- `ToolExecutor` 会把工具结果的 `data` 写入 TOOL 消息的 `images`。
- `PipelineBubble` 跳过 TOOL 消息正文。
- 主渲染只看到用户消息的 `userImages`，没有消费 TOOL 消息 `images`。

建议：

- 将工具产物归一为 `ToolResultArtifact` 或 assistant message artifact。
- 在执行步骤和最终气泡中都提供图片缩略图、下载、复制链接。
- 避免把图片 URL / Base64 藏在不可见 TOOL 消息里。

### BF-09 P0：OpenAI Responses API 入口实际仍走 Chat Completions

用户视角：用户选择 “OpenAI Responses” 协议，期望使用 Responses API 的能力和参数，但实际请求仍是 `/chat/completions`。这会导致多模态、工具、Reasoning、输出格式等行为与预期不一致。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/ProtocolFactory.kt:21`
- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/provider/LlmProvider.kt:72`
- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/OpenAIProtocol.kt:177`

问题说明：

- `ProtocolType.OpenAI_Responses` 被映射到 `OpenAIProtocol`。
- `OpenAIProtocol.buildUrl()` 固定拼接 `/chat/completions`。

建议：

- 要么隐藏 Responses 协议入口，要么实现独立 `OpenAIResponsesProtocol`。
- 协议选择页应展示实际 endpoint，便于用户理解当前请求路线。

## 6. 中优先级问题

### BF-10 P1：统一 LLM 客户端路径会丢失部分推理参数和超时配置

用户视角：用户在设置里调了惩罚项、topK、streamTimeout、搜索相关配置，但请求进入统一客户端后可能没有生效。用户会感觉“设置面板不可信”。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt:451-490`
- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/UnifiedLlmClient.kt:43-72`

问题说明：

- `ChatViewModel` 组装了较完整的 `PromptRequest`。
- 但走 `unifiedLlmClient.sendStream()` 时重新构造请求，只保留了 model、temperature、topP、maxOutput、tools、webSearch 等少量字段。

建议：

- 建立单一请求模型，避免 ChatViewModel 和 UnifiedLlmClient 双重映射。
- 参数面板每个字段应有“已进入请求 / 当前协议不支持”的可见状态。

### BF-11 P1：OpenAI-Compatible 参数适配过宽，可能向服务商发送不支持的字段

用户视角：用户稍微调整高级参数后，某些服务商突然 400 报错，错误提示看起来像服务商问题，实际上是客户端发了它不支持的字段。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/ProtocolParamAdapter.kt:19-30`

问题说明：

- `top_k` 会被无条件加入。
- `repetition_penalty` 会发给多类 OpenAI-Compatible 协议。
- 不同国产 / OpenAI 兼容服务对未知字段容忍度不同。

建议：

- 参数适配应按协议和服务商白名单控制。
- UI 应对不支持字段置灰或提示“当前服务商不支持”。

### BF-12 P1：流式解析、联网搜索和 RAG 错误大量被吞掉，用户只看到“没结果”

用户视角：联网搜索失败、SSE 某块 JSON 解析失败、RAG 报错时，界面往往只是少了引用或输出中断，用户不知道是网络、配置、服务商还是应用问题。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/OpenAIProtocol.kt:93-108`
- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/GenericOpenAICompatProtocol.kt:102-118`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/ContextBuilder.kt:59-88`

问题说明：

- 部分流式 JSON 解析异常被忽略。
- passive web search 报错只记录日志并返回空。
- RAG/KG 阶段缺少“失败但已降级”的可见状态。

建议：

- 区分“无结果”和“检索失败”。
- RAG 卡片阶段状态应包含 error / skipped / disabled / partial。
- SSE malformed chunk 至少应计数并在调试面板可见。

### BF-13 P1：删除生成中的消息不一定能停止实际生成，且存在待刷写内容回写风险

用户视角：用户删除一条正在生成的消息后，后台请求可能继续跑，或稍后又把内容写回数据库，造成“删了又冒出来”或资源浪费。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/MessageManager.kt:59-150`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/MessageManager.kt:286-324`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt:936`

问题说明：

- 是否停止依赖 `currentGeneratingSessionId == sessionId` 和当前最后消息判断。
- `pendingUpdates` 是延迟刷写队列，删除时没有看到同步清理对应 key 的机制。

建议：

- 删除消息时清除该消息 pending update。
- 生成任务应绑定 assistant message id，停止和删除按 message id 精确取消。

### BF-14 P1：工具调用缺少已执行去重状态，重复流式片段可能导致工具重复执行

用户视角：模型一次请求里同一个工具可能被执行两次，特别是写文件、改计划、联网、图片生成这类工具，用户会看到重复操作或状态错乱。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/ToolExecutor.kt:22-143`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt:586-672`

问题说明：

- 工具执行基于当前解析出的 tool call 列表。
- 缺少持久化的 `executedToolCallIds` 或 assistant message 内的幂等标记。

建议：

- 以 provider tool call id + assistant message id 做幂等键。
- 写入型工具执行前后都记录状态，失败重试也要可见。

### BF-15 P1：自定义 Skill 显示“已创建 / 可执行”，但实际只是回显代码

用户视角：用户让 Agent 创建一个工具后，会以为下次模型能真的调用它；但当前实现没有安全沙箱和执行引擎，只是返回“sandbox not implemented”。这会让 Skill 系统显得很神秘但不可预测。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/registry/UserSkillRegistry.kt:14-21`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/skills/CreateToolSkill.kt:13-14`

问题说明：

- 自定义 skill registry 使用 `runBlocking` 从 DAO 读取。
- 自定义工具没有真正执行用户代码。
- 但用户路径里容易把它理解为“工具创建成功并可用”。

建议：

- 在 UI 和工具返回中明确区分“已保存草稿”和“可执行工具”。
- 未实现沙箱前，不应把自定义工具暴露为可执行能力。

### BF-16 P1：工作区全文搜索声明了 fts 模式，但实现只做文件名搜索

用户视角：用户让 Agent 在工作区里“搜索正文内容”，工具可能返回没有结果，因为实际没有搜正文。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/skills/FileSearchSkill.kt:16`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/skills/FileSearchSkill.kt:21`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/skills/FileSearchSkill.kt:56-62`

问题说明：

- 参数 schema 暴露 `mode = name | fts`。
- `searchTree()` 中只有 `mode == "name"` 时才匹配。
- `fts` 模式会遍历但不匹配内容。

建议：

- 未实现前移除 `fts`。
- 或接入文件内容索引 / 小文件即时扫描，并把搜索范围、命中行、截断状态返回给模型和用户。

### BF-17 P1：工作区文件移动/回收站依赖 renameTo 且不检查结果，DB 和磁盘可能不一致

用户视角：用户或 Agent 移动文件后，文件树显示成功，但实际文件可能仍在原处或丢失。后续 read / patch / diff 会出现非常难懂的错误。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/WorkspaceRepository.kt:105`
- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/WorkspaceRepository.kt:137`
- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/WorkspaceRepository.kt:202`
- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/WorkspaceRepository.kt:223`
- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/WorkspaceRepository.kt:282`
- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/WorkspaceRepository.kt:308`

问题说明：

- `File.renameTo()` 在 Android / 不同挂载点 / 权限场景下可能失败。
- 当前调用没有检查返回值，也没有回滚数据库更新。

建议：

- 使用可校验的 copy + fsync + delete 或 `Files.move` 可用分支。
- 文件系统操作和 DB 更新要具备事务边界或补偿逻辑。

### BF-18 P1：diff_file 在旧 basisHash 下会把基线重建为空，产生巨大假 diff

用户视角：Agent 查看差异时可能以为整个文件都是新增，进而给出错误补丁或错误解释。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/FileOperationRepository.kt:105`
- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/FileOperationRepository.kt:267-277`

问题说明：

- 当传入的 `basisHash` 与当前文件 hash 不一致时，`reconstructBasisContent()` 返回空字符串。
- 这会让 diff 变成“空文件到当前文件”的全量差异。

建议：

- 无法重建基线时应返回明确错误，要求调用方先 `read_file`。
- 不应用空字符串伪造历史版本。

### BF-19 P1：工作区写入 / patch 后没有重建索引，知识库和文件系统可能脱节

用户视角：Agent 刚写入或修改了文件，但 RAG 或工作区搜索仍搜不到最新内容；用户会认为“它改了文件却不知道自己改了什么”。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/FileOperationRepository.kt:23-59`
- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/FileOperationRepository.kt:133-265`

问题说明：

- 写入和 patch 后有重新索引 TODO。
- 这会影响后续 RAG、全文搜索和 Agent 文件理解。

建议：

- 文件写入成功后发布索引失效事件。
- UI 显示“索引中 / 已索引 / 索引失败”。

### BF-20 P1：任务计划 move_step 可制造循环，树构建没有防环

用户视角：任务面板可能卡死、崩溃或任务树无限展开。Agent 修改任务计划后，用户很难理解为什么整个任务面板坏掉。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/TaskRepository.kt:118-135`
- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/TaskRepository.kt:245-281`

问题说明：

- `move_step` 没有阻止把节点移动到自身或后代下。
- `buildTree()` 没有 visited set。

建议：

- 移动前做祖先链校验。
- 树构建加入 cycle detection，异常时返回可恢复错误。

### BF-21 P1：任务批量更新不是事务，部分成功会让计划进入半更新状态

用户视角：Agent 一次改多个步骤，界面显示一部分改了，一部分失败，父子状态可能不一致。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/TaskRepository.kt:71-156`

问题说明：

- 多个 operation 逐个执行。
- 中途失败没有整体回滚。

建议：

- `updatePlan` 批处理应放入数据库事务。
- 返回结果中展示每个 operation 的成功 / 失败和回滚状态。

### BF-22 P1：任务状态字符串不一致，部分放弃状态可能不显示

用户视角：计划里有“部分放弃”的真实状态，但 UI 不显示对应标签，造成任务进度判断失真。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/data/repository/TaskRepository.kt:191`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/components/TaskFloatingPanel.kt:195`

问题说明：

- Repository 产出 `partial_dropped`。
- UI 判断的是 `partial-dropped`。

建议：

- 任务状态改为 enum / sealed class，禁止散落字符串。

## 7. 低到中优先级问题

### BF-23 P2：MCP 工具列表只增不删，工具可用性可能陈旧

用户视角：某个 MCP server 断开或工具被移除后，模型仍可能看到旧工具，调用后失败。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/registry/McpSkillRegistry.kt:44-53`

建议：

- 按 server 做全量替换，并记录连接状态、同步时间、错误原因。

### BF-24 P2：工具启用使用全局 SharedPreferences，而不是会话级 activeSkillIds

用户视角：用户可能以为某个会话配置了专属工具集，但实际工具列表由全局设置控制。不同会话之间的工具边界不清晰。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt:1207-1225`

建议：

- 明确工具启用层级：全局默认、会话覆盖、临时本轮启用。
- Prompt 中也应标注当前可用工具来自哪个层级。

### BF-25 P2：高风险工具名单过窄

用户视角：用户开启审批模式后，仍可能有一些会修改工作区、丢弃计划或访问内部网络的工具没有触发确认。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt:1705-1719`

问题说明：

- 高风险工具包含 `write_file`、`exec_js`、`generate_image`、`create_tool`。
- `patch_file`、`drop_plan`、`web_fetch` 等也可能有明显风险。

建议：

- 将风险级别放进 skill metadata。
- UI 显示“本轮即将执行的高风险动作”。

### BF-26 P2：web_fetch 缺少协议、域名和内网访问保护

用户视角：模型如果被提示注入诱导，可能访问本机、路由器、内网服务或云元数据地址；用户界面很难察觉风险。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/skills/WebFetchSkill.kt:14-15`

建议：

- 限制协议为 http / https。
- 默认阻止 localhost、私有网段、链路本地地址、file scheme。
- 高风险 URL 触发二次确认。

### BF-27 P2：RAG 阶段卡片会初始化全套 pending，禁用状态和失败状态不够可见

用户视角：即使 RAG 或 KG 没有真正启用，用户也可能看到类似检索流程的 UI，难以判断“到底查没查”。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt:318-334`
- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/ContextBuilder.kt:59-88`

建议：

- 卡片阶段应区分 disabled / skipped / running / completed / error。
- 如果 KG 因无 RAG references 被跳过，应明确显示“图谱未启用：没有检索结果可扩展”。

### BF-28 P2：KG 只在 RAG 有引用后启用，不能独立参与生成链路

用户视角：用户开启知识图谱后，如果 RAG 没有返回引用，KG 也不会工作。用户会以为图谱坏了。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/ContextBuilder.kt:68`

建议：

- 明确 KG 模式：基于 RAG 扩展，还是可独立图谱查询。
- UI 文案应说明当前 KG 依赖检索结果。

### BF-29 P2：HTML block 被当普通文本渲染，富文本预期不稳定

用户视角：模型输出 HTML / XML 片段时，用户可能看到原文或异常排版，而不是结构化预览。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt:601-613`

建议：

- 如果安全策略是不渲染 HTML，应在设计上明确“代码 / 原文展示”。
- 如果要预览，应走安全沙箱组件，并限制脚本和外链。

### BF-30 P2：输入区只支持 image/*，与数据模型里的 audio / file 能力不一致

用户视角：应用看起来是多模态 Agent，但实际主输入只支持图片；文档、音频、普通文件没有统一入口。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt:471`
- `native-ui/app/src/main/java/com/promenar/nexara/data/remote/protocol/LlmProtocol.kt:39`

建议：

- 主输入区明确分层：图片、文件、音频、知识库导入、工作区文件引用。
- 没实现的类型不应在模型能力或产品文案里造成误导。

### BF-31 P2：发送循环达到上限时缺少用户可见解释

用户视角：Agent 执行多轮工具后突然停止，没有解释是达到 loop limit，用户会以为模型或网络卡住。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt:293-305`

建议：

- 达到 loop limit 时追加系统可见提示，并给出继续按钮或调整入口。

### BF-32 P3：生成中高频自动滚动可能带来性能和电量压力

用户视角：长回答生成时界面滚动很积极，低端设备可能掉帧、耗电或输入区响应变慢。

代码证据：

- `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt` 自动滚动逻辑需真机性能复验。

建议：

- 用帧同步或节流策略替代极高频循环。
- 用户手动上滑查看历史时应暂停自动追踪。

## 8. 做得较好的部分

以下设计值得保留并继续强化：

- 主对话流已经有较完整的 `ChatViewModel -> ContextBuilder -> Protocol -> ToolExecutor -> MessageManager` 分层。
- 工具执行步骤有可视化基础，用户能看到部分工具状态，而不是完全黑盒。
- RAG 引用、联网引用、KG path、token usage 等数据模型较完整，具备继续做可解释性的基础。
- 知识库导入、向量化队列、KG 抽取和文件系统能力已经搭出端到端框架。
- Markdown 渲染层已有专门测试，且本轮之前已修复本地模型压行和长按复制问题。

## 9. 建议修复顺序

第一阶段：修复“用户选择不生效”的主信任问题。

1. 修 BF-01、BF-02、BF-03：RAG 文档选择和 Prompt 拼接。
2. 修 BF-04、BF-09、BF-10、BF-11：协议层空白保真、Responses API、参数透传。
3. 修 BF-05、BF-06、BF-07、BF-08：多模态真实可用性和工具产物可见性。

第二阶段：修复“工具显示成功但状态不一致”的数据安全问题。

1. 修 BF-17、BF-18、BF-19：工作区文件一致性与索引。
2. 修 BF-20、BF-21、BF-22：任务计划事务、防环和状态类型。
3. 修 BF-14、BF-25、BF-26：工具幂等、审批和访问边界。

第三阶段：提升可观测性和用户理解。

1. 修 BF-12、BF-27、BF-28、BF-31：RAG/KG/联网/loop limit 的可见解释。
2. 修 BF-15、BF-16、BF-23、BF-24、BF-30：Skill、MCP、工作区、多模态入口的能力边界。

## 10. 后续交叉验证清单

建议后续交叉审计按以下用例真机验证：

- 文档 A 中答案在第 800 字之后，只选择文档 A，关闭全局检索，提问是否能命中。
- 选择文件夹作为知识范围，确认检索过滤是否生效。
- 本地 Qwen VL / 远程 VL 分别发送同一张图，抓请求确认图片是否真实进入模型。
- 带图消息长按重发，抓协议消息确认 `imageUrls` 是否仍存在。
- 选择 OpenAI Responses 协议，抓 URL 是否为 Responses endpoint。
- 设置 topK / repetition penalty / streamTimeout，分别走普通协议和 UnifiedLlmClient，确认参数是否一致。
- 模拟 SSE delta 以空格和换行开头，确认最终 Markdown 不丢空白。
- 让 image_generation 工具返回图片，确认聊天气泡和工具步骤都能看见缩略图。
- 对工作区文件执行 move / recycle / restore，并人为制造 rename 失败，确认 DB 不会假成功。
- 对任务计划执行把父节点移动到子节点下，确认被拒绝且 UI 不崩溃。
- `search_files(mode="fts")` 搜索文件正文，确认不是只搜文件名。
- web_fetch 访问 localhost / 私有 IP，确认默认阻止或请求用户确认。

## 11. 文档影响

本报告新增在 `docs/audit/20260706-business-flow-full-code-audit.md`。

需要同步：

- `.agent/registry.md`：注册本审计报告。
- `CHANGELOG.md`：记录审计报告落盘。
- `.agent/handover.md`：记录审计完成、主要风险和后续修复入口。
- `.agent/tmp-agent-reports/`：保留本轮模块级原始审计材料，供后续交叉验证使用。
