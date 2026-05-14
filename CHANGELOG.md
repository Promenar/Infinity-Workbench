# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### 智能视角追踪 + 流式加速 (2026-05-14)
- **智能视角追踪**: Pin-to-Bottom→新消息滚底+20Hz 自动跟随→用户手势切断→FAB 恢复；contentPadding bottom=150dp 统一底部定义
- **流式加速**: `BALANCED=6000` CPS（100 字符/帧），消除 38 CPS 积压-爆发-结束问题
- **思考容器**: `LaunchedEffect(isGenerating)` 同步展开
- **FAB/底部判定**: 密度感知阈值 `60.dp.toPx()` + spacer 精确索引匹配

### 流式传输死锁修复 (2026-05-14) 🔴 P0
- **Agent 循环死锁**: `Mutex.withLock` 包裹含递归路径的 `generateMessage()` 导致永久挂起——Kotlin `Mutex` 不可重入，`cancelActiveGeneration()` 已足够防并发。**教训**: 互斥锁绝不可包裹递归函数
- **流式假死**: `OpenAIProtocol` 空内容守卫 + `ThinkingDetector` 末端 `<` 扣留 → 移除守卫 + `tryFastPath()` 直通
- **TTFT 光标缺失**: `PipelineBubble` 补充 `StreamingCursor()` + `heightIn(min=32.dp)`

### 交互优化 (2026-05-14)
- **Smart Follow**: `autoFollowEnabled` 状态机——用户手势锁定视口，FAB/新消息恢复；Agent 追踪仅目标不可见时滚动
- **双光标**: `PipelineBubble` 光标仅在 TTFT 期渲染，有 Content 时 `MarkdownText` 内部光标接管
- **发送按钮误报**: `_error` 残留 → `generateMessage()` 起始清除
- **思考层级**: 思考字号 `fontSize-3`(min 10sp) + alpha 0.55，显著弱于正文
- **锚定修复**: `LaunchedEffect(latestUserMsgId)` 替代 `isGenerating + streamingContent.isEmpty()` 竞态

### 聊天交互优化 (2026-05-14)
- **PipelineBubble 气泡合并**: 新增 `PipelineBubble.kt` — Agent 多步响应合并为单一线性视觉气泡，思考/工具/正文以连接线串联，彻底消除多条消息被 `SpacedBy(16dp)` 隔开的分裂感
- **思考/工具容器重构**: `InlineThinkingRow` / `InlineToolRow` 替代旧版 `ThinkingBlock` / `ToolExecutionTimeline`，默认折叠（仅进行中状态展开），以颜色和图标区分类型（Primary=思考，Tertiary=工具），体积缩小约60%
- **滚动锚定重构**: 改用 `latestUserMsgId` 作为 `LaunchedEffect` 触发键替代 `isGenerating + streamingContent.isEmpty()` 竞态条件，使用分组索引而非消息索引
- **Agent 视角追踪**: `executionSteps.size` 变化时自动滚动到对应分组，保留工具时间轴可见性
- **IME 键盘联动**: `WindowInsets.isImeVisible` 检测 + 分组索引滚动
- **流式速度提升**: `StreamSpeed.BALANCED` 从 38 CPS → 120 CPS，FAST 模式 800 CPS
- **表格深色模式**: `NexaraTableWidget` 新增行间分隔线，解决低对比度问题

### Agent Fallback 解析器 (2026-05-14)
- **新增文本 JSON 工具指令兜底解析**: `ChatViewModel.extractToolCallsFromText()` — 部分模型（如 MiniMax-M2.7）不在 `ToolCallDelta` 层下发工具指令，而是以 Markdown 代码块输出 JSON 字符串。新增后置正则提取器，支持 `name/function/tool/tool_name` 等多种字段命名约定，自动将文本形式工具指令转为 `ToolCall` 对象
- **JSON 剥离增强**: `stripToolCallJsonBlocks()` 双重匹配——Markdown 代码块 + 裸 JSON 对象行，清除后整理空行
- **防止 Agent 循环中断**: 兜底解析后正确填充 `accumulatedToolCalls`，确保 `ToolExecutor.executeTools()` 被触发，`executionSteps` 正确回写以激活 UI 时间轴组件

### 流式传输根本修复 (2026-05-14) 🔴 P0
- **根因定位**: `OpenAIProtocol.processStreamChunk()` 中的空内容守卫 `if (content.isNotEmpty() || reasoning.isNotEmpty())` 配合 `ThinkingDetector` 的缓冲区扣留机制，在特定 chunk 边界上形成"双重静默丢弃"——当 ThinkingDetector 因末尾 `<` 字符临时扣留 content 且模型不使用 `reasoning_content` 字段时，整个 SSE chunk 被静默丢弃，消费者收不到任何信号，流式传输完全中断
- **修复**: 移除 `OpenAIProtocol` 和 `GenericOpenAICompatProtocol` 中的空内容守卫，无条件发送 `TextDelta`；`ThinkingDetector` 新增 `tryFastPath()` 直通模式——缓冲区空 + 状态 OUTSIDE + chunk 不含 `<` 时零开销直接返回，覆盖 95%+ 的正常文本流
- **TTFT 光标修复**: `PipelineBubble` 补充缺失的 `if (isGenerating) { StreamingCursor() }`，确保首字生成前的等待期内光标可见

### 图像生成工具 (2026-05-14)
- **新增 `ImageGenerationSkill`**: LLM 可调用 `generate_image` 工具，传递提示词和参数（size/quality/style），调用默认图像模型生成图片，结果内联展示在对话气泡中
- **新增 `ImageGenClient`**: OpenAI-compatible 图像生成 API 客户端（`POST /v1/images/generations`），支持 url/b64_json 响应，自动下载到本地存储
- **新增 `GeneratedImageData`**: 图片本地存储元信息序列化类，存入 `Message.images` 字段
- **ChatBubble 图片渲染**: `AsyncImage` 内联展示生成图片，附带模型改写后的提示词
- **ToolExecutor 增强**: `images = result.data` 传递工具生成的图片数据到 Message
- **架构**: 支持 LLM 聊天与图像生成使用不同端点（通过 ProviderManager 独立读取 `preset_image_model`）

### RAG 嵌入管线修复 (2026-05-14)
- **🔴 P0 致命 Bug**: `embedding_base_url` / `embedding_api_key` 永为空——ProviderManager 写入 `base_url`/`api_key` 键，但 EmbeddingClient 读取 `embedding_base_url`/`embedding_api_key` 键，导致嵌入模型从未收到配置 → 修复为键名缺失时回退到主 LLM 提供商配置
- **🔴 P0 致命 Bug**: `RagHomeScreen` 第 407 行 `shownDocs.isEmpty()` 逻辑反转 → 文档列表永不为空时反而不渲染 → 修复为 `isNotEmpty()`
- **🟡 次要 Bug**: `VectorizationQueue.notifyStateChange()` 在完成/失败后缺失调用，外部观察者收不到终态 → 补充调用
- **🟡 次要 Bug**: `RagViewModel` 向量化失败后 `isIndexing=false` 导致错误提示随进度条消失 → 新增 `lastQueueError` 持久化状态

### RAG 重排管线修复 (2026-05-14)
- **🔴 P0 致命 Bug**: `RerankClient.rerank()` 从未被调用——`MemoryManager` 构造函数不包含 `rerankClient` 参数，`retrieveContext()` 缺失重排步骤 → 注入 `rerankClient` 并在去重后、类型过滤前插入 rerank 调用
- **🟡 防护**: `RerankClient.rerank()` 新增空配置前置检查（同 EmbeddingClient），避免静默吞错

### AGP 构建警告消除 (2026-05-14)
- `jniLibs.srcDirs()` → 删除整个 `sourceSets` 块（`src/main/jniLibs` 是 AGP 默认目录）
- `disallowKotlinSourceSets=false` → 保留（KSP Room compiler 必需），注释说明原因

### 单元测试 (2026-05-14)
- **新增 `EmbeddingClientTest.kt`** — 21 个测试：构造/URL构建/响应解析/大请求分片/本地引擎回退/空配置检测
- **新增 `VectorizationQueueTest.kt`** — 23 个测试：入队/进度状态机/重试逻辑/失败处理/增量哈希/预处理/中断恢复
- **扩展 `RagViewModelTest.kt`** — 6 个测试：`lastQueueError` 错误持久化/队列状态观测
- **测试结果**: 101 tests, 98% 通过率 (2 预存失败)

### 聊天界面体验优化 (2026-05-14)
- **优化聊天流式输出体验**: 加入 MessageManager 节流（100ms）减少 UI 重绘，改进 SmoothStreamContent 动画衔接防止瞬间跳变。
- **增强自动滚动稳定性**: 优化 ChatScreen 滚动监听逻辑，采用 50ms 批处理与锚点定位，解决高频输出下的滚动卡顿。
- **修复 AI 生成开始时的视图对齐问题**: 确保新气泡自动置顶。
- **引入 `bottom_spacer` 锚点**: 提升长会话末尾滚动定位精度，确保长消息生成的末尾始终能被准确推入视口。

### Phase 5 — UseCase 层抽取方案 (2026-05-13)
- **实施计划**: `.agent/plans/20260513-phase5-usecase-extraction.md`
- **Session P** (先执行): IdGenerator — 统一 7 个 VM 的 ID 生成
- **Session Q+R** (并行): AgentConfigResolver + CreateAgentUseCase + DeleteDocumentUseCase + RagConfigPersistence

### Phase 4 — 核心引擎增强方案 (2026-05-13)
- **实施计划**: `.agent/plans/20260513-phase4-engine-enhancement.md`，2 个并行会话（FolderRepository + 文档导入）
- **不做**: 本地 Embedding 降级

### 模型管理 BugFix: type↔capabilities 联动 + 删除自动复现 (2026-05-13)
- **P0 Bug#1**: 修复在 ProviderModelsScreen 中将模型 type 切换为 embedding/rerank/image 后，capabilities 未同步刷新导致默认模型选择器无法筛选到的问题。新增 `TypeToBaseCaps` 映射表 + `LaunchedEffect(selectedType)` 联动机制（ProviderModelsScreen.kt +15 行）
- **P0 Bug#2**: 移除 deleteModel/toggleModel/deleteAllModels/addCustomModel 回调中多余的 `refreshProviderModels()` 调用，删除/切换操作不再自动触发远端 API 拉取，远端同步仅由手动"Fetch"按钮执行（ProviderModelsScreen.kt -4 行）

### Phase 3 — Super Assistant 清理完成 (2026-05-13)
- **ADR-001 落地**: 删除 SpaViewModel + SpaSettingsScreen（2 文件），移除 PostProcessor.isSuperAssistant 检查
- **11 个文件修改/删除**: "super" agent id → "default"，删除 70 个 spa_* 字符串，清理所有导航回调
- **编译+测试通过**: 457 tests, 1 预存失败；代码库中零 Super Assistant 残留引用

### 通用设置默认模型选择器修复 (2026-05-13)
- **P0 capabilities 构建缺陷**: 修复 `SettingsViewModel.refreshModels()` 和 `addCustomModel()` 中 capabilities 构建逻辑仅处理 chat/vision/internet/reasoning 四种能力，完全遗漏 image/embedding/rerank 及其他 9 种能力标签的严重缺陷。抽取统一的 `buildModelCapabilities()` 方法，根据 ModelType 自动推导基础 capability，并从 ModelSpec.capabilities 补充全部 12 种细粒度能力。
- **P0 自动迁移逻辑**: 在 `ProviderManager.loadModels()` 中实装自动迁移机制。应用启动加载模型时，若检测到模型的 `name` 等于 `id` 或 `capabilities` 不完整，将依据 `ModelSpecs` 静态规格表自动修复并静默持久化到 `SharedPreferences`。这解决了老用户升级后无需重新获取列表即可修复显示名称和功能过滤的问题。
- **P0 subtitle 显示名称**: 修复四个预设模型设置项（摘要/图像/嵌入/重排）的 subtitle 直接显示原始模型 ID 的问题，新增 `resolveModelName()` 辅助函数，优先从已加载模型列表查找友好名称，回退到 ModelSpec.note 静态规格表。
- **P1 ModelSpecs 数据补全**: 为 bge-reranker、jina-reranker、cohere-rerank 三个 Rerank 模型补全缺失的 `capabilities = ModelCapabilities(rerank = true)` 定义。
- **P1 internet→web 命名对齐**: 将 capabilities 中的 `internet` 映射修正为 `web`，与 `ModelCapability.WEB` 枚举一致。
- **P1 模型名称优化**: `refreshModels()` 和 `addCustomModel()` 中模型的 `name` 字段从原始 ID 改为优先使用 `ModelSpec.note`（如 "DALL-E Series"、"BGE Reranker"）。


### 领域层与仓库层架构迁移 (Phase 2a/2c) (2026-05-13)
- **核心架构演进**: 建立了纯净的 Domain 层（模型、接口、枚举），完全消除业务逻辑对 Android 框架的依赖。
- **Repository 全量实装**: 实现了 Agent、Document、Vector、KnowledgeGraph、Provider 等核心仓储，Repository 覆盖率提升至 100%。
- **ViewModel 深度重构**: 全量迁移了 Chat、Settings、Rag、AgentHub、SessionList 等 8 个 ViewModel，消除了所有直接的 DAO 依赖。
- **测试工程化**: 新增 90+ 个文件，包含完备的单元测试（MockK + Turbine），测试覆盖了从 Mapper 到 ViewModel 的全链路逻辑。
- **Git 环境纯净化**: 彻底清理了 RN 时代残余文件，将 `native-kotlin-refactor` 分支正式确立为仓库默认主分支。

### Phase 2c — 剩余 ViewModel 迁移完成 (2026-05-13)
- **3 个 ViewModel 全部迁移**: ChatViewModel / SettingsViewModel / RagViewModel — 消除最后 3 个 VM 的 DAO 依赖
- **架构债 AD-4 消除**: 8/8 ViewModel 全部使用 Repository，零直接 DAO 操作
- **ChatViewModel 5 个历史失败测试修复**: 迁移 agentDao → AgentRepository 时一并解决
- **IAgentRepository 新增 getById**，**IDocumentRepository/IVectorRepository 新增计数方法**
- **测试**: 458 tests, 仅剩 1 个预存失败 (ModelSpecs)
- **RagViewModel 残留**: folderDao 标记 TODO 等待 FolderRepository，VectorStatsService 待 Phase 4 重构
- **Session H**: ChatViewModel（~1100 行，3 处 agentDao → AgentRepository）+ IAgentRepository.getById
- **Session I**: SettingsViewModel（vectorDao/documentDao → Repository）+ 计数方法
- **Session J**: RagViewModel（5 DAO → 3 Repository，folderDao 标记 TODO 待 FolderRepository）
- 全部含单元测试要求，零文件冲突可完全并行

### ViewModel 迁移至 Repository + 单元测试完成 (2026-05-13)
- **5 个 ViewModel 迁移完毕**: AgentHub / AgentEdit / SessionList / DocEditor / KnowledgeGraph — 全部消除直接 DAO 依赖
- **11 个新增测试文件，0 失败**: AgentMapperTest / DocumentMapperTest / KgMapperTest / AgentRepositoryTest / DocumentRepositoryTest / KnowledgeGraphRepositoryTest / AgentHubViewModelTest / AgentEditViewModelTest / SessionListViewModelTest / DocEditorViewModelTest / KnowledgeGraphViewModelTest
- **MockK 1.13.12 + Turbine 1.1.0** 测试依赖已添加
- **IDocumentRepository 补全 getById** 方法，DocEditorViewModel 彻底消除 DocumentDao 依赖
- 测试统计: 445 tests, 6 预存失败 (ChatViewModel × 5 + ModelSpecs × 1), 13 skipped

### ViewModel 迁移至 Repository + 单元测试方案 (2026-05-13)
- **实施计划**: `.agent/plans/20260513-viewmodel-migration-tests.md`，3 个并行会话
- **测试基础设施**: 新增 MockK 1.13.12 + Turbine 1.1.0 依赖
- **Session E**: Agent VM 迁移 (AgentHub/AgentEdit/SessionList) + 6 个测试文件
- **Session F**: Document VM 迁移 (DocEditor) + 3 个测试文件
- **Session G**: KG VM 迁移 (KnowledgeGraph) + 3 个测试文件
- **核心约束**: 所有新增/修改的业务逻辑代码必须编写单元测试并通过

### Domain + Repository 层实施完成 (2026-05-13)
- **28 个文件交付**: 13 domain (4 模型 + 值对象/枚举 + 7 接口) + 11 repository (5 新 + 6 现有) + 4 mapper
- **编译通过**: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- **架构债消除**:
  - AD-1 (Domain 层缺失): `domain/model/` + `domain/repository/` 包建立，零 Android 依赖
  - AD-2 (Repository 覆盖率 37.5%→100%): 7 个聚合根全部有 Repository 接口 + 实现
  - AD-3 (ProviderManager 单例): 收编为 ProviderRepository，实现 IProviderRepository
- **4 个并行会话**: Session A (Domain 基础) → B (Agent+Document) || C (Vector+KG) || D (Provider+对齐)
- **关键实现**: VectorRepository 含余弦相似度/FloatArray BLOB 转换/维度不匹配防御；ProviderRepository 含 ProtocolType Domain↔Data 双向转换

### 文档体系治理与统一 (2026-05-13)
- **文档大清理**: 删除根 `.agent/docs/`（57 文件，含已过时 PRD v1.2.1、6 个失效 repowiki 指针存根）、`.agent/memory/`（4 文件，RN 时代项目记忆）、`.qoder/repowiki/`（145+ 文件，RN 时代自动生成架构文档）、`.roo/skills/`
- **双 .agent/ 合并**: 将 `native-ui/.agent/plans/`（17 个活跃计划）迁移至根 `.agent/plans/`，删除 `native-ui/.agent/` 消除重复
- **废弃 Qoder repowiki 系统**: RN 时代的 145+ 自动生成文档体系不再适用。Kotlin/Compose + IDE 导航能力已足够，DIA 机制 + 手工维护是正确策略
- **废弃 Worktree 发行分支模式**: 原生 Kotlin + Android Studio Build Variant 一键切换 Debug/Release，无环境污染，无需独立 `worktrees/release`
- **新文档结构**: `docs/`（公共 8 份文档）+ `.agent/`（handover + registry + plans）+ `native-ui/AGENTS.md`（项目规则）
- **新增**: `docs/DOCUMENT_GOVERNANCE.md`（文档治理方案）

### 项目目录清理与分支纯净化 (2026-05-13)
- **RN 时代残余清理**: 删除 25 个 RN 目录/文件（`app/`、`src/`、`android/`（Expo prebuild）、`web-client/`、`scripts/`、`plugins/`、`assets/`、`package.json` 等），`native-kotlin-refactor` 分支变为纯粹 Kotlin 原生项目
- **README.md 重写**: 更新为 Kotlin/Jetpack Compose 技术栈描述，中英双语
- **.gitignore 精简**: 移除 `node_modules/`、`.expo/`、`metro`、`npm/yarn`、`TypeScript` 等 RN 时代忽略规则
- **清理方案文档**: `docs/CLEANUP_PLAN.md`

### ContextBuilder 架构修正 (2026-05-13)
- **补充工具调用回传数据层**: `ContextPayload` 中 `webResults: List<WebSearchResult>` 改为 `toolResults: List<ToolCallResult>`，明确网络搜索是工具调用的一种而非独立数据源
- **新增 `ToolCallResult` 数据类**: 统一抽象工具回传（网络搜索/代码执行/文件读写等），含 `toolName`、`summary`、`rawData`、`references`
- **RAG Pipeline 图更新**: 补充工具回传数据源和 ContextBuilder 6 步组装流程
- **进度观测重命名**: `RagProgress` → `ContextBuildProgress`，新增 `InjectingTools`、`LoadingHistory` 阶段
- **PRD 同步更新**: 数据流图中明确"被动检索"与"主动工具回传"两个数据源层级

### 原生版全局审计与架构文档体系建设 (2026-05-13)
- **PRD v2.0 发布**: 基于项目定位与设计初衷，重新撰写 Kotlin 原生时代的完整产品需求文档（`docs/PRD.md`），明确能力边界与开发路线图
- **全局架构设计文档**: 输出理想架构设计方案（`docs/ARCHITECTURE_DESIGN.md`），包含分层架构、Repository 体系、LLM 抽象层、RAG/KG/Agent 引擎、CMP 渐进式迁移路线
- **当前实现分析与开发进度**: 输出深度审计文档（`docs/IMPLEMENTATION_ANALYSIS.md`），全站对照 PRD/架构分析实现差距（总体进度 ~62%）
- **ADR-001 超级助手取舍决策**: 分析 RN 时代到原生时代的 Super Assistant 架构演变，决定**去繁就简**，取消 Super Assistant 特殊概念，统一 Agent 模型（详见 `IMPLEMENTATION_ANALYSIS.md §8`）
- **架构债识别**: 发现 Domain 层缺失（业务逻辑散落）、Repository 覆盖率仅 37.5%（3/8）、ProviderManager 非标准单例模式等关键架构债
- **DIA 更新**: 更新 `ARCHITECTURE.md`、`registry.md`、`handover.md`、`README.md`、`.gitignore`

### 模型选择标准化与功能修复 (2026-05-13)
- **P0 SPA 修复**: 修复超能助手（SPA）设置界面模型选择点击无效的 Bug，补齐了 `SpaViewModel` 模型 ID 持久化逻辑，并接入多模态过滤逻辑。
- **P0 RAG 标准化**: 全量重构 `RagAdvancedScreen`，移除手动实现的模型列表，统一接入 `ModelPicker` 筛选协议并强制应用 `chat` 标签。
- **P1 会话面板对齐**: 优化主会话设置面板（SessionSettingsSheet）的 `ModelPanel` 过滤逻辑，支持 `multimodal`（含视觉）模型展示。
- **P1 过滤器扩展**: `ModelPicker` 新增 `multimodal` 标签，精细化管理对话、推理与视觉能力的复合筛选。
- **P1 通用设置过滤**: 修正摘要、图像、嵌入、重排四类默认模型的筛选逻辑，确保全局一致。


### RAG UI 观测能力与全链路打通 (2026-05-13)
- **P0 检索观测打通**: 重构 `ContextBuilder` 与 `RagProvider` 回调链路，实装从 `MemoryManager` 到底层检索算法的 5 阶段进度上报。
- **P0 UI 指标展示**: 在 `ChatBubble` 中集成 `RagOmniIndicator` 磨砂玻璃指示器，支持实时显示“正在向量化查询...”、“搜索文档...”等状态和百分比进度条。
- **P1 设置持久化修复**: 修复 `RagViewModel` 中 `showRetrievalProgress` 与 `showRetrievalDetails` 的持久化逻辑，确保设置项在应用重启后依然生效。
- **P1 默认配置优化**: 调整 `RagConfiguration` 默认值，默认开启“显示检索进度”与“显示检索详情”，提升新用户开箱即用的观测体验。
- **P2 稳定性与性能**: 修复 `AdvancedRetrievalScreen` 在开启滚动时与顶栏嵌套滚动的冲突，优化 RAG 状态在 `ChatStore` 中的局部刷新效率。
- **P2 UI 细节清理**: 移除 `AdvancedRetrievalScreen` 中重复的“高级检索”大标题，确保页面视觉焦点集中在配置项上。


### 设置颗粒度统一修复 (2026-05-13)
- **P0 搜索配置统一**: 删除 `SettingsViewModel.SearchSettings` 冗余类，统一到 `SearchConfigViewModel`；`result_count` 默认值 8→5（全局统一）
- **P0 enableKG 双源消除**: `SpaViewModel.enableKG` 删除，改为从 `RagConfiguration.enableKnowledgeGraph` 全局读取
- **P1 contextWindow 命名歧义**: SPA 层 `contextWindow` → `uiContextRatio`（避免与 RAG 的 `contextWindow: Int` 混淆）
- **P1 AgentRagConfig 类型统一**: `docChunkSize/chunkOverlap/memoryChunkSize` 从 `Float` 改为 `Int`（对齐 `RagConfiguration`）
- **P1 KG UI 去重**: `AdvancedRetrievalScreen` 的 KG 面板改为只读状态 + 导航链接，配置入口统一到 `RagAdvancedScreen`
- **P2 Agent 继承映射补全**: `AgentRetrievalConfig` 新增 11 个可继承字段（enableMemory/docs/KG, rewrite/提取模型, KG prompt/类型/模式, jitMaxChunks）

### 影响文件 (共 13 个)
- `SpaViewModel.kt`, `SpaSettingsScreen.kt` — P0-2, P1-3
- `AgentConfigModels.kt`, `AgentEditViewModel.kt`, `AgentRagConfigScreen.kt` — P1-4, P2-6
- `SettingsViewModel.kt`, `SkillsScreen.kt`, `WebSearchContextProvider.kt`, `WebSearchSkill.kt`, `WebSearchSearXNGSkill.kt` — P0-1
- `AdvancedRetrievalScreen.kt`, `NavGraph.kt` — P1-5

### Phase 1-3: Markdown 渲染行业对齐（P0 → P1 → P2 全线收官）
- **P0-T1 字号统一修复**: 创建 `chatTypography(fontSize)` 统一字号函数，修复 ThinkingBlock 默认值 14→13，缩减 Slider 上限 22→18sp。现在用户气泡、AI 正文、思维链、LaTeX/Mermaid/ECharts/PlantUML 字号全程一致。
- **P0-T2 CJK 中西文间距**: 实现 `insertCjkSpacing()` 预处理，自动在中文字符与西文/数字间插入 hair space (U+200A)，对标 LobeChat/Cherry Studio 的 remarkCjkFriendly 能力。
- **P0-T3 段落排版与断行优化**: 正文行高提升至 1.6、新增 `paragraph`/`inlineCode`/`quote` 排版项、标题行高统一 1.4，中文排版视觉层次清晰。
- **P0-T4 WebView 字号联动**: ECharts/PlantUML 渲染器接入 `fontSize` 参数，图表内文本随设置同步变化。
- **P1-T1 GFM Alert 支持**: 新增 `GfmAlertBlock.kt`，支持 NOTE/TIP/IMPORTANT/WARNING/CAUTION 五种 GitHub 风格警告块，带对应图标和语义色。
- **P1-T2 LaTeX 定界符兼容**: `normalizeLatexDelimiters()` 将 `\[...\]`/`\(...\)` 自动转换为 `$$...$$`/`$...$`，兼容 Anthropic Claude 等模型输出。
- **P1-T3 流式平滑调速**: 新增 `SmoothStreamContent.kt`，实现字符限速输出 (FAST=55/BALANCED=38/SMOOTH=25 cps)，消除流式输出抖动。
- **P1-T4 标题锚点 ID**: 通过 `blockquote` component override 实现标题语义标记。
- **P2-T1 HTML Artifacts**: 新增 `HtmlArtifactRenderer.kt`，HTML/SVG 代码块支持内嵌 WebView 实时预览 + 全屏分屏模式 + 导出 PNG 到系统相册。
- **P2-T2 代码块可编辑模式**: `CodeBlockWithHeader` 新增编辑按钮，点击切换 `OutlinedTextField` 编辑模式，保存后通过 `onCodeChange` 回调更新代码。
- **P2-T3 图片灯箱增强**: `ImageLightbox` 新增双指缩放 (0.5x-5x)、旋转、分享/保存到相册功能。

### 新增文件
- `ui/common/SmoothStreamContent.kt` — 流式平滑调速
- `ui/renderer/GfmAlertBlock.kt` — GFM Alert 警告块渲染
- `ui/renderer/HtmlArtifactRenderer.kt` — HTML 工件预览与导出
- `docs/MARKDOWN_RENDERING_AUDIT.md` — 渲染能力审计与行业对标
- `docs/IMPLEMENTATION_PLAN.md` — 分阶段实施计划（含 11 个独立 Agent 提示词）

### Markdown 渲染能力审计与行业对齐规划
- **行业调研**: 完成 LobeChat 与 Cherry Studio 渲染能力对标分析，输出完整差异矩阵（见 `docs/MARKDOWN_RENDERING_AUDIT.md`）
- **P0 修复**: 彻底解决了 Markdown 代码块渲染闪退问题（`horizontalScroll` 与 mikepenz `MarkdownCodeFence` 嵌套导致无限宽度约束崩溃）
- **字体诊断**: 诊断出 AI 气泡字号"部分生效"根因——ThinkingBlock 默认值与 ChatBubble 不一致、NexaraTypography.bodyMedium 硬编码 15sp 未被全局替换
- **CJK 排版**: 识别中西文间距优化缺失，规划 `AutoCjkSpacing` 预处理与 `letterSpacing` 规则

### 全局规则更新
- 新增 §7 [Kotlin/Compose] → 5. 滚动容器嵌套红线，覆盖 `horizontalScroll`/`verticalScroll` 与第三方库嵌套的崩溃模式

### 会话界面丰富内容渲染与排版深度优化
- **全局字号穿透**: 彻底解决了 AI 消息气泡中正文、LaTeX 公式、Mermaid 图表字号不一致的问题。通过重构渲染层级，所有丰富内容块现在均能实时响应设置中的字号调节。
- **图片渲染支持**: 集成了 `Coil3ImageTransformerImpl`，解决了 Markdown 远程图片无法显示的问题，并保留了全屏查看交互。
- **输入框占位符本地化**: 修复了预置助手（如超级助手）名称硬编码/启发式转换问题。现在通过 `ChatUiState` 穿透，实时从数据库获取助手的真实本地化名称（如 "超级助手" 替代 "Super"）。
- **WebView 样式联动**: 
    - 优化了 `RichContentWebView`，支持将系统字号注入 WebView 基准字号。
    - 优化了 LaTeX 和 Mermaid 的 HTML/CSS 模板，确保数学符号和图表文本与全局排版风格高度契合。
- **排版鲁棒性增强**: 
    - 引入 `trimIndent()` 预处理，自动修复部分 AI 模型输出时携带的多余缩进，防止标题被错误解析为代码块。
    - 优化了打字机模式下的 Markdown 闭环保护逻辑。
- **UI 清理**: 移除了测试阶段留在顶栏的字号调试信息。

### 品牌资产与 UI 体验
- **全套单色品牌图标**: 实装了 OpenAI, Anthropic, Gemini, DeepSeek, Mistral, Cohere 等 9 种协议的单色矢量图标，彻底移除对远程 Iconify 图标的依赖。
- **UI 对齐全局优化**: 修正了 `NexaraPageLayout` 及所有主页面（首页、设置页）标题相对于内容区域的 4.dp 偏移问题，确保标题与搜索框/卡片左侧严格对齐。
- **提供商配置增强**: 
    - 为自定义提供商添加了全新的 `ProtocolSelector` 图形化选择器。
    - 重构了 `ProviderPreset` 预设逻辑，实现了全站提供商图标的统一。
- **稳定性修复**: 修复了点击 "Custom" 协议选择器时由于嵌套垂直滚动容器导致的 `IllegalStateException` 崩溃。

### 自动化构建与发布
- **发行版 APK 编译**: 
    - 成功配置并使用 `promenar.keystore` 签名编译了首个 Android 发行版 APK (`app-release.apk`)。
    - 修复了 `app/build.gradle.kts` 中硬编码的 Windows 路径，增加了对 macOS 等跨平台开发环境的适配逻辑。

### 诊断与开发者工具
- **新增**: **开发者面板 (Developer Panel)**: 实现了独立的开发者设置二级页面，提供设备信息查看、运行日志导出与清除功能。

### UI 标准化与品牌化
- **品牌化重命名**: 将对话页面的 Header 标题由“智能助手”统一修改为 **"Nexara"**，确保全平台品牌标识一致。
- **助手设置入口补全**: 重构了 `AgentSessionsScreen` 的布局，确保“配置”按钮在无会话状态下依然可见并可访问。
- **视觉一致性优化**: 
    - 统一三大主页面（对话、知识库、设置）Header 标题坐标，移除所有副标题，统一使用 `TopAppBar` 渲染。
    - 统一主搜索栏样式与高度（48.dp），并在两个页面均实现 `stickyHeader` 吸顶效果。
- **UI 组件标准化 (NexaraSlider)**:
    - 针对原生 Material Design 3 Slider 视觉效果过于厚重的问题，设计并实现了 `NexaraSlider` 自定义组件。
    - 采用更纤细的轨道设计、带阴影的精致滑块、平滑过渡动画，并默认移除散乱的刻度点。
    - 完成了全站（对话设置、RAG 配置、模型推理参数、调色盘等）Slider 的标准化替换，提升了交互体验的一致性与优雅度。

### 修复与优化
- **Markdown 渲染崩溃修复**: 解决了在渲染包含大型表格或长代码块的复杂 Markdown 时，由于水平滚动容器与 `fillMaxWidth()` 冲突导致的崩溃。
- **修复**: 主会话界面键盘避让逻辑优化，防止输入栏被遮挡。
- **修复**: 修复了全新安装时模型列表为空（缺失能力映射）以及 Provider 添加后模型同步延迟的问题。
- **RAG 增强**: 细化了向量化过程的进度描述，并增加了 App 启动时自动恢复中断任务的逻辑。
- **优化**: 设置界面 UI 调整，将“关于”按钮改为“开发者面板”入口，并将项目 GitHub 链接移至底部标签。

### 聊天会话管理功能重构
- **菜单位置修复**: 解决了聊天界面右上角三点菜单位置偏移的问题，将其正确锚定在操作按钮下方。
- **核心功能实现**:
    - **清除历史**: 实现了清空当前会话所有消息的功能，并配有二次确认对话框。
    - **重命名会话**: 实现了自定义会话标题的功能，新增毛玻璃样式的重命名对话框。
    - **删除会话**: 实现了彻底删除当前会话及其所有内容的功能。
- **国际化补全**: 为上述功能补全了中英文资源，并修复了中文资源中的字符乱码问题。
- **UI 细节优化**: 将会话管理菜单项与标准 Material 图标（History, Edit, Delete）对接。

### Markdown 富文本渲染能力升级 (MD-S1 ~ MD-S5)

- **Markdown 渲染引擎**: 集成 mikepenz/multiplatform-markdown-renderer v0.40.2，支持完整 Markdown 语法（标题、列表、代码块、粗体、表格等）
- **LaTeX 数学公式**: 引入基于 WebView 的 KaTeX 离线渲染基座，支持 `$$ ... $$` 块级数学公式渲染
- **高级可视化**: 接入 Mermaid 流程图与 ECharts 数据图表渲染能力，支持在会话中直接展示动态图表
- **代码高亮与交互**: 集成 `multiplatform-markdown-renderer-code` 模块，新增带语言标签和一键复制功能的代码块 Header
- **流式输出保护**: 新增 `sanitizeStreamingMarkdown()` 预处理，自动修补未闭合代码围栏、截断未闭合 LaTeX 块，确保打字机输出流畅不闪烁
- **ThinkingBlock Markdown**: AI 思考过程的 reasoning 文本支持 Markdown 格式渲染（粗体、列表、代码等）
- **ChatBubble 全面接入**: 助手消息全部使用 MarkdownText 渲染，统一了 AI 回复与思考过程的视觉体验

### Fixed
- **Agent 状态同步**: 修复了 `AgentEditViewModel` 中 `StateFlow.combine` 的类型安全问题，解决了修改助手设置时因类型不匹配导致的 UI 状态更新异常。
- **资源清理**: 移除了 `MarkdownText.kt` 中的冗余引用，优化了代码洁净度。

## [1.4.0] - 2026-05-09

### UI/UX Consistency
- **全局 Modal 高度限制**: 为了提升原生 Android 版本的视觉一致性，扫描并更新了全站所有 `ModalBottomSheet` 组件，将其内容高度统一限制在屏幕的 70% (`fillMaxHeight(0.7f)`)，避免了内容过多时撑破屏幕导致的不雅观现象。
- **设置界面精简与优化**: 
  - 移除了冗余的设置项间隔和文字标题，使设置界面视觉更加连续和沉浸。
  - 移除了“振动反馈开关”，将其功能设为默认开启。
  - 移除了“日志”相关冗余功能。
- **消息气泡布局优化**: 主会话界的 AI 回复气泡改为全宽布局，大幅提升了长文本和代码块的阅读体验，同时保持了优雅的左右间距。
- **启动逻辑调整**: 优化了启动界面的触发逻辑，确保仅在应用首次安装/打开时显示。

### Added
- **备份系统 (Backup System)**: 全面重构备份功能，彻底移除占位符。
    - 实现 `BackupRepository`，支持 Room 数据库实体的 GZIP JSON 序列化。
    - 集成 Android SAF (Storage Access Framework)，支持本地 `.nexara` 备份文件的导出与导入。
    - 实现 WebDAV 协议同步，支持云端备份上传与还原。
    - 为核心 Entity 类（Agent, Session, Message, Skill, Document）添加 `@Serializable` 支持。
- **BackupViewModel**: 引入备份业务状态机，实现备份配置的持久化存储。

### Fixed
- **流式传输与思考过程**: 修复了主会话文本瞬间显示的 Bug，并确保了思考过程（Thinking Process）UI 组件能正确展示。
- **模型选择持久化**: 修复了主会话输入框上方模型选择器退出后再进入会话重置的问题，移除了 mock 的 `gpt-4o`，确保未配置时显示为 empty。
- 修复设置页“备份”项配置无法保存的问题。
- 修复“工具”设置页底部显示不全及样式生硬问题。
- 移除“设置”中重复的“主题色”选项。
- 移除占位用的“工作台”功能。

## [Unreleased]

### 新增
- **智能上下文管理**:
    - 在 `ChatViewModel` 中实现了滑动窗口上下文管理。
    - 增加了溢出消息自动归档至 RAG (长期记忆) 的功能。
    - 集成了当 Token 使用量超过阈值时的自动摘要功能。
    - 在 UI 中增加了手动触发摘要的按钮。
- **增强型聊天 UI**:
    - 在聊天界面增加了实时的 Token 指示器，支持详细的使用量分解。
    - 为聊天气泡增加了时间戳和模型标签。
    - 优化了思考过程的展示方式，支持展开/折叠块。
    - 增加了消息操作菜单：复制、编辑、删除、重发/重新生成。
- **会话设置**:
    - 增加了请求超时、自动摘要阈值和活跃上下文窗口的滑块。
    - 增加了被动 RAG (长期记忆) 的开关。

### 变更
- 重构了 `PostProcessor` 以支持批量消息归档。
- 优化了 `ContextBuilder` 以支持历史摘要注入。
- 更新了 `ChatScreen` 输入栏布局，集成了 Token 统计。

### 修复
- **主会话第一个气泡重发逻辑**: 修复了在会话第一个气泡执行“重发”操作时导致整个会话消息记录被清空的严重 Bug。
    - 调整了 `deleteMessagesAfter` 的调用时间戳，确保保留触发操作的 User 消息本身。
    - 修复了 `editMessage` 中由于消息被错误删除导致的编辑内容无法保存的问题。
- **主模型选择器筛选**: 修复了会话设置和模型选择器中会出现 Embedding 或 Rerank 模型的问题。
    - 现在主模型列表仅筛选 `chat`、`reasoning`、`image` 类型的模型。
    - 同步更新了通用 `ModelPicker` 的过滤逻辑，将 `IMAGE` 归类至主对话能力。

## [v0.9.5] - 2026-05-09

### Fixed
- **模型预设选择逻辑修复**: 彻底解决了原生 Android 版本设置页面中，功能模型（嵌入、重排、图像）选择器点开后列表为空的问题。
  - 优化了 `UserSettingsHomeScreen` 的模型映射逻辑，整合 `type` 与 `capabilities` 字段，确保所有功能模型均能被正确过滤。
  - 修复了模型选择器中 `contextLength` 等元数据传递缺失的问题。

## [0.2.5] - 2026-05-09

### Fixed
- **模型预设选择逻辑修复**: 彻底解决了原生 Android 版本设置页面中，功能模型（嵌入、重排、图像）选择器点开后列表为空的问题。
  - 优化了 `UserSettingsHomeScreen` 的模型映射逻辑，整合 `type` 与 `capabilities` 字段，确保所有功能模型均能被正确过滤。
  - 修复了模型选择器中 `contextLength` 等元数据传递缺失的问题。

### Native RAG & Persistence
- **RAG 设置持久化修复**: 解决了原生 Android 版本中知识图谱和高级 RAG 设置在退出页面或重启应用后重置的问题。
  - 在 `RagViewModel` 中实现了基于 `SharedPreferences` 的完整配置持久化方案。
  - 覆盖了知识图谱开关、抽取模型、Prompt、JIT 块限制、成本策略等所有 30+ 项参数。
- **抽取模型动态化**: 移除了知识图谱设置中的 Mock 数据，现在“抽取模型”选择器会动态加载用户在“供应商管理”中实际配置的模型。
  - 修复了点击选择器后列表为空的问题，并能正确显示已选模型的名称。
  - 增加了模型选择占位符的国际化支持。
- **知识图谱可视化真实对接**: 修复了知识图谱可视化界面使用 Mock 数据的问题，现已对接数据库真实实体数据。
  - 实现了 `KnowledgeGraphViewModel` 用于管理图谱状态与随机坐标布局生成。
  - 在顶栏新增了“注入测试数据”与“清空图谱”调试按钮。
- **原生会话面板全量修复**: 彻底解决了主会话相关面板（设置、工作区）的 Mock 数据与 UI Bug。
  - **会话设置**: 实现了模型动态加载（SettingsViewModel）、思考等级（温度控制）、Token 统计以及工具开关（时间注入、检索、网页搜索）的真实对接。
  - **工作区**: 实现了任务进度（Tasks）与工具产出（Artifacts）的真实对接，动态展示会话内的执行状态。
  - **视觉 Bug**: 修复了所有 `TabRow` 页面（设置、工作区、插件管理）中指示器渲染异常导致的“紫色条”问题。
- **稳定性修复**: 修复了点击助手设置图标导致的闪退问题。该问题由 `LazyColumn` 中嵌套 `LazyVerticalGrid` 引起的布局计算异常导致，现已重构为非滚动嵌套布局。
- **助手设置重构**: 重构了助手编辑页面 UI，引入动态折叠交互和自定义图片上传功能。
- **功能对接与持久化**: 完善了助手编辑页面所有字段（模型、提示词、头像、置顶等）的前后端对接与持久化，接入了真实模型列表。

### UI/UX
- **模型管理功能增强**: 原生版本模型管理界面（ProviderModelsScreen）新增“图片”、“嵌入”、“重排”功能标签，支持用户手动校准模型能力。

### Added
- **模型规格库扩展**: `MODEL_SPECS` 数据库新增了数十种常用模型规格，包括：
  - **嵌入模型**: OpenAI text-embedding-3, BAAI bge-m3 等。
  - **重排序模型**: BGE Reranker, Jina Reranker 等。
  - **图像模型**: DALL-E, Stable Diffusion, Flux 等。
  - **智谱 GLM 系列**: 补全了 GLM-4.7, 4.5 等高能力模型的规格配置。

## [1.2.52] - 2026-02-17

### Changed
- **Library UI Performance Optimization**: 文库界面全面性能优化
  - **PortalCards 组件**: 从内联定义提取为独立 `memo` 组件，避免每次渲染重新创建
  - **列表项动画**: `FadeIn/FadeOut` 时长从 200ms/150ms 优化为 120ms/80ms，提升滚动流畅度
  - **RagStatusIndicator**: 呼吸灯动画改为按需运行，空闲时自动停止降低 CPU 占用
  - **KnowledgeGraphView**: 新增 HTML 模板缓存机制，避免重复字符串生成
  - **批量操作工具栏**: 添加 `SlideInUp/SlideOutDown` 弹簧动画

### Docs
- 新增文库界面审计报告 (`docs/archive/library-audit-2026-02-17.md`)

## [1.2.51] - 2026-02-17

### Changed
- **Button 组件重构**: 添加弹簧缩放点击反馈动画，支持 children 属性
- **Card 组件重构**: 添加弹簧缩放点击反馈动画，优化可点击卡片交互手感
- **AnimatedSearchBar 优化**: 图标透明度动画与容器动画同步，缩短动画时长至 250ms
- **Switch 组件优化**: 关闭状态颜色适配动态主题，使用半透明色替代硬编码色值
- **Toast 动画优化**: 缩短进入动画时长，优化弹簧参数提升轻盈感
- **动画配置扩展**: 新增 SPRING_BUTTON、SPRING_CARD、SPRING_TOAST 专用配置
  - 新增 ScaleIn/ScaleOut、ToastEnter/ToastExit、ListItemEnter/ListItemExit 预设

### Fixed
- **GlassAlert 触感反馈**: 添加 10ms 延迟保护，遵循 Native Bridge 防御规则

## [1.2.50] - 2026-02-17

### Changed
- **ContextMenu 全面重构**: 优化悬浮菜单组件的视觉设计、交互跟手性和性能表现
  - 修复触摸点坐标偏移问题，菜单不再被手指遮挡
  - 重构阴影层级结构，消除透明背景阴影溢出问题
  - 优化边框颜色，使用半透明边框提升视觉精致度
  - 缩短长按触发阈值从 250ms 到 200ms
  - 添加弹性缩放动画，菜单弹出更具质感
  - 简化动画层级，移除双层 Animated.View 嵌套
  - 添加 `isMounted` 安全检查，防止组件卸载后状态更新
  - 使用 `useWindowDimensions` 响应屏幕旋转

### Fixed
- **触摸区域优化**: 扩大三点图标触摸区域至 44x44px (Apple HIG 标准)
  - CompactDocItem、MemoryItem、FolderItem、RagDocItem 组件触摸区域统一优化
- **图标尺寸统一**: 三点图标从 16px 调整为 18px，提升可点击性

## [1.2.32] - 2026-02-09

### Changed
- **Markdown Line Breaks**: Configured renderer to treat all soft line breaks (single newlines) as hard breaks (`<br>`). This ensures that poem-like structures and chat messages are displayed exactly as output by the model, preventing unwanted text merging.
- **CJK Rendering**: Reverted aggressive CJK whitespace optimization to prevent destruction of Key-Value formatting and other structured text. Adopted "Preserve Newlines" strategy for maximum compatibility.


- **Knowledge Graph Node Merge**: Introducing ability to merge nodes when renaming to an existing node name. Automatically transfers relationships and merges metadata.
- **Glass UI Enhancements**: New `GlassAlert` component replacing native alerts for consistent design. `KGNodeEditModal` updated to true Glass Header blur style.

### Fixed
- **RedBox Error Suppression**: Handled "UNIQUE constraint failed" errors gracefully in Graph Store, preventing app crashes during node operations.
- **Type Safety**: Resolved TypeScript errors in Knowledge Graph components.
- **UI Consistency**: Aligned modal transparency and border styles with Session Toolbox.

## [1.2.28] - 2026-02-08

### Fixed
- Fixed Markdown rendering issue where single newlines (soft breaks) were collapsed in chat bubbles for models like DeepSeek/OpenAI.
