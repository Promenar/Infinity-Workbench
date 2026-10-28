# 设置整理与知识检索实施拆波

- 状态：第一波完成（W1-B 已合并 3ea269dc，W1-P 原型已确认），第二波实施中；W1-P2 原型已确认
- 依据：`docs/superpowers/specs/2026-10-28-settings-ia-audit.md`（问题编号与目标结构）、`docs/superpowers/specs/2026-10-28-settings-ia-prototype-decisions.md`（已确认的界面决定）、`docs/superpowers/specs/2026-10-28-agent-knowledge-search-tools.md`（知识检索设计）
- 执行方式：主控会话负责沟通、派发、合并、全量门禁与真机测试；每个工作包由一个分支会话在独立 worktree 中实施。后端工作包由分支会话经 Codex CLI 交 GPT 实现、分支会话复核；前端工作包由分支会话实现，界面先出原型经用户确认
- 通用约束：`.agent/tmp-agent-reports/common-rules.md`，下文“工作包规则”对其中两条做了覆盖

## 工作包规则

- 分支会话只跑定向单元测试与三目标编译（`:app:compileSideloadDebugKotlin :app:compilePlayDebugKotlin :app:compileSideloadDebugAndroidTestKotlin`），不跑完整单元测试、lint、截图校验，不打 APK，不连接真机或模拟器。
- 分支会话在自己的分支上提交，不推送；完成后回报主控，由主控合并。
- 新 worktree 缺 `native-ui/local.properties` 时，以 `ANDROID_HOME` 环境变量或复制主仓库同名文件解决，不读取其内容。
- 文案：新增文字放进本工作包自己的资源文件，中英文都要有。只有 W3-F 可以修改现有的 `strings.xml` 及其它已有资源文件中的既有条目。
- 术语：新文案一律使用中文“智能体”、英文 “Agent”。
- 不改 `CHANGELOG.md`、`README.md`、`docs/`、`.agent/handover.md`；需要补写的文档要点写进工作包报告，由主控统一撰写。
- 报告写到 `.agent/tmp-agent-reports/settings-ia/<工作包编号>-report.md`。

## 波次与依赖

| 波次 | 工作包 | 执行 | 依赖 | 并行 |
| --- | --- | --- | --- | --- |
| 1 | W1-B 检索配置收敛与会话行为修复 | 后端（Codex） | 无 | 与 W1-P 并行 |
| 1 | W1-P 设置信息架构原型 | 前端原型 | 无 | 与 W1-B 并行，不编译 |
| 1 | W1-P2 提供商模型页与悬浮菜单原型 | 前端原型 | 无 | 不编译 |
| 2 | W2-B 知识检索工具 | 后端（Codex） | W1-B 合并 | 与 W2-F 并行 |
| 2 | W2-F 全局设置与会话面板 | 前端 | W1-B 合并、W1-P 确认 | 与 W2-B 并行 |
| 2 | W2-M 提供商模型页与悬浮菜单 | 前端（含 Codex 后端小项） | W1-P2 确认 | W2-B、W2-F 之一完成后开始 |
| 3 | W3-F 智能体页、知识检索界面与文案统一 | 前端 | W2-B、W2-F 合并 | 单独 |
| 收尾 | 合并、全量门禁、签名包、真机测试、文档 | 主控 | 全部 | — |

同时编译的分支会话不超过 2 个。

## W1-B 检索配置收敛与会话行为修复

目标：取消智能体级检索参数覆盖，增加检索深度档位，修复会话行为问题，清理无入口的残留字段。只改数据、领域与 ViewModel 逻辑，不改 Compose 界面与字符串资源。

范围：

1. 取消智能体级检索参数覆盖（P1、D1、D2、S2）：
   - 运行时不再读取 `AgentRagConfig`、`Agent.useInheritedConfig` 及 `AgentRetrievalConfig` 中除 `memoryPolicy` 以外的字段；`ContextBuilder.applyAgentRetrievalConfig`、`AgentConfigResolver`、`MemoryManagerRagAdapter` 相应收敛，检索参数一律来自全局 `rag_settings` 与会话 `RagOptions` 的开关与范围。
   - `AgentMemoryPolicy` 的上限收紧与新会话默认保持不变。
   - 旧数据照常解码，不做数据库迁移。
2. 检索深度档位：新增纯函数模块，定义 `RetrievalDepth { LEAN, BALANCED, COMPREHENSIVE }` 与四个原始值的映射（记忆数量 / 文档数量 / 召回候选数 / 最终结果数：精简 3/4/20/3，均衡 5/8/30/5，全面 8/15/60/10），提供“套用档位”与“由当前值判定档位，不匹配返回自定义”两个函数，并在全局 RAG 配置读写层暴露。不新增持久化键。
3. 智能体默认工作区（P2）：`SessionListViewModel.createSession` 与 `/new` 一致，带入智能体的 `defaultWorkspaceId`；两条新建路径抽出同一个会话初值构造函数。
4. 切换模型不改工具开关（S1）：`SessionManager.updateSessionModel` 只改模型。
5. 编辑页删除智能体与首页一致，先关闭其会话（D3）。
6. 关闭工具时保留联网搜索（P5）：
   - `SessionOptions` 新增 `keepWebSearchWhenToolsOff`，默认 `true`。
   - `toolsEnabled = false` 且该项为真、内置 `web_search` 全局启用时，`SessionToolResolver` 只返回 `web_search`，工具执行层只放行它；长期记忆、状态板等其它工具相关上下文仍随工具关闭而关闭。
   - 停止发送 Gemini 原生联网搜索（`enableGeminiSearch`），字段保留解码。
7. 清理残留（P6、P7、D6）：移除被动联网搜索读取路径（`SessionOptions.webSearch`）、会话 `RagOptions` 中 12 个检索调优字段的读取、会话 `InferenceParams.streamTimeout` 的读取（以全局等待响应超时为准）。字段是否删除以旧会话 JSON 能否解码为准（`Mappers.kt` 的 `Json` 已开启 `ignoreUnknownKeys`），删除前确认所有解码入口。

不做：界面、字符串、知识检索工具、`retrievalMode` 与 `knowledgeFileAccess` 字段（属于 W2-B）；会话自定义技能开关的移除（界面调用方在 W2-F 一并删除）。

验收：

- 单元测试覆盖：智能体自定义检索参数不再影响检索；`memoryPolicy` 收紧仍生效；检索深度映射与判定（含自定义）；首页与 `/new` 新建会话初值一致（含默认工作区）；切换模型不改工具开关；关闭工具时只注册并放行 `web_search`、关闭保留项时无任何工具；旧会话 JSON（含 `webSearch`、12 个调优字段、`streamTimeout`、`enableGeminiSearch`）解码不失败。
- 受影响的现有测试同步更新，定向测试与三目标编译通过。

## W1-P 设置信息架构原型

目标：把目标结构做成可点击的界面原型，经用户确认后作为 W2-F、W3-F 的界面依据。只产出原型，不改仓库代码。

范围：

- 设置首页新分组：通用、模型、智能体运行、知识库、执行环境与连接、数据、关于。
- 知识库各页：新会话默认、检索（检索深度档位、混合搜索、查询改写）、模型、知识图谱（抽取预算、查看图谱）、每轮抽取、长期记忆、调试与高级（向量统计、清除、全部重新向量化、默认折叠的高级参数，改分块时的重建索引提示）。
- 执行环境与连接分组页。
- 智能体编辑页：基本信息与外观、模型与参数、提示词、新会话默认（执行模式、默认工作区、技能、MCP）、知识与记忆（检索方式、知识库文件访问、检索范围、私密智能体）、管理。
- 会话设置面板：工具页（启用工具、关闭工具时保留联网搜索、执行模式、MCP、时间注入、技能只读摘要与跳转）、上下文页（压缩、检索开关、检索范围、消息字号）。
- 知识检索：工具步骤行与检索详情、`request_knowledge_access` 审批卡、外部文件夹与 Agent 可达范围中的“知识库 · 只读”挂载标识。

要求：沿用 Nexara 现有视觉体系（参考 `ui/common/` 组件、`NexaraSettingsSection`、`NexaraValueRow`、底部面板与深色主题），深浅色、360dp 宽度可用；文案按审计文档第 3.5 节与术语规则书写；原型以 Artifact 页面发布，回报链接。

## W2-B 知识检索工具

目标：按知识检索设计稿实现后端：`knowledge_search`、`request_knowledge_access`、知识库只读挂载、检索方式与知识库文件访问两个智能体字段、资料目录注入。

范围与验收：以设计稿第 3 至第 5 节为准。另含界面决定 K3：查询改写模型未单独设置时，按“快速模型 → 主模型”回退，与图谱抽取一致（现状直接回退主模型，见 `NexaraApplication.kt` 中 `QueryRewriter` 的 `modelRouteResolver`），补单元测试。同时清理 W1-B 留下的停用参数（`ContextBuilderParams.agentRetrievalConfig`、`ContextBuilder.webSearchProvider`），并恢复工具关闭时拒绝消息的完整提示（要求模型停止调用工具、直接作答）。界面展示（工具步骤行、检索详情、审批卡、挂载标识、设置页选项）不在本包，由 W3-F 实现；本包需保证 `ToolResult.data` 结构、审批请求的参数与挂载记录的 `readOnly` 可供界面读取，并在报告中写明数据契约。

文件归属：`ui/chat/manager/skills/KnowledgeSearchSkills.kt`（新）、`domain/tool/ToolExecutionPolicy.kt`、`domain/storage/ExternalMount.kt`、`data/storage/ExternalFolderManager.kt`、`data/storage/ExternalMountPaths.kt`、`data/rag/` 下检索与图谱提供者、`data/generation/SessionToolResolver.kt`、`ui/chat/manager/ContextBuilder.kt`、`data/agent/AgentConfigModels.kt`、备份相关文件及对应测试。

## W2-F 全局设置与会话面板

目标：按已确认的原型实施全局设置与会话面板部分，界面决定 H、K、A、S、T 各条与第 9 节中归属 W2-F 的组件缺口见原型决定文档。

范围：设置首页分组与导航；知识库各页（含检索深度档位、新会话默认开关、调试与高级折叠区与重建索引提示，P4、P9、M2、M4、L5、L6）；执行环境与连接分组（L4、L7、D4）；Web 搜索域名过滤入口（P8）；语言行显示（D8）；会话设置面板工具页与上下文页（P3、P5、L1、M3、D5；P3 含删除 `ChatViewModel.toggleSessionSkill`、`SessionManager.toggleSkill` 与全局旧版自定义工具入口）；Agent 可达范围面板网络白名单改为跳转（L2）。

文件归属：`ui/hub/UserSettingsHomeScreen.kt`、`ui/settings/`、`ui/rag/` 中的设置类页面、`ui/linux/settings/`、`ui/chat/SessionSettingsSheet.kt`、`ui/chat/SessionScopeSection.kt`、`ui/workspace/AgentReachSheet.kt`、`navigation/NavGraph.kt`、`navigation/SettingsPane.kt`、本包新资源文件及对应测试与截图预览。不改智能体编辑页与知识检索界面；不改提供商与模型相关文件（`ProviderListScreen.kt`、`ProviderFormScreen.kt`、`ProviderApiSourceSection.kt`、`ProviderModelsScreen.kt`、`ModelEditorSheet.kt`、`ModelSyncNotice.kt`、`SessionHeaderSettings.kt`）与 `ui/common/NexaraDropdownMenu.kt`，它们归 W2-M。

## W2-M 提供商模型页与悬浮菜单

目标：按已确认的 W1-P2 原型重做提供商列表、提供商编辑、模型列表、模型编辑四页与全站悬浮菜单的外观和动效。界面决定、新部件、数据来源与风险见 `docs/superpowers/specs/2026-10-28-providers-models-menu-decisions.md`。

范围：

1. 后端小项（经 Codex）：持久化提供商最近一次连接测试的结果与时间、上次同步模型的时间，供列表与模型页读取；保存在提供商配置之外的本地偏好中，不进备份；删除提供商时一并清除。补单元测试。
2. 前端：决定 D1–D14 全部实施，新增第 5 节列出的通用部件；悬浮菜单只改组件内部的外观与动效，定位逻辑与调用方不动。
3. 新文案放进本包自己的资源文件，中英文都要有；不改现有 `strings.xml` 条目。

文件归属：`ui/settings/ProviderListScreen.kt`、`ProviderFormScreen.kt`、`ProviderApiSourceSection.kt`、`SessionHeaderSettings.kt`、`ProviderModelsScreen.kt`、`ModelSyncNotice.kt`、`ModelEditorSheet.kt`（可更名为 `ModelEditorScreen.kt`）、`SettingsViewModel.kt` 中提供商与模型相关部分（只做增量改动）、`ui/common/NexaraDropdownMenu.kt`、`ui/theme/` 中的动效常量、第 5 节新部件、`data/manager/ProviderManager.kt` 或新增的提供商状态存储、本包新资源文件及对应测试与截图预览。

验收：连接测试结果与同步时间持久化及清除的单元测试；模型编辑页内切换、返回关闭、同步删除时自动退出编辑的测试；定向测试与三目标编译通过。菜单动效的等帧数与掉帧情况由主控真机取证决定。

开始时机：W2-B、W2-F 中任一个完成后开始；与 W2-F 并行时，`SettingsViewModel.kt` 只做增量改动，合并冲突由主控处理。

## W3-F 智能体页、知识检索界面与文案统一

目标：智能体编辑页按目标结构重排；实现知识检索的全部界面；完成术语与文案统一。界面决定 G、C、R 各条与第 9 节中归属 W3-F 的组件缺口见原型决定文档。

范围：

- 智能体编辑页：新会话默认（执行模式、默认工作区、技能、MCP，M1）、知识与记忆（检索方式、知识库文件访问、检索范围、私密智能体说明 T9）、参数方案标注全局共享（L3）；移除智能体“记忆设置”“检索设置”两页及其路由。
- 知识检索界面：工具步骤行、由 `ToolResult.data` 打开检索详情、`request_knowledge_access` 审批卡、只读挂载标识。
- 文案：T1 术语统一（中文“智能体”、英文 Agent，预置“&brand; 助手”改为“通用智能体”）、T2、T3、T6、T7、T8、T9，并检查系统提示词中给模型看的称呼。

文件归属：`ui/hub/AgentEditScreen.kt`、`ui/hub/AgentEditViewModel.kt`、`ui/hub/AgentHubScreen.kt`、移除 `AgentRagConfigScreen.kt`、`AgentAdvancedRetrievalScreen.kt`、`ui/chat/` 中工具步骤与审批卡相关文件、`ui/chat/components/ExternalFoldersPanel.kt`、全部字符串资源文件。

## 收尾（主控）

合并全部分支 → 完整单元测试、lint、截图校验、两渠道构建、发行清单与 16KB 检查 → 签名正式版装机真机测试 → CHANGELOG、README、ARCHITECTURE 与审计文档状态更新 → 提交推送 → 清理分支、worktree 与分支会话。
