# Nexara 工具调用、工作区与发行契约修复规格

> 日期：2026-08-24
> 分支：`B-native-refactor`
> 状态：已冻结，按对应实施计划串行施工
> 对应计划：`docs/superpowers/plans/2026-08-24-nexara-release-contract-repair.md`

## 1. 目标

本轮修复以当前工作树的真实实现为准，关闭上一轮项目审计中会影响数据安全、工具调用正确性或发行真实性的 P0/P1 缺口，并交付与当前安装签名兼容的 Release APK。

完成后的最低产品合同是：

- 新会话默认不会在没有用户确认的情况下执行高风险工具；
- Provider 预设、协议请求路径、凭据类型和 Tool Call 往返结构一致；
- 流式响应失败后不会执行已收集但尚未提交的工具调用；
- 会话删除、文件回收、永久删除和版本清理不留下跨会话可检索数据；
- 二进制文件不会进入文本编辑/补丁链路，文本工具有明确大小与边界限制；
- 会话级 Skill/MCP 选择真实约束发送给模型的工具集合；
- 设置、RAG、知识图谱和备份页面只展示真实存在且可到达的能力；
- 当前 README、PRD、发行账本与当前 HEAD 的实现及验证结果一致；
- Release APK 通过本地签名、对齐、包名、版本和可安装性检查。

## 2. 冻结边界

### 2.1 不得改变

- 手机底部 Agent / Chat / RAG 三按钮导航的组件、尺寸、选中态、动画和布局不得改动。
- 已确认的 Material 3 连续列表、SE 风格排版和二三级设置页视觉基线不得回退。
- 不恢复 Glass、逐项卡片、永久描边或第二套 UI 组件体系。
- 不更改 `applicationId`、现有数据库身份、备份包身份或 Release 签名身份。
- 不创建 tag、PR、GitHub Release，不向公开渠道发布。

### 2.2 本轮明确不伪装为完成

- 当前项目没有完整后台调度、保留策略、失败通知和状态追踪，因此不临时引入半套 WorkManager 自动备份；删除无实现的自动备份开关，新备份不再导出该键，旧包仅兼容读取并丢弃该键。
- MCP STDIO 在 Android 端没有传输实现时不得继续作为可选能力；只保留实际可用的 HTTP 传输。
- 自定义数据库脚本没有受控沙箱时不得向模型声明为可执行工具；保留配置数据，但工具注册返回明确“不支持”或不注册。
- Cohere v2 若未实现其原生消息和 Tool Call schema，则从可选 Provider 预设中移除；不得以 OpenAI 兼容协议伪装支持。
- 未运行真实 Provider 网络测试的协议只能声明为本地契约已验证，不能声明线上连通性已通过。

## 3. 安全执行合同

### 3.1 会话执行模式

- `Session`、`SessionEntity` 和所有空值/未知值回退统一为 `semi`。
- 新会话继承所属 Agent 的 `ExecutionMode`；找不到 Agent 或值无效时回退 `semi`。
- 会话设置页提供 Auto / Semi / Manual 的真实单选控件并持久化。
- `auto` 只允许在用户主动选定后存在；不得由默认构造或迁移静默产生。

### 3.2 风险分类与审批

- 工具风险不再由散落的硬编码名称决定，统一使用 `ToolRiskLevel` / `requiresApproval` 元数据。
- `semi`：所有写文件、补丁、删除、进程/脚本、外部写操作和未知风险工具都必须审批；只读且已知安全的工具可直接执行。
- `manual`：所有工具逐个审批。
- `auto`：仅跳过审批，不跳过参数校验、工作区边界、幂等账本和失败关闭。
- 一次模型响应含多个待批工具时，审批界面展示完整队列；确认只处理当前明确展示的调用，剩余调用继续等待，不得一次确认隐式批准全部。
- 拒绝、取消、超时、崩溃恢复均写入稳定终态和对应 Tool 消息。

### 3.3 流式失败关闭

- 协议流中出现 Error、连接取消、解析失败或缺少 Done 时，当前轮所有尚未提交执行的 Tool Call 均不得执行。
- Runner 必须先确认流成功终止，再把完整 Tool Call 集合交给 runtime。
- 错误前已产生的文本可以保留为失败消息，但不能成为执行工具的授权。

## 4. Provider 与协议合同

### 4.1 EndpointResolver

建立唯一 `EndpointResolver`：

- 输入为 `ProtocolType` 与用户保存的 base URL；
- 若 base URL 已包含完整协议路径，规范化后原样使用；
- 若只包含 host 或 provider 前缀，追加对应 `defaultPath`；
- 不产生双 `/v1`、双 `/chat/completions`、缺 `/v1` 或越过用户自定义路径；
- 列表模型、连接验证和推理请求使用同一 URL 语义；
- 所有官方预设有 golden 测试。

### 4.2 能力真值

- OpenAI Chat Completions、OpenAI Responses、Anthropic、Mistral、DeepSeek、Kimi、Qwen、GLM、Doubao、Baichuan 与 Generic OpenAI Compat 显式映射到实际 wire protocol。
- Yi 仅保留历史配置可识别能力。[零一万物开放平台公告](https://platform.lingyiwanwu.com/)已说明停止新注册与充值，并将于 2026-09-03 24:00 停止 API 调用，因此本发行版对 Yi 的 probe、route 与 inference 统一返回 `PROTOCOL_UNSUPPORTED`，不得携带历史凭据发起网络请求，也不得静默迁移为 Generic。
- Anthropic 连接验证不得依赖默认空模型列表；使用无推理成本的协议验证或清晰能力探测。
- Vertex 服务账号 JSON 只进入 `serviceAccountJson`，从统一解析器提取并验证 `project_id`、`client_email` 和 PKCS8 RSA 私钥；不得当作 API key。
- Vertex 连接验证只做凭据解析与 OAuth token exchange，不发送付费模型推理请求。
- Vertex 推理只允许 location 对应的 Google 官方 `aiplatform.googleapis.com` origin；用户配置、旧备份或导入数据不得把 OAuth access token 转发到自定义 host。标准 Google JSON 中私钥结尾换行必须可解析。
- Provider 表单不直接构造协议，由 `ProviderConnectionProbe` 负责参数映射。
- Generic OpenAI Compat 的表单校验与 resolver 使用同一合同：host-only 地址不得保存或探测；完整 inference URL 保持原样。历史已下线协议只读 fail-closed，未经用户明确迁移不得静默改写为 Generic。

### 4.3 Tool Call 往返

- 每次生成必须且只能产生一个终态：`Completed(END_TURN, empty)`、`Completed(TOOL_CALLS, completedToolCallIds)` 或结构化 `Error`。未知 finish/stop reason、重复终态、EOF 截断、取消均失败关闭。
- 只有 `TOOL_CALLS` 可以进入工具执行；其 ID 集合必须非空、唯一，并与本轮已完整组装且参数为 JSON object 的调用精确相等。`END_TURN` 不得附带可执行工具。
- 单次生成最多接收 10 个 Tool Call；重复 ID、终态遗漏/多报 ID、未知工具或非法参数均不得进入审批或执行。
- 同步与流式响应使用同一终态合同；任何 Error、取消、截断或无效同步终态都必须清除本轮未确认 Tool Call 的持久化快照，后续轮不得把它重新作为历史 assistant tool call 发送。
- Responses 在完整 `output_item` 中一次给出 function call、但不发送 arguments delta 时，适配器仍要向统一客户端补齐恰好一次调用数据，再核验 `response.completed`。
- OpenAI Responses 同步与流式都支持 function call name、call id、arguments delta/complete、usage 和错误。
- Responses 后续轮使用 assistant function call item 与 `function_call_output(call_id, output)`，不得把工具结果伪装为普通 user 文本。
- Anthropic assistant `tool_use` 与 user `tool_result` content block 成对；不得发送 OpenAI 风格顶层 `role=tool`。
- 同一轮多个 Tool Call 的顺序、ID、参数和结果一一保持。
- Chat Completions、Responses、Anthropic 至少各有一组“模型请求工具 → 应用返回结果 → 模型继续回答”的两轮 golden 测试；流式与非流式关键字段均覆盖。

## 5. 工具注册、参数与幂等合同

### 5.1 参数

- JSON 参数保持类型；object、array、number、boolean、null 不转为字符串。
- `update_plan` 接受模型协议传入的 JSON array/object，并验证 step/status 结构。
- schema 不匹配返回结构化失败，不执行技能。
- `CancellationException` 必须重新抛出，不能转成普通工具失败。

### 5.2 账本

- 没有 Provider call id 时生成的稳定调用 ID/账本 key 必须包含规范化工具名、参数 payload hash 和序号。
- 相同消息内不同参数的同名工具不得互相去重；真正的重复重放仍返回既有终态。
- Prompt 时冻结的定义身份至少绑定 runtimeToolId、wire name、description、规范化参数 schema、risk、source/server id；审批、账本注册、原子 claim 和执行前复核必须使用同一个版本化 definition digest。
- Skill Registry 在模型生成期间发生删除、替换、禁用、schema/risk/server 变化时，既有调用失败关闭；不得按当前同名定义继续执行。

### 5.3 Skill/MCP

最终工具集合为：

```text
内置已启用工具
∪（全局已启用 Skill ∩ 当前会话 activeSkillIds）
∪（全局已启用 MCP Server ∩ 当前会话 activeMcpServerIds ∩ 当前同步成功工具）
```

兼容规则：旧会话 active 列表为空时，只允许安全内置工具；不能解释为“全部自定义工具”。

- MCP 工具 ID 使用 `mcp:{serverId}:{remoteToolName}`，避免服务器间重名。
- 同步以 server 为事务边界：成功后原子替换该 server 的工具；禁用/删除/同步失败时清除或保留上次成功快照并显示明确状态，不能混入半批新旧工具。
- MCP 调用必须携带 serverId，不得由名称猜测服务器。
- 本发行版只支持 MCP `2026-07-28` modern Streamable HTTP：每个请求在 `_meta`、`MCP-Protocol-Version`、`Mcp-Method` 中声明一致协议版本与方法，`tools/call` 还要发送 `Mcp-Name`；不实现旧版 initialize/session 回退。
- `text/event-stream` 响应必须按有界字节、行、事件增量读取；只允许忽略合法 `jsonrpc: 2.0` notification。首个完整且精确匹配请求 ID 的 response event 是当前请求终态，客户端随即关闭响应通道，不等待持久连接 EOF；畸形对象、错误版本、带 result/error 的错 ID response、截断与任一上限溢出均 fail-closed。
- `tools/list` 必须完整读取全部 `nextCursor` 后才可提交快照；HTTP 非成功、JSON-RPC id 不一致、RPC error、非法工具名、非 object/不受支持的 JSON Schema、重复工具定义或分页循环均失败关闭并保留上一批快照。
- `x-mcp-header` 只接受 schema 中可安全到达的 primitive string/integer/boolean 参数；调用时按规范编码为 `Mcp-Param-*`，非法声明使该远端工具不可广告。
- `tools/call` 必须区分成功内容、`isError=true` 和 `input_required`；本发行版不支持交互式补参，遇到 `input_required` 返回稳定“不支持”失败，不能伪装成功。
- Settings 只允许新增 HTTPS HTTP transport；历史 HTTP 明文或 STDIO 配置仍可见并可删除/迁移，但不可同步、不可广告、不可执行。正式版不新增 cleartext 例外。
- 自定义数据库 Skill 在没有沙箱前不进入模型 tool schema。

### 5.4 MCP discovery 持久化

- Room v5 新增 `mcp_tool_snapshots(server_id, remote_tool_name, description, input_schema_json, synced_at)`，以 `(server_id, remote_tool_name)` 为复合主键并对 `mcp_servers` 使用删除级联。
- `mcp:{serverId}:{remoteToolName}` 是稳定内部身份；Provider wire alias 可以另行确定性派生，但不得反向解析别名猜服务器。
- 同步只在完整响应和 schema 校验成功后，于单个 Room 事务中替换一个服务器的整批快照；失败保留上一批并显示错误，空成功批次明确清空。事务提交前再次确认服务器仍存在、启用且为 HTTP。
- 禁用、删除、修改 URL/type 必须同步清除快照；晚到的同步结果不得复活已失效工具。
- discovery 是派生缓存，不进入备份；恢复仅保留 MCP server 源配置并清空快照，重新同步成功前不向模型广告 MCP 工具。

## 6. 工作区与删除合同

### 6.1 会话删除

新增 `SessionDeletionCoordinator`，按以下顺序执行：

1. 获取会话级删除互斥锁并标记 deleting，阻止新生成和工具写入；
2. 取消/等待该会话 generation、pending approval 和后台索引任务；
3. 解析并校验该会话唯一 workspace root；
4. 事务化永久删除 workspace 物理树、FileEntry、FileVersion/快照、文档、向量、FTS、KG、标签与任务关联；
5. 删除消息、工具账本和 Session；
6. 成功后从内存 Store 移除；任一步失败时保留可恢复状态并向 UI 返回失败。

不得先删除 Session 后留下不可寻址的工作区或全局可检索数据。

### 6.2 回收站与版本

- RAG/普通文件删除调用 `moveToRecycleBin`；只有回收站“永久删除/清空”或回滚新建失败可调用 `permanentDelete`。
- 回收站条目不进入普通列表、文件工具、全文搜索、文档检索或知识图谱。
- 普通消费者只能调用 active-only DAO；回收动作取消并等待在途索引后清理 vector、FTS、KG、任务和缓存，恢复后精确排队一次重建，候选构建与提交两端都要复核 active 状态。
- 永久删除目录时清理整棵子树的 FileVersion DB 记录与物理快照。
- 恢复后重新进入索引协调器；失败时保留回收条目并可重试。

### 6.3 文件类型与资源上限

- 建立 `TextFilePolicy`，只允许明确文本 MIME/扩展名进入读取、编辑、diff 和 patch。
- PDF/DOCX/图片等二进制文件可导入和索引，但文本编辑器与文件工具返回“该格式只读/不支持文本编辑”，不得按 UTF-8 覆盖。
- read/diff/patch 设置最大字节数；超限返回结构化错误并建议缩小范围。
- patch 的 start/end 均严格校验；`end > EOF`、负数、交叠或上下文不匹配直接失败，不得静默裁剪。

### 6.4 进程死亡恢复

- create、createStreaming、mkdir、rename、move、recycle、restore 与 delete 都必须通过持久化 operation journal 或等价 staged transaction。
- journal 至少记录 operation id、root UUID、源/目标相对路径、数据库目标、阶段和校验摘要。
- 应用启动维护按阶段幂等恢复或回滚；路径再次经过 root/symlink 边界校验。
- backup/restore 后重新验证 workspace root UUID、物理路径和内部系统目录，不能恢复外部绝对路径。

## 7. RAG、知识图谱与设置产品合同

### 7.1 RAG 搜索与删除

- 搜索非空时展示 FTS 正文命中的稳定结果和 snippet；空查询回到原 FilesPanel。
- 新查询取消旧查询并防止乱序覆盖；FTS 失败显示 typed error/warning，标题回退必须明确标注。
- 普通删除进入回收站；失败不清除选择、不关闭确认。

### 7.2 高级配置

- 删除 JIT、自动域、增量 Hash、本地预处理四个没有 runtime 消费者的假开关和 Coming Soon 文案。
- 旧备份字段继续兼容读取，但不再作为可配置能力展示或导出。

### 7.3 知识图谱

- 文档模式必须先选一个明确 docId，再加载该文档子图；切换文档或全局模式不复用错误缓存。
- 加载失败保留最近一次成功画面并显示可重试错误，不伪装为空图。
- Canvas 保留现有视觉，同时提供 48dp 可发现的列表入口；列表逐项朗读节点和关系。
- Canvas 本身提供节点数、边数、范围和手势摘要语义；减少动态效果时停止装饰性脉冲。

### 7.4 错误与死入口

- RAG、Token、Agent、Avatar 的加载/写入/删除错误使用本地化 typed notice；异常详情只保留截断 technical，不直接显示给用户。
- Avatar 只接受图片，临时写成功后原子替换旧文件；空结果、视频 URI 或复制中断不得删除旧头像。
- 默认模型允许明确清除，清除结果持久化为空而不是自动恢复旧值。
- 分享错误全部走 strings。
- 删除无生产入口的 `SESSION_SETTINGS` 页面、Developer Panel 和经检索确认无消费者的 dead route；聊天内 `SessionSettingsSheet` 保留。

## 8. 备份合同

- 设置页删除自动备份开关与对应 ViewModel API。
- 新备份不导出 `auto_backup`；恢复旧包时识别并丢弃该键，其它合法偏好继续恢复。
- WebDAV 只代表用户主动上传、列出和恢复，不代表后台自动调度。
- 导入前验证 schema、包身份和 workspace root；提交前保留可恢复快照，失败回滚。

## 9. UI 与可访问性合同

- 本轮新增/调整 UI 只使用现有 MD3 组件和项目 Design Tokens。
- 连续设置列表不逐项加卡；触控目标至少 48dp；2.0x 字体不裁切主动作。
- loading、empty、error、warning 和 success 必须是不同状态；异步完成前不能提前显示成功。
- 底部导航相关生产文件及截图基线属于禁止写入区。

## 10. 验收矩阵

### 10.1 自动化

- 每个工作包先运行新增/修改测试并观察预期 RED，再实现 GREEN。
- 全量 JVM Unit：0 failure / 0 error；依赖真实凭据的历史测试只可保持显式 skip。
- `lintDebug`：0 Error / 0 Fatal。
- `validateDebugScreenshotTest`：全部 reference 匹配；新增视觉状态逐张人工检查。
- `compileDebugAndroidTestKotlin`：通过。
- Android device core E2E、minified blackbox smoke、Metro TUI tests、release readiness validator：通过或明确记录环境阻断。

### 10.2 Release APK

- 使用现有安全注入读取本地签名，不打印、复制或记录秘密值。
- `assembleRelease` 成功，APK 通过 `zipalign -c` 与 `apksigner verify --verbose --print-certs`。
- 包名、versionCode、versionName、min/target SDK 与当前发行配置一致。
- 签名证书摘要与已安装/既有发行身份一致，支持 `adb install -r` 数据继承；没有可控设备时只声明静态签名兼容，不能声明实机升级已通过。
- 记录 APK 绝对路径、字节数和 SHA-256。

## 11. 文档与治理

- 同步 `README.md`、`docs/PRD.md`、`CHANGELOG.md`、`docs/release/v0.2.1-beta.md`、`docs/release/v0.2.1-beta-validation.md` 及受影响 ADR/架构说明。
- 旧验证段保持历史身份，新验证以当前最终 commit 追加，未运行项为 PENDING/阻断，不改写成 PASS。
- HLG 由主控使用 Skill 的 `append` 先 dry-run 后 `--apply`，不得直接编辑既有 handover 记录。
- 最终提交只包含本轮明确成果；排除签名材料、`secure_env/`、APK/build 产物和临时工作区；推送当前 `B-native-refactor`，不强推。
