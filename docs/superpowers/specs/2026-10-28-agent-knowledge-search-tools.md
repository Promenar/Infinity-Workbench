# Agent 知识检索工具设计

- 状态：待评审，未实施
- 范围：`native-ui`（Android）
- 方向：给 Agent 只读的知识库与历史对话检索工具，以及按授权开放的知识库文件访问；向量、FTS 与图谱的写入仍由自动索引、自动图谱抽取负责，用户长期事实仍由长期记忆工具负责

## 1. 目标与非目标

目标：

1. Agent 在任务任一步骤都能用自己拟定的查询词检索知识库文档、历史对话和知识图谱，不只依赖每轮开始前按用户原话做的一次检索。
2. 工具检索与每轮自动检索共用同一条检索管线和同一套范围权限，结果质量与可见范围一致。
3. 助手可选择检索方式：每轮自动、仅由 Agent 调用工具，或两者并用；仅工具方式省去每轮前置检索，首字更快。
4. 全局知识库的文件可以作为只读挂载开放给 Agent，用现有文件工具读全文、按行读取和搜索。默认不开放，由用户按需开启，或由 Agent 申请、用户批准后开放。
5. 工具检索的命中在界面上与自动检索一样可查看来源、分数和图谱路径。

非目标：

- 不让 Agent 写入或删除向量、FTS、图谱节点与关系。
- 知识库挂载首版只读。Agent 写入、移动、删除知识库文件不在首版范围；需要时另行设计读写挂载及其审批。
- 不把长期记忆并入检索工具。长期记忆条目少、目录常驻上下文，由 `memory_save`、`memory_forget`、`memory_read` 管理。
- 不单独提供图谱遍历工具。图谱路径随搜索结果返回；真机使用表明关系类问题确有需要时再增加。
- 不改变自动检索的现有行为与默认值。

## 2. 现状

| 能力 | 现状 | 位置 |
| --- | --- | --- |
| 自动检索 | 每轮生成前用用户本轮原文检索一次文档、对话记忆和图谱，结果注入上下文；同一轮后续工具循环复用这份结果，不重新检索 | `ContextBuilder.retrieve()`；`DefaultChatGenerationRuntime` 中 `cachedRetrieval = reusedRetrieval` |
| 检索管线 | 混合检索（向量 + FTS）、可选查询改写（默认 multi-query，3 条）、重排 | `MemoryManager.retrieveContext`，经 `MemoryManagerRagAdapter` 实现 `RagProvider` |
| 图谱检索 | 先查持久图；种子不足或无路径且有可见命中片段时，可触发 JIT 临时抽取（一次 LLM 请求） | `PersistentGraphKgProvider`、`KgProvider` |
| 检索范围 | 对话记忆与图谱对话证据分本会话 / 本助手 / 全部助手；文档分本会话工作区 / 加全局知识库 / 全部已索引文档；会话选择按助手上限收紧 | `RetrievalScopeResolver`、`AgentMemoryPolicy.clamp`、`ConversationScope`、`DocumentScope` |
| 生效配置 | 会话选项、临时选项与助手检索配置合并后按助手上限收紧 | `ContextBuilder.computeEffectiveRagOptions` |
| 对话图谱抽取 | 每轮结束后自动排队 | `SessionGraphExtraction.schedule` |
| 长期记忆 | Agent 主动读写的唯一知识通道，条目目录常驻上下文（内容预算 6000 字符） | `LongTermMemorySkills`、`LongTermMemoryContext` |
| 文件工具 | `read_file`、`search_files` 作用于当前会话工作区根，以及会话挂载的 `/mnt/<别名>/...` 与 `/skills/...`；`search_files` 是文件名或逐行关键词匹配，不走向量检索 | `FileReadSkill`、`FileSearchSkill`、`MountedWorkspaceSkill` |
| 会话挂载 | 每个会话独立的挂载表 `external_mounts`；来源类型有 `saf_tree`（系统授权目录）、`real_path`（设备路径，仅侧载渠道）和 `workspace`（当前会话工作区根）。表中已有 `read_only` 列，但写入时恒为 `false`，领域模型 `ExternalMount` 不含该字段，后端不做只读拦截 | `ExternalFolderManager`、`ExternalMountPaths`、`ExternalMountEntity` |
| 知识库存储 | 全局知识库是系统助手（`__system__`）会话的目录型工作区，可有多个根 | `RagWorkspaceProvisioner.listAvailableRoots`、`RetrievalScopeResolver.knowledgeBaseRoots` |
| 审批 | `ToolRisk.SCHEDULE` 在所有执行模式下都需用户批准，其余风险按执行模式和参数规则判定 | `ToolExecutionPolicy.requiresApproval` |
| 联网搜索 | 只靠 `web_search` 工具。`SessionOptions.webSearch` 的被动预注入默认关闭，界面没有开启入口 | `WebSearchSkill`、`ChatModels.SessionOptions` |
| 已有界面映射 | `knowledge_search` 已映射为“检索了知识库”步骤类别，文案已存在 | `ToolInvocationPresentation.toolSummaryAction`、`PipelineBubble` |

全局知识库的文件不在当前会话工作区内，未挂载时 `read_file` 读不到。

## 3. 设计

### 3.1 工具集

| 工具 | 作用 | 风险等级 | 审批 |
| --- | --- | --- | --- |
| `knowledge_search` | 检索知识库与工作区文档、历史对话，附带图谱路径 | `SAFE_READ` | 不需要 |
| `request_knowledge_access` | 申请把全局知识库只读挂载到当前会话 | 新增 `ACCESS_GRANT` | 所有执行模式都需要 |

知识库挂载后，全文读取、按行读取、文件名与内容搜索都用现有 `read_file`、`list_files`、`search_files`，不另设扩读工具。

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

工具不提供范围参数。可见范围一律取本会话的生效检索配置，Agent 无法扩大。

执行：

1. 用与自动检索相同的合并与收紧逻辑得到生效 `RagOptions`。从 `ContextBuilder.computeEffectiveRagOptions` 抽出可复用的解析函数，自动检索与工具都调用它，避免两套范围判定。
2. 按 `sources` 覆盖 `enableDocs` / `enableMemory`；某一来源被助手或会话关闭时，即使 `sources` 请求也不检索，并在结果里说明该来源已关闭。
3. 关闭查询改写：查询词由 Agent 拟定，再改写只增加一次 LLM 请求和多条嵌入。
4. 重排沿用生效配置。
5. 图谱启用时只查持久图，不触发 JIT 抽取，避免一次搜索隐含一次 LLM 抽取请求。
6. `limit` 作为最终返回条数，上限 10。

返回给模型的文本按条列出，每条带引用编号；文档命中附带当前会话可用的读取路径：

```text
[K1] 文档《部署手册.md》 片段 12 · 相关度 0.82 · 路径 /mnt/knowledge/运维/部署手册.md
……片段正文……

[K2] 文档《接口说明.md》 片段 3 · 相关度 0.77 · 知识库未挂载，需要全文时调用 request_knowledge_access

[K3] 历史对话「接口迁移讨论」 2026-10-12 · 相关度 0.74
……片段正文……

图谱：部署脚本 —依赖→ 签名配置；签名配置 —存放于→ secure_env
```

读取路径规则：

- 当前会话工作区内的文档：工作区内路径，直接可用 `read_file`。
- 全局知识库文档：已挂载时给 `/mnt/<别名>/...` 路径；未挂载时提示申请访问，助手设置为“关闭”时不提示。
- 其它会话工作区的文档（仅“全部已索引文档”范围可见）：只给片段，不给路径。

`ToolResult.data` 携带结构化结果：`List<RagReference>` 与 `List<KgPath>`，供界面展示。引用编号与 `RagReference.id` 一一对应，在同一轮内稳定。

无结果时返回明确的“未找到相关内容”与已检索的来源，状态为成功而非错误，避免模型误以为工具故障而反复重试。未配置嵌入模型时返回错误与配置指引，不触发自动检索的一次性配置引导弹窗。

### 3.3 知识库文件访问

助手级设置 `knowledgeFileAccess`：

| 取值 | 行为 |
| --- | --- |
| `OFF` | 不挂载，不注册 `request_knowledge_access`；用户仍可在会话的外部文件夹面板手动挂载 |
| `ON_REQUEST`（默认） | 不预先挂载；注册 `request_knowledge_access`，Agent 申请、用户批准后挂载 |
| `ALWAYS` | 新会话创建时自动只读挂载，不注册申请工具 |

挂载形态：

- 新增挂载来源类型 `knowledge_base`，`sourceId` 为知识库工作区根 UUID，后端复用 `workspace` 来源的目录型工作区解析。
- 每个知识库根一个挂载；默认根别名 `knowledge`，其余根由标题生成别名，冲突时加序号。
- 挂载记录的 `read_only` 置为 `true`。领域模型 `ExternalMount` 增加 `readOnly`，`ExternalFolderManager` 在写入、建目录、删除、移动入口统一拦截只读挂载，返回 `READ_ONLY` 错误码与“知识库挂载为只读”说明。表中已有该列，无需迁移表结构。
- 只对文件工具可见，`commandAccessible` 为 `false`，命令后端不暴露知识库路径。
- 挂载随会话存在；会话删除时随 `deleteSessionMounts` 清理。

`request_knowledge_access`：

- 参数：`reason`（必填，向用户说明为什么需要全文）、`knowledge_base`（可选，知识库名称；省略时为全部可见知识库）。
- 审批卡显示申请原因、将挂载的知识库名称与只读说明。批准后创建挂载并返回可用路径；拒绝时返回“用户未授权”，同一轮内再次申请直接返回拒绝结果，不重复弹卡。
- 只挂载当前会话文档范围内可见的知识库根：文档范围为“本会话工作区”时不挂载任何知识库，并返回范围说明。
- 用户可在外部文件夹面板随时移除挂载。

挂载信息进入现有系统提示中的挂载说明，Agent 能看到 `/mnt/knowledge` 及其只读属性。

### 3.4 检索方式

在 `AgentRetrievalConfig` 增加 `retrievalMode`：

| 取值 | 每轮自动检索 | 提供 `knowledge_search` | 适用 |
| --- | --- | --- | --- |
| `AUTO`（默认） | 是 | 否 | 普通对话、不支持工具调用的模型 |
| `TOOL` | 否 | 是 | Agent 任务，追求首字速度 |
| `BOTH` | 是 | 是 | Agent 任务，希望首轮就有背景资料 |

规则：

- 会话关闭工具（`toolsEnabled = false`）时，`TOOL` 与 `BOTH` 都按 `AUTO` 执行，保证检索不丢失；`knowledgeFileAccess` 同时失效。
- `TOOL` 模式下 `ContextBuilder.retrieve()` 跳过文档、对话记忆和图谱检索。
- `knowledge_search` 只在 `TOOL` 或 `BOTH` 下注册，`request_knowledge_access` 按 `knowledgeFileAccess` 注册，均由 `SessionToolResolver` 过滤，做法与长期记忆工具的过滤一致。
- `BOTH` 模式下，工具结果中与本轮自动检索已注入的片段相同的条目标注“已在上下文中”，只返回编号不重复正文。
- 首版只做助手级设置，不做会话级覆盖。

### 3.5 资料目录

`TOOL` 或 `BOTH` 下，在系统上下文中加入一段简短的可检索资料目录，让模型知道有资料可查：

- 可见范围内的知识库与工作区名称、各自已索引文档数，以及知识库是否已挂载；
- 文档标题最多 30 个，按最近更新排序，超出部分只给总数；
- 历史对话只给可检索的范围说明（本会话 / 本助手 / 全部助手）与会话数，不列标题；
- 整段字符预算 1500。

目录只描述资料，不包含身份、品牌或角色措辞。可见范围内没有任何已索引资料时不注入目录，同时不注册 `knowledge_search`。

### 3.6 成本控制

- 每次 `knowledge_search` 至少一次嵌入请求；开启重排时再加一次重排请求；不做查询改写，不触发 JIT 抽取。
- 每轮 `knowledge_search` 次数上限 6 次，超出返回提示，要求模型基于已有结果作答。
- 知识库挂载后的文件读取不调用任何模型接口。
- 用量计入现有 RAG 用量统计，与自动检索合并展示。

### 3.7 界面

- 步骤行沿用 `ToolSummaryAction.KNOWLEDGE_SEARCH` 的“检索了知识库”文案。
- 点开 `knowledge_search` 步骤时，用 `ToolResult.data` 中的引用与图谱路径打开现有 `RagDetailsSheet`，展示方式与自动检索一致。
- `request_knowledge_access` 使用现有审批卡，展示原因、知识库名称与只读标识。
- 外部文件夹面板与“Agent 可达范围”面板中，知识库挂载显示为“知识库 · 只读”。
- 助手检索设置页增加“检索方式”三选一与“知识库文件访问”三选一，说明各自对首字速度、工具调用和授权的影响。原型先行，经用户确认后实施。

### 3.8 与长期记忆的边界

工具说明中写明分工，避免模型混用：

- `memory_*`：关于用户的长期事实与偏好，由 Agent 判断后主动保存，条目目录已在上下文中。
- `knowledge_search`：查找用户导入的文档和过往对话里讨论过的内容，只读。
- 知识库挂载：读取知识库文档全文，只读。

## 4. 预计改动

| 区域 | 改动 |
| --- | --- |
| `data/agent/AgentConfigModels.kt` | `AgentRetrievalConfig.retrievalMode`、`knowledgeFileAccess` 与序列化默认值 |
| `ui/chat/manager/ContextBuilder.kt` | 抽出生效检索配置解析函数；`TOOL` 模式跳过检索；注入资料目录 |
| `ui/chat/manager/skills/` | 新增 `KnowledgeSearchSkills.kt`（`knowledge_search`、`request_knowledge_access`） |
| `domain/tool/ToolExecutionPolicy.kt` | 新增 `ToolRisk.ACCESS_GRANT`，所有模式需审批 |
| `domain/storage/ExternalMount.kt`、`data/storage/ExternalFolderManager.kt`、`ExternalMountPaths.kt` | `readOnly` 字段与写入拦截；`knowledge_base` 来源类型与别名规则 |
| `data/rag/MemoryManager.kt` 及适配层 | 支持按调用关闭查询改写；图谱检索支持禁用 JIT |
| `data/generation/SessionToolResolver.kt` | 按检索方式与文件访问设置过滤工具 |
| `ui/chat/ToolInvocationPresentation.kt`、审批卡、外部文件夹与可达范围面板 | 新工具映射、审批展示、只读挂载标识 |
| 助手检索设置页 | 两个新选项 |
| 备份 | 新配置项随助手配置备份与恢复 |

实施时需确认 `SkillExecutionContext` 能取到会话、助手检索配置与助手权限；取不到时通过工具构造注入所需仓储。

## 5. 测试与验收

单元测试：

- 生效范围：工具与自动检索对同一会话解析出相同范围；会话选择超出助手上限时按上限执行；私密助手的对话不出现在其他助手的工具结果中。
- `sources` 请求已关闭来源时不检索并说明原因。
- 工具检索不调用查询改写，不触发 JIT 抽取。
- 读取路径：工作区文档给工作区路径；知识库文档已挂载给挂载路径、未挂载给申请提示、`OFF` 时无提示；其它会话工作区文档无路径。
- 只读挂载：写入、建目录、删除、移动均返回 `READ_ONLY`，读取与搜索正常；`commandAccessible` 为 `false`。
- `request_knowledge_access`：所有执行模式都进入审批；批准后挂载可用；拒绝后同一轮不重复弹卡；文档范围为“本会话工作区”时不挂载。
- `ALWAYS` 新会话自动挂载；`OFF` 不注册申请工具；关闭工具时两个设置都失效。
- 检索方式：`TOOL` 跳过前置检索；关闭工具时回退为 `AUTO`；没有可检索资料时不注册 `knowledge_search`、不注入目录。
- 每轮次数上限生效；`BOTH` 下重复片段标注“已在上下文中”。
- 新配置项旧数据解码为默认值，备份往返一致。

真机验收：

- 同一份知识库，`AUTO` 与 `TOOL` 各跑一组多步 Agent 任务，对比首字时间、回答是否引用到正确文档、模型是否在需要时主动检索。至少覆盖 DeepSeek V4 Flash 与 Qwen3.8 Flash Next。
- `ON_REQUEST` 下让 Agent 需要某文档全文，确认弹出审批卡、批准后能 `read_file` 读取、尝试写入被拒绝。
- 工具步骤可打开检索详情，来源、分数与图谱路径正确。

## 6. 风险与待定

- 部分模型在 `TOOL` 模式下该查不查。资料目录与工具说明缓解；真机对比后再决定 Agent 类助手是否默认改为 `TOOL`。
- 嵌入与重排若使用付费服务，工具检索会增加费用；每轮上限之外是否需要用户可调上限，待真机数据决定。
- 去掉查询改写后，模型拟定的查询词质量直接决定召回。真机对比召回率，必要时允许工具调用时可选开启改写。
- 图谱只查持久图，新导入文档在图谱抽取完成前只能召回片段，没有图谱路径。
- 只读拦截必须覆盖所有写入口，包括脚本网关与分支 Linux 文件命名空间；遗漏任一入口都会让 Agent 绕过只读。实施时逐一列出 `ExternalFolderBackend` 的写方法并加测试。
- 知识库挂载让 Agent 能读到检索阈值以下、从未被召回的文档内容。这是授权后的预期能力，审批卡需写明“可读取该知识库全部文件”。

## 7. 分工

检索管线、范围解析、挂载只读与审批、工具实现与测试属于后端，交 Codex 实施；设置页两个选项、审批卡与挂载标识的展示由 Claude 先出原型，经用户确认后实施。
