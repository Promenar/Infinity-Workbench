# Nexara 设置页与会话界面重设计规格

> 状态：批次一、批次二均已实施
> 日期：2026-10-17
> 适用范围：全部设置页（含二级、三级页面与底部弹层）、会话设置弹层、会话界面及其外框（顶栏、消息流、步骤行、审批卡片、输入区）
> 组件底座：Material 3 + 项目自有组件 + mikepenz multiplatform-markdown-renderer 0.41.0；不引入新的 UI 库
> 视觉基准：Claude、Gemini Android 客户端的会话排版与信息密度

本规格定义当前有效的视觉契约。数值以 dp/sp 为单位，均为硬性规则；“建议”字样之外的条目不得凭观感放宽。每个页面以第 9 节截图清单验收。

## 1. 目标与非目标

### 1.1 目标

- 设置页信息密度对齐商业应用：每个设置项是一行，控件与标题同行、垂直居中；说明文字不撑大分组。
- 数值类设置统一为“值 + 箭头”行加底部选择弹层；小范围整数使用同行紧凑步进器。
- 会话正文无气泡、通栏排版，段落节奏对标 Claude/Gemini；用户消息保留右侧气泡。
- 思考、工具、检索步骤改为无边框、无底色的小字单行，用自然语言概括；工具内部名称和参数只在展开后出现。
- 流式输出采用缓冲匀速放出与新文字渐显，慢模型平滑、快模型不拖尾。
- 输入区一体化：输入框在上，底部工具行承载附件、模型选择、上下文用量与发送/停止。

### 1.2 非目标

- 不改变任何设置的存储键、取值范围、默认值与业务语义（第 4.6 节合并“不限”开关时保持原存储值）。
- 不改变导航结构、页面层级与路由。
- 不重设计欢迎引导、知识库文件树、DocEditor、知识图谱画布与 SSH 终端；全局字体令牌变化后这些页面只需保证无裁切、无重叠。
- 不改变生成中输入框的可编辑性、审批语义、工具执行与上下文压缩逻辑。
- 不引入新依赖、新权限或新网络访问。

## 2. 全局令牌

### 2.1 字体（`ui/theme/Type.kt`）

界面角色调整如下，换行策略（`NexaraUiLineBreak` 等）保持不变。

| 角色 | 字号/行高 | 字重 | 用途 |
| --- | --- | --- | --- |
| `titleLarge` | 22/28 | Normal | 页面大标题、弹层标题 |
| `titleMedium` | 16/24 | Medium | 会话顶栏标题、弹层小节标题 |
| `bodyLarge` | 16/22 | Normal | 设置行标题、列表主文本、用户消息与输入框正文 |
| `bodyMedium` | 14/20 | Normal | 界面普通说明、弹层说明、值文本 |
| `bodySmall` | 13/18 | Normal | 设置行副标题、分组脚注、步骤展开内容 |
| `labelLarge` | 14/20 | Medium | 按钮、胶囊主文字 |
| `labelMedium` | 12/16 | Medium | 分组标题 |
| `labelSmall` | 11/16 | Medium | 元信息、计数 |

`headlineLarge`/`headlineMedium` 维持现值。数值显示（值文本、步进器数字、百分比）一律加 `fontFeatureSettings = "tnum"`，不使用等宽字体。

### 2.2 间距与尺寸（`ui/theme/Spacing.kt`）

在 `NexaraSpacing` 现有令牌之外新增：

| 令牌 | 值 | 含义 |
| --- | --- | --- |
| `RowHorizontal` | 16dp | 设置行左右内边距 |
| `RowSingleLineHeight` | 48dp | 单行设置行最小高度 |
| `RowTwoLineHeight` | 56dp | 标题 + 一行副标题的设置行最小高度 |
| `RowIconSize` | 20dp | 设置行前导图标 |
| `RowIconGap` | 16dp | 图标与文字间距（文字起点 = 16 + 20 + 16 = 52dp） |
| `SectionGap` | 24dp | 相邻分组之间的垂直间距 |
| `SectionHeaderBottom` | 8dp | 分组标题到分组容器 |
| `FooterTop` | 8dp | 分组容器到脚注 |
| `ChatHorizontal` | 20dp | 会话消息流左右边距 |
| `ChatMaxWidth` | 720dp | 会话内容最大宽度（宽屏居中） |

### 2.3 颜色角色

- 设置分组容器：`surfaceContainerLow`，圆角 20dp，无描边（沿用 `BettboxListGroup`）。
- 分组内各行透明，不加分隔线；确需分隔时用 `outlineVariant` 50% 透明度、0.5dp，从文字起点开始。
- 次要文字一律 `onSurfaceVariant`；步骤行文字 `onSurfaceVariant` 85% 透明度。
- 强调色 `primary` 只用于：选中状态、开关、步进器可用按钮图标、每屏唯一的主按钮、发送按钮。
- 危险操作文字用 `error`，不使用红色填充块。

## 3. 设置页通用规则

### 3.1 版式硬规则

1. 一个设置项只占一行。开关、值、步进器、箭头位于行尾，与标题文字块垂直居中（容差 ±1dp）。
2. 禁止控件单独占一行。例外仅限：文本输入框、搜索框、多行代码/命令块、颜色选择盘、底部弹层内的滑块。
3. 副标题最多 1 行，超出时截断并视为文案违规（第 3.3 节）。标题默认最多 2 行；设置行宽不足 280dp 或系统字号放大时，标题完整换行并允许行高随内容增长。
4. 普通默认设置行的单行高度 48dp、带副标题高度 56dp；窄屏长标题与放大字号允许按完整内容增高，控件保持同行居中。
5. 同一分组内文字起点一致：所有行统一有或无前导图标；多数行无图标时删除少数图标，多数行有图标时为其它行补充语义合适的 Material Rounded 图标。生产页面不使用 `leadingSpacer` 空位。
6. 每屏（一个视口）最多一个实心或色调强调按钮；其余操作做成设置行、`TextButton` 或行尾图标按钮。
7. 分组标题 `labelMedium`、`primary`，起点与分组容器外缘内 16dp 对齐；分组之间 24dp。

### 3.2 组件契约（`ui/common/`）

**NexaraSettingsItem（重写）**：不再使用 M3 `ListItem`（其两行默认 72dp 是分组臃肿的来源），改为自定义 `Row`，按 2.2 节令牌实现。插槽：可选前导图标、标题（`bodyLarge`，普通状态最多 2 行，窄屏与放大字号按 3.1 节完整换行）、可选副标题（`bodySmall`，1 行，`TextOverflow.Ellipsis`）、尾部插槽。保留现有参数签名（`showChevron`、`trailingContent`、`subtitleIsIdentifier`、`enabled` 等）以免改动调用方；禁用态整体 38% 透明度。

**SettingsToggle（调整）**：与 NexaraSettingsItem 共用同一行布局与高度规则；`description` 同样限 1 行。

**NexaraSettingsSection（扩展）**：新增 `footer: String? = null`。脚注渲染在分组容器下方，`bodySmall`、`onSurfaceVariant`、`NexaraReadingLineBreak`，左右 16dp、上 8dp；411dp 默认中文不超过 3 行，完整安全或风险说明可例外并在验收报告登记。首页导航不使用脚注。数值解释进入其选择弹层头部，其它说明每项一句；列表说明紧邻所属项目，不拼成整组文字墙。`NexaraSettingsGroup` 提供无重复标题的单项分组。

**NexaraValueRow（新增）**：`title`、可选 `subtitle`、`value: String`、`onClick`。尾部为值文本（`bodyMedium`、`onSurfaceVariant`、tnum，最大宽度为行宽 40%，超出截断）加 18dp `ChevronRight`。点击打开 NexaraValuePickerSheet。语义：`Role.Button`，`stateDescription` 为当前值。

**NexaraValuePickerSheet（新增）**：基于现有 `NexaraBottomSheet`。

- 头部：标题与触发行标题完全一致，`titleMedium`；说明 `bodySmall`，窄屏时允许完整换行。标题、说明、预设文字、“自定义…”与输入框左缘均距弹层边缘 24dp。
- 离散模式：预设项为单选行（48dp，选中项尾部显示 20dp `Check`、`primary`）；点击预设立即生效并关闭弹层。
- 自定义：列表末行“自定义…”，点击后原地展开数字输入框（`OutlinedTextField`，数字键盘，带单位后缀），超出范围时在输入框下方以 `bodySmall`、`error` 提示合法范围；展开后弹层底部出现唯一主按钮“完成”。
- 连续模式（小数参数）：顶部大号当前值（`headlineMedium`、tnum、居中），下方 `NexaraSlider` 与两端最小/最大值标签（`labelSmall`），再下方一行预设胶囊（`FilterChip`，高 32dp）与“恢复默认”`TextButton`。拖动时实时更新值，松手提交。
- 弹层内容不超过屏高 70%，超出时内部滚动。
- 枚举选择弹层显式启用内容滚动，横屏与放大字号下全部选项可达；通用 `NexaraBottomSheet` 的内容滚动为可选参数，已有滚动表单保持自身容器。

**NumericSettingSpec（新增纯逻辑）**：描述 `min`、`max`、`step`、`presets: List<Preset(value, labelRes?)>`、`unit`、`unlimitedValue: Number?`（如 0 表示“不限”）、`format`、`kind`（离散/连续）。提供 `parseCustom(input): Result`、`clamp`、`format(value)`、`selectedPreset(value)` 等纯函数，并配单元测试（边界、非法输入、不限值、小数精度、步长对齐）。

**NexaraInlineStepper（新增，替换 NexaraStepperSetting 的行尾部分）**：行尾胶囊 `[− n +]`，整体高 36dp、圆角 18dp、`surfaceContainerHigh`；两侧按钮可见区 32dp 圆形，图标 18dp，触控区 48dp（通过 `minimumInteractiveComponentSize` 横向延展，不增加行高）；中间数字最小宽 32dp、`labelLarge`、tnum、居中。到达边界时对应按钮 38% 透明并禁用。长按连续步进（首次 400ms 后每 80ms 一步）。`NexaraStepperSetting` 改为“设置行 + 行尾 NexaraInlineStepper”的组合，删除值叠在按钮上方的布局。

### 3.3 控件选择规则

| 设置性质 | 控件 |
| --- | --- |
| 布尔 | SettingsToggle |
| 整数，步长 1，可选值不超过 20 个 | 设置行 + NexaraInlineStepper |
| 其它整数（大范围、步长非 1、含“不限”） | NexaraValueRow + 离散模式选择弹层 |
| 小数（温度、Top P、惩罚系数等） | NexaraValueRow + 连续模式选择弹层 |
| 2–5 个枚举选项 | NexaraValueRow + 离散弹层；页面主维度的切换（如搜索引擎）可用分段按钮 |
| 跳转 | NexaraSettingsItem + 箭头 |

设置页正文中不得再直接出现 `NexaraSlider`；滑块只存在于连续模式弹层。实施前以 `grep -rn "NexaraSlider(\|NexaraStepperSetting(" app/src/main` 列出全部调用点，逐项按上表迁移并在报告中列出映射。

### 3.4 文案规则

- 副标题只写“是什么/当前状态”，中文不超过 16 个汉字，英文不超过 36 个字符；能由标题自明时不写副标题。
- 原副标题中的解释、原理、风险、操作步骤移入分组脚注或选择弹层说明，信息不得丢失。
- 中英文资源同步修改；不新增硬编码字符串。
- 预设项标签示例：“不限”“关闭”“100K”“1M”“60 秒”“0.95（默认）”。默认值在预设中以“（默认）/(default)”标注。

## 4. 页面落地要点（批次一）

### 4.1 设置首页与各二级页

全部改用 3.2 节组件；页面左右边距 16dp，顶部大标题沿用 `NexaraSettingsPageLayout`。逐页检查第 3.1 节七条规则。

### 4.2 工具设置（`SkillsScreen`、`AgentRuntimeSettingsCard`、`ScriptRuntimeSettingsCard`）

- “执行预算”分组：
  - 最大轮次：NexaraValueRow，预设 10 / 25 / 50（默认）/ 100 / 不限，自定义 1–100。原“不限”独立开关删除，由预设“不限”写入 0，保持原存储语义。
  - Token 上限：NexaraValueRow，预设 关闭 / 100K / 500K / 1M / 5M，自定义 0–10,000,000，步长对齐 10,000。
  - 重复调用上限：设置行 + 行内步进器（2–20）。
  - 重置授权：设置行，标题 `error` 色，无副标题，说明移入脚注。
- 搜索配置弹层：结果数量（1–20）改为行内步进器；搜索引擎保留分段/单选；密钥编辑器保持现状。
- 各分组长说明移入脚注。

### 4.3 Termux、SSH、备份、主题、模型与提供商页

- Termux：诊断卡保留“运行诊断测试”为本屏唯一主按钮；安装/权限步骤的操作改为 `TextButton` 或设置行；后台限制说明进入脚注。
- 主题：文字缩放改为 NexaraValueRow，预设 80%–140% 每 10% 一档；颜色选择盘为允许的独立控件。
- 提供商、模型、默认模型、备份、用量统计页：按 3.1 节规则逐页整理，不改变数据展示内容。

### 4.4 检索设置（`ui/rag/*`、`ui/hub/AgentRagConfigScreen`、`AgentAdvancedRetrievalScreen`）

所有滑块按 3.3 节迁移为 NexaraValueRow + 弹层；阈值类小数用连续模式；数量类按可选值个数决定步进器或离散弹层。

### 4.5 会话设置弹层（`SessionSettingsSheet`）

- 四个标签依次为：模型 / 参数 / 工具 / 上下文。`sheet_tab_settings` 中文改为“上下文”、英文改为“Context”，键名不变；其余标签名不变。
- 弹层标题 `titleLarge`；标签切换器高 40dp。
- 各面板的“分隔线 + 粗体小标题 + 独立标题行 + 滑块行”结构全部改为 NexaraSettingsSection + 3.2 节组件。
- 参数面板（思考强度选择保持现有卡片式单选）中的推理参数：流式超时（预设 60 / 120 / 180 / 300 秒，自定义 30–300）、Top P（连续）、最大输出 Token（预设 不限 / 1024 / 2048 / 4096 / 8192，自定义 0–8192）、Top K（预设 不限 / 20 / 40 / 100，自定义）、重复/存在/频率惩罚（连续）。
- 上下文面板顺序：
  1. “上下文管理”：自动压缩阈值（NexaraValueRow，预设 70% / 80% / 90%，自定义 50–95 步长 5）、保留最近轮次（行内步进器 1–20）、立即压缩（设置行，不可用原因作为副标题，原理说明进脚注）。
  2. “检索”：记忆、全局记忆、文档、重排、知识图谱等开关改用 SettingsToggle（替换自定义 `ToolToggleRow`），不可用提示进脚注。
  3. “长期记忆”：与其它分组同样的卡片样式。
  4. “显示”：字号（行内步进器 10–18）。
- 保留现有 testTag（如 `session_context_management`、`session_context_compact_now`）。

### 4.6 兼容约束

- “不限”合并进预设后，存储值与原开关写入值完全一致（轮次 0；关闭开启时写入的默认值保持 50）。
- 所有 `UiTags` 与 testTag 保留在语义等价的新元素上；被删除元素的标签迁移到其替代元素。

## 5. 会话界面（批次二）

### 5.1 顶栏

- 高 56dp，透明背景。左侧返回（24dp 图标，48dp 触控）；顶栏保留 `WindowInsets.safeDrawing` 的水平安全区，垂直安全区由页面提供。
- 标题：会话名单行，`titleMedium`，超长截断；点击仍触发重命名，删除标题旁的铅笔图标与智能体名副标题（智能体名已出现在输入框占位文字中）。
- 右侧：文件（`Icons.Outlined.Folder`，22dp）、更多（`MoreVert`）。更多菜单项保持现有内容与顺序。
- 列表滚动离开顶部时，顶栏底部淡入 0.5dp `outlineVariant` 分隔线；回到顶部时消失。

### 5.2 消息流

- 左右边距 20dp，最大宽度 720dp 居中。
- 垂直节奏：用户消息到其后的助手内容 20dp；助手内容（含操作行）到下一条用户消息 28dp。由各项自身 padding 实现，`LazyColumn` 不再统一 `spacedBy`。
- 首条消息距顶栏 12dp；末条内容距输入区 16dp。自动跟随时观察目标条目的位置与高度，生成结束后的操作行、流式积压和长文异步解析也保持尾部可见；用户主动上滑后保持阅读位置。

### 5.3 用户消息

- 右对齐气泡，最大宽度为可用宽度的 82%；`surfaceContainerHigh`；圆角 20dp；内边距左右 16dp、上下 10dp。
- 文字 `bodyLarge` 阅读换行，字号跟随会话字号设置，行高 = 字号 × 1.5。
- 不在气泡下显示时间；时间、复制、编辑、分支、删除通过长按菜单访问（现有菜单保持）。
- 图片、文档附件缩略图位于气泡上方，右对齐，间距 6dp。

### 5.4 助手正文

- 无气泡、无底色、无描边，通栏。
- 正文：字号 = 会话字号（默认 15sp），行高 = 字号 × 1.6，字色 `onSurface`。
- 块间距以默认字号截图墨迹实测，文字块从上一块末行墨迹底到下一块首行墨迹顶；容器块以可见外缘计量：

  | 边界 | 间距 |
  | --- | --- |
  | 段落之间 | 16dp |
  | 标题上方（非首块） | 24dp |
  | 标题下方 | 10dp |
  | 列表项之间（同级、进入或退出层级） | 10dp |
  | 列表与相邻段落、标题之后的首个列表 | 12dp |
  | 代码块、表格、公式、图表、引用块与相邻块 | 16dp |

- 多行列表项与多行引用的内部行距与正文相同；列表嵌套缩进 16dp。
- 引用块左侧 3dp `outlineVariant` 竖线，左内边距 12dp。
- 标题字号：h1 = 1.35×、h2 = 1.2×、h3 = 1.1×，SemiBold，行高 1.35×。
- 代码块：圆角 12dp，`surfaceContainerLow`；头部 36dp，左侧语言名 `labelSmall`，右侧复制、编辑图标 18dp；正文等宽 13sp/20sp，内边距左右 16dp、上 4dp、下 12dp，与头部语言名左缘对齐；字形墨迹完整可见。
- 表格表头与正文单元格使用相同字号，表头字重 Medium。
- 行内代码：`surfaceContainerHigh`，圆角 4dp，左右内边距 4dp。
- 预处理：代码围栏外连续 3 个及以上换行折叠为 2 个；必须保护代码块、行内代码、链接与缩进代码块（遵循 AGENTS.md 的 Markdown 排障分层规则）。
- 操作行：生成完成后在正文下方 8dp 显示，高 36dp；左侧复制、重新生成、更多（分支、删除）三个 18dp 图标按钮（48dp 触控），`onSurfaceVariant`；右侧元信息“模型名 · HH:mm”，`labelSmall`、不加透明度的 `onSurfaceVariant`，超长截断模型名；第一个图标可见左缘与正文左缘对齐（偏差 ≤1dp），48dp 触控区可以向正文列左侧扩展。生成中不显示操作行；复制连接该轮按出现顺序排列的所有非空正文片段，片段之间保留一个空行，不包含思考与工具数据。保留 `CHAT_ASSISTANT_MODEL_METADATA`、`CHAT_ASSISTANT_TIME_METADATA` 标签。

### 5.5 步骤行（思考、工具、检索）

视觉：

- 无底色、无边框、无圆角容器；单行；布局高 44dp，可见文字垂直居中；整行宽度可点击。
- 文字 `labelLarge` 字号降为 13sp/18sp、Normal 字重，`onSurfaceVariant` 85%；尾部 16dp 箭头（收起 `ChevronRight`，展开旋转 90°，动画 150ms）。
- 成功与进行中均无前导图标，文字左缘始终与正文对齐；进行中文字在 `onSurfaceVariant` 底色上显示一道 `onSurface` 微光，1.2 秒线性循环，系统动画缩放为 0 时为静态文字；失败态摘要使用 `error` 色；“· N 个失败”使用独立、不收缩的 `error` 色文字，摘要占剩余宽度并优先截断。
- 相邻步骤行之间 0dp（由 44dp 行高自然分隔）；步骤行与正文之间 8dp。
- 进行中的思考预览紧跟标签之后，间距 12dp，左对齐；超出剩余宽度时保留最新文字，左缘 24dp 渐隐，不与尾部箭头重叠，无底色。

文案（中文 / 英文）：

| 状态 | 思考 | 工具 |
| --- | --- | --- |
| 进行中 | 正在思考 · 6 秒 / Thinking · 6s | 正在运行命令… / Running a command… |
| 完成 | 思考了 6 秒 / Thought for 6s | 运行了 2 条命令 · 2.3 秒 / Ran 2 commands · 2.3s |
| 失败 | — | 运行了 2 条命令 · 1 个失败 / Ran 2 commands · 1 failed |

工具摘要由新增纯函数 `toolGroupSummary(invocations)` 生成，按工具 ID 归类：

| 类别 | 工具 ID | 完成文案（中 / 英，含复数） |
| --- | --- | --- |
| 命令 | `termux_exec`、`ssh_exec`、`ssh_session_open`、`ssh_session_send`、`ssh_session_read`、`ssh_session_close`、`run_script`、`exec_js` | 运行了 N 条命令 / Ran N command(s) |
| 网页搜索 | `web_search`、`search_tavily`、`search_searxng` | 搜索了网页（N>1 时“搜索了 N 次网页”）/ Searched the web (N times) |
| 网页读取 | `web_fetch` | 读取了 N 个网页 / Read N page(s) |
| 查看文件 | `read_file`、`list_files`、`search_files`、`read_tool_output`、`read_skill_file` | 查看了 N 个文件 / Read N file(s) |
| 修改文件 | `write_file`、`create_file`、`patch_file`、`diff_file`、`move_file`、`delete_file`、`create_directory` | 修改了 N 个文件 / Edited N file(s) |
| 检索 | `knowledge_search`、`memory_search` | 检索了知识库 / 检索了记忆；Searched knowledge / memory |
| 图片 | `generate_image`、`image_generation` | 生成了 N 张图片 / Generated N image(s) |
| 计划 | `initialize_plan`、`update_plan`、`get_plan`、`drop_plan` | 更新了任务计划 / Updated the plan |
| 实用 | `current_time`、`calculator` | 查询了时间 / 进行了计算；Checked the time / Calculated |
| 技能 | `activate_skill` | 启用了技能 / Activated a skill |
| 其它（含 MCP 与未知） | 其余 | 调用了 N 个工具 / Used N tool(s) |

- 一组含 2 个类别时用“，”连接（“运行了 2 条命令，查看了 3 个文件”）；3 个及以上类别时用“调用了 N 个工具”。
- 进行中按最后一个未完成调用的类别用进行时（“正在搜索网页…”“正在查看文件…”“正在修改文件…”）。
- 折叠态任何位置不得出现工具内部 ID 或参数。
- 中英文使用 `plurals` 资源。

展开内容（左缩进 0，与正文对齐，项间 12dp）：

- 每个调用一行标题：友好名称（`bodySmall`、`onSurface`）+ 内部 ID（等宽 11sp、不加透明度的 `onSurfaceVariant`）+ 耗时。
- 参数：等宽 12sp/18sp，`surfaceContainerLow`、圆角 8dp、内边距 10dp，默认最多 6 行，超出时显示“展开全部”。
- 结果：`bodySmall`，默认最多 12 行，超出时“展开全部”；错误结果 `error` 色；图片结果缩略图保持现状。
- “展开全部”与收起入口使用紧凑文字链接：`labelLarge`、`primary`，无水平内边距，与所属内容左缘对齐，距上方内容 4dp；48dp 触控区不撑大可见留白，参数块底部内边距保持 10dp。
- 思考展开：使用 Markdown 渲染列表、代码与公式，推理正文 `bodySmall` 字号与阅读换行，`onSurfaceVariant`，左侧 2dp `outlineVariant` 竖线、左内边距 12dp。

检索指示（如“知识库 · 未能检索”）改用同一步骤行样式，文案改为自然语言（“检索了知识库 · 5 条结果”“未能检索知识库”），点击行为不变。

### 5.6 审批卡片

- 容器 `surfaceContainerLow`、圆角 16dp、内边距 16dp，去掉左侧强调竖条与描边。
- 标题行：16dp 警示图标（`error` 色）+“需要审批”`titleSmall`。
- 内容首行：友好工具名 + “ · ” + 执行目标（`bodyMedium`）；其下单独显示可选择复制的内部工具 ID（`labelSmall`、等宽字体、不加透明度的 `onSurfaceVariant`）。审批卡为展开态，单项、多项与已执行状态均展示确切工具身份。
- 请求原因去除 `Execution mode:` 等技术行后，以 `bodySmall`、`onSurfaceVariant` 单独显示在首行与内部 ID 下方。风险说明只使用不可恢复、高风险目标、脚本联网等风险事实和请求原因中的风险语句，合并去重为一段 `bodySmall`、`error` 文字；没有风险事实或风险语句时显示风险标签。普通请求原因和参数不进入风险说明；参数只在参数块显示一次。预览标题与执行目标相同时只保留动作词；单项、多调用、已执行状态遵循同一规则。
- 卡内参数预览块使用 `surfaceContainerHigh`，其余样式与紧凑展开链接同 5.5 节。
- 按钮行：右对齐，“拒绝”为 `OutlinedButton`，“批准”为唯一实心主按钮，可见高 40dp、触控高至少 48dp；多项待审批时按钮为“拒绝当前 / 批准当前”（英文“Reject current / Approve current”），单项为“拒绝 / 批准”；“本会话始终允许”为下方 `TextButton`。

### 5.7 流式放出

替换 `SmoothStreamContent` 的固定速率实现为可单测的 `StreamRevealer`（纯 Kotlin 状态机，时间由调用方注入），Compose 侧用 `withFrameNanos` 驱动。

算法：

1. 首次挂载立即显示当前全文；挂载之后的新到达增量参与放出与渐显。状态：已显示长度 `shown`（浮点累加，取整显示）、到达速率估计 `rate`（字符/秒）。
2. 放出器只接收原始累计文本，临时闭合语法不参与速率估计；已放出位置映射到渲染片段。每次目标文本增长时，以本次增量与距上次增长的间隔更新 `rate`（指数移动平均，α = 0.3）。
3. 每帧计算积压 `backlog = target.length - shown`，放出速率 `v = clamp(max(rate, backlog / 0.5s), 30, 2000)` 字符/秒，`shown += v × Δt`。
4. 截断位置不得拆分代理对、组合字符与 emoji 序列（用 `BreakIterator` 字形边界或等效实现）。
5. 流结束后继续放出，放出速率不低于 `backlog / 0.3s`，保证 300ms 内显示完整。
6. 积压超过 4000 字符（恢复、重连、历史载入）时直接跳到 `target.length - 200` 再放出。
7. 目标文本不是已显示文本的前缀（被改写）时，`shown` 回退到公共前缀长度。
8. 非流式消息直接显示全文，不创建放出器或切分字形。系统动画缩放为 0、Compose 预览/检查模式下也直接显示全文；应用内共用一份系统动画设置观察。

流式代码与宽内容：按行缓存宽度测量，已测量行复用；高亮保留最近完成结果覆盖的前缀，新尾部立即以未着色文本显示。流式高亮启动间隔不小于 250ms，不取消正在执行的一轮；流结束后对全文完成最终高亮。

新文字渐显：

- 新放出的字符在 180ms 内从 30% 透明度过渡到不透明。
- 首选实现：通过 0.41.0 `markdownAnnotator` 的注解回调，按 AST 节点源偏移给末段中“新近放出”的文本加透明度样式，分 3 档（距放出 <60ms：30%，<120ms：65%，其余：100%）；只作用于最后一个 Markdown 片段。实施前先在依赖源码中核实回调签名。
- 如注解方案不可行，删除现有底部 36dp 渐变遮罩（它让整块末行闪烁），只保留匀速放出，并在报告中说明原因。

### 5.8 输入区

- 位置：距屏幕左右 12dp，距导航栏或输入法上沿 8dp；容器 `surfaceContainerHigh`（不透明）、圆角 24dp、内边距左右 12dp、上 12dp、下 8dp；无阴影。
- 自上而下：
  1. 附件条（有图片或文档时）：缩略图 56dp，圆角 12dp，间距 8dp。
  2. 输入框：`bodyLarge` 输入换行，最少 1 行、最多 6 行，之后内部滚动；占位文字 `onSurfaceVariant`；文字上下各留 12dp，整块点击高度不小于 48dp，单行时文字垂直居中。
  3. 工具行，高 40dp，紧接输入框：
     - 左：“+”附件按钮，40dp 圆形可见区、22dp 图标，按钮左缘贴齐输入区容器内侧边缘、不被容器裁剪，图标可见左缘与输入文字左缘偏差 ≤2dp；48dp 触控区不放大背景或撑高工具行；展开附件菜单时旋转 45°。
     - 模型胶囊：高 32dp、圆角 16dp、透明底；16dp 品牌图标 + 模型名（`labelLarge`，最大宽 160dp，截断）+ 16dp `ExpandMore`；点击打开会话设置弹层的“模型”标签。
     - 右侧：上下文圆环 18dp（2dp 线宽，超过自动压缩阈值时 `tertiary`）+ 百分比（`labelMedium`、tnum），整体可点击，弹出现有上下文用量面板；然后发送按钮。
     - 发送按钮：可见区严格为 40dp 实心圆，右缘距容器右边 12dp，48dp 触控区不放大可见背景或撑高工具行；可发送时 `primary` 底 + `onPrimary` 20dp 向上箭头；不可发送时 `surfaceContainerHighest` 底 + 38% 透明图标；生成中为 `primary` 底 + 14dp 圆角方块停止图标；错误态保持现有语义。
- 无附件时容器顶边直接接输入框（文字距顶边由输入框的 12dp 留白提供）；有附件时附件条距顶边 12dp。单行输入容器高度 = max(48dp, 12dp + 输入行高 + 12dp) + 40dp + 8dp，默认字号约 96dp。
- 原输入框上方的模型胶囊行删除。任务胶囊与后处理胶囊移到输入区容器上方 8dp 的状态条，高 28dp，仅在存在任务时出现。
- 保留 `CHAT_COMPOSER`、`CHAT_INPUT_BAR_SURFACE`、`CHAT_INPUT`、`CHAT_ADD_ATTACHMENT`、`CHAT_MODEL_SELECTOR`、`CHAT_TOKEN_INDICATOR`、`CHAT_TASK_CAPSULE` 等标签。

## 6. 无障碍与适配

- 触控区不小于 48dp。已知例外：步骤行 44dp（整行宽度可点击）、模型胶囊可见高 32dp（触控高 40dp）；在报告中保留例外清单。
- 所有图标按钮有 `contentDescription`；步进器提供 `stateDescription` 与增减的自定义无障碍操作；选择弹层的预设行为单选语义。
- 浅色、深色、动态取色主题下，`onSurfaceVariant` 文字对比度不低于 4.5:1。
- 系统字体放大 130% 时：设置行允许增高但控件仍同行、垂直居中；输入区工具行不换行（模型名先截断）。
- 横屏与 600dp 以上宽屏：会话内容最大 720dp 居中；设置页沿用现有自适应规则。

## 7. 实施批次与文件所有权

两批串行，批次二依赖批次一的字体与间距令牌。

| 批次 | 所有权 |
| --- | --- |
| 一：令牌、设置组件与全部设置页 | `ui/theme/Type.kt`、`ui/theme/Spacing.kt`；`ui/common/` 中设置行、开关、分组、步进器、新增值行/选择弹层/NumericSettingSpec；`ui/settings/*`、`ui/rag/*`、`ui/hub/` 中的配置页、`ui/chat/SessionSettingsSheet.kt`；相关中英文字符串；截图基线 |
| 二：会话界面 | `ui/chat/PipelineBubble.kt`、`CompactStepRow.kt`、`ToolInvocationPresentation.kt`（含新增摘要函数）、`ChatScreen.kt` 顶栏/消息流/输入区、`ChatInlineComponents.kt` 审批卡与检索行；`ui/common/SmoothStreamContent.kt`（StreamRevealer）、`ui/common/MarkdownText.kt`、`ui/renderer/NexaraMarkdownTheme.kt`；相关字符串；截图基线 |

每批验收命令（Gradle 在 `native-ui/` 运行，Python 在仓库根运行）：

```bash
./gradlew :app:testSideloadDebugUnitTest :app:lintSideloadDebug :app:assembleSideloadDebug :app:assemblePlayDebug
./gradlew :app:updateSideloadDebugScreenshotTest :app:validateSideloadDebugScreenshotTest
python3 scripts/ci/check-distribution-manifests.py
```

截图基线更新必须逐张目视复核，不得批量接受；报告列出更新的基线清单。

## 8. 测试要求

- 纯逻辑单测：`NumericSettingSpec`（解析、边界、不限值、步长对齐、小数格式）；`toolGroupSummary`（各类别、混合类别、进行中、失败计数、未知工具）；`StreamRevealer`（见下）；空行折叠预处理（代码块、行内代码、缩进代码保护）。
- `StreamRevealer` 场景（模拟时钟，16.67ms 帧）：
  1. 每 100ms 到达 5 个字符（50 字符/秒）：稳态下每帧放出 0–2 个字符，不出现单帧放出超过中位数 3 倍的跳变，且无连续 6 帧以上停顿（积压为零时除外）。
  2. 一次到达 2000 字符：受 2000 字符/秒上限约束，1.2 秒内追平。
  3. 流结束时积压 300 字符：300ms 内显示完整。
  4. emoji 与组合字符不被拆分。
  5. 目标被改写为非前缀：回退到公共前缀。
  6. 积压超过 4000：跳到末尾前 200 字符。
- Compose 截图测试覆盖第 9 节每个页面的默认状态（深色、中文、宽 411dp），并为设置行、步进器、值行、选择弹层、步骤行（进行中/完成/失败/展开）、输入区（空/有文字/生成中/有附件）添加组件级截图。
- 既有设备测试与 `UiTags` 断言保持通过。

## 9. 逐屏截图验收清单

拍摄条件：Nexara_API_35 模拟器（1080×2400，420dpi，宽约 411dp）与 360dp 窄宽各一套；深色为主，浅色抽查设置首页、工具设置、会话页；中文为主，英文抽查工具设置与会话页。

### 9.1 全局检查项（每张设置截图逐项检查）

| 编号 | 检查 |
| --- | --- |
| G1 | 无控件单独占一行（3.1 节例外除外） |
| G2 | 行尾控件与标题文字块垂直居中，偏差 ≤1dp |
| G3 | 副标题均为 1 行且未截断（中文默认文案） |
| G4 | 视口内强调按钮不超过 1 个 |
| G5 | 普通默认设置行 48dp/56dp；窄屏长标题与放大字号完整可读且控件同行 |
| G6 | 同一分组文字起点一致 |
| G7 | 长说明只出现在脚注或弹层说明中 |
| G8 | 无行首标点、无末行孤字 |
| G9 | 数值使用等宽数字（tnum），各行数值右缘对齐 |

### 9.2 设置页

| 页面 | 状态 | 专项检查 |
| --- | --- | --- |
| 设置首页 | 默认 | 分组间距 24dp；各入口单行或 56dp 两行 |
| 工具设置 | 默认、滚动到执行预算 | 最大轮次显示“50 ›”；无独立“不限”开关；重复上限为行内步进器 |
| 工具设置 · 最大轮次弹层 | 打开、展开自定义 | 预设 5 项，选中项打勾；自定义输入与范围提示；唯一“完成”按钮 |
| 工具设置 · Token 上限弹层 | 打开 | 预设 关闭/100K/500K/1M/5M |
| 搜索配置弹层 | 默认 | 结果数量为行内步进器；无独立滑块行 |
| Termux | 已就绪、诊断失败 | 唯一主按钮为“运行诊断测试”；失败详情可读；后台说明在脚注 |
| SSH、备份、用量、提供商列表、提供商表单、模型列表、默认模型、本地模型 | 默认 | G1–G9 |
| 主题 | 文字缩放开启 | 缩放为值行“100% ›”，弹层预设 80%–140% |
| 全局检索、检索高级、高级检索、智能体检索配置、智能体高级检索 | 默认、一个连续弹层打开 | 页面无滑块；连续弹层含大号数值、滑块、预设胶囊、恢复默认 |
| 会话设置 · 模型 / 参数 / 工具 / 上下文 | 各标签默认 | 第四标签名“上下文”；推理参数为值行；上下文管理、检索、长期记忆、显示四组同一卡片样式 |
| 会话设置 · 参数 · Top P 弹层 | 打开 | 连续模式完整 |

### 9.3 会话页

| 状态 | 专项检查 |
| --- | --- |
| 新会话空态 | 顶栏仅返回、单行标题、文件、更多；输入区一体化，工具行四要素齐全 |
| 输入多行文字 | 输入框随内容增高，最多 6 行后内部滚动；工具行位置稳定 |
| 带图片与文档附件 | 附件条位于输入框上方；缩略图 56dp |
| 生成中（思考） | 思考行无底色，“正在思考 · N 秒”文字微光 + 紧随其后的推理预览；发送键变停止 |
| 生成中（正文流式） | 录屏 10 秒：慢速模型字符匀速流出、无整段突现；新字有渐显 |
| 工具运行中 / 完成 / 失败 | 折叠态为自然语言摘要，无工具内部 ID；失败段为红色小字 |
| 工具展开 | 友好名 + 内部 ID + 参数块 + 结果，长内容可“展开全部” |
| 审批卡片 | 无红色填充块；唯一实心“批准”按钮 |
| 长回复（标题、列表、代码、表格、引用） | 块间距符合 5.4 节实测值；无连续空行造成的大空白 |
| 完成后的助手消息 | 操作行三图标 + “模型名 · 时间”；用户消息下无时间 |
| 上下文用量面板 | 点击圆环弹出现有面板 |
| 横屏或平板宽度 | 内容最大 720dp 居中 |

## 10. 验收流程

1. 实施方提交：代码、更新的截图基线、单测与命令输出、控件迁移映射表、无障碍例外清单、未完成或偏离项说明。
2. 主控复核：逐张截图对照第 9 节清单，在模拟器上实拍并抽量尺寸；任何一项不满足即退回，不以“观感可以”放行。
3. 两批均通过后，按项目发行流程构建签名候选并在真机复核会话流式与输入区。
