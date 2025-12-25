# NeuralFlow 项目开发历史 (HISTORY.md)

## [2025-12-25] - Library 交互精度与稳定性重塑

### 新增 (Added)
- **液态布局过渡 (Liquid Transitions)**：为 Library 页面引入了 `LinearTransition` 布局动画，实现了从 Header 到列表项的全屏协调平移，消除了模式切换时的瞬移感。
- **3D 悬浮操作栏 (3D Action Bar)**：重新设计了多选模式操作栏，通过 `elevation: 15` 和深度偏移投影，强化了界面层级的空间感。

### 修复 (Fixed)
- **致命语法错误**：修正了 `rag.tsx` 中由于嵌套过深导致的 JSX 闭合对齐错误及非法的动画叠加语法（解决红屏崩溃）。
- **Android 渲染异常**：修复了 Android 系统上 `elevation` 属性与半透明背景冲突导致的白块渲染 Bug。
- **触感反馈增强**：将核心交互（进入管理模式、勾选切换）的震动机级提升至 `Success` 和 `Medium`，解决了反馈感微弱的问题。
- **布局回归**：补回了之前因操作误删的 "Documents" 分组标题。

### 优化 (Improved)
- **全局 Header 规范对齐**：统一了 Library 和 Settings 页面的标题字号（32px）、字重（Black）及间距，与 Chat 页面保持高度一致。
- **交互逻辑提速**：取消了选中标记消失时的退场动画，使退出管理模式的视觉回馈更加干脆利落。
