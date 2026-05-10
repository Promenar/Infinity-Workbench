# CHANGELOG

## [Unreleased]

### Planning
- [Inference] **本地模型功能审计**：确认 `LocalModelsScreen` 及全套端侧推理链路为纯占位符，零实际实现。创建完整补完实施方案，选型 llama.cpp + JNI + GGUF，详见 `.agent/plans/20260510-local-model-implementation.md`
- [Inference] **会话拆分方案**：将实施拆分为 7 个独立会话，每会话附带即复制即用提示词，详见 `.agent/plans/20260510-local-model-sessions.md`
- [Inference] **架构升级**：引入 `InferenceBackend` 抽象接口，为远期 ggml-hexagon NPU 加速和 ExecuTorch QNN 预留插拔式扩展点

### Fixed
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
