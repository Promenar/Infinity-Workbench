# NeuralFlow 项目工程记忆 (project_memory.md)

## 核心技术选型与最佳实践

### 1. 动画与布局过渡 (React Native Reanimated)
- **LinearTransition 策略**：在存在频繁高度变化（如 Header 伸缩）的页面中，必须为受到影响的下层容器添加 `layout={LinearTransition}`。
- **动画组合红线**：禁止在 Reanimated 属性中使用 `+` 运算符组合动画（如 `FadeIn + SlideInDown`），必须使用 `withSequence` 或拆分为独立的嵌套容器。

### 2. Android 平台渲染陷阱
- **Elevation 与透明度冲突**：在 Android 上，当 `elevation` > 0 时，容器背景不能设置半透明（如 `bg-white/98`），否则会渲染出不透明的杂色块。解决方案：使用纯色背景并配合 `shadow` 相关样式实现 iOS 侧的柔和感。

### 3. 用户体验 (UX) 指纹
- **触感分级 (Haptics)**：
    - `Impact.Light` 在部分 Android 手机上近乎无感，建议使用 `Impact.Medium` 作为常规确认，`Notification.Success` 作为重大状态切换后的反馈。
- **视觉连续性**：在涉及大量列表项位移时，布局过渡时长建议设定在 `300ms - 400ms` 之间，以提供类似“液态”的流动感，避免视觉断层。

### 4. 架构与语法安全
- **JSX 深度防御**：当单文件超过 300 行且包含复杂动画嵌套时，应主动将子模块（如 `renderHeader`, `renderItem`）提取为独立函数或组件，以降低 Babel 解析错误概率并提升热重载性能。
