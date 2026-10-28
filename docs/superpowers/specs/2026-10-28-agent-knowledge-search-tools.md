# Agent 知识检索工具设计

- 状态：待评审，未实施
- 范围：`native-ui`（Android）
- 方向：给 Agent 只读的知识库与历史对话检索工具；写入仍由自动索引、自动图谱抽取和长期记忆工具负责

## 1. 目标与非目标

目标：

1. Agent 在任务任一步骤都能用自己拟定的查询词检索知识库文档、历史对话和知识图谱，不再只依赖每轮开始前按用户原话做的一次检索。
2. 工具检索与每轮自动检索共用同一条检索管线和同一套范围权限，结果质量与可见范围一致。
3. 助手可选择检索方式：每轮自动、仅由 Agent 调用工具，或两者并用；仅工具方式省去每轮前置检索，首字更快。
4. 工具检索的命中在界面上与自动检索一样可查看来源、分数和图谱路径。

非目标：

- 不让 Agent 写入或删除向量、FTS、图谱节点与关系，也不让 Agent 导入或删除知识库文档。需要“把网页存入知识库”时另立带审批的工具。
- 不把长期记忆并入检索工具。长期记忆条目少、目录常驻上下文，继续由 `memory_save`、`memory_forget`、`memory_read` 管理。
- 不单独提供图谱遍历工具。图谱路径随搜索结果返回；真机使用表明关系类问题确有需要时再增加。
- 不改变自动检索的现有行为与默认值。

## 2. 现状

| 能力 | 现状 | 位置 |
| --- | --- | --- |
| 自动检索 | 每轮生成前用用户本轮原文检索一次文档、对话记忆和图谱，结果注入上下文；同一轮后续工具循环复用这份结果，不再检索 | `ContextBuilder.retrieve()`；`DefaultChatGenerationRuntime` 中 `cachedRetrieval = reusedRetrieval` |
| 检索管线 | 混合检索（向量 + FTS）、可选查询改写（默认 multi-query，3 条）、重排 | `MemoryManager.retrieveContext`，经 `MemoryManagerRagAdapter` 实现 `RagProvider` |
| 图谱检索 | 先查持久图；种子不足或无路径且有可见命中片段时，可触发 JIT 临时抽取（一次 LLM 请求） | `PersistentGraphKgProvider`、`KgProvider` |
| 检索范围 | 对话记忆与图谱对话证据分本会话 / 本助手 / 全部助手；文档分本会话工作区 / 加全局知识库 / 全部已索引文档；会话选择按助手上限收紧 | `RetrievalScopeResolver`、`AgentMemoryPolicy.clamp`、`ConversationScope`、`DocumentScope` |
| 生效配置 | 会话选项、临时选项与助手检索配置合并后按助手上限收紧 | `ContextBuilder.computeEffectiveRagOptions` |
| 对话图谱抽取 | 每轮结束后自动排队 | `SessionGraphExtraction.schedule` |
| 长期记忆 | Agent 主动读写的唯一知识通道，条目目录常驻上下文（内容预算 6000 字符） | `LongTermMemorySkills`、`LongTermMemoryContext` |
| 文件工具 | `read_file`、`search_files` 只作用于当前会话工作区根（`workspaceRootUuid`）；`search_files` 是文件名或逐行关键词匹配，不走向量检索 | `FileReadSkill`、`FileSearchSkill` |
| 先例 | 联网搜索同时有每轮被动搜索（会话开关）和 `web_search` 工具 | `ContextBuilder.retrieve()`、`WebSearchSkill` |
| 已有界面映射 | `knowledge_search` 已映射为“检索了知识库”步骤类别，文案已存在 | `ToolInvocationPresentation.toolSummaryAction`、`PipelineBubble` |

知识库文档以工作区文件存储，全局知识库是系统助手（`__system__`）会话的工作区根。因此全局知识库中的文档不在当前会话工作区内，`read_file` 读不到，命中片段需要扩读时必须另有入口。

## 3. 设计

### 3.1 工具集

新增两个内置工具，风险等级均为 `ToolRisk.SAFE_READ`，无需审批：

- `knowledge_search`：检索知识库文档与历史对话，附带相关图谱路径。
- `knowledge_read`：按命中片段读取同一文档的相邻片段，用于扩读上下文。

工具名沿用已有界面映射 `knowledge_search`；`knowledge_read` 归入同一“检索”步骤类别。

### 3.2 `knowledge_search`

参数：

```json
{
  "type": "object",
  "properties": {
    "query": { "type": "string", "description": "检索语句，写成完整的问题或关键短语，而不是单个词" },
    "sources": {
      "type": "array",
      "items": { "enum": ["documents", "history"] },
      "description": "documents 为知识库与工作区文档，history 为历史对话；默认两者都查"
    },
    "limit": { "type": "integer", "minimum": 1, "maximum": 10, "default": 5 }
  },
  "required": ["query"]
}
```

工具不提供范围参数。可见范围一律取本会话的生效检索配置，Agent 无法扩大，也无需自行缩小。

执行：

1. 用与自动检索相同的合并与收紧逻辑得到生效 `RagOptions`。从 `ContextBuilder.computeEffectiveRagOptions` 抽出可复用的解析函数，自动检索与工具都调用它，避免两套范围判定。
2. 按 `sources` 覆盖 `enableDocs` / `enableMemory`；某一来源被助手或会话关闭时，即使 `sources` 请求也不检索，并在结果里说明该来源已关闭。
3. 关闭查询改写：查询词由 Agent 拟定，再改写只增加一次 LLM 请求和多条嵌入。
4. 重排沿用生效配置。
5. 图谱启用时只查持久图，不触发 JIT 抽取，避免一次搜索隐含一次 LLM 抽取请求。
6. `limit` 作为最终返回条数，上限 10。

返回给模型的文本按条列出，每条带稳定引用编号，供 `knowledge_read` 和回答引用：

```text
[K1] 文档《部署手册.md》 片段 12 · 相关度 0.82
……片段正文……

[K2] 历史对话「接口迁移讨论」 2026-10-12 · 相关度 0.74
……片段正文……

图谱：部署脚本 —依赖→ 签名配置；签名配置 —存放于→ secure_env
```

`ToolResult.data` 携带结构化结果：`List<RagReference>` 与 `List<KgPath>`，供界面展示。引用编号与 `RagReference.id` 一一对应，在同一轮内稳定。

无结果时返回明确的“未找到相关内容”与已检索的来源，状态为成功而非错误，避免模型误以为工具故障而反复重试。未配置嵌入模型时返回错误与配置指引，不触发自动检索已有的一次性配置引导弹窗。

### 3.3 `knowledge_read`

参数：`ref`（本轮 `knowledge_search` 返回的引用编号或 `RagReference.id`）、`before`（默认 1，最大 3）、`after`（默认 1，最大 3）。

执行：按引用定位 `documentId` 与 `chunkIndex`，用 `VectorDao` 取同一文档的有效片段并按 `chunkIndex` 排序，返回相邻片段原文。读取前重新按生效范围校验该文档可见，范围变化后不可读。只支持文档片段；历史对话片段返回提示，说明历史对话不支持扩读。

当前会话工作区内的文档也可用 `read_file` 读全文；工具说明里写明：工作区文件需要全文时用 `read_file`。

### 3.4 检索方式

在 `AgentRetrievalConfig` 增加 `retrievalMode`：

| 取值 | 每轮自动检索 | 提供检索工具 | 适用 |
| --- | --- | --- | --- |
| `AUTO`（默认） | 是 | 否 | 普通对话、不支持工具调用的模型 |
| `TOOL` | 否 | 是 | Agent 任务，追求首字速度 |
| `BOTH` | 是 | 是 | Agent 任务，希望首轮就有背景资料 |

规则：

- 会话关闭工具（`toolsEnabled = false`）时，`TOOL` 与 `BOTH` 都按 `AUTO` 执行，保证检索不丢失。
- `TOOL` 模式下 `ContextBuilder.retrieve()` 跳过文档、对话记忆和图谱检索；联网被动搜索不受影响，仍由会话开关决定。
- 检索工具只在 `TOOL` 或 `BOTH` 下注册到会话工具列表，由 `SessionToolResolver` 按生效检索方式过滤，做法与长期记忆工具的过滤一致。
- `BOTH` 模式下，工具结果中与本轮自动检索已注入的片段相同的条目标注“已在上下文中”，只返回编号不重复正文。
- 首版只做助手级设置，不做会话级覆盖。

### 3.5 知识库目录

`TOOL` 或 `BOTH` 下，在系统上下文中加入一段简短的可检索资料目录，让模型知道有资料可查：

- 可见范围内的知识库与工作区名称，以及各自已索引文档数；
- 文档标题最多 30 个，按最近更新排序，超出部分只给总数；
- 历史对话只给可检索的范围说明（本会话 / 本助手 / 全部助手）与会话数，不列标题；
- 整段字符预算 1500。

目录只描述资料，不包含身份、品牌或角色措辞。可见范围内没有任何已索引资料时不注入目录，同时不注册检索工具。

### 3.6 成本控制

- 每次 `knowledge_search` 至少一次嵌入请求；开启重排时再加一次重排请求；不做查询改写，不触发 JIT 抽取。
- 每轮工具检索次数上限 6 次，超出返回提示，要求模型基于已有结果作答。
- `knowledge_read` 不调用任何模型接口。
- 用量计入现有 RAG 用量统计，与自动检索合并展示。

### 3.7 界面

- 步骤行沿用 `ToolSummaryAction.KNOWLEDGE_SEARCH` 的“检索了知识库”文案；`knowledge_read` 显示为“读取了知识库片段”。
- 点开工具步骤时，用 `ToolResult.data` 中的引用与图谱路径打开现有 `RagDetailsSheet`，展示方式与自动检索一致。
- 助手检索设置页增加“检索方式”三选一，说明各自对首字速度和工具调用的影响。原型先行，经用户确认后实施。

### 3.8 与长期记忆的边界

工具说明中写明分工，避免模型混用：

- `memory_*`：关于用户的长期事实与偏好，由 Agent 判断后主动保存，条目目录已在上下文中。
- `knowledge_search`：查找用户导入的文档和过往对话里讨论过的内容，只读。

## 4. 预计改动

| 区域 | 改动 |
| --- | --- |
| `data/agent/AgentConfigModels.kt` | `AgentRetrievalConfig.retrievalMode` 与序列化默认值 |
| `ui/chat/manager/ContextBuilder.kt` | 抽出生效检索配置解析函数；`TOOL` 模式跳过检索；注入资料目录 |
| `ui/chat/manager/skills/` | 新增 `KnowledgeSearchSkills.kt`（两个工具） |
| `data/rag/MemoryManager.kt` 及适配层 | 支持按调用关闭查询改写；图谱检索支持禁用 JIT |
| `data/generation/SessionToolResolver.kt` | 按生效检索方式过滤检索工具 |
| `ui/chat/ToolInvocationPresentation.kt`、`PipelineBubble.kt` | `knowledge_read` 映射与文案；工具步骤打开 `RagDetailsSheet` |
| 助手检索设置页 | 检索方式选项 |
| 备份 | `retrievalMode` 随助手配置备份与恢复 |

实施时需确认 `SkillExecutionContext` 能取到会话、助手检索配置与助手权限；取不到时通过工具构造注入所需仓储。

## 5. 测试与验收

单元测试：

- 生效范围：工具与自动检索对同一会话解析出相同范围；会话选择超出助手上限时按上限执行；私密助手的对话不出现在其他助手的工具结果中。
- `sources` 请求已关闭来源时不检索并说明原因。
- 工具检索不调用查询改写，不触发 JIT 抽取。
- `knowledge_read` 返回相邻片段且顺序正确；文档移出范围或进入回收站后不可读；历史对话引用返回提示。
- 检索方式：`TOOL` 跳过前置检索；关闭工具时回退为 `AUTO`；没有可检索资料时不注册工具、不注入目录。
- 每轮次数上限生效。
- `BOTH` 下重复片段标注“已在上下文中”。
- `retrievalMode` 旧配置解码为 `AUTO`，备份往返一致。

真机验收：

- 同一份知识库，`AUTO` 与 `TOOL` 各跑一组多步 Agent 任务，对比首字时间、回答是否引用到正确文档、模型是否在需要时主动检索。至少覆盖 DeepSeek V4 Flash 与 Qwen3.8 Flash Next。
- 工具步骤可打开检索详情，来源、分数与图谱路径正确。

## 6. 风险与待定

- 部分模型在 `TOOL` 模式下该查不查。资料目录与工具说明缓解；真机对比后再决定 Agent 类助手是否默认改为 `TOOL`。
- 嵌入与重排若使用付费服务，工具检索会增加费用；每轮上限之外是否需要用户可调上限，待真机数据决定。
- 去掉查询改写后，模型拟定的查询词质量直接决定召回。真机对比召回率，必要时允许工具调用时可选开启改写。
- 图谱只查持久图，新导入文档在图谱抽取完成前只能召回片段，没有图谱路径。

## 7. 分工

检索管线、范围解析、工具实现与测试属于后端，交 Codex 实施；设置页检索方式选项与工具步骤展示由 Claude 先出原型，经用户确认后实施。
