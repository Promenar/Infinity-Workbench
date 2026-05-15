# CHANGELOG

## [Unreleased]

### Changed
- [Architecture] **统一资源操作系统**: 移除旧 documents/folders 系统（12 文件删除），全面切换至 UUID 锚定的 workspace_files 体系。RagViewModel、DocEditorViewModel、VectorizationQueue、KnowledgeGraphRepository 等组件已适配新接口（IWorkspaceRepository / IFileOperationRepository）。数据库迁移 v8→v9。
- [Architecture] **FK 解耦**: VectorEntity、KgEdgeEntity 等移除对 DocumentEntity 的 ForeignKey，保留 doc_id 列数据。

### Added
- [Testing] **WorkspaceSeqDaoTest**: 原子序号并发递增测试（4 测试，含 20 并发协程竞态验证）
- [Testing] **FileOperationRepositoryTest**: 乐观锁写入测试（5 测试：成功写入/Hash 冲突/UUID 不存在/内容读取/行号范围读取）
- [RAG/UX] **知识审计系统 (Knowledge Inspection)**：实装了全新的 RAG 详情弹窗 `RagDetailsSheet`，支持查看检索片段的原始文本、向量得分、重排得分及排名变化。
- [RAG/KG] **知识图谱可视化**：实装了 `KgPath` 可视化组件，支持在 UI 上以拓扑路径形式展示 AI 的图谱推理链路，并包含推理说明（Reasoning）。
- [RAG/Data] **增强型数据模型**：在 `Message` 中整合了 `kgPaths`，并在 `RagReference` 中新增了 `rerankScore` 和 `rankChange` 元数据。
- [Testing] **RAG 详情序列化测试**：新增 `RagDetailsSerializationTest.kt`，验证了复杂图谱路径与增强型引用数据的无损持久化。
- [Planning] **全局资源管理器架构**：完成了 Nexara 统一资源操作系统的架构规划（存档于 `.agent/plans/20260515-ResourceManagerArchitecture.md`），将实现 UUID 锚定的文件操作系统。
- [Planning] **内置任务规划工具 (Task Planner)**：完成了任务拆分与追踪工具的架构规划（存档于 `.agent/plans/20260515-TaskPlanningToolArchitecture.md`），设定为最高优先级任务。

### Fixed
- [RAG] **UI 点击反馈**：`RagOmniIndicator` 现支持点击，在存在检索参考或图谱路径时可弹出知识审计面板。
- [Architecture] **MessageManager 状态同步**：修复了流式消息更新过程中 `kgPaths` 数据丢失的问题。

### Added
- [RAG/Parameters] **生成参数控制中心**：将"思考级别"标签页重构为"参数" (Parameters)，整合了 Top K、重复惩罚 (Repetition Penalty)、存在惩罚 (Presence Penalty) 及频率惩罚 (Frequency Penalty) 等高级模型采样控制项。
- [Protocol] **标准化参数透传**：在 `LlmProtocol` 接口及所有实现类（OpenAI, Anthropic, VertexAI, Local, GenericOpenAICompat）中实装了对新增采样参数的支持，确保高级设置能透传至各后端模型。
- [UI/Settings] **参数折叠与 UI 优化**：引入 `NexaraCollapsibleSection` 隐藏极客参数，提升面板整洁度；修复了字体大小滑动条的步进断点失效问题（固定为 10-18px）。
- [UI/RAG] **命名标准化**：统一了功能区标题，将"会话向量化"更名为"上下文管理"，将"向量检索"更名为"长期记忆"，并独立了"会话 RAG"与"跨会话检索"开关。
- [Testing] **参数链路回归测试**：新增 `ProtocolParamTest.kt`，验证了各协议对新增参数的 Payload 封装与序列化一致性。
- [Audit] **全链路审计**：已完成 RAG 配置与参数控制系统的最终审计，评审结论已存档至 `.agent/plans/20260515-rag-parameter-audit.md`，发现 P0 级参数透传不一致问题
- [Protocol] **ProtocolParamAdapter 抽象层**：新建 `ProtocolParamAdapter` 共享参数映射工具，将 5 个协议实现类中分散的手写参数映射统一为 3 行 Adapter 调用，彻底解决了参数透传不一致问题
- [Protocol] **全协议参数回归**：5 个协议（OpenAI/Anthropic/VertexAI/GenericCompat/Local）全部迁移至 `ProtocolParamAdapter`，参数透传矩阵从 4/5 协议缺参数提升至 5/5 协议全覆盖
- [Protocol] **LlmProvider 工厂路由修复**：Cohere/Mistral/DeepSeek/GenericCompat 协议类型现路由至 `GenericOpenAICompatProtocol`（全 7 参数支持），修复了此前错误路由至 `OpenAIProtocol`（缺 topK/repetitionPenalty）的 Bug
- [Testing] **协议参数矩阵测试**：新建 `CrossProtocolParamAuditTest.kt`（5 测试）+ `ProtocolParamAdapterTest.kt`（8 测试），扩展 `ProtocolParamTest.kt`（+2 测试），15 个测试全部通过

### Changed
- [Local] **GenerateConfig 扩展**：新增 `frequencyPenalty` 和 `presencePenalty` 字段（Float, 默认 0.0），数据类从 5 字段扩展至 7 字段
- [Local] **LlamaCppBackend JNI 参数修复**：`generate()` 现传递完整的 `GenerateConfig` 到 JNI 层，修复了此前仅传 `maxTokens` 导致 temperature/topP/topK/repeatPenalty 全部丢失的问题
- [Local] **极端值安全裁剪**：`ProtocolParamAdapter.clampGenerateConfig()` 对 temperature(top:2.0)、topK(min:1)、repeatPenalty(top:1.5) 执行安全边界裁剪
- [UI/RAG] **RAG 关闭状态反馈**：在 SettingsPanel 的 RAG 开关下方新增半透明提示条，当所有 RAG 功能（会话记忆/跨会话检索/知识库/知识图谱）同时关闭时显示"所有记忆功能已关闭 — AI 将仅基于当前对话上下文回答"
- [UI/Params] **思考级别联动扩展**：点击 Minimal/Low/Medium/High 卡片现联动设置 temperature + topP + topK 三参数预设组合，替代此前仅改 temperature 的行为
- [UI/Common] **小屏动画优化**：`NexaraCollapsibleSection` 在 `<360dp` 设备上动画从 300ms 缩至 150ms，缓解低端设备展开高级参数时的卡顿

### Fixed
- [RAG] **P0-1 引用写入修复**：`ChatViewModel.generateMessage()` 中 `contextBuilder.buildContext()` 返回的 `ragReferences` 现在正确写入 `Message` 模型，`RagOmniIndicator` 可在 AI 回复上方正确显示检索引用来源
- [RAG] **P0-2 Embedding 本地降级**：`EmbeddingClient.embedQuery()` / `embedDocuments()` 现支持 API 失败时自动降级到 `LocalInferenceEngine` 本地推理引擎，防止 RAG 静默失效
- [RAG] **P0-3 向量维度告警**：`VectorStore.search()` 新增 `onWarning` 回调，维度不匹配时输出 `Log.w` 警告并回调通知调用方，帮助用户在更换 Embedding 模型时感知旧向量失效
- [UI/Markdown] **P1-1 流式缓存边界检测**：增量追加时检测 ` ``` ` / `$$` 跨边界，自动重新分段解析，解决代码块和 LaTeX 在流式输出中被碎片化的问题
- [UI/Markdown] **P1-2 缩进代码块保护**：新增 `safeTrimIndent()` 辅助函数，在 `trimIndent()` 前检测 4 空格缩进代码块并跳过 trim，防止 GFM 缩进代码块被破坏
- [UI/Markdown] **P1-3 CJK 间距保护**：`insertCjkSpacing()` 现保护行内代码（\`...\`）和链接（[...](...)）不被注入 hair space (U+200A)
- [UI/Markdown] **P2-2 崩溃降级回退**：新增 `MarkdownSafe` composable，Markdown 渲染崩溃时自动降级为 `Text` 纯文本显示，防止整个聊天页白屏
- [RAG] **P1-4 多格式文档导入**：`DocumentImporter` 现支持 PDF / Word / HTML 格式识别与分发，PDF 使用 Android `PdfRenderer`，HTML 自动剥离标签
- [RAG] **P1-5 阈值滑块**：`GlobalRagConfigScreen` 新增 `memoryThreshold`（对话记忆阈值）和 `docThreshold`（文档检索阈值）滑块
- [RAG] **P1-6 进度条状态优化**：`RagOmniIndicator` 进度条仅在做检索过程中显示，已完成/消息重载后自动隐藏，避免旧检索进度条残留
- [UI/Hub] **P1-7 删除二次确认**：在助手列表和二级会话列表中实装了删除确认对话框，防止用户因误触导致数据丢失

### Added
- [Provider] **协议类型体系升级**：将 `ProtocolId` 枚举重构为 `ProtocolType` sealed class，支持 9 种协议类型（OpenAI Chat/Responses、Anthropic Messages、Google VertexAI、Cohere Chat、Mistral Chat、DeepSeek、通用 OpenAI 兼容、本地推理），包含独立 `ProtocolSelector` UI 组件
- [Provider] **ProviderManager 统一数据源**：引入全站单一数据源单例，统一管理提供商 CRUD、模型状态、预设模型选择，取代散落在 SettingsViewModel 和 NexaraApplication 中的冗余逻辑
- [Provider] **多模态协议支持**：在 `PromptRequest` 和 `ProtocolMessage` 中新增 `images`/`audio`/`documents` 字段，OpenAI/Anthropic/VertexAI/Local 协议实现均已支持多模态内容块构建
- [Provider] **GenericOpenAICompatProtocol**：新增通用 OpenAI 兼容协议实现，支持 Ollama/vLLM/LiteLLM/LocalAI 等任意兼容端点
- [Model DB] **内置模型数据库全面刷新**：`ModelCapabilities` 维度从 6 扩展至 12（含 audio_input/output、video_understanding、structured_output 等），新增 50+ 2025-2026 年主流模型条目
- [UI/Provider] **协议类型选择器**：新增 `ProtocolSelector` 可组合组件，支持 8 种远程协议类型的独立选择

### Fixed
- [UI/Common] **Markdown 渲染编译修复**：解决了由于 `multiplatform-markdown-renderer` (0.40.2) 库 API 变更导致的编译错误。修复了 `thematicBreak` (已重命名为 `horizontalRule`)、`htmlBlock` 和 `inlineHtml` 的参数缺失及类型推断问题，并移除了 `MarkdownComponentModel` 中已废弃的 `colors` 属性引用。
- [Provider] **编辑回填修复**：进入已有提供商编辑页时，名称/BaseURL/API密钥/协议类型现在正确从持久化存储加载并回填
- [Provider] **页面标题动态化**：提供商编辑页标题现在显示实际提供商名称（替代硬编码的 "Provider"）
- [Model] **全站模型选择器同步**：修复 ProviderModelsScreen 配置模型后，设置页 4 个预设模型选择器（摘要/图片/嵌入/重排）不同步的问题，引入 `derivedStateOf` + `refreshProviderModels()` 刷新机制

### Changed
- [Architecture] **ProviderListItem 提取为共享数据模型**：从 `SettingsViewModel` 迁移至独立的 `data/model/ProviderModels.kt`，新增 `protocolType` 和 `apiKey` 字段
- [Architecture] **ProviderConfig 统一**：从 `NexaraApplication` 内联类迁移至 `ProviderModels.kt`，作为 ProviderManager 的标准返回类型
- [Stability] **解决多处编译错误与测试失败**：修复了 `ChatScreen.kt` 和 `NavGraph.kt` 中缺失的引用；修正了 `ModelSpecs.kt` 的匹配优先级顺序；修复了 `ContextBuilderTest` 和 `ModelSpecsTest` 中的逻辑断言错误。
- [Stability] **运行时崩溃修复**：彻底解决了 Markdown 渲染过程中由横向滚动条测量无限宽度引起的 `IllegalStateException` 闪退问题。
- [UI/Chat] **模型选择器补完**：修复了真机环境下模型选择器全为空的 Bug，补全了 `SettingsViewModel` 在获取模型时缺失的 `chat` 能力标签映射。
- [UI/Chat] **上下文上限动态计算**：优化了 Token 占用指示器的上限获取逻辑，支持通过本地 SharedPrefs 动态匹配不同模型的 Context Length，修复了上限恒定为 128k 的问题。
- [UI/Chat] **流式渲染优化**：改进了 `sanitizeStreamingMarkdown` 逻辑，解决了流式输出时 Math 与 Code 块渲染闪烁及不显示的问题。
- [RAG] **统计稳定性增强**：优化了 `VectorStatsService` 的类型匹配逻辑与 `RagViewModel` 的数据观测流，修复了索引完成后统计显示为 0 的潜在竞争问题。
- [UI/Chat] **交互逻辑重映射**：修复了聊天页 TopBar 按钮功能，设置按钮正确弹出工作区面板（WorkspaceSheet），并实装了三点菜单的下拉操作项（清空历史、重命名、删除会话）。
- [RAG] **UI 冗余清理**：移除了 RAG 首页底部重复的进度条浮窗，将所有索引状态统一收敛至列表顶部的进度组件中。

### Added
- [RAG] **颗粒化进度指示器**：重构了 `IndexingProgressBar`，支持显示"切块处理"、"发送向量"、"保存入库"、"知识提取"等细分状态及 sub-status 文字描述。
- [UI/Chat] **动态上下文指示器**：将静态 Context 图标替换为动态圆环占比组件（ContextCircularIndicator），实现 Token 占用百分比的实时可视化。

### Planning
- [Inference] **本地模型功能审计**：确认 `LocalModelsScreen` 及全套端侧推理链路为纯占位符，零实际实现。创建完整补完实施方案，选型 llama.cpp + JNI + GGUF，详见 `.agent/plans/20260510-local-model-implementation.md`
- [Inference] **会话拆分方案**：将实施拆分为 7 个独立会话，每会话附带即复制即用提示词，详见 `.agent/plans/20260510-local-model-sessions.md`
- [Inference] **架构升级**：引入 `InferenceBackend` 抽象接口，为远期 ggml-hexagon NPU 加速和 ExecuTorch QNN 预留插拔式扩展点

### Fixed
- [RAG] 统一知识图谱（KG）抽取模型选择器，支持 `ModelPicker` 过滤 `chat` 能力模型。
- [RAG] 改进高级检索设置语义，将"连接知识服务器"更名为"启用混合搜索"，并实装可观测性开关。
- [UI/Chat] 修复思考胶囊（Thinking Capsule）无法展开的问题，增加自动展开逻辑。
- [UI/Chat] 优化思考内容渲染，支持 Markdown 格式。
- [Stream] 增强 OpenAI 协议解析稳定性，实装动态超时逻辑（默认 120s，最高 300s），解决生成卡顿与假死。
- [Local Models] 修复活跃插槽高度不一致的问题，确保所有槽位视觉统一。
- [Hub] 移除首页智能体列表界面的 FAB 悬浮按钮，精简 UI 层级。
- [RAG] 优化知识库顶部状态卡片视觉样式，将"记忆"计数从字节改为条数，并提升数字展示的视觉重心。
- [RAG] 修复"集合"区域"新建"按钮无效的问题，实装了新建文件夹功能及对话框。
- [Backup] 修复 WebDAV 同步开关布局重叠问题，将冗余的 `SettingsToggle` 替换为标准 `Switch`。
- [UI/Chat] 移除 intrusive 的红色错误 Snackbar，改为气泡内小字提示。
- [UI/Chat] 为消息气泡添加发送时间戳与 AI 模型标识（采用统一的暗色小字样式）。

### Added
- [UI/Chat] **消息管理系统**：支持消息长按菜单，提供复制、编辑、删除、重发功能。编辑或重发将自动同步清理后续历史。
- [Settings] **会话超时设置**：在会话详情中增加"请求超时"滑块，支持按需调整推理等待时长。
- [UI] 统一全局二级/三级页面的背景色，解决 `NexaraPageLayout` 缺少背景色导致的视觉不一致（浅灰色变更为 CanvasBackground 深灰色）。
- [UI] 优化了 Agent 工具页面的 Tab 标签样式，将其从分段式改为下划线式，与会话设置面板保持一致。
- [Skills] 修复 `Sync` 图标未导入及 `Modifier.align` 作用域错误导致的编译失败
- [Skills] 修复因 `NexaraPageLayout` 与内部 `Column` 嵌套 `verticalScroll` 导致的测量异常闪退 (IllegalStateException)
- [UI] 修复沉浸式状态栏图标在深色主题下不可见的问题 (MainActivity.kt)
- [Settings] 彻底移除 `bge-m3` 等硬编码 Mock 模型定义
- [Skills] 修复技能标签页过滤逻辑，防止预设工具混入用户自定义列表
- [Skills] 修复内置工具可被误删的问题，并实装了自定义工具的增删改查 UI

### Added
- [Skills] 扩展预设搜索工具，新增 `search_tavily` 和 `search_searxng` 显式工具
- [Skills] 实装了自定义工具的 JS/Kotlin 沙箱代码编辑 UI
- [Skills] **深度集成配置**：将 Web 搜索设置从系统一级菜单迁移至技能管理页面，支持在 `SkillsScreen` 中直接配置各搜索引擎参数
- [MCP] 完善了 MCP 服务器管理，支持连接状态显示与工具同步
- [UI] 增加技能列表空状态展示与引导
- [RAG] **实装文档导入全流程**：新增 `DocumentImporter`，打通了从 UI 选择文件到向量库落库的完整 RAG 链路
- [RAG] **UI/UX 优化**：将知识库导入区域从 Web 端的"拖放"模式重构为移动端"点击弹出"模式，适配 Android 系统文件选择器
- [RAG] **系统分享集成**：支持从外部 APP 分享文件至 Nexara 进行知识库导入，并实装了 `SEND` / `SEND_MULTIPLE` 处理逻辑
- [RAG] **实时状态反馈**：实装了向量化队列进度在 RAG 主页的实时同步显示功能
- [RAG] **UI 精简与数据审计**：移除了"集合"区域冗余的图谱跳转按钮；重构了统计逻辑，确保"文档"与"图谱"计数直接查询自数据库，消除了 UI 初始化时的显示延迟
- [User] **实装头像自定义**：支持用户点击头像通过 Android 原生媒体选择器更换头像，支持图片与视频格式，并实装了 URI 权限持久化与 Coil 视频帧解码支持
- [User] **设置项精简**：移除了设置页中与"外观"功能重复的"主题色"入口，提升页面简洁度
- [Settings] **精简本地引擎配置**：移除设置主页中冗余的"启用本地引擎"开关，将其收敛至"本地模型"详情页中，并统一了状态的持久化逻辑。
- [Model Management] **优化模型名称显示**：在模型管理中默认优先展示模型 ID，增强辨识度。
- [Model Management] **模型能力标签去重**：自动排除与模型主类型重复的能力标签，保持界面整洁。
- [Model Management] **修复默认模型筛选**：增强了设置页"默认模型"选择器的筛选逻辑，确保正确加载可用模型。
- [Chat] **修复模型选择器闪退**：修复了 `SessionSettingsSheet` 因缺少 `IMAGE` 能力颜色映射导致的崩溃问题。
- [UI] **优化模型管理操作按钮**：增加了模型管理页面顶部操作按钮（自动获取、添加等）的高度，使其视觉上更协调。
- [UI] **修复提供商图标显示**：为预设提供商增加了 Material Icon 兜底展示，解决了 OpenAI、Anthropic 等图标加载失败的问题，并优化了 Local 和 Custom 的图标。
### [2026-05-14] 优化思考块渲染与流式性能

- **思考块渲染优化**：
    - 修复了思考内容在超高速模型输出时由于高频状态更新导致 `LaunchedEffect` 频繁重启而造成的渲染滞后（Freeze）问题。
    - 引入了基于 `rememberUpdatedState` 的非阻塞流式 catch-up 机制。
    - 为思考块容器增加了 `animateContentSize()`，实现重排（Markdown Layout）时的平滑视觉过渡。
- **Markdown 渲染引擎优化**：
    - 实现了流式分段合并逻辑，避免由于 Token 增量更新导致产生大量微小 `Markdown` 组件。
    - 优化了 `MarkdownSafe` 的组件缓存机制，移除了不必要的 Key 依赖。
- **交互精准度**：
    - 细化了思考块的流式状态判定逻辑，仅在实际 `THINKING` 阶段开启平滑动画，生成正文时即刻同步。

### [2026-05-14] UI 交互与稳定性修复

- **模型选择拦截**：在 `ChatScreen` 实现了发送按钮拦截器。若未选择主模型，点击发送会弹出提示气泡。
- **Pipeline 指标优化**：
    - 调整思考 (Thinking) 与工具 (Tool) 容器宽度至屏幕 70%。
    - 弱化思考过程的视觉存在感（字号减小 4sp，颜色变灰透明）。
    - 修复了点击产生的 MD3 涟漪动画溢出至全屏的布局缺陷。
- **流式渲染修正**：
    - 解决了生成态光标位置偏右的问题，确保其与气泡左侧对齐。
    - 修复了 `AnimatedVisibility` 在特定 Compose 作用域下的编译冲突。
