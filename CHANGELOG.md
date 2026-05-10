# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

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
