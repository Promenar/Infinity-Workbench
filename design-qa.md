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

# 设置全层级 Solid Explorer 连续列表视觉验收

- 验收日期：2026-08-04
- 视觉目标：`/Users/promenar/.codex/generated_images/019fb5e7-35b5-7e93-8c9d-dd4203ce2c8e/exec-d2105ed2-67be-452c-a2f8-807a9c1a74c0.png`
- SE 参考：`/Users/promenar/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files/lengzehua_8cef/temp/RWTemp/2026-08/9e20f478899dc29eb19741386f9343c8/6de85487ec40e0fea33906d81f1eca39.jpg`
- 实现截图：`native-ui/app/build/outputs/screenshotTest-results/preview/debug/rendered/com/promenar/nexara/ui/ReleasePreviewScreenshotTestKt/userSettingsIntegratedNavigationChinesePhoneReleasePreview_User settings integrated navigation Chinese phone_8b19e30b_0.png`
- 同屏对比：`native-ui/app/build/design-qa/settings-home-iconless-two-line-comparison.png`
- 视口与状态：360×800dp、360×800px、中文、深色、设置 Tab 选中；并复核手机、横屏、平板、英文/中文与 2.0x 字体状态。

## 对比结论

- **字体**：全设置层级采用 Material 3 标准角色。一级导航与深层设置行使用 16sp/24sp 主标题和 14sp/20sp 副标题；二至四级页面标题为 22sp/28sp，分组标题为 14sp/20sp，不再沿用 10–14sp 的压缩体系。2.0x 字体自然放大并滚动，没有内容重叠。
- **间距与布局**：一级入口使用标准双行 `ListItem` 的 72dp 节奏、16dp 水平起点和统一文字轴线；取消 48dp 前导图标列。账户区独立为至少 88dp 的身份头部，头像 56dp、用户名 22sp/28sp，与普通设置项形成明确层级。
- **颜色与表面**：使用现有 `MaterialTheme.colorScheme` 的中性黑紫背景、主色分组标题、正文/次要正文和低对比分隔线；普通设置项不再使用逐项卡片或 Glass 容器。
- **图标**：首页设置入口不再渲染前导图标、箭头或账户铅笔；尾部空间只为未来真实状态或操作保留，不使用装饰元素暗示可点击。
- **信息层级**：一级页恢复简短功能副标题，但不显示语言、主题、Provider 数量、模型数量、费用或版本等动态状态。整行按压反馈、Button 语义及至少 48dp 触控目标继续表达可操作性。
- **响应式与行为**：设置首页、主题、Provider 列表/表单/模型、默认模型、RAG、知识图谱、检索、技能、备份、本地模型、开发者和用量页共用设置骨架；返回、搜索、开关、单选、表单和底部动作行为保持不变。
- **导航边界**：`MainTabScaffold.kt` 为零 diff。底部三按钮导航的宽度、槽位、选中胶囊和动画未改动；集成截图只验证新设置页面在原导航容器内的构图。

## 发现、修复与复审

- 第一轮实现存在首屏密度偏低和“知识图谱”孤字换行 P1；第二次人工反馈仍指出字号与显示密度显得粗糙。通过设置链路独立的 10–20sp 文字层级、48dp 前导列、32dp 尾随图标列、18dp 线性图标及紧凑分组 token 完成二次修复，没有降低触控目标。
- 实机继续验证后确认根因是一级页固定双行导致字号被迫缩小，而非单纯字号 token。第三轮删除一级页全部副标题、状态、箭头及账户铅笔，并把标题恢复为 16sp；同屏对比中页面重心更安静，文字可读性明显提高。用户选择暂时保留左侧图标作为扫描锚点。
- 第四轮重新对照 SE 后进一步区分“副标题存在”与“压缩排版”两个变量：移除前导图标和尾随装饰，副标题改为 14sp/20sp 的稳定功能说明，主标题恢复 16sp/24sp；账户身份另用 56dp 头像和 `titleLarge`。同屏对比显示文字起点、主副层级和组间节奏已接近 SE，且比第三轮单行版更易读，P0/P1/P2 为 0。
- 第五轮将同一规则扩展到所有二、三、四级页面：删除设置骨架内部的压缩字体映射，提供商列表移除品牌图标前导列，模型列表收紧文字轴，默认模型取消默认箭头；主题、Provider 表单/模型编辑、RAG、检索、技能、备份、本地模型、开发者和用量页面统一继承标准 Token。SE 参考、主题页、Provider Models 与默认模型的四宫格对比见 `native-ui/app/build/design-qa/settings-deep-pages-se-comparison.png`。
- 第二轮独立视觉复审确认 P0/P1/P2 均为 0；背景层次、三个入口的具体图标造型和截图未模拟系统状态栏属于非阻断 P3。
- 源图与实现合并对比聚焦首页整体密度；深层页面另以 Theme、Default Models、Provider Form、Provider Models、RAG Advanced、Search 与 Backup 的真实渲染，以及手机/横屏/平板和 2.0x 截图检查页面骨架、表单、长文本和操作状态。普通设置项使用连续列表；必须承载输入、统计或主动作的富功能区保留必要表面。
- 单行版源图为用户实机双行截图 `f079dffa56f64b0b9307a62f5356ed8a.jpg`，实现为同一中文深色设置 Tab 状态；源图 1140×2616px、实现 945×2100px，比较图按等高归一化并保留各自纵横比。完整标签在全图可辨，因此无需额外局部裁切。

## 自动化证据

- 全量 JVM：2140 项，0 failure、0 error、14 个既有配置/真实网络条件 skip。
- `validateDebugScreenshotTest`：101/101 通过；一级设置首页手机、集成底栏、平板与 2.0x 字体基线已更新。
- `lintDebug`：0 Error/Fatal；425 Warning、25 Hint 为既有静态建议。
- `compileDebugAndroidTestKotlin`、`assembleDebug`：通过。
- Debug APK 在视觉回归阶段构建通过；最终 clean 发行构建随后清理了该可再生调试产物。
- 正式签名 R8 APK：深层页面标准 MD3 Token 收敛后重新 clean 构建 `native-ui/app/build/outputs/apk/release/nexara-v0.2-beta.apk`，18,369,076 bytes，SHA-256 `4b19f50fa7c383d69b4acc28eebc4965f484194fe022f8a45f67fe9587f6bbe1`。统一验证器确认包名 `com.promenar.nexara.native`、versionCode 2、versionName `0.2-beta`、单一 signer、稳定正式证书、ZIP/体积、敏感内容和 GGUF/llama/ggml 排除；R8 mapping/seeds/usage/configuration 非空，16 KiB zipalign 与 checksum 回读通过。
- `git diff --check`：通过；`MainTabScaffold.kt` 零 diff。

final result: passed

---

# Agent 首页身份列表 Material 3 视觉验收

- 验收日期：2026-08-25
- 用户实机参考：`/var/folders/tf/qf5lsvm91j91bh107wqfwr9h0000gn/T/codex-clipboard-f25465a5-3703-4dd9-9947-b4ae3032c001.jpg`
- 覆盖状态：普通英文列表、自定义头像分支、中文 2.0x 字体。

## 视觉结论

- Agent 首页继续使用无外层卡片的连续 Material 3 `ListItem`，单行最小高度 88dp；56dp 圆形身份头像、17sp/28sp 中字重主标题和 15sp/26sp 副标题形成比旧 64dp 行高、40dp 头像更大方的身份层级。
- 自定义图片与预设图标共用同一头像容器和裁切形状；分隔线从 88dp 文字轴开始，不穿过头像。列表使用标准 16dp 内容轴，避免页面与 `ListItem` 重复缩进。
- 普通深色基线和中文 2.0x 基线均无文字重叠或头像裁切；大字体时列表行自然增高。用户实机对最终密度与 OEM 字体的主观验收仍保留为人工项。
- `MainTabScaffold.kt` 零 diff，底部聊天、知识库、设置三按钮导航的尺寸、选中胶囊与动画保持不变。

## 自动化证据

- API 35 `AgentHubScreenContentTest`：9/9 通过，包含持久化自定义路径选择图片分支。
- `validateDebugScreenshotTest`：100/100 通过；两张 Agent 首页 reference 已更新并逐张检查。
- 全量 JVM：2546 项，0 failure、0 error、14 skip；Lint 0 Error/Fatal；Debug APK、AndroidTest APK 与正式签名 R8 APK 构建通过。

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
