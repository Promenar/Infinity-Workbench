# Nexara Material 3 交互、设置与主题总收敛设计

> 日期：2026-07-20
> 分支：`codex/md3-redesign`
> 状态：用户已确认方向，等待按实施计划串行施工
> 延续：`docs/superpowers/specs/2026-07-16-nexara-md3-redesign-design.md`
> 对应计划：`docs/superpowers/plans/2026-07-20-nexara-md3-convergence.md`

## 1. 目的

本规格收敛 Nexara 当前仍混用的三套界面语言：稳定 Material 3 组件、自定义 Glass/描边容器，以及缺少统一规则的自绘交互。目标不是逐页缩字号或替换颜色，而是建立可贯穿主导航、会话输入、搜索、模型选择和全部设置层级的 Android 原生产品语言。

完成后的 Nexara 应保持以下产品感受：

- 像成熟 Android 工具一样清楚、克制、连续和可预测；
- 以内容、排版、间距和 tonal surface 建立层级，不以每项成卡建立层级；
- 主会话保留少量 Nexara 专属动效，管理页面优先使用标准 Material 3 语义；
- 深色、浅色、系统主题和动态色使用同一组语义色彩角色；
- 普通字号、大字体、横屏、平板、IME、系统栏和 TalkBack 都是设计输入，不是实现后的补丁。

## 2. 与既有设计的关系

本规格保留 2026-07-16 总设计已经冻结的稳定 Material 3 `1.4.0`、Compose BOM `2026.06.00`、连续列表、48dp 触控目标和“90% Material 3 + 10% Nexara 特征”原则。

本规格增量覆盖旧设计的两项边界：

1. 旧设计第 13 节将完整浅色主题列为非目标；用户已在 2026-07-20 明确同意将浅色、系统和动态主题纳入新的独立实施阶段，因此该项只对已完成的前四阶段保持历史有效，不再约束本规格。
2. 旧设计允许模型次要信息通过“展开区域或详情页”披露；本规格冻结为：长模型列表首层只保留摘要，完整编辑进入独立 Sheet/详情表面，不在列表中无限展开完整表单。

旧文档、旧截图和旧 handover 不回写。当前发行说明仍以“仅深色主题”为事实，只有本规格全部主题门禁通过后才允许修改发行承诺。

## 3. 当前问题与证据

### 3.1 会话附件入口

`ChatInputBar` 使用普通 `DropdownMenu`。输入栏位于屏幕底部时，Popup 会为可用空间向上避让，形成菜单与 `+` 按钮距离过远、覆盖模型状态行的视觉断裂。

### 3.2 模型选择

`ModelPicker` 和会话设置中的模型面板虽已使用 `ModalBottomSheet + LazyColumn`，但每个模型仍套 `NexaraGlassCard`、描边、图标容器和多枚彩色能力标签。它们提高了视觉噪声，也让“选择模型”和“阅读模型数据库”混成同一任务。

### 3.3 搜索与会话列表

`AgentSessionsScreen` 使用固定搜索栏和逐项 Glass 卡片。固定搜索占据常态首屏高度；逐项卡片让两条会话也呈现为稀疏卡片墙。项目的 `NexaraSearchBar` 是自定义 `BasicTextField + Surface`，缺少“常态顶栏动作 / 搜索模式”这一页面级状态。

### 3.4 主导航

手机底栏是自绘 `Row`、发光和缩放反馈，没有使用 `NavigationBar` / `NavigationBarItem` 的选中指示器和语义。平板已有 `NavigationRail`，但手机和平板不共享同一导航组件原则。

### 3.5 设置体系

- 设置首页已经使用 `TopAppBar`、`TabRow` 和 `ListItem`，但“应用 / 提供商”双 Tab、用户横幅和大量一级分组让它更像管理仪表盘；
- `GlobalRagConfigScreen` 仍使用多层 `NexaraGlassCard`，包括卡片套卡片；
- `SearchConfigScreen` 的 Switch、RadioButton 和 Slider 是 Material 组件，但外层仍是描边选择块和静态颜色；
- Provider 表单使用标准字段，但整组字段再次包入大 `Surface`，测试与保存以两条巨型按钮堆叠；
- Provider Models 把搜索、同步、添加、通知、能力 Chip、启用 Switch 和完整内联编辑放在同一视觉层级；
- Backup、Skills、Token、Local Models、Developer、Theme 等二三级页面仍存在多套卡片与分组语言。

### 3.6 主题系统

`NexaraTheme(darkTheme = ...)` 当前在非动态色路径始终返回 `NexaraDarkColorScheme`，系统栏图标也被强制为深色背景模式。`ThemeScreen` 的模式和强调色只存于 `remember`，不会驱动根主题。大量 UI 和 WebView/图表渲染器直接读取静态 `NexaraColors` 或深色十六进制值，因此不能靠一个浅色开关完成可靠主题切换。

## 4. 总体设计原则

### 4.1 标准组件优先，但不机械套组件

Material 3 是语义和层级系统，不是“使用了 Switch 就自动成为 MD3”。页面需要同时满足：

- 正确组件：`TopAppBar`、`NavigationBarItem`、`ListItem`、`TextField`、`Switch`、`Slider`、`Dialog`、`ModalBottomSheet`；
- 正确组合：一个清晰主任务、有限主操作、连续列表、渐进披露；
- 正确令牌：全部来自 `MaterialTheme.colorScheme`、`typography`、`shapes` 和项目 spacing；
- 正确行为：返回、IME、焦点、滚动、手势、减少动效和 TalkBack 均可预测。

### 4.2 不通过缩小文字制造“密度”

普通正文、标题和辅助信息继续映射 Material typography。拥挤问题通过删除重复标题、减少容器、调整内容优先级、限制辅助文本行数和拆分详情解决。2.0x 字体允许页面变长，但不得裁切、重叠、隐藏主动作或把 48dp 目标缩小。

### 4.3 卡片使用边界

卡片只允许用于真正独立、可整体操作的对象或需要框定的工具。设置行、Provider 行、模型摘要行、会话行和导航行全部使用连续列表或透明 `ListItem`，不得逐项描边成卡。

## 5. 页面信息架构

### 5.1 设置首页

设置首页取消“应用 / 提供商”双 Tab，恢复单一层级：

1. 账户：用户头像、名称；
2. 通用：语言、外观；
3. AI 与模型：Provider 管理、默认模型、本地模型；
4. 知识与检索：记忆与索引、检索策略；
5. 工具与数据：Skills、Token 使用、备份与恢复；
6. 关于：版本和项目链接。

用户资料使用普通 `ListItem`，Provider 管理成为二级目的地。默认模型进入一个独立二级页，集中管理摘要、图像、嵌入和重排四类默认模型；首页只显示摘要，不铺开四个模型选择器。每次选择仍沿用现有 `SettingsViewModel.setPresetModel(type, modelId)` 即时持久化语义，返回不再二次保存。

### 5.2 Provider 管理

- Provider 列表是连续 `ListItem`；
- 顶栏承担添加入口，行尾 overflow 承担编辑和删除；
- 行首展示协议/来源图标，正文展示名称、协议和安全状态；
- 整行进入模型管理，编辑不与进入动作争抢；
- 空态保留明确“添加 Provider”动作。

### 5.3 Provider 表单

- 字段直接位于页面内容区，使用小型分组标题，不再包大卡；
- Preset 和协议使用标准 exposed dropdown；
- 名称、Base URL、API Key 保留原安全、遮罩、回遮和校验契约；
- “测试连接”是次要动作，“保存”是唯一主动作；
- 手机使用底部操作区，横屏/平板允许尾部操作栏，但不得覆盖 IME；
- 错误显示在字段 supporting text 或紧邻相关分组，不使用整页装饰性红卡。

### 5.4 Provider Models

- 搜索是该长列表的主要任务，保持持续可见；
- 同步进入顶栏动作，添加使用主动作，禁用全部和删除全部进入 overflow；
- 模型摘要行展示友好名称、真实调用 ID、主要能力和启用 Switch；
- 最多展示两个关键能力，其余显示数量，不使用彩色标签墙；
- 点击模型进入编辑 Sheet/详情，不在列表中展开全部字段；
- 编辑表面保留类型、能力、上下文、测试、删除和用户字段来源契约。

### 5.5 记忆与索引

- 平衡、写作、编程预设使用标准单选分段控件；
- 文档分块、重叠、Embedding 维度和批量 Token 使用标题 + 数值 + Slider/输入控件；
- 摘要模板使用导航行进入编辑器，不再卡片套卡片；
- 高级、调试入口使用连续 `ListItem`；
- 清除向量等破坏性动作位于页面尾部，使用错误色文本动作和标准确认对话框。

### 5.6 检索设置

- 总开关使用 `ListItem + Switch`；
- 搜索引擎使用单选列表或标准下拉，不为每个引擎画描边卡；
- 结果数量、改写、混合检索和重排序采用标准参数行；
- 域名包含/排除规则使用输入行和连续条目，删除是行尾图标动作；
- Agent 级检索页面复用同一组件语言，但不改变全局/会话/Agent 配置作用域。

### 5.7 其余设置页面

Backup、Skills、Token Usage、Local Models、Developer 和 Theme 使用同一页面骨架、分组标题、透明列表行和标准操作区。功能型卡片可以保留，但不得卡片套卡片，也不得把普通设置行伪装成仪表盘卡片。

## 6. 搜索策略

搜索按任务频率分类，不全局强制为固定栏或图标：

| 场景 | 常态 | 搜索后 |
| :--- | :--- | :--- |
| Agent 首页、助手会话列表 | Top App Bar 搜索图标 | 顶栏进入搜索模式，自动聚焦并显示清空/退出 |
| Provider Models、Model Picker | 持续搜索输入 | 原位过滤，保持列表上下文 |
| 知识库文档、文件夹 | 有内容时持续搜索；空态隐藏 | 原位过滤文档/文件 |
| 设置首页 | 不提供搜索 | 本阶段不新增全设置搜索 |

搜索模式必须冻结以下行为：

- 返回键先退出搜索，再离开页面；
- 清空按钮保持至少 48dp；
- 退出后恢复滚动位置和顶栏标题；
- IME Search 触发提交或收起键盘；
- TalkBack 焦点顺序为返回/搜索输入/清空/页面结果；
- 减少动效时使用瞬时状态切换，不删除状态反馈。

## 7. 会话附件动效

附件菜单使用稳定 Compose API 自行实现锚定动作簇，不引入 Material 3 `1.5.0-alpha` 的 `FloatingActionButtonMenu`：

- `+` 在展开时旋转 45 度并成为关闭语义；
- “图片”和“文本文档”从按钮正上方依次上移、缩放和淡入；
- 动作簇与按钮共享左边缘或中心轴，不覆盖模型/上下文状态；
- 动作簇与全屏透明 dismiss 层位于输入栏圆角裁剪之外的父级 overlay；只裁剪输入栏背景，绝不裁剪展开动作；
- 展开和收起使用约 180–220ms 的 Material easing；
- 点击外部、返回键、选择动作、开始生成或输入栏禁用时收起；
- 每个动作保持 48dp 目标，并为 TalkBack 提供展开状态和准确动作名称；
- IME、2.0x、横屏和窗口边缘下，overlay 按可用窗口向上定位且完整可见，不遮挡发送按钮和模型/Token 摘要；
- 减少动效时取消位移/缩放，只保留状态显隐。

## 8. 导航设计

### 8.1 手机

- 2026-07-20 实机反馈后，手机导航改为 Material 语义上的 Nexara 流体导航坞：保留 `Role.Tab`、选中态、48dp 触控目标、主题角色与系统 Insets，不再受官方 `NavigationBarItem` 的高纵向布局约束；
- 外层使用完整 `CircleShape` 的低层级浮动 `Surface`，视觉高度保持 64dp，形成更矮、更饱满的立体大胶囊；
- 容器距屏幕左右和系统手势区保留稳定间距；
- 当前目的地使用一个完整 `CircleShape` 的流体指示块并显示“图标 + 标签”，未选目的地只显示图标但必须保留完整可访问名称；
- 切换目的地时，唯一流体指示块横向滑动，并在运动中短暂拉长、压扁后回到 1:1；不得使用 glow、无限循环、粒子或自绘发光阴影；
- 动效必须服从系统 Animator duration scale。比例为 0 时立即到达终态，快速连续点击不得留下重复指示块或错误选中态；
- 页面内容可以延伸到导航 Surface 后方，但最后一项必须可滚动到不被遮挡的位置。

### 8.2 平板和大屏

宽度 `>= 600dp` 继续使用 `NavigationRail`。Rail 和手机导航共享目的地、选中逻辑、图标、标签和主题角色。不得把手机浮动底栏简单放大到平板。

## 9. 模型选择器

统一模型选择表面使用全高或接近全高的 `ModalBottomSheet`：

- 标题、持续搜索、筛选摘要和模型列表构成单一层级；
- 模型使用连续 `ListItem`，正文起始位分隔线；
- 选中项使用 `secondaryContainer` 或等价 tonal 高亮，尾部显示勾选；
- 首行友好名称，次行 Provider、上下文和关键能力；
- 未知能力保持 unknown，不因视觉简化改变元数据三态或 endpoint 兼容性；
- 会话输入区和 AI 尾注仍使用同一友好名称解析器；
- 列表保留稳定 key、懒加载和搜索 debounce。

共享行不得继续消费会丢失证据状态的旧 `ModelItem.capabilities: List<ModelCapability>`。冻结选择器投影：

```kotlin
data class ModelSelectionUiModel(
    val selectionId: String,
    val remoteModelId: String,
    val displayName: String,
    val providerName: String,
    val contextTokens: Int?,
    val workload: ModelWorkload,
    val capabilityStates: Map<ModelCapability, SupportState>,
    val chatEndpointCompatible: SupportState,
)
```

投影工厂先把 `ModelInfo.userEditedFields` 中的 `name`、`type`、`capabilities`、`contextLength`、`maxOutputTokens` 转换为 `ModelMetadataOverride`，再调用 `ModelMetadataResolver.resolve(remoteModelId, providerId, userOverride = ...)`。`type` 按现有 legacy 类型映射到 workload；`reasoning` 同时提供 `REASONING=SUPPORTED`，而显式 capabilities 编辑具有最终优先级：用户勾选项为 `SUPPORTED`、明确取消项为 `UNSUPPORTED`。投影的显示名、workload、context 和能力三态全部取合并后的解析结果；模型选择 ID、Provider 与显式 `chatEndpointCompatible` 取当前 `ModelInfo`。`capabilityStates` 必须排除 `ModelCapability.CHAT_ENDPOINT`，Chat endpoint 只存在于独立字段，禁止从工作负载、推理能力或空能力列表推导。迁移完成后删除旧 UI 专用 `ModelCapability`/`ModelItem`，两个消费端只接收该投影。

## 10. 主题架构

### 10.1 用户可见选项

- 模式：系统、浅色、深色；
- 色彩来源：Nexara 默认、系统动态色；
- 动态色只在 Android 12+ 可选，不支持时显示不可用状态；
- 本阶段不提供任意 HEX 或多套手工强调色预设，避免只改变 `primary` 而破坏完整 tonal palette；
- 默认继续为深色，避免覆盖安装后突然改变既有用户外观；用户主动选择系统或浅色后持久生效。

### 10.2 数据契约

冻结以下生产接口：

```kotlin
enum class NexaraThemeMode { SYSTEM, LIGHT, DARK }

enum class NexaraColorSource { NEXARA, DYNAMIC }

data class NexaraThemePreferences(
    val mode: NexaraThemeMode = NexaraThemeMode.DARK,
    val colorSource: NexaraColorSource = NexaraColorSource.NEXARA,
)

class ThemePreferenceStore(context: Context) {
    val state: StateFlow<NexaraThemePreferences>
    fun setMode(mode: NexaraThemeMode)
    fun setColorSource(source: NexaraColorSource)
}
```

SharedPreferences 键固定为：

- `theme_mode`: `system | light | dark`；
- `theme_color_source`: `nexara | dynamic`。

未知或损坏值回退到 `dark` / `nexara`。这两个键加入 UI 备份白名单；恢复后根主题无需重启即可更新。

`ThemePreferenceStore` 是唯一主题事实源，由 `NexaraApplication` 持有一个进程级实例。Store 必须监听同一 `nexara_settings` SharedPreferences 的外部变化，使备份恢复或兼容写入能立即反映到 `state`。现有 `SettingsViewModel.themeMode` 在迁移期只允许从 Store 派生并把写入委托给 Store，不得继续维护独立 `MutableStateFlow` 或直接写 `theme_mode`；外观页面接通后移除该兼容桥。

### 10.3 ColorScheme

- 提供完整 `NexaraLightColorScheme` 和 `NexaraDarkColorScheme`；
- Android 12+ 且用户选择动态色时使用 `dynamicLightColorScheme` / `dynamicDarkColorScheme`；
- 系统模式只决定亮暗，不自动启用动态色；
- 状态栏和导航栏图标依据 `colorScheme` 明暗切换；
- 所有正式页面通过 `MaterialTheme.colorScheme` 读取颜色；
- `NexaraColors` 只允许在迁移期间作为兼容桥，完成定义要求正式 UI 不再依赖静态深色值。

### 10.4 富文本和 WebView

Markdown、Mermaid、ECharts、LaTeX、PlantUML、HTML Artifact、表格和代码编辑表面接收当前主题语义色。主题切换必须触发内容重绘或重新注入 CSS，禁止浅色页面中仍出现深色 WebView，或图表文字与背景失去对比。

## 11. 动效与性能

- 不引入无限装饰动画、blur 或 glow；
- 导航、搜索、附件、Sheet 和列表选择只使用状态相关动效；
- “减少动效”遵循 Android 系统 Animator duration scale；本阶段不新增应用内独立开关。Compose 动效不得绕过 `MotionDurationScale`，系统比例为 0 时状态必须立即到达终态；
- Provider Models 和 Model Picker 继续使用 `LazyColumn`、稳定 key 和记忆化过滤；
- 模型详情从列表内联表单移出，降低可见列表组合复杂度；
- 500 个模型的搜索、快速滚动、启用切换和详情打开需要设备帧指标验证；
- 性能结论必须来自当前候选设备数据，不能只凭“标准组件更快”作判断。

## 12. 无障碍与输入法

- 所有动作至少 48dp；
- 图标按钮必须有中文、英文 content description；
- 选中、展开、开关、同步和测试状态提供 state description；
- 列表整行点击与尾部动作拥有独立语义，不产生重复焦点；
- 2.0x 字体允许辅助文本换行，主操作不得被挤出屏幕；
- IME 打开时 Provider 表单、搜索模式和主题选项可滚动到当前焦点；
- 自动语义测试不能替代最终签名 APK 的真机 TalkBack 听觉与遍历验收。

## 13. 实施阶段

1. 主题状态与双配色基础；
2. 共享列表、搜索和页面原语；
3. 手机导航、附件动作簇、会话列表与模型选择器；
4. 设置首页信息架构与 Provider 列表；
5. 记忆、索引和检索页面；
6. Provider 表单与模型管理；
7. 其余设置页与全站主题迁移；
8. 深浅色截图、设备、性能、TalkBack 和发行文档收口。

每阶段必须单独 RED/GREEN、独立代码/规格复审、人工检查 actual，并形成可回滚提交。共享热点文件串行施工。

## 14. 非目标

- 不改变 Provider 请求、模型元数据、RAG 检索、记忆、备份或会话生成业务契约；
- 不引入 Material 3 `1.5.0-alpha` 或 Expressive Alpha API；
- 不复制 Solid Explorer 或参考应用的品牌和专有布局；
- 不新增任意颜色选择器、主题商店或可下载主题；
- 不为视觉改造重写 Repository、Room schema 或网络层；
- 不在计划落盘时修改当前发行说明的“仅深色”事实；
- 不把 screenshot test 通过等同于人工视觉验收通过。

## 15. 完成定义

只有同时满足以下条件，才能将本规格标记完成：

- 手机主导航使用 `NavigationBarItem`，平板使用同目的地 `NavigationRail`；
- 附件动作从 `+` 锚定展开，菜单不再与触发按钮脱离；
- Agent 会话列表为连续 `ListItem`，低频搜索进入顶栏搜索模式；
- 模型选择器为连续列表，选择与完整编辑分离；
- 设置首页取消双 Tab，二三级设置使用统一列表/表单语言；
- `GlobalRagConfigScreen`、`SearchConfigScreen` 和正式设置页面不再依赖 `NexaraGlassCard`；
- 深色、浅色、系统和动态色在支持设备上真实生效并可持久恢复；
- UI、Markdown 和 WebView/图表在两种亮暗方案下均满足对比和可读性；
- 360dp、普通手机、2.0x、横屏、平板、IME、减少动效、中文和英文矩阵通过；
- 500 模型列表性能门禁通过；
- API 31/35/36 相关设备测试通过，所有 actual 逐张人工审阅；
- 当前签名候选完成真机 TalkBack 和核心业务人工验收；
- CHANGELOG、release validation、registry 和 HLG 与当前实现同步；
- 未经用户授权不执行 tag、PR 或 GitHub Release，整体发行在剩余门禁关闭前保持 NO-GO。
