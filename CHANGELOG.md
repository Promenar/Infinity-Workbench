# Changelog

All notable changes to this project will be documented in this file.

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
