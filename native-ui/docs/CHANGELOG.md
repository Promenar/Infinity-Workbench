# CHANGELOG

## [Unreleased]

### Fixed
- [Stability] **运行时崩溃修复**：彻底解决了 Markdown 渲染过程中由横向滚动条测量无限宽度引起的 `IllegalStateException` 闪退问题。
- [UI/Chat] **模型选择器补完**：修复了真机环境下模型选择器全为空的 Bug，补全了 `SettingsViewModel` 在获取模型时缺失的 `chat` 能力标签映射。
- [UI/Chat] **上下文上限动态计算**：优化了 Token 占用指示器的上限获取逻辑，支持通过本地 SharedPrefs 动态匹配不同模型的 Context Length，修复了上限恒定为 128k 的问题。
- [UI/Chat] **流式渲染优化**：改进了 `sanitizeStreamingMarkdown` 逻辑，解决了流式输出时 Math 与 Code 块渲染闪烁及不显示的问题。
- [RAG] **统计稳定性增强**：优化了 `VectorStatsService` 的类型匹配逻辑与 `RagViewModel` 的数据观测流，修复了索引完成后统计显示为 0 的潜在竞争问题。
- [UI/Chat] **交互逻辑重映射**：修复了聊天页 TopBar 按钮功能，设置按钮正确弹出工作区面板（WorkspaceSheet），并实装了三点菜单的下拉操作项（清空历史、重命名、删除会话）。
- [RAG] **UI 冗余清理**：移除了 RAG 首页底部重复的进度条浮窗，将所有索引状态统一收敛至列表顶部的进度组件中。

### Added
- [RAG] **颗粒化进度指示器**：重构了 `IndexingProgressBar`，支持显示“切块处理”、“发送向量”、“保存入库”、“知识提取”等细分状态及 sub-status 文字描述。
- [UI/Chat] **动态上下文指示器**：将静态 Context 图标替换为动态圆环占比组件（ContextCircularIndicator），实现 Token 占用百分比的实时可视化。

### Planning
- [Inference] **本地模型功能审计**：确认 `LocalModelsScreen` 及全套端侧推理链路为纯占位符，零实际实现。创建完整补完实施方案，选型 llama.cpp + JNI + GGUF，详见 `.agent/plans/20260510-local-model-implementation.md`
- [Inference] **会话拆分方案**：将实施拆分为 7 个独立会话，每会话附带即复制即用提示词，详见 `.agent/plans/20260510-local-model-sessions.md`
- [Inference] **架构升级**：引入 `InferenceBackend` 抽象接口，为远期 ggml-hexagon NPU 加速和 ExecuTorch QNN 预留插拔式扩展点

### Fixed
- [RAG] 统一知识图谱（KG）抽取模型选择器，支持 `ModelPicker` 过滤 `chat` 能力模型。
- [RAG] 改进高级检索设置语义，将“连接知识服务器”更名为“启用混合搜索”，并实装可观测性开关。
- [UI/Chat] 修复思考胶囊（Thinking Capsule）无法展开的问题，增加自动展开逻辑。
- [UI/Chat] 优化思考内容渲染，支持 Markdown 格式。
- [Stream] 增强 OpenAI 协议解析稳定性，实装动态超时逻辑（默认 120s，最高 300s），解决生成卡顿与假死。
- [Local Models] 修复活跃插槽高度不一致的问题，确保所有槽位视觉统一。
- [Hub] 移除首页智能体列表界面的 FAB 悬浮按钮，精简 UI 层级。
- [RAG] 优化知识库顶部状态卡片视觉样式，将“记忆”计数从字节改为条数，并提升数字展示的视觉重心。
- [RAG] 修复“集合”区域“新建”按钮无效的问题，实装了新建文件夹功能及对话框。
- [Backup] 修复 WebDAV 同步开关布局重叠问题，将冗余的 `SettingsToggle` 替换为标准 `Switch`。
- [UI/Chat] 移除 intrusive 的红色错误 Snackbar，改为气泡内小字提示。
- [UI/Chat] 为消息气泡添加发送时间戳与 AI 模型标识（采用统一的暗色小字样式）。

### Added
- [UI/Chat] **消息管理系统**：支持消息长按菜单，提供复制、编辑、删除、重发功能。编辑或重发将自动同步清理后续历史。
- [Settings] **会话超时设置**：在会话详情中增加“请求超时”滑块，支持按需调整推理等待时长。
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
- [RAG] **UI/UX 优化**：将知识库导入区域从 Web 端的“拖放”模式重构为移动端“点击弹出”模式，适配 Android 系统文件选择器
- [RAG] **系统分享集成**：支持从外部 APP 分享文件至 Nexara 进行知识库导入，并实装了 `SEND` / `SEND_MULTIPLE` 处理逻辑
- [RAG] **实时状态反馈**：实装了向量化队列进度在 RAG 主页的实时同步显示功能
- [RAG] **UI 精简与数据审计**：移除了“集合”区域冗余的图谱跳转按钮；重构了统计逻辑，确保“文档”与“图谱”计数直接查询自数据库，消除了 UI 初始化时的显示延迟
- [User] **实装头像自定义**：支持用户点击头像通过 Android 原生媒体选择器更换头像，支持图片与视频格式，并实装了 URI 权限持久化与 Coil 视频帧解码支持
- [User] **设置项精简**：移除了设置页中与“外观”功能重复的“主题色”入口，提升页面简洁度
- [Settings] **精简本地引擎配置**：移除设置主页中冗余的“启用本地引擎”开关，将其收敛至“本地模型”详情页中，并统一了状态的持久化逻辑。
- [Model Management] **优化模型名称显示**：在模型管理中默认优先展示模型 ID，增强辨识度。
- [Model Management] **模型能力标签去重**：自动排除与模型主类型重复的能力标签，保持界面整洁。
- [Model Management] **修复默认模型筛选**：增强了设置页“默认模型”选择器的筛选逻辑，确保正确加载可用模型。
- [Chat] **修复模型选择器闪退**：修复了 `SessionSettingsSheet` 因缺少 `IMAGE` 能力颜色映射导致的崩溃问题。
- [UI] **优化模型管理操作按钮**：增加了模型管理页面顶部操作按钮（自动获取、添加等）的高度，使其视觉上更协调。
- [UI] **修复提供商图标显示**：为预设提供商增加了 Material Icon 兜底展示，解决了 OpenAI、Anthropic 等图标加载失败的问题，并优化了 Local 和 Custom 的图标。
