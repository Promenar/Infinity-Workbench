# Nexara DocEditor Material 3 视觉验收

- 验收日期：2026-07-17
- 验收范围：第四阶段 DocEditor Material 3 页面骨架、状态、响应式、真实 IME、长代码横向可达与截图基线
- 设计参考：`docs/superpowers/specs/assets/nexara-md3-option-3-reference.png`
- 参考图 SHA-256：`a0065ea2aa606b1699c025d37ff35ac6467d75b0efebea95cd4414a87031395f`

## 视觉状态矩阵

已逐张检查以下 11 个 DocEditor 状态：

1. 英文手机加载态
2. 中文 2.0x 字体加载失败态
3. 英文手机编辑脏态
4. 中文 2.0x 字体保存冲突态
5. 英文富 Markdown 预览态
6. 英文平板分屏态
7. 中文平板大文件态
8. 英文手机放弃修改对话框
9. 英文横屏编辑态
10. 英文精确 800x360 编辑态
11. 中文 360x800 索引待重试态

检查项包括：页面层级、Material 3 色彩与形状、标题和元数据权重、主内容可读性、返回与保存图标、48dp 操作目标、2.0x 字体裁切、横屏密度、冲突双动作、索引重试提示、对话框层级、长代码横向可达和状态信息不只依赖颜色表达。

## 本轮发现与修复

- 精确 800x360 下长标题越过身份区域并与元数据重叠：已通过边界裁切修复。
- 富 Markdown 中独立长行内代码被软换行并产生孤立尾部：已基于 Markdown AST 为真正的顶层宽内容提供独立横向滚动，同时保留语法高亮与普通 Markdown 上下文。
- 截图夹具的字数固定为 0：已改为按预览内容计算，避免视觉基线掩盖状态错误。
- 360x800 索引待重试状态缺少专用基线：已补充中性色通知、警告图标、明确重试文案和可操作重试按钮。

## 自动化证据

- `DocEditorInteractionTest`：API 31、35、36 分别 31/31 通过，0 skipped、0 failures、0 errors；覆盖真实 IME、800x360、360dp/2.0x、冲突恢复、索引待重试和 CJK/Emoji 长代码横向可达。
- Markdown 宽内容与嵌套表格设备测试：API 36 聚焦 4/4 通过；CodeBlock 工具栏无障碍设备测试 2/2 通过。
- `validateDebugScreenshotTest`：56/56 通过，0 skipped、0 failures、0 errors。
- 全量 JVM：1915 tests，0 failures、0 errors、14 个既有配置/真实网络条件 skip；Lint、Debug APK、deviceTest APK 与 AndroidTest 编译通过。
- API 36 完整设备门禁：常规 bulk 228 tests，0 failures、3 个既有分阶段 checkpoint skip；两项后台冷启动恢复方法按既有 force-stop 协议分别 1/1 通过。
- 性能门禁：两次完整 6/6、共 60 个原始样本全部直接接受，没有拒绝或替换超标样本；最差 p95 35 ms、最大帧 40 ms、PSS 增量 12,194 KiB，均低于 50 ms / 150 ms / 64 MiB 门禁。
- `git diff --check`：通过。

本文件只判定 DocEditor 视觉与交互设计验收。独立第四轮代码与视觉复核结论为 C0/C1/C2/C3=0、P0/P1/P2=0；完整发行仍须完成签名 APK 真机 TalkBack 人工听觉/全焦点、核心业务人工验收和 GitHub tag/Release。

宽代码真实像素宽度、IME 保存终态反馈、代码块工具栏触控/本地化、嵌套 GFM 表格横向可达及性能样本替换风险均已修复并重新验收。

final result: passed

---

# 会话任务进度面板 Material 3 视觉验收

- 验收日期：2026-08-01
- 源方案：`/Users/promenar/.codex/visualizations/2026/07/31/019fb5e7-35b5-7e93-8c9d-dd4203ce2c8e/nexara-task-panel-final-runtime.png`
- 同屏对比：`native-ui/app/build/reports/task-panel-md3-comparison.png`
- 覆盖基线：待确认、真实生成、中文 2.0x 字体三种任务状态。

## 视觉结论

- 任务卡已从 composer 移入消息流；composer 保留模型、Token 与第三任务 `AssistChip`，不再使用大描边容器包裹整组控件。
- 真实生成态显示脉冲“正在生成”，生成停止但仍有未销项任务时显示静态“待确认 N”，状态不再只依赖模型是否正确销项。
- 卡片仅保留标题、`x/y` 数字进度、当前未完成项和两个人工动作；没有进度条、“计划待确认”或本轮结束说明。
- 实现使用目标分支的 `MaterialTheme`、语义 surface、Shape/Spacing token、标准文本按钮和 48dp 触控目标，没有恢复 Glass 组件或硬编码容器圆角。
- 已逐张查看三个基线与源图/实现合并对比图。2.0x 字体下胶囊自然换行，输入栏与操作仍可达；当前步骤末字孤行是非阻断视觉瑕疵。
- GPT-5.6 Sol 对合并图和三个目标截图进行独立主观视觉验收，结论为 PASS。

## 自动化证据

- `validateDebugScreenshotTest`：通过。
- API 35 `ChatScreenContentStateTest`：10/10 通过。
- API 35 `AccessibilitySmokeTest`：9/9 通过。
- 全量 JVM：2125 项，0 failure、0 error、14 skip；Lint 0 Error/Fatal；Debug APK 构建通过。
- `git diff --check`：通过。

final result: passed
