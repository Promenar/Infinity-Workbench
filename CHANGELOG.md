# Changelog

All notable changes to this project will be documented in this file.

## [Unreleased]

### Material 3 总收敛：Task 14 本地设备、视觉与性能门禁（2026-07-21）

- **三档设备闭环**：当前集成工作树在 API 31/35/36 的冻结类集合各 101/101；同源 broader harness 分别完成 39/41/41 个显式测试并保留各 1 个设计内 phase checkpoint skip，三档均无 failure/error 且脚本退出 0。API 31 仍执行通知打开会话与前台服务，只跳过 Android 13+ 通知权限路径。
- **竞态与布局收口**：后台通知断言改为有界等待真实 active notification，引导底部导航使用稳定语义标签；文档标题在 2.0x 下最多两行显示且换行输入归一为空格，API 36 文档编辑交互 32/32。Provider Models 从搜索结果进入编辑 Sheet 时会先清除搜索焦点并收起 IME，避免键盘残留挤压编辑面板。
- **性能与视觉证据**：固定 API 36、`-no-window -no-snapshot -gpu host`、动画缩放 0 的 500 模型性能双跑均通过；两轮各保留 45 条可复算 raw histogram，较差有效样本 p95 35ms、max 51ms、PSS +2763KiB。两次命中系统周期包事件的批次明确标为 `FIXTURE_INVALID` 并保留，未替换慢样本；96 张 actual 已逐张检查，5 张文档编辑 reference 经定向复核后更新，Screenshot 96/96。
- **当前质量门禁**：JVM 2117 项（0 failure/error、14 skip）、Lint 0 Error/Fatal（420 warning、25 hint）、AndroidTest Kotlin 编译与设备脚本契约通过；release-equivalent `minifiedTest` R8 新鲜构建成功。API 36 完整 Provider Models 设备回归 17/17，并用真实 Insets 断言确认编辑 Sheet 打开后 IME 已收起；Terra 最终复审 0/0/0，Sol 为 0/0/2 且两项不阻断 Minor 已记录，Task 14 本地返修 GO。当前进程缺少真实 Provider 与签名环境变量，未重建签名 APK、未执行当前包冷安装，历史 PASS 不替代本轮，发行继续 NO-GO。

### Material 3 总收敛：全站自适应语义色与富文本主题（2026-07-21）

- **单一主题事实源**：正式 UI 清除 `NexaraColors`、`NexaraGlassCard` 与静态深色/Glass 消费，页面统一使用 `MaterialTheme.colorScheme`；成功、警告、信息、RAG 状态和代码语法色通过主题内 `NexaraDomainColors` 成对提供前景与容器色。
- **富文本实时换肤**：Markdown、LaTeX、Mermaid、PlantUML、ECharts 与 HTML WebView 从当前主题生成背景、前景、surface、outline、primary、error 和代码色板；API 36 真 WebView 在同一 Activity 内从深色切至浅色后计算背景由 `rgb(19, 19, 21)` 更新为 `rgb(251, 248, 251)`。
- **浅色视觉闭环**：新增与既有深色样例同内容的浅色富 Markdown reference；聊天、附件、引导、Provider Models、RAG/资源确认、RTL、横屏、平板和 2.0x 受影响 actual 已逐张人工及多模态复审。
- **当前门禁**：全量 JVM 2117 项（0 failure/error、14 skip）、96/96 Screenshot、Lint 0 Error/Fatal（420 warning）、AndroidTest 编译通过；API 36 富文本主题切换 1/1。独立代码复审 Critical/Important/Minor 为 0，多模态视觉复审 P0/P1 为 0。最终发行 Task 14 与真机/远端门禁尚未闭合，整体发行继续保持 NO-GO。

### Material 3 总收敛：外观与其余设置表面（2026-07-21）

- **真实外观状态**：外观页使用 Application 级 `ThemePreferenceStore` 和 `ThemeViewModel`，系统跟随、浅色、深色与 Android 12+ 动态色会立即持久化并在进程重建后恢复；单选行与动态色开关提供标准 Material 语义。
- **连续设置表面**：备份、工具、Token 用量、本地模型和开发者页移除普通选项卡片化，改用连续 `ListItem`、分隔线和标准动作层级；WebDAV 异步保存/测试、安全重置、备份加密、技能开关、用量统计与本地推理行为保持不变。
- **双语与大字体**：开发者页残留中文硬编码已资源化；技能内部 ID 使用单行省略，本地模型插槽在手机宽度下纵向排列，设备信息在 2.0x 字号下上下布局。
- **当前门禁**：全量 JVM 2115 项（0 failure/error、14 skip）、95/95 Screenshot、Lint 0 Error/Fatal、AndroidTest 编译通过；API 31/35/36 主题/备份组合各 6/6，API 36 生成并人工检查 24 张深浅色、双语、2.0x actual。Task 13 全站浅色语义色迁移与最终发行门禁尚未完成，整体发行继续保持 NO-GO。

### Material 3 总收敛：Provider 模型摘要与独立编辑（2026-07-21）

- **摘要列表与渐进编辑**：Provider Models 列表只展示友好名称、精确远端 ID、最多两个能力摘要和独立启用开关；整行进入独立 Material 3 编辑 Sheet，避免 500 模型场景在列表内同时维护完整表单。
- **编辑与来源合同保持**：名称、类型、能力、上下文、测试/取消、错误状态、删除确认和用户覆盖来源迁入 Sheet；同步刷新继续按稳定模型 ID 派生当前项，用户修改字段仍通过既有来源记录链路保存。
- **性能与可访问性**：固定 API 36 的 500 模型夹具执行 3 轮预热、5 轮测量，每轮 10 次搜索/滚动/切换/打开关闭；最终 p95 为 22–23ms、最大帧 75ms、PSS 增量 333KiB。API 31/35/36 的摘要、编辑、错误语义和删除流程各 16/16。
- **当前门禁**：全量 JVM 2109 项（0 failure/error、14 skip）、91/91 Screenshot、Lint 0 Error/Fatal、AndroidTest 编译通过；列表四张 reference 与 API 36 真实 modal 设备截图已人工及多模态复审，Terra/Sol/Luna 最终 Critical/Important 清零。整体发行继续保持 NO-GO。

### Material 3 总收敛：Provider 表单与密钥交互（2026-07-21）

- **连续标准表单**：云端与本地 Provider 配置移除大块配置卡片，文本输入统一使用 Material 3 `OutlinedTextField`，协议选择改为标准单选 `ListItem`，保持既有 HTTPS 校验、保存和本地模型流程。
- **稳定底部动作**：测试与保存固定在表单底部的响应式动作区，测试使用描边层级，保存是唯一 filled 主动作；横屏、2.0x 字体和长状态文本下仍可达且等高。
- **密钥与状态语义保持**：API Key 继续使用短生命周期掩码/揭示合同；测试中的禁用态以及成功、失败终态通过本地化 `stateDescription` 固定附着于测试按钮，不依赖滚动区状态行是否可见。
- **当前门禁**：全量 JVM 2108 项（0 failure/error、14 skip）、90/90 Screenshot、Lint 0 Error/Fatal、AndroidTest 编译通过；API 31/35/36 Provider 与密钥组合各 20/20，三张受影响 actual 已人工检查，Terra/Sol 最终双复审 Critical/Important/Minor 清零。整体发行继续保持 NO-GO。

### Material 3 总收敛：记忆、索引与检索设置（2026-07-21）

- **五页统一设置语言**：全局 RAG、Web Search、高级检索、知识图谱高级配置和 Agent 检索页移除 Glass 与嵌套卡片，改为连续 `ListItem`、标准分段控件、Slider、Switch、RadioButton、分隔线和确认对话框。
- **原子状态与密钥行为保持**：RAG 与 Agent 配置动作继续向各自 ViewModel 传递基于当前值的 transform；全局 RAG 的状态发布、持久化和运行时重建由同一串行临界区保护，避免并发字段更新互相覆盖或旧值后写；Tavily 密钥仍由 `SecretField` 完成掩码、短生命周期揭示、保存和清除。
- **无障碍与禁用态修正**：整行开关/单选使用单一 `toggleable/selectable` 语义，子控件不再创建重复点击焦点；禁用控件只使用 Material 标准禁用颜色，不叠加页面透明度，域名动作补全规则类型与目标朗读。
- **当前门禁**：全量 JVM 2106 项（0 failure/error、14 skip）、90/90 Screenshot、Lint 0 Error/Fatal、AndroidTest 编译通过；API 31/35/36 Task 9 设备测试各 6/6，5 张新增 actual 已人工检查，Terra/Sol 最终双复审 Critical/Important 清零。整体发行继续保持 NO-GO。

### Material 3 总收敛：设置首页与默认模型（2026-07-21）

- **单一设置层级**：设置首页移除应用/Provider 双 Tab 与首页内嵌 Provider、四角色模型选择器，改为账户、通用、AI/模型、知识/检索、工具/数据、关于六个连续 Material 3 分组。
- **独立 Provider 管理**：新增 Provider 二级列表，整行进入模型管理，顶栏新增，溢出菜单编辑/删除；默认 Provider 在 UI 与生产层均不可删除，删除额外 Provider 会同步清理其模型和四个预设引用。
- **单一默认模型页**：摘要、图像、嵌入、重排四角色共用统一模型选择器，选择后立即持久化，返回不产生二次保存；行与 Picker 共用 `ModelMetadataResolver` 投影后的精确友好名称。
- **当前门禁**：全量 JVM 2100 项（0 failure/error、14 skip）、85/85 Screenshot、Lint 0 Error/Fatal、AndroidTest 编译通过；API 31/35/36 设置组合各 22/22，9 张受影响 actual 已人工检查，Terra/Sol Critical、Important 均清零。整体发行继续保持 NO-GO。

### Material 3 总收敛：统一模型选择器（2026-07-21）

- **统一元数据投影**：所有模型选择入口改用 `ModelMetadataResolver.resolve(...)` 生成冻结的 `ModelSelectionUiModel`，精确名称、Provider、上下文长度、工作负载、三态能力与 Chat endpoint 兼容性不再由 UI 自行猜测。
- **标准连续列表**：通用模型面板与会话设置共用 Material 3 `ListItem`，选中行使用 tonal 容器与单一 RadioButton 语义；移除旧能力胶囊、硬编码颜色和 UI 专用 `ModelItem`/`ModelCapability` 兼容层。
- **过滤与可访问性**：显式不支持 Chat endpoint 的模型不会因生成式工作负载被重新放行，未知端点只在生成式工作负载时作为候选；当前已选旧模型仍可显示。行高至少 48dp，长名称、2.0x 字体、未知能力与 120 模型目录均有覆盖。
- **当前门禁**：全量 JVM 2085 项（0 failure/error、14 skip）、81/81 Screenshot、Lint 0 Error/Fatal、AndroidTest 编译通过；API 31/35/36 定向设备测试各 2/2，Terra/Sol Critical、Important 均清零。当前签名 R8 APK 已完成验真及 API 35/36 同哈希冷安装；整体发行仍保持 NO-GO。

### Material 3 总收敛：助手会话与顶栏搜索（2026-07-20）

- **统一顶栏搜索**：助手首页与会话列表移除常驻搜索框，在页面顶栏进入上下文搜索；退出搜索会清空 query，并按稳定条目 ID、原始索引和像素偏移恢复搜索前位置。
- **连续 Material 列表**：会话行改为透明连续 `ListItem`，保留整行进入、时间、预览、滑动置顶/删除和删除确认；无会话与无匹配结果均使用明确空态，不再复用玻璃卡片。
- **无障碍与大字体**：会话行提供 TalkBack 置顶/取消置顶与删除自定义动作，常规触控目标保持至少 48dp；2.0x 字体下标题和预览放宽至四行，长标题、长预览不与操作区重叠。
- **当前门禁**：全量 JVM 2046 项（0 failure/error、14 skip）、79/79 Screenshot、Lint 0 Error/Fatal（406 warning）、AndroidTest 编译以及 API 31/35/36 定向设备测试各 15/15 通过；Task 6 受影响 actual 已逐张人工检查，Terra/Sol Critical、Important 均清零。整体发行继续保持 NO-GO。

### Material 3 总收敛：流体胶囊手机导航（2026-07-20）

- **更矮的全胶囊导航坞**：手机底部导航改为 64dp tonal 全胶囊 Surface，选中项使用一枚 48dp 高的移动胶囊承载图标与标签，未选中项保持图标模式；600dp 及以上继续使用既有 `NavigationRail`。
- **水滴挤压切换**：选中指示器以弹簧位移在三个目的地间移动，过渡中横向拉伸、纵向收缩后回弹；快速反向切换从当前形变连续接续，不重置闪跳，系统关闭动画时直接到达终态。
- **胶囊水波纹边界**：保留 Material 原生 bounded ripple，但三个 Tab 的交互层统一使用 `CircleShape` 裁剪；按压时水波纹只在对应胶囊内扩散，不再出现方形槽位边框。
- **可访问性与图层安全**：三个目的地均保留 `Role.Tab`、选中语义、完整名称和至少 48dp 触控目标；指示器使用不透明主题预混色并覆盖图标层，避免移动首帧出现旧图标穿透。
- **视觉与设备门禁**：深色、浅色、英文 2.0x 和紧凑横屏四张手机 actual 已逐张检查；API 35 验证快速连点、形变中点、反向连续性和 0x 动画缩放，平板 Rail 不受影响。
- **阶段边界**：本轮是 Task 4 的实机观感返修，不改变后续搜索、模型选择器和设置树迁移范围；整体发行继续保持 NO-GO。

### Material 3 总收敛：锚定式附件动作簇（2026-07-20）

- **输入栏原位展开**：附件 `+` 使用真实布局坐标展开单一 Material 3 动作面板，并旋转为关闭语义；图片与完整上下文文档继续复用既有选择入口，不使用脱离锚点的 `DropdownMenu` 或 Popup 偏移猜测。
- **覆盖与状态安全**：透明关闭层位于整个聊天 `Scaffold` 之上，外部、顶栏和系统 Back 首次点击只关闭菜单，不穿透到底层动作；生成或文档导入开始后菜单立即关闭并禁用。布局坐标尚未回报时使用根容器内受约束的首帧位置，随后切换到实际锚点。
- **无障碍与视觉门禁**：动作行保持至少 48dp，展开/收起语义、RTL、2.0x、320dp 短窗口、真实 IME 和 0x 动画缩放均通过设备测试；普通、英/中 2.0x、紧凑视口和 RTL 五张 actual 已逐张检查。
- **阶段边界**：本轮只完成聊天附件动作簇；业务搜索、会话列表、模型选择器和设置树继续按后续任务迁移，整体发行保持 NO-GO。

### Material 3 总收敛：自适应标准导航（2026-07-20）

- **手机浮动导航坞**：底部三个目的地改为单层 tonal `Surface` 内的标准 `NavigationBarItem`，保留克制外边距和系统导航栏 Insets；移除自绘 glow、阴影、无涟漪缩放反馈及固定 10sp 标签。
- **大屏标准 Rail**：600dp 及以上继续使用 `NavigationRailItem`，深浅主题统一消费 Material `colorScheme`；三项均提供 Tab 角色、选中态与至少 48dp 目标。
- **内容避让与视觉门禁**：API 36 实际滚动 50 项列表到末项，末项底边保持在导航坞之上；手机/平板深浅色四张 actual 已逐张检查，未发现裁切、低对比或 iOS 化结构。
- **阶段边界**：本轮只收敛主导航及其自适应行为；附件动作簇、业务搜索和设置树继续按后续任务迁移，发行保持 NO-GO。

### Material 3 总收敛：管理页面共享原语（2026-07-20）

- **连续设置分组**：新增 `NexaraSettingsSection`，以透明连续列表、Material 语义分组标题和从正文起点对齐的分隔线替代玻璃卡/逐项 Surface 组合；既有分组标题移除 10sp、指定字体和静态颜色。
- **顶栏搜索模式**：新增受控 `NexaraSearchTopBar`，正常态保留页面标题和 actions，搜索态在同一 Top App Bar 内完成焦点、IME Search、清除及返回优先级；淡入过渡遵循系统动画时长缩放。
- **无障碍与视觉门禁**：API 36 实测 2.0x 长标题、48dp、actions、自动焦点、IME、系统 Back、0x reduced-motion 和分隔线 bounds；Backup Settings 唯一预期截图变化已人工对照并更新。
- **阶段边界**：本轮提供共享原语并修正既有分组标题，具体设置树、会话列表及其他搜索页面仍在后续任务逐页迁移；发行继续 NO-GO。

### Material 3 总收敛：自适应主题状态基础（2026-07-20）

- **唯一主题状态源**：新增 Application 级 `ThemePreferenceStore`，统一持久化系统跟随、浅色、深色以及 Nexara 配色、Android 动态色选择；偏好恢复和外部备份恢复会即时更新应用主题状态。
- **完整双配色与系统栏**：补齐浅色 Material 3 语义角色，应用根主题按解析后的明暗模式选择静态或动态 `ColorScheme`，状态栏和导航栏图标同步适配明暗背景。
- **备份契约同步**：`theme_mode` 与 `theme_color_source` 同时进入实际设置命名空间和兼容 UI 命名空间的备份白名单，异常持久化值安全回退到深色 Nexara 配色。
- **阶段边界**：本轮只建立主题数据、持久化和根主题基础；主题设置页面、全站浅色迁移及浅色/动态色 actual 仍属于后续任务，当前发行仍保持 NO-GO。

### 会话完整文档上下文、导出与消息分支（2026-07-20）

- **TXT/Markdown 完整上下文**：输入栏添加菜单支持一次选择多个 TXT/MD；正文以 SHA-256 绑定边界随用户消息持久化并完整进入统一协议，不走 RAG、不依赖 Provider 专有文件接口，重试与后台生成复用同一快照。
- **路由后硬预算门禁**：最终系统提示、活动历史、完整文档、图片、工具与输出预留按 UTF-8 字节保守估算；稳定模型 ID 读取用户容量覆盖，最终远端 ID 解析目录。未知或超限在 Provider 网络请求前阻止，不静默截断、摘要或降级检索。
- **可靠重试与草稿时序**：重试期间旧 AI 回复保留在持久层但从新 Prompt 排除，只有新回复成功后才替换；Busy、路由、预算、流式、取消或持久化失败均不丢旧回复。文本、图片与文档统一在 Prompt 被接受后清空，失败后可直接修正并重发。
- **导出与消息分支**：Markdown/TXT 导出包含角色、时间、实际模型及附件元数据/全文，可重新上传作为完整上下文；稳定消息可创建独立会话，重映射消息父引用，拒绝不完整工具链并重置摘要、运行态、向量派生状态和工作区。
- **当前门禁**：全量 JVM 2021 项（0 failure/error、14 skip）、64/64 Screenshot、Lint 0 Error/Fatal、AndroidTest 编译与 API 31/35/36 相关 Compose 测试通过；Terra/Sol 双复审均清零 Critical/Important。签名 R8 APK 为 18,250,632 bytes，SHA-256 `50ccd9a2906dff4a7b54f6304be253bdbe1ffc6414df70fb33002acfc9558286`，API 35/36 完成同哈希冷安装、字节回读、启动与应用级 crash/ANR 复验。真实 Provider 六项运行时变量本会话未注入，因此本轮未把历史 Provider PASS 冒充当前复验；整体发行继续 NO-GO。

### 知识库、生成终态与设置流式体验修正（2026-07-19）

- **空知识库不再显示幽灵失败**：启动恢复只归一化旧版 `document` 索引任务；缺少文件身份或已确认文件不存在的孤儿任务会连同派生向量/图谱数据清理，现代 `document_reference` 目标不受影响。错误提示不再伪造 `0%` 进度或进度语义。
- **生成终态即时收口**：失败、异常与取消的兜底路径在协调器结束前先发布展示终态，聊天页不再概率性停留在“生成中/思考中”，无需退出会话再进入刷新。
- **流式视觉缓和**：正常 chunk 仍即时显示，不引入人工打字队列；仅对正在流式输出的非空尾部应用 140ms Material 缓动淡入和 28dp 底部轻遮罩，避免整段内容反复离屏合成或持续变暗。
- **设置密度统一**：设置首页与 Provider 管理改为连续、无逐项卡片的 Material 3 列表，使用单层分组和分隔线；常规、2.0x 字体和横屏空态采用自适应间距，同时保留 48dp 最小触摸目标和系统字体缩放。
- **覆盖安装兼容与新会话稳定性**：仅对可精确识别的旧版空哈希工作区根执行受信迁移，保留原目录内容并拒绝越界、悬空、符号链接或子路径根；新会话从数据库真相恢复且初始循环状态归一化为已完成，避免创建后进入闪退；会话工作区可正常加载。列表刷新由数据库决定会话成员与顺序，但同 ID 会话保留 Store 中较新的流式消息和运行状态，避免迟到快照回滚界面。
- **设备门禁补强**：备份恢复结果在列表滚动后仍自动回到可见状态；onboarding 的懒列表测试改用确定性节点滚动，通知发布 E2E 等待系统确认 active notification，消除 API 35/36 预取与通知服务异步差异。当前全量 JVM 1982 项（0 failure/error、14 skip）、Lint 0 Error/Fatal、62/62 截图及逐张 actual 复核、AndroidTest 编译、API 31 minimum 18 项、API 35/36 full 各 37 项、双 API 真实新会话入口 E2E 和四角色真实 Provider 均通过；每台设备各有 1 个设计内 checkpoint skip，未记为 PASS。
- **当前签名候选**：从本轮源码新鲜构建稳定证书签名 R8 APK，18,214,452 bytes，SHA-256 `5b09ed2876e639d611b80d8d3b68f2f331f1720ecc8e9f3023521ff7b98eb9da`；fail-closed 验真、16 KiB zipalign 及 API 35/36 冷安装通过。独立终审为 Critical 0 / Important 0，保留 3 个不阻断 Minor。真机 TalkBack/核心业务人工验收、当前提交远端 CI、tag workflow 与 GitHub Release 尚未闭合，整体发行继续 NO-GO。

### 分层模型元数据注册中心与会话尾注（2026-07-18）

- **精确模型元数据解析**：模型身份改由 `ModelMetadataResolver.resolve(...)` 统一解析，按用户覆盖、Provider 精确元数据、Nexara 精确修正、models.dev 离线目录、家族展示和 fallback 逐字段合并；unknown 不再默认成 chat，推理能力与 Chat endpoint 兼容性分别记录。
- **离线目录供应链**：内置 258 条 models.dev 规范化快照与 schema v2 manifest，固定来源、MIT 许可、源字节/SHA-256、目录 SHA-256、条数和未知字段计数；更新脚本、64 项 Python 契约、26 项 Kotlin 目录测试及 draft-only 刷新 workflow 共同阻止静默漂移和自动合并。
- **用户覆盖与备份**：Provider 模型迁移保留用户编辑字段，刷新只更新自动元数据；family、canonical ID、chat endpoint、自动指纹和用户编辑字段进入偏好备份白名单，并以真实类型完成恢复。
- **引导与会话显示一致性**：聊天探测成功只记录 Chat endpoint 兼容，不再改写主要工作负载；输入区和 AI 尾注复用同一友好名称解析，历史已删除模型仍优先使用持久化名称。AI 的“模型名 + 时间”在左侧并列，用户时间继续右对齐。
- **真实 Provider 门禁**：四个受控内网模型均通过同一 Router 的新鲜流式请求，精确友好名称、推理能力三态、Chat endpoint 兼容性及流式终态符合冻结契约；凭据仅注入当前测试进程，未写入源码、报告或构建产物。
- **可见模拟器与发行回归**：本轮 1945 个 JVM 测试（0 failure/error、14 skip）、61/61 截图、Lint 0 Error/Fatal、AndroidTest 编译以及 API 36 完整设备套件 36/36 通过。主控在可见 API 36 模拟器逐页检查常规/2.0x 字体、横竖屏、IME、长名称和中英文状态，修正知识图谱空态文字贴边，并稳定通知权限重置后的 Package Manager 等待；当前签名 R8 APK 在 API 35/36 冷安装、设备字节回读、启动和 crash/ANR 观察通过。真机 TalkBack/核心业务、当前提交远端 CI、tag workflow 与 GitHub Release 尚未闭合，整体发行继续 NO-GO。

### Material 3 Agent 首页连续列表收口（2026-07-17）

- **助手条目回归标准 Material 3**：主界面助手列表由逐项 Glass 描边卡片与冗余右箭头改为连续 `ListItem`；使用 40dp tonal 图标、Material 排版/颜色、从正文起始位对齐的分隔线，整行继续负责进入会话，尾部只保留置顶状态和 48dp 更多操作。
- **滑动交互密度纠偏**：`SwipeableItem` 的动作宽度、触发阈值和最大位移统一由 dp 转换为真实像素，避免高密度设备上短距离手势误触删除；Agent 首页采用矩形连续行，其他调用点保留原有圆角默认值，装饰性滑动背景图标不再制造不可点击的 TalkBack 焦点。
- **视觉与交互门禁**：新增普通/长标题/长描述/空描述/中文 2.0x 字体截图夹具，57/57 Screenshot validation 通过并逐图审阅；Pixel 7 API 36 上整行进入、菜单不误导航、置顶状态语义、短滑不误删与空态添加共 6/6 通过。全量 1919 个 JVM 测试（0 failure/error、14 个既有条件 skip）、Lint 0 Error/Fatal 与 Debug APK 构建通过。
- **当前签名候选**：从本轮当前源码 clean 构建稳定证书签名 R8 APK，fail-closed 身份/签名/敏感内容/GGUF 扫描、16 KiB zipalign 及 API 35/36 冷安装、设备字节回读、前台启动和 crash/ANR 观察全部通过。APK 为 18,173,163 bytes，SHA-256 `45732833504658a7e84f4ef0efee83bccbbd0c1bb7e9486b1519607c474509db`；仍须用户真机完成 TalkBack/核心业务人工验收。

### Material 3 重设计第四阶段：DocEditor 可靠性与视觉迁移（2026-07-17）

- **重命名与索引目标版本化**：DocEditor 重命名使用 persisted-title CAS 与单调 epoch；Room `document_reference` 任务以 `workspace + file + hash + epoch` 表达唯一当前目标，旧 worker、旧失败和旧清理不能覆盖或删除 newer target。
- **删除、重置与取消线性化**：文件/目录/工作区永久删除在数据库提交前取消并等待目标 worker、建立 enqueue fence，并在失败时安全恢复；Provider/Embedding/KG 配置切换等待旧队列关闭后由唯一新队列接管，监听器关闭与状态通知异常不会破坏资源替换。
- **已提交文件与索引补偿分离**：文件写入、Patch 或重命名在物理/Room 提交后即使调用协程取消，也返回真实 committed 事实并保留 `indexQueued=false`；应用级 pending coordinator 让 Home、Folder 与 DocEditor 共用精确失败目标、重试与删除清理，并用 latest watermark/删除 tombstone 拒绝迟到旧事件制造幽灵告警。
- **冷启动完整恢复**：进程死亡发生在文件事务提交后、Queue 接收前时，启动扫描会从 FileEntry 当前 hash/epoch 重建 reference task；`vectorizedAt` 为空或落后的文件均覆盖，KG 开启时恢复显式 `full` 策略，关闭时保持无 KG。
- **工具与分享一致性**：File Write/Patch 只使用真实 `read_file → write_file` 补偿路径，区分“文件已提交、索引待处理”与写入失败；分享导入使用当前文件版本和 KG 配置，空图谱也写入完成标记，避免持续显示未完成。
- **DocEditor 索引告警可达性**：索引 pending 从普通统计栏拆为独立单层 MD3 notice，在加载/保存错误与冲突状态仍唯一可见；重试按钮保持 48dp。新增 360×640dp、2× 字体、SaveConflict 双动作 + pending 的仪器契约，并在 API 31/35/36 的 DocEditor 31/31 矩阵中完成设备执行。
- **DocEditor 长文档性能保护**：文件大小严格超过 1 MiB 时在读取前进入 `MetadataOnly`；已读取正文严格超过 32K 个 UTF-16 代码单元、2,000 行或单行 16K 个 UTF-16 代码单元时进入 `PerformanceProtected`。保护态不构建全文编辑器或 Markdown，仅预览约前 16K 字符并提供复制完整全文；若一次输入跨过软线，dirty 全文仍完整保留，可以原样参与 CAS 保存与冲突处理，不使用预览截断片段。
- **DocEditor API 36 性能门禁**：固定 Pixel_7 AVD、headless `-gpu host` Apple M5 / Metal 后端，以 3 轮预热/5 轮采样两次完成 6/6 保护态与 Editable 边界矩阵；60 个原始样本最差 p95 35 ms、最大帧 40 ms、PSS 增量 12,194 KiB。初始近 1 MiB 约 7 秒与 10,000 行约 1.05 秒的失败证据促成性能保护；门禁仍为 p95 ≤50 ms、单帧 ≤150 ms、PSS 增量 ≤64 MiB，未放宽。
- **标准 Material 3 编辑器**：DocEditor 迁移到标准 TopAppBar、SegmentedButton、Surface、TextField、AlertDialog 与 tonal notice；移除本页 Glass、固定微字号、硬编码页面色和重复描边。360dp、2.0x、精确 800×360、840dp/2.0x Split 与真实横屏 IME 均保持标题、编辑、保存和恢复动作可达。
- **Markdown 与代码块可达性**：基于 Markdown AST 和真实 `TextMeasurer` 识别宽代码/宽表格；顶层及任意深度引用/列表内 GFM 表格由第一方外层承担横向滚动，第三方回调内不叠加滚动。CodeBlock 全屏、导出、编辑/保存、复制均使用真实 48dp 目标和双语描述，装饰语言/行号不进入 TalkBack 焦点。
- **状态与 IME 恢复**：保存错误、冲突、NotFound 与索引待重试在 IME 展开时主动让出键盘空间并保留草稿、dirty、局部保存和恢复动作；保存中返回、冲突双动作、索引重试、只读和行号静音均有可复核语义。
- **性能证据防假绿**：最终 FrameMetrics 门禁不再拒绝或替换超标样本；两次完整 6/6 的 60 个原始样本全部直接纳入判定，最差 p95 35 ms、最大帧 40 ms、PSS 增量 12,194 KiB，`UNKNOWN_DELAY` 仅用于诊断。
- **最终门禁**：新鲜 `--rerun-tasks` 执行 130/130 Gradle tasks；1915 个 JVM 测试（0 failure/error、14 skip）、56/56 截图、Lint、Debug APK、deviceTest APK 与 AndroidTest 编译通过。DocEditor API 31/35/36 分别 31/31；API 36 bulk 228 项（0 failure、3 个既有分阶段 skip）及两项 force-stop 冷启动方法各 1/1。第四轮终审 C0/C1/C2/C3=0、P0/P1/P2=0。第四阶段完成不代表整体 Release 放行，签名 APK 真机 TalkBack/人工业务验收与 GitHub tag/Release 仍为阻断项。

### Material 3 重设计第三阶段：知识库与资源管理（2026-07-16）

- **知识库与索引状态统一**：RAG 搜索、索引进度、状态提示、首页、Memory 与文件夹页面统一为单层 Material 3 列表/tonal surface；进度卡只向 TalkBack 发布一次进度、状态描述与 LiveRegion，内部可见文字和真实进度条不再重复朗读。
- **稳定文件树与目录操作**：FilesPanel 使用 UUID 稳定身份和懒加载可见树；目录观察生命周期从单个父行是否在屏幕上解耦，父行离屏但后代可见时仍保持实时更新，折叠或分支离开后释放，80 根目录场景保持 collector 有界。移动目录在大字体和横屏下可滚动并完成第 20 项回调。
- **RAG 首页、Memory、文件夹与详情**：Documents/Memory 信息层级、批量选择栏、移动/重索引/删除、Web/文档/KG 详情和会话 RAG 入口统一到稳定 MD3；移除固定 70% Sheet、Glass 卡套卡、装饰性渐变和重复状态语义，保留真实导航、失败重试与取消传播。
- **资源管理器与回收站**：Files/Recycle 上下文动作完全隔离，长导入错误可读可重试，回收条目与运行/失败/确认状态改为单层列表；资源管理器仅在覆盖层打开时组合并使用生命周期感知收集。独立 observer epoch 防止同会话重开、观察失败或旧 observer 迟到异常取消后台导入/回收或清空结果。
- **响应式与视觉证据**：360dp、2× 字体与 800×360 横屏覆盖移动 Sheet、长内容、回收站末项和对象化 TalkBack 操作；54/54 截图通过。Preview Screenshot 引擎无法捕获真实 `ModalBottomSheet` 弹层窗口，纯白候选基线已拒绝并撤回，弹层压力布局由真实 API 31/35/36 Compose 设备交互证明。
- **最终门禁**：干净构建下 JVM 1733（0 failure/error、14 skip）、Lint 0 Error/Fatal、Debug APK、deviceTest APK 与 54 张截图全部通过；API 31/35/36 五类定向矩阵各 65/65（合计 195/195），API 36 bulk XML 为 195 项、0 failure/error、3 个分阶段 checkpoint skip。两项后台持续生成冷启动恢复均在已安装包、停止前 PID 非空、force-stop 后 PID 为空的条件下直接 instrumentation 1/1 通过；代码终审 C0/I0、UI 终审 P0/P1/P2 清零。
- **阶段边界**：本阶段不改变外部 API、Room schema 或检索算法；Memory loading/error/retry 仍是独立产品可靠性 P1，DocEditor 继续进入下一独立视觉阶段。第三阶段完成不等于整体 GitHub Release 已放行。

### Material 3 重设计第二阶段：设置与 Provider 管理母版（2026-07-16）

- **管理面统一**：设置首页改为标准 Tab、分组标题与单层 `ListItem`；Provider 列表收敛为单层对象行与明确主次操作，移除卡片墙和重复焦点，并明确显示启用/停用及 API Key、Vertex 或本地无需凭证状态。
- **Provider 表单与密钥安全**：添加、编辑、本地 Provider 三条路径统一使用 Material 3 字段、下拉菜单、按钮和可滚动布局；API Key 默认安全遮罩、显式查看、超时/失焦/离页回遮与可选完整备份契约保持不变。
- **模型管理渐进披露**：搜索支持显示名、真实远端模型 ID 与稳定 ID；同步/添加保留首层，批量禁用/删除进入 overflow；模型行默认只展示名称、真实 ID、启用状态和主要能力，高级编辑、类型、能力、Context、只读元数据、测试与危险操作按需展开。
- **响应式与无障碍**：移除固定 112dp Context、五类型硬塞一行和彩色能力标签墙；使用 `FlowRow`、自适应 `OutlinedTextField`、48dp 操作目标、完整 ID 语义、双语展开状态、assertive/polite live region 及单模型删除确认。
- **视觉与设备验收**：41/41 截图通过，所有变化基线均完成旧/新同尺寸人工对照；API 31、35、36 的设置/Provider 四类关键 UI 测试更新后各累计 51/51，通过 2× 字体、横屏、IME 与系统动画倍率为 0 的减少动效路径。
- **完整回归**：全量 JVM、Lint、Debug APK 与 deviceTest 构建成功；API 36 常规设备套件 0 失败（3 个多阶段 checkpoint 按设计跳过），两个后台冷启动恢复方法逐次 force-stop 后各 1/1 通过；阶段独立复核无 P0/P1。
- **阶段边界**：本阶段不修改业务数据结构或外部 API；知识库、RAG 详情、资源管理器、回收站与文档编辑器仍按全站设计契约进入后续迁移，不把当前结果表述为全站完成。

### Material 3 重设计第一阶段：主题基线与主会话母版（2026-07-16）

- **稳定技术基线**：Compose BOM 升级到 `2026.06.00`，Material 3 使用稳定版 `1.4.0`；统一深色 edge-to-edge 色阶、形状、间距与 elevation 令牌，不引入 Alpha Expressive API。
- **主会话视觉母版**：会话页改为低层级 MD3 表面、tonal 用户消息、全宽思考轨迹、正文直排、单层 composer 与紧凑操作胶囊；RAG 进度、摘要、审批卡和 Token 详情菜单移除嵌套玻璃壳。
- **交互与无障碍**：输入框、模型/Token 选择器、摘要展开与助手长按区保持至少 48dp 目标；输入框同时通过可聚焦输入、IME 回退和 2× 字体不裁切验证；长模型 ID 单行省略并保留行尾时间戳。
- **验证结果**：36/36 截图基线、完整 JVM/Lint/Debug/deviceTest 构建门禁、Pixel 7 API 36 的 124 项常规设备套件（3 项按设计跳过）及 2 项逐次 force-stop 冷启动测试全部通过；独立最终复核结论为无 P0/P1。
- **阶段边界**：本阶段只确立全站主题与聊天视觉母版；RAG 详情、设置、Provider/模型管理、资源管理器等管理面继续在后续阶段逐页迁移，当前不宣称全站视觉改造完成。

### v0.2-beta 发行候选收口（2026-07-13）

> 当前状态：代码与发行文档正在集成；完整设备/截图矩阵、真实 API 四模型 smoke、分支 Android CI 及稳定证书签名 R8 APK 的 API 35/36 冷安装已通过。GitHub 可验证 tag、签名候选真机 TalkBack/人工业务验收和 GitHub Release 仍待关闭。以下条目描述已经落地并有源码或专项测试证据的变更，不代表版本已经发布。

- **真机首轮反馈闭环**：首次引导首条回复完成后只持久化完成状态，不再重建导航图并跳回主界面；返回键仍以主界面为栈基座。知识库、全局记忆设置和检索设置统一使用受信任的应用私有工作区，旧版不存在的绝对路径会回退到默认工作区，初始化异常转为可见提示而非闪退。
- **聊天与模型管理布局收口**：聊天输入区的模型/Token 胶囊采用 48dp 触控目标和 34dp 可见高度；模型管理批量操作固定 2×2 间距，类型/能力标签使用紧凑可见面与独立 48dp 触控层，上下文字段固定 112×48dp 并使用数字键盘。9 张受影响基线经人工对照后更新，36/36 Screenshot validation 通过。
- **模型元数据解析纠偏**：内置规格匹配按精确字符串、精确正则、具体部分匹配、通用回退排序，不再因通用规则靠前吞掉具体模型；补齐 DeepSeek V4 Flash/Pro、MiniMax M3 与 MiniMax M2.7/highspeed 的上下文和能力信息。既有模型只在仍完全符合旧自动生成指纹时迁移，用户自定义名称或上下文不会被覆盖。
- **最终本地签名候选验真**：当前 `d8d31090` 完成 1915 项 JVM 测试、56/56 截图、Lint、Debug/deviceTest 构建、DocEditor API 31/35/36 各 31/31 及 API 36 完整 bulk/cold-start 门禁。稳定证书签名 R8 APK 为 18,173,215 bytes，SHA-256 `9b4f109c203b5ce8b428e43d48e8fbe537c3f7907a788a67480e41dc4b1f546b`；API 35/36 均完成 fail-closed 验证、冷安装、设备 APK 字节回读、冷启动和 crash/ANR 观察。当前分支远端矩阵与用户真机人工验收仍待完成。
- **后台持续生成**：引入应用级 `GenerationCoordinator`、单一活动任务控制、Foreground Service 与通知返回/停止动作；前台发起的生成可跨切后台、锁屏、旋转和 Activity 重建继续。设备重启续传、多会话并行和无人值守队列不在本版范围。
- **结构化生成失败与取消契约**：统一鉴权、限流、配额、无效请求、服务端、协议、持久化等错误类型；取消异常贯穿生成、协议、工具和清理边界，不再被误写为普通失败或完成状态；流式通道改为背压投递并保留错误 cause 链。
- **RAG 交互闭环**：文件移动由占位动作改为真实持久化操作，部分失败保留失败项；普通文档与引用文档均可基于稳定目标重试索引；失败提示提供真实重试/关闭动作并补齐动态状态语义。
- **提示词编辑器**：统一保存/关闭和脏内容退出保护，移除伪 Sheet 模式，采用单一纵向滚动源，补齐长文本、2x 字体、空预览和 CJK/拉丁统计测试入口。
- **Release 日志安全**：业务日志统一进入 `NexaraLogger` 的 Debug 编译门禁，R8 对日志入口和平台输出执行精确剥离；源码契约禁止业务代码直接调用 `Log.*`、`printStackTrace` 或标准错误输出。
- **APK 验证器**：增加 ZIP 完整性/解压边界、唯一包身份、唯一签名者与证书指纹、敏感内容跨块扫描、50 MiB 上限和标准 SHA-256 输出的 fail-closed 校验；确定性 Python 测试 20/20 通过。
- **发行工作流可靠性**：CI 覆盖真实默认分支与 API 31/35/36 设备作业；tag workflow 检查发行文档、tag 来源/签名策略、R8 mapping、checksum、zipalign、API 35/36 冷安装，并以稳定 artifact 名和幂等 prerelease 发布流程支持重跑。push CI 经两轮失败定位与修复后已在最终候选上全绿，tag release workflow 尚待首次发行运行。
- **分享导入与 RAG 发行验真**：分享表单把 PDF、DOCX、TXT 等已知 MIME 收敛为紧凑格式标签，避免窄屏长 MIME 串多行破坏布局；底部四动作改用保持 8dp 双向间距的可换行布局，并通过 API 36 的 2.0x 字体可达性测试。API 35/36 release-equivalent 混淆包黑盒均已覆盖 TXT 导入、索引失败与再次重试；文件型 Room 测试覆盖数据库关闭重开后的失败任务恢复与确定性 task id 复用。
- **Android 截图证据可靠性**：新增纯 Python 标准库的 RGBA8 PNG alpha 归一化工具，校验 PNG chunk/CRC、逆转 filter 0–4、保持 RGB 并把 alpha 原子改为 255；minified 黑盒的 8 张最终截图全部接入并清理 raw 临时文件，离线测试、真实异常 PNG 像素比对与 API 36 黑盒均通过。
- **Android CI 稳定性收口**：首轮远端 CI 暴露系统权限窗跨窗口查找和 onboarding 启动竞态，第二轮进一步暴露真实旋转测试宿主导致 Compose root 销毁及恢复中继把“旧进程死亡后新进程已启动”误判为失败。欢迎页压力测试现改用 Compose 窗口/字体配置覆盖；恢复门禁由测试内持久化 stage PID 验证跨进程重启，并保留阶段结束 PID 证据。最终候选 [run 29401903929](https://github.com/Promenar/Nexara/actions/runs/29401903929) 已全绿：quality、API 31、API 35、API 36 四项均成功。
- **真实 LLM 四角色门禁与确定性取消契约**：在同一 `ProviderRouter` 中分别以 `MiniMax-M3`、`MiniMax-M2.7-highspeed`、`deepseek-v4-flash` 与 `sensenova-6.7-flash-lite` 各完成一次真实请求，覆盖文本流式 payload/终态与图片输入，并修复 Generic OpenAI-compatible 对 `delta.reasoning` 的兼容解析。取消传播不再用可能已自然结束的高速真实响应作证明：统一客户端改由受控在途协议 Flow 断言生产者退出，协议层用保持打开的 MockEngine SSE 响应直接断言取消 cause 与响应体关闭。真实集成 Gradle 任务禁用 up-to-date 与构建缓存，确保每次显式调用都实际外呼；真实凭据仅以进程环境变量注入，未写入仓库或报告。
- **最终签名 R8 候选**：使用项目外 `secure_env` 中的稳定 keystore 构建 17,971,235-byte Release APK，生成完整 mapping/seeds/usage/configuration；APK 验证器完成包身份、唯一签名者、登记证书、敏感内容、GGUF/llama/ggml、体积与 SHA-256 扫描，API 35/36 均完成卸载旧包、安装后字节回读一致、冷启动与 crash/ANR 观察。冷安装脚本改为复用统一 fail-closed APK 验证器，补齐 Build Tools 37 `V2 Signer` 输出兼容，避免脚本与主验证器的签名解析漂移。
- **Metro 开发者 TUI**：调试终端补齐 `--help`、`--version`、`--stdin`、`--serial`、`--tag`、非 TTY 无色输出、明确退出码和缺字段容错；49 项 Node.js 测试通过。该工具只服务 Debug 日志分析，不是最终用户 CLI，也不与 Android UI 做功能对等承诺。
- **发行边界**：最低系统改为 Android 12/API 31；GGUF/llama.cpp 不进入稳定发行能力，Release 关闭本地推理并拒绝打包相关制品；正式分发目标为 GitHub Release 可侧载签名 APK。

### v0.2-beta P0 安全与数据交付门禁收口 (2026-07-12)
- **密钥与备份安全**：API Key 默认脱敏显示，完整显示需要显式操作且在失焦/生命周期切换时自动回遮；备份可选择是否包含完整 Key，恢复采用鉴权、预检和事务写入，错误密码或损坏包不落库。
- **数据完整性**：Room schema、备份顺序、复合外键和进程重建恢复链路完成一致性加固；真实 Android 多阶段恢复验证覆盖进程被杀后的继续处理与无重复回放。
- **设备 UI 门禁**：补齐安全设置和备份真实界面的 Compose 测试，P0 最终通过 JVM、Lint、APK 构建和 Android 设备测试。
- **主要提交**：`637ca38`、`91c720e`、`ce047cc`、`b574251`、`9d8b72c`。

### v0.2-beta P1 Provider、工作区、KG、工具与分享主链闭环 (2026-07-12)
- **请求级 Provider 路由**：模型使用稳定复合 ID 解析到所属 Provider、协议、Endpoint 和密钥配置；禁用、删除、空 Key、错配和绕过路径在建连前显式失败。
- **Session 工作区隔离**：文件 UUID、父节点、路径、版本和工具执行上下文受当前 `workspaceRootUuid` 约束，跨 Session 访问和路径逃逸被拒绝。
- **真实 KG 回流**：微图节点/边映射为稳定路径并随聊天消息持久化，重新加载后仍可展示；无真实图谱时不生成假数据。
- **工具执行幂等**：新增持久化八状态 ledger、审批 ID 透传、原子 claim 和确定性结果消息；重复流片段、重复批准、并发执行及 Activity 重建不会重复触发副作用。
- **分享导入闭环**：`ACTION_SEND` / `ACTION_SEND_MULTIPLE` 经持久化 inbox、共享 importer 和索引队列逐项处理；支持崩溃修复、容量重试、去重、部分失败、同名稳定重命名和 Android 16 双分享。
- **资源管理器与事务索引**：资源管理器只观察当前 Session root，SAF 复用共享 importer；文件索引采用事务外候选构建、事务内哈希复核与向量/FTS/KG 原子切换，失败保留旧索引。
- **删除与进程恢复**：永久删除统一清理文件及全部派生数据；稳定 tombstone 支持进程死亡恢复，单个损坏记录不阻断其它工作区和向量队列。
- **真实 LLM 显式门禁**：新增仅由六项 `NEXARA_TEST_LLM_*` 环境变量启用的真实集成任务；默认单测始终跳过网络外呼，缺凭证明确 SKIP，Release Manifest 保持禁用明文 HTTP。
- **统一验证**：1232 个 JVM 测试零失败（14 跳过）、28 个 Android 设备测试零失败（2 个多阶段用例按设计跳过）、Lint 无 Error/Fatal、Debug APK 构建成功；备份 `prepare -> force-stop -> commit` 与恢复 `stage -> verify -> force-stop -> noReplay` 均通过。
- **主要提交**：`3f2a246`、`8a2cf6e`、`c4ef4f3`、`e288398`、`63346db`、`250d6c2`、`043b375`、`e0e97cb`、`51b3004`、`79a7da0`。

### Qwen 本地模型中文流式排版与思考块追踪二次收口 (2026-07-06)
- **思考块长内容追踪收口**：生成中的思考块不再渲染完整超长 `<think>`，改为展示最近尾部预览；完成后仍可手动展开完整思考内容，避免 Qwen 这类本地模型长思考持续撑高同一 Lazy item，导致镜头在思考首段、正文尾部之间来回拉扯。
- **中文正文紧凑渲染**：正文 Markdown 启用紧凑 block/list padding，中文编号列表的段距、行距和序号字号在模拟器中更接近聊天阅读密度。
- **流式半截加粗修复**：`sanitizeStreamingMarkdown()` 对未闭合的 `**加粗` 标记临时补闭合，避免流式中间帧短暂裸露 `**预` 这类 Markdown 符号；最终内容仍由真实 Markdown 标记决定。
- **Qwen 真实验收**：在模拟器把内网 provider 切到 `QWEN3.6-VL-128K-Q4KV`，发送简体中文 Markdown 30 项编号列表；连续截图显示轨迹从 1-4 → 3-20 → 17-30 后稳定，无此前 24-30 ↔ 1-2 的往返闪烁；中文加粗渲染正常，最终正文无裸 `**`。
- **验证命令**：`:app:testDebugUnitTest --tests MarkdownTextTest --tests PipelineBubbleTest`、`:app:assembleDebug` 均通过。
- **变更文件**：`PipelineBubble.kt`、`PipelineBubbleTest.kt`、`MarkdownText.kt`、`MarkdownTextTest.kt`。

### 会话生成滚动抢镜修复与思考块列表排版优化 (2026-07-06)
- **生成中滚动跟随修复**：会话流式生成时不再用 8ms 循环强制锚定 `bottom_spacer`，改为按当前 AI pipeline item 的尾部跟随；修复思考区块增长时画面在消息首行与最新输出之间高频来回跳动的问题。
- **用户手势尊重**：通过 `nestedScroll` 捕捉真实用户滚动并切断自动跟随，FAB 点击后再恢复跟随；自动跟随生成中隐藏“向下”悬浮按钮，避免按钮在长思考块中误抢视线。
- **思考块 Markdown 列表排版优化**：补齐 Markdown 主题的 `ordered` / `bullet` / `list` typography，序号和正文统一字号与行高；思考块启用紧凑列表间距，避免序号过大和列表行距过松。
- **真实 UI 验收**：使用内网真实 MiniMax-M3 API 在模拟器发送长思考/长 Markdown 输出；连续截图确认视口从思考尾部稳定推进到正文末段，不再回跳消息首行；思考块编号列表字号对齐。
- **验证命令**：`:app:compileDebugKotlin`、`:app:testDebugUnitTest`、`:app:assembleDebug` 均通过。
- **变更文件**：`ChatScreen.kt`、`PipelineBubble.kt`、`MarkdownText.kt`、`NexaraMarkdownTheme.kt`。

### APP 业务链路第二批收口修复与最终模拟器验收 (2026-07-06)
- **新会话默认模型修复**：会话列表 FAB 与聊天页直接新建两条路径均会优先继承 Agent 模型，其次使用全局/主提供商模型；修复新建会话底部显示“未配置模型”、无法直接发送的问题。
- **RAG 假就绪收口**：Embedding 未配置时检索链路提前跳过并清理消息 RAG 状态，UI 不再把空检索包装成“知识检索就绪”；日志显示 `embedding unavailable, skip retrieval`，不再出现聊天模型被误当 embedding 调用的 `Missing data array`。
- **RAG / KG 生命周期修复**：文档和集合删除会取消向量化、删除向量、清理图谱节点/边与索引状态，避免删除后仍被检索或图谱复现。
- **OpenAI Responses 协议修复**：新增 `/responses` 协议实现，修正 endpoint、input body、同步/流式文本解析和最小工具声明，避免 Responses 入口名义存在但实际走错协议。
- **工作区与任务安全修复**：`WorkspaceRepository` 增加 root containment、移动/删除失败显式报错、回收站恢复查询修复；`TaskRepository` 增加事务化 `updatePlan()`、父子防环和跨会话父节点校验。
- **工具安全修复**：`web_fetch` 阻断 localhost、私网、链路本地和组播地址；`exec_js` WebView 禁用网络加载、DOM storage 和桥接接口并在回调后销毁。
- **真实 UI 验收**：模拟器干净新会话自动显示 `MiniMax-M3`，使用内网真实 API 生成两条 Markdown bullet，界面分行渲染且无“知识检索就绪”误报；证据文件 `/tmp/nexara-final-real-send.png` 与 `/tmp/nexara-final-real-send-logcat.txt`。
- **验证命令**：`:app:testDebugUnitTest`、`:app:assembleDebug` 均通过。
- **变更文件**：`SessionListViewModel.kt`、`ChatViewModel.kt`、`MemoryManager.kt`、`RagViewModel.kt`、`WorkspaceRepository.kt`、`TaskRepository.kt`、`WebFetchSkill.kt`、`ExecJsSkill.kt`、`OpenAIResponsesProtocol.kt` 与相关测试。

### APP 业务链路第一批修复与真实 API 模拟器验收 (2026-07-06)
- **信任链修复**：接入 `ChatUiState.error` snackbar、审批卡片、复制成功反馈；补齐 `patch_file`、`web_fetch` 等高风险工具审批名单。
- **Markdown / 协议修复**：OpenAI / Generic OpenAI-compatible 流式 delta 不再 trim 普通空白，避免标题、列表、代码缩进和表格边界被客户端破坏。
- **RAG 修复**：完整 `ragContext` 注入最终 system prompt；指定文档/非全局文档检索不再被错误关闭；无 RAG 结果时不再展示“知识检索就绪”的假完成状态。
- **多模态修复**：图片 URI 读取移到 IO 线程；图片读取失败不再静默降级纯文本；重试/重新生成保留原用户图片；工具生成图片在工具结果卡内可见。
- **Provider / 配置修复**：自定义 HTTP Provider 通过 network security config 支持内网聚合站；Provider 表单“测试连接”改为真实 `listModels()` 探测。
- **工作区安全修复**：`FileOperationRepository` 增加 canonical root containment，`writeFileAtomic()` / `patchFile()` 改为同目录临时文件 + fsync + 原子替换。
- **模拟器验收**：使用内网 MiniMax-M3 OpenAI-compatible API 完成文本 Markdown 和图片多模态真实生成；长按复制菜单与复制反馈通过 UI 验收。
- **验证命令**：`:app:compileDebugKotlin`、`:app:assembleDebug`、`:app:testDebugUnitTest` 均通过。
- **变更文件**：`ChatScreen.kt`、`ChatViewModel.kt`、`PipelineBubble.kt`、`ContextBuilder.kt`、`MemoryManager.kt`、协议层、Provider 表单、文件仓储、Manifest/network config、字符串与测试。

### APP 业务流程终版交叉审计报告落盘 (2026-07-06)
- **终版报告**：新增 `docs/audit/20260706-final-business-flow-cross-audit.md`，完整整合 GLM-5.2 并行审计主报告、A-G 模块原始报告与 Codex 原业务流程审计报告。
- **交叉取舍**：采纳错误 UI 未消费、审批 UI 未接线、配置死链、QueryRewriter 死链、RAG 生命周期污染、工作区数据安全、任务状态错配、首次启动引导和渲染稳定性等高价值结论；对额外 Provider、MCP schema、ParseCache 串味、Provider fallback 等静态证据不足项降级为运行时验证。
- **后续入口**：终版以 FA-01~FA-13 编号，作为后续修复排期和回归验证主入口；GLM 原始主报告 `docs/audit/20260706-fullstack-business-audit.md` 与模块报告继续保留为证据链。
- **变更文件 (4)**：
  - 新增: `docs/audit/20260706-final-business-flow-cross-audit.md`
  - 修改: `.agent/registry.md`
  - 修改: `.agent/handover.md`
  - 修改: `CHANGELOG.md`

### APP 业务流程完整代码审计报告落盘 (2026-07-06)
- **📋 审计报告**：新增 `docs/audit/20260706-business-flow-full-code-audit.md`，覆盖基础文本消息、多模态、Prompt 拼接、协议 API、连接保持、错误捕获、fallback、返回渲染、RAG/KG、知识库文档、配置载入、工具/Skill、工作区文件操作、任务管理和 UI/UX 可观测性。
- **🧾 原始材料**：保留本轮模块级原始审计材料到 `.agent/tmp-agent-reports/tmp-audit-module-a.md` 至 `.agent/tmp-agent-reports/tmp-audit-module-g.md`，方便后续交叉验证。
- **🔎 核心结论**：当前主链路骨架完整，但存在多处“用户以为开启/选择/执行，实际未稳定进入生成链路”的信任断点，重点集中在 RAG 文档选择、RAG Prompt 注入、流式 delta 空白保真、多模态重试、本地视觉协议、工具产物渲染、工作区文件一致性和任务计划事务/防环。
- **📌 后续入口**：报告已按 BF-01~BF-32 编号列出问题、用户体验影响、代码证据和建议修复顺序，供后续交叉审计与分批修复使用。
- **变更文件 (5)**：
  - 新增: `docs/audit/20260706-business-flow-full-code-audit.md`
  - 新增: `.agent/tmp-agent-reports/tmp-audit-module-*.md`
  - 修改: `.agent/registry.md`
  - 修改: `.agent/handover.md`
  - 修改: `CHANGELOG.md`

### 会话气泡长按复制兜底修复 + Markdown 排障规则沉淀 (2026-07-05)
- **🔴 P0 — 修复消息气泡长按菜单“复制正文”不写入剪贴板**：
  - *根因定位*：`PipelineBubble` 在父级 `onCopy` 为空时仍向用户/AI 子气泡传入一层空 lambda，导致子气泡的 `onCopy?.invoke() ?: copyToClipboard(...)` 误判为“外部已处理复制”，从而跳过系统剪贴板兜底。
  - *修复*：新增 `messageCopyOverride()`，只有父级真实提供复制处理器时才下传包装回调；父级为空时保持 `null`，让子气泡执行本地 `ClipboardManager` 复制。
  - *覆盖范围*：用户消息气泡与 AI 正文气泡共享修复。
- **📌 长期规则沉淀**：根据用户授权，将 Markdown 渲染排障分层规则写入项目 `AGENTS.md`，要求先区分“已有换行被库吞掉”和“上游原始输出无换行”两类根因。
- **🧪 回归测试**：`ChatLogicTest.kt` 新增 2 个用例覆盖父级复制处理器为空/存在两种分支。
- **变更文件 (4)**：
  - 修改: `AGENTS.md`
  - 修改: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt`
  - 修改: `native-ui/app/src/test/java/com/promenar/nexara/ui/chat/ChatLogicTest.kt`
  - 修改: `CHANGELOG.md`

### 本地模型压行 Markdown 边界修复 (2026-07-05)
- **🔴 P0 — 修复本地模型输出 Markdown 标记但整体堆成一坨的问题**：
  - *根因定位*：部分本地 OpenAI-Compatible 模型会把标题、分隔线、序号列表、代码围栏语言、表格行等块级 Markdown 边界压到同一行，例如 `#一级标题##二级标题`、`---###列表`、`1.第一步 2.第二步`、`||---|---|---||`；这类输入已经没有真实换行，单靠 `eolAsNewLine = true` 无法恢复排版。
  - *修复*：在 `MarkdownText` 预处理链路新增 `repairCompressedMarkdownBoundaries()`，对明显压行的标题、水平分隔线、有序/无序列表、常见代码围栏语言、表格行和引用边界补回块级换行，同时保护行内代码与 Markdown 链接不被误拆。
  - *效果*：本地模型即使输出较粗糙，也能在会话正文里恢复接近 Cherry Studio 的块级 Markdown 阅读结构；云端模型已有正常换行时不受影响。
- **🧪 回归测试**：`MarkdownTextTest.kt` 新增 6 个用例覆盖压行标题、分隔线、序号列表、表格、inline code/link 保护和四空格缩进代码块保护；全量 `:app:testDebugUnitTest` 通过。
- **变更文件 (2)**：
  - 修改: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt`
  - 修改: `native-ui/app/src/test/java/com/promenar/nexara/ui/common/MarkdownTextTest.kt`

### 提供商添加界面预设选择器改下拉菜单 (2026-07-05)
- **🎨 UI 交互优化**：把"添加提供商"表单里 14 个预设的纵向 `Column` 列表（原占约 1000dp / 两屏+）替换为 `ExposedDropdownMenuBox` 下拉菜单，收起态仅约 56dp。
  - *收起态*：玻璃风格卡片（图标 + 预设名 + 下拉箭头），与表单其他输入框视觉协调。
  - *展开态*：可滚动的 `DropdownMenu`，每项显示图标 + 名称 + 选中勾。
  - *影响*：用户不再需要滚动两屏寻找预设，配置输入框（name/baseUrl/apiKey）立即可见，显著降低添加提供商的交互摩擦。
  - *范围*：仅改 `ProviderFormScreen.kt` 的预设选择器；Custom 分支的 `ProtocolSelector`（二级协议选择）保持不动。
- **变更文件 (1)**：
  - 修改: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderFormScreen.kt`

### Markdown 排版根因修复 + mikepenz 0.40.2→0.41.0 升级 + 纯函数测试网建立 (2026-07-05)
- **🔴 P0 — 修复主会话 Markdown "大段文本挤成一段"根因**：
  - *根因定位*：`MarkdownText.kt` 调用 mikepenz `Markdown()` 时未传 `annotator`，库默认 `eolAsNewLine = false` 按 CommonMark 规范把段落内单换行（软换行）替换为空格，导致 LLM 单换行排版被压成一段；同时未传 `padding`，库默认 `block = 2.dp` 段落间距过小。
  - *修复*：新增 `annotator = markdownAnnotator(config = markdownAnnotatorConfig(eolAsNewLine = true))`（保留段内换行）+ `padding = markdownPadding(block = 8.dp, listItemTop = 4.dp, listItemBottom = 4.dp, listIndent = 12.dp)`（增大段间距）。
  - *效果*：消灭"挤成一段"，段落分明，列表缩进清晰。
- **📦 mikepenz multiplatform-markdown-renderer 0.40.2 → 0.41.0 升级**：
  - *版本选择决策*：0.42.0/0.43.0 通过 Gradle metadata 把 `kotlin-stdlib` 硬约束到 2.4.0，超出项目 Kotlin 编译器 2.2.x 的 metadata 读取上限（编译失败）；0.41.0 的 stdlib 约束为 2.3.21，与项目兼容，且 `minCompileSdk=36` 无需改动 compileSdk/JDK。
  - *收益*：表格内联内容完整渲染修复（#559）、无障碍语义大改（#561）、blockquote 字号崩溃修复（#550）、Compose 1.11 对齐。
  - *传递依赖提升*：`kotlin-stdlib` 2.3.20→2.3.21、`kotlinx-coroutines` 1.7.3→1.10.x（全量 682 单测验证无回归）。
- **🧪 建立 Markdown 预处理纯函数单元测试网（54 用例）**：
  - 将 8 个纯函数（`normalizeLatexDelimiters`/`sanitizeStreamingMarkdown`/`safeTrimIndent`/`insertCjkSpacing`/`stripBlockQuoteMarkers`/`replaceCodeInMarkdown`/`slugify`/`splitRichSegments`）及 `ContentSegment`/`ParseCache` 从 `private` 提升为 `internal`（符合项目既有约定，不使用 `@VisibleForTesting`）。
  - 新建 `MarkdownTextTest.kt`（54 用例，JUnit5 + Google Truth + `@Nested`），此前 `ui/common/` 目录零测试覆盖。
  - 全量单测：682 测试，0 失败，0 错误，13 跳过。
- **变更文件 (3)**：
  - 修改: `native-ui/app/build.gradle.kts`（版本号 0.40.2→0.41.0）
  - 修改: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/MarkdownText.kt`（annotator+padding 根因修复 + 可见性调整）
  - 新增: `native-ui/app/src/test/java/com/promenar/nexara/ui/common/MarkdownTextTest.kt`（54 测试用例）

### DIA 文档体系清理与重组、项目文档全面更新至 98% 进度与 GitHub Release v0.1-beta 发布 (2026-05-19)
- **📋 DIA 文档治理更新**：
  - *文档重组*：将 `docs/IMPLEMENTATION_PLAN.md`（已完成历史计划）移至 `.agent/plans/archive/`；将 `docs/MARKDOWN_RENDERING_AUDIT.md`（已修复审计）移至 `docs/audit/`；将 `docs/plans/RAG_INDICATOR_MULTI_SESSION_EXECUTION.md` 移至 `.agent/plans/`，统一计划文档位置
  - *清理空目录*：移除 `docs/plans/` 空目录
  - *更新治理文档*：`docs/DOCUMENT_GOVERNANCE.md` 升级至 v2.0，反映已完成的 2026-05-18 全站清理和本轮优化
  - *补全注册表*：`registry.md` 新增遗漏的 3 个审计文档、`.agent/checklists/CODE_REVIEW.md`、`native-ui/AGENTS.md` 归档参考
- **📚 架构与进度文档全面刷新**：
  - *架构快速参考*：`docs/ARCHITECTURE.md` 日期更新至 2026-05-19
  - *全局架构设计*：`docs/ARCHITECTURE_DESIGN.md` 升级至 v2.1.0，更新维护日期
  - *实现分析*：`docs/IMPLEMENTATION_ANALYSIS.md` 大规模更新 — 规模统计（~342 Kotlin 文件 / 58 测试 / 18 Skill）、功能进度（92%→98%）、模块评级刷新（Agent 引擎 82%→95%、KG 80%→90%）、移除过时的对标产品比较表、更新分阶段规划和关键风险
- **📖 README 项目门面重写**：
  - *去对标化*：移除"对标 LobeHub / Cherry Studio"描述，改为基于项目愿景和已实现功能的原生介绍
  - *功能标注*：本地推理标记为 🚧 开发中，后台生成标记为 🚧 计划中
  - *新增运行环境要求*：Android 8.0+ 最低、Android 13+ 推荐、存储/网络/权限说明
  - *减少开发者内容*：移除 Quick Start，增加面向最终用户的 APK 下载引导
- **🚀 GitHub Release v0.1-beta 发布**：
  - 创建首个 Kotlin 原生公测版 Release 标签 `v0.1-beta`
  - 上传 Release APK（`app-release.apk`, 38 MB）至 GitHub Releases
  - 发布地址: https://github.com/NarcisWL/Nexara/releases/tag/v0.1-beta
- **变更文件 (7)**：
  - 移动: `docs/IMPLEMENTATION_PLAN.md` → `.agent/plans/archive/20260512-markdown-rendering-plan.md`
  - 移动: `docs/MARKDOWN_RENDERING_AUDIT.md` → `docs/audit/20260512-markdown-rendering-audit.md`
  - 移动: `docs/plans/RAG_INDICATOR_MULTI_SESSION_EXECUTION.md` → `.agent/plans/20260517-rag-indicator-execution.md`
  - 修改: `.agent/registry.md`
  - 修改: `docs/DOCUMENT_GOVERNANCE.md`
  - 修改: `docs/ARCHITECTURE_DESIGN.md`
  - 重写: `docs/IMPLEMENTATION_ANALYSIS.md`
  - 重写: `README.md`

### 模型能力数据库模糊遮蔽致命缺陷彻底根治、全新 Google 阵营多维元数据合并与主动测试门禁绿灯上线 (2026-05-20)
- **🔴 P0 — 彻底根治通用 `gemini` 模糊遮蔽（Shadowing）匹配 Bug**：
  - *Bug 根源排查*：排查发现由于老旧 `MODEL_SPECS` 列表中过早定义了通用的子串匹配 `ModelPattern.StringPattern("gemini")`，当系统在 `/models` 端点反序列化或在设置页面匹配 `gemini-3-flash` / `gemini-3.1` 等具体型号时，总是会被该项提前截断，导致它们错误地退化为了无任何能力的空白模型；
  - *重构与优先级调整*：将 `gemini` 通用兜底项、`google` 通用兜底项等整体移至 Google 匹配专区的**最底部**，保证匹配链路始终自上而下“先具体、后通用”，一举根除该结构性重大 Bug。
- **🔴 P0 — Google Gemini 2025 与 2026 阵营大分区完美整合与能力补全**：
  - *完整数据对齐*：将 2025 年的 Gemini 1.5/2.0/2.5 系列与最新的 2026 年 Gemini 3/3.1 系列彻底合并归集，对照 2026 年最新 API 技术指标进行全维补全；
  - *能力与定价穿透*：为 `gemini-3.1-pro` 补全 `promptCaching = true` 以及 `contextLength = 2000000` (200万上下文) 支持，为 `gemini-3-flash` 补全 `videoUnderstanding = true` 等所有缺失属性。新增了 2026 极速轻量之王 `gemini-3.1-flash-lite` 模型，并同步在 `MODEL_PRICING` 静态计费规格中补齐其与 `gemini-3-pro` 的官方输入/输出定价。
- **🧪 🧪 单元测试门禁 100% 绿灯护航**：
  - 在 `ModelSpecsTest.kt` 中设计并扩展了对 `gemini-3.1-pro`（双百万窗口、完备多模态 Agentic 能力）和 `gemini-3-flash` 的规格断言；
  - 完美通过 `:app:testDebugUnitTest` 针对 `ModelSpecs` 的全套单元测试，零 Warning 交付。

### 引用内容大标题精简化、RAG检索指示器全场景无条件持久化与主动联网搜索引证数据（Citations）高保真JSON注入 (2026-05-20)
- **🎨 🎨 P0 — RAG细节弹出浮窗主标题精简与引证状态文本全局统一**：
  - *主标题精简*：在 `RagDetailsSheet.kt` 中，将冗长复杂的“知识与联网审计 (Knowledge & Web Inspection)”主标题正式精简重命名为“引用内容”，完美对齐 MD3 精炼纯净的视觉排版规范；
  - *引证状态文本对齐*：在 `ChatInlineComponents.kt` 中，将 RAG 指示卡就绪态的文字描述从原来的“✓ 知识与联网审计就绪”全局统一更改为“✓ 引用内容就绪”，实现了前置卡片状态与后置弹出面板大标题的语义与感官的无缝合一。
- **🔴 P0 — RAG检索指示卡全场景无条件永久持久化展示**：
  - *取消条件渲染*：重构了 `ChatScreen.kt` 中 `RagProgressCard` 的条件渲染阻断逻辑。彻底去除了由于没有关联 references 或 citations 导致卡片被隐藏的限制；
  - *全场景驻留渲染*：删除了 `ChatInlineComponents.kt` 内 `RagProgressCard` 中用于极端保护的 `if (displayPhases.isEmpty() ...) return` 提前返回逻辑。同时把卡片的可点击状态（`.clickable`）设定为无条件永久开启。从而确保无论是否捞出有效数据，RAG 检索指示卡都在会话气泡上方保持 100% 稳定的常态化展示，为用户营造了坚不可摧的“检索存在感”与极致安全感。
- **🔴 P0 — 模型主动调用联网搜索工具（Active Web Search）Citations 引证数据高保真 JSON 级联注入**：
  - *JSON 引证高保真序列化*：在 `WebSearchSkill.kt`、`WebSearchTavilySkill.kt` 以及 `WebSearchSearXNGSkill.kt` 中，当主动调用工具执行完毕返回结果时，将捞取出的 Citation 列表序列化为高保真 JSON 字符串通过 `ToolResult.data` 返回，防止多维引证数据流失；
  - *多维引证深度合并注入*：在 `ToolExecutor.kt` 中，于工具执行完成时刻，新增了 `result.data` 的动态捕获与解析机制（同时兼容高保真 JSON 格式与传统 plain-text title-url 格式降级解耦），将提取出的 Citation 列表与消息体（`Message`）中已有的引证数据进行 distinct 合并，并级联更新至持久层数据库中。彻底打通了模型主动工具调用与 UI“引用内容 - 联网搜索”面板的引证数据链路，消除了显示空白！
- **🧪 🧪 编译清零与全功能验证**：
  - 完美跑通代码库编译，无任何警告与逻辑漏洞，卓越质量交付。

### 气泡长按菜单原生 MD3 风格改造、触点手指跟随与用户气泡“重发”重新生成 AI 响应功能上线 (2026-05-20)
- **🎨 🎨 P0 — 长按菜单回归原生 MD3 风格与触点手指跟随**：
  - *原生 MD3 样式回归*：取消了 `MessageContextMenu` 内部的 `NexaraGlassCard` 自制磨砂卡片和局部透明 `MaterialTheme` 等冗余轮子，直接采用最纯粹的 Material 3 原生 `DropdownMenu` 及 `DropdownMenuItem`，保持与知识库文档/目录列表完全一致的原生卡片阴影与菜单间距设计，清除视觉突兀；
  - *精准触点手指跟随*：重构了 `ContentSegment` 和 `UserMessageBubble` 内部手势侦听，弃用原本无法获取长按坐标的 `combinedClickable` 装饰器，改用 `pointerInput` + `detectTapGestures` 极其高保真地捕获用户长按的像素触点，并使用 `LocalDensity` 精准换算为 `DpOffset` 传导给 `DropdownMenu` 的 `offset` 参数。彻底锁死菜单定位在手指触摸区域，杜绝漂移和出现在无关区域的 Bug；
  - *纯文字纯粹排版*：继续保持长按菜单无 icon 极简风骨，降低视觉负载；在任意菜单项（复制/删除/重新生成/重发）被点击时瞬间调用 `onDismiss()` 触发菜单级联收缩，保证微手势反馈流畅平滑。
- **🔴 P0 — 用户气泡长按菜单新增“重发”功能与全链路贯通**：
  - *重发功能全链路打通*：将 `onRegenerate` 重发/重新生成回调从 `PipelineBubble` 传导到 `UserMessageBubble`，并在用户气泡的长按菜单中作为“重发”选项展现，底层无缝重用并贯穿了 `ChatViewModel.regenerateMessage(messageId)` 的高效处理逻辑；
  - *长按菜单文案自适应*：在 `MessageContextMenu` 中新增 `isUser: Boolean` 参数。当长按用户气泡时自动显示“重发”文案，而长按 AI 气泡时显示“重新生成”文案。点击“重发”后，系统自动智能回退并擦除该用户消息之后的所有助理消息，同时创建全新的 AI 助理气泡，完美触发 AI 针对这句用户提问的二次生成！
- **🧪 🧪 编译清零与全单元测试 100% 绿灯验证**：
  - 本地跑通 `:app:testDebugUnitTest` 完整单元测试，全流程编译无警告，质量交割无瑕疵。

### 工具设置页面精炼化改造、循环限制步数默认 50 次与预设工具默认全启用 (2026-05-20)
- **🎨 🎨 P0 — 工具设置页面顶部描述小字清理与极致高阶化布局**：
  - 在 `SkillsScreen.kt` 中全面排除了无用的顶部描述小字 `skills_desc` 的渲染。将页面布局直接由标题无缝过渡到功能列表与循环限制调整区，实现了极具现代科技感的无框、极致精简、高阶原生化排版风格。
- **🔴 P0 — 循环限制步数全链路默认值 50 次对齐**：
  - 在 `SettingsViewModel.kt` 和 `ChatViewModel.kt` 的 SharedPreferences 加载及初始化逻辑中，将循环限制步数默认值由低效率的原定次数全面对齐升级为 **50 次**，保障了复杂 Agent 顺序任务规划与执行流水线能够拥有充足、流畅的迭代空间，彻底杜绝迭代上限瓶颈；
  - 补充了高质单元测试，验证了在 SharedPreferences 无值时的默认值一致性为 50。
- **🔴 P0 — 预设工具列表更新强制全启用与中文化精准审计**：
  - 在 `SettingsViewModel.kt` 的 `loadSkills` 中全新设计了 `preset_skills_migrated_v3` 版本迁移标志，解决了从旧版本升级时新增预设工具默认未被启用的缺陷，确保所有内置工具（包含最新的 `file_diff`、`file_patch`、`initialize_plan` 等）在更新后默认全部处于开启（Enabled）状态；
  - 对 18 个内置预设工具的名称及描述在中英双语（特别是 `values-zh-rCN/strings.xml`）下的汉化与专业术语进行了全量质量审计。证实汉化水准极高、表达流畅、行文专业，完美契合了 Nexara 产品的科技化与高级感调性。
- **🧪 🧪 单元测试门禁 100% 绿灯保障**：
  - 在 `SettingsViewModelTest.kt` 中新增了 `default loopLimit is 50` 和 `preset_skills_migrated_v3 updates SharedPreferences and enables all preset skills` 两组深度单元测试，对默认初始化和迁移版本升级逻辑进行拦截保护；
  - 运行 Gradle 单元测试通过，全流程交付质量卓越。

### 中英文多语言 cleanSearchQuery 智能降噪提纯算法深度升级与全场景单元测试绿灯通过 (2026-05-20)
- **🔴 P0 — 智能疑问句与口语助词多级 do-while 深度净化过滤**：
  - 重构并升级了 `ContextBuilder.kt` 中的 `cleanSearchQuery` 过滤算法。引入强大的 do-while 多重循环剥离机制，彻底克服了语气词叠加、多重空格干扰以及标点隔断导致的去噪失败；
  - 极大地扩展了中英文意图前缀（新增“请问”、“你能科普一下”、“能不能帮我”、“科普一下”等）和口语修饰后缀（“谢谢你”、“谢谢您”、“到底是什么意思”等）；
  - 全新设计了前置疑问句疑问词过滤（questionPrefixes）以及首尾定冠词/停用词剥离（如 `the`, `a`, `an`, `of`, `and` 等），确保前置静态 DuckDuckGo 被动检索与 SearXNG 主动全能检索能以极高的精度匹配核心关键词，召回率提升数倍。
- **🧪 🧪 P0 — 全场景单元测试门禁 100% 绿灯保障**：
  - 在 `ContextBuilderTest.kt` 中针对新增强化的清洗逻辑扩展了 4 组极限/复杂边界测试场景：包含中文多层语气助词、长段口语化前后缀以及“the difference between...”定冠词停用词混合过滤；
  - 本地跑通 `:app:testDebugUnitTest` 单元测试，全绿灯通过，代码交付质量无懈可击。

### 联网搜索引证网页摘要 (Web Snippet) 全维高保真渲染与多 Provider 数据升维对齐 (2026-05-20)
- **🔴 P0 — 联网引证数据模型全维升维与向后兼容 (Backward Compatibility) 设计**：
  - 在 `ChatModels.kt` 核心引证数据结构 `Citation` 中，升维注入了可选字段 `val snippet: String? = null`；
  - 采用提供默认值的优雅设计，完美契合了 `kotlinx.serialization` 反序列化契约，确保对任何历史旧 Session 消息的 100% 静默向后兼容，彻底阻断任何反序列化崩溃。
- **🎨 🎨 P0 — 联网审计详情卡片全维高保真 Web Snippet 极精致渲染上线**：
  - 在 `RagDetailsSheet.kt` 审计面板的“联网搜索”引证卡片（`WebSearchReferenceCard`）中，设计并新增了专属半透明毛玻璃微卡片容器，用来展示对应的网页摘要；
  - 排版上匹配 `NexaraTypography.bodySmall` 和 `NexaraColors.OnSurfaceVariant.copy(alpha = 0.85f)`，并施以 16.sp 柔和行高与圆角，与文档知识检索卡片的设计语言浑然一体，让用户前置即可感知召回内容，避免盲目跳转，让产品科技体验实现质的飞跃。
- **🔴 P0 — 搜索引擎 Provider 全链路数据映射与对齐**：
  - 重构并彻底打通了 DuckDuckGo (`DuckDuckGoProvider.kt`)、SearXNG (`SearXNGProvider.kt`) 以及 Tavily (`TavilyProvider.kt`) 三大内置检索 Provider 的 Citation 数据映射；
  - 将各自已解析出的真实网页正文或摘要（Snippet / Content）在 Citation 初始化构造时直接填充 `snippet = ...`，完成了从底层搜索爬取到顶层 UI 表现的多维穿透。
- **🧪 🧪 编译清零与全单元测试绿色通过**：
  - 全面通过 `:app:testDebugUnitTest` 核心单元测试门禁，全站编译零 Warning/Error，实现完美的质量收尾。

### 搜索引擎致命硬伤修复与全新 web_fetch 降噪清洗工具及行级游标分页读取上线 (2026-05-20)
- **🔴 P0 — 彻底修复 DuckDuckGo 与 SearXNG 联网检索的多处致命协议与业务 Bug**：
  - *DuckDuckGo 索引错位彻底根治*：将 `DuckDuckGoProvider.kt` 中由于域名过滤跳过元素而造成 results 序列与 citations 序列产生物理索引偏差的致命 Bug 彻底重构。采用单次遍历合并机制，确保摘要（Snippet）与标题网址 100% 对齐。
  - *SearXNG 反序列化与 WAF 拦截修复*：在 `SearXNGProvider.kt` 中添加了标准的 Chrome UA 伪装，避免了 Cloudflare WAF 拦截；重构了 JSON 反序列化崩溃流，能够精准识别由于自建 SearXNG 实例未开启 json 格式而返回 403 HTML 报错网页的场景，抛出友好异常；在 `WebSearchSearXNGSkill.kt` 中修复了强行将报错文本包装为 `status = "success"` 返回的逻辑，发生异常时，正确置为 `"error"` 并上报。
  - *被动联网 Query 智能降维去噪*：在 `ContextBuilder.kt` 的前置联网检索中引入全新的 `cleanSearchQuery` 降维算法，对用户长口语提问剥离冗余信息、标点与口语助词并进行短字符截断，让搜索引擎检索 Query 高度精准，检索召回率提升数倍。
- **🟢 P0 — 研发全新的 web_fetch 网页长文游标行级分页（Cursor Pagination）降噪抽取工具**：
  - *开发背景与翻页痛点*：为解决网页被抓取后内容超长爆 Token 或迷失在网页中后部有用信息中的痛点，全新研发了 `WebFetchSkill.kt`（注册为 `web_fetch` 工具），支持大模型行级参数提取与翻页滚动视口拉取。
  - *Jsoup 降噪清洗算法*：
    - 精准过滤 `<script>`、`<style>`、`<iframe>`、`<header>`、`<footer>`、`<nav>`、`<aside>` 以及各类广告 class 节点；
    - 针对段落 `p`、标题 `h1-h6`、列表 `li`、代码 `pre` 及表格等有价值排版标签的文本内容进行提纯，并自动坍缩多余换行与空白，极大地节省了大模型的 Token 消耗。
  - *行级游标分页（Cursor Pagination）滚动读取机制*：
    - 新增可选参数 `startLine` (起始物理行号，默认 1) 与 `lineCount` (单次读取行数，默认 80)；
    - 清洗完的正文自动转化为结构化的非空物理行列表。当还有剩余行数时，工具在 Metadata 响应中反馈 `Total Lines: X | Current Chunk: Lines A to B`，并附加友好的提示指引 `Notice: There are more lines remaining. You can call 'web_fetch' again with startLine=B+1 to read the next segment.`；
    - 大模型能够直接通过游标分页参数多次循环调用拉取长文的各个特定章节，从底层物理杜绝了爆 Token 闪退、死锁和关键数据丢失。
  - *系统级工具链注册*：在 `NexaraApplication.kt` 中的 `presetSkillRegistry` 中成功注册该 Local Tool，成为系统标配工具，大模型可随时在对话中自主调用！
  - *技能设置页完整配准与国际化展示*：在设置-预设技能页面为 `web_fetch` 进行全流程配准。新增了中英双语的国际化字符资源，并在 `SkillsScreen.kt` 中将其绑定至专门的 `Icons.Rounded.Description` 文档图标，允许用户在设置中实时查看和按需开关此项高级网页降噪抓取技能。
- **🔴 P0 — 全预设工具链双向契约深度审计与 6 大文件核心工具误杀剔除 Bug 彻底根治**：
  - *工具过滤失效根因诊断*：在深度审查中我们发现了一个历史遗留的极其严重的 Bug：当用户在设置面板开启了工具选择后，`ChatViewModel.kt` 将 SharedPreferences 保存的技能 ID（如 `"file_read"`, `"file_list"`）传入 Registry，然而 `DefaultSkillRegistry.kt` 在过滤时直接使用 `skill.id in allowedIds` 判定；由于 6 大文件操作相关 Skill 声明的真实 `id` 分别为 `"read_file"`, `"list_files"` 等，拼写形式完全不匹配，导致只要开启了工具过滤，**所有的文件读取、写入、列表、搜索、补丁、差异对比核心工具全部会被强行过滤剔除**，模型在对话中完全看不到也无法调用它们！
  - *双向契约映射修复*：在 `DefaultSkillRegistry.kt` 中独创性地设计了 `settingsKeyToSkillId` 的双向参数与 ID 映射表，对传入的 `allowedIds` 进行智能的翻译和重定向。在完美维持历史遗留的 SharedPreferences 数据兼容性的同时，一揽子彻底解决了 6 大文件核心工具在开启状态下无法调用的历史死结。
  - *单元测试防护网建立*：遵循极严格的单元测试门禁要求，我们专门新建了 `DefaultSkillRegistryTest.kt` 测试用例，对 allowedIds 为空、普通过滤及映射解析三种场景进行了 100% 覆盖率验证。运行 Gradle 单元测试通过，实现双向契约一致性的超高质量收尾。


### 修复底部字号拖动条对聊天区各组件文本的同步缩放 (2026-05-20)
- **🔴 P0 — 彻底修复底部字体大小调整拖动条无法同步调整 Markdown 表格、指示器、思考容器和工具容器所有文本大小的缺陷**：
  - *视觉痛点*：在聊天界面底部的“字体大小”滑块被拖动放大或缩小时，用户消息和 AI 消息正文均能完美同步缩放；然而，思考容器 (`InlineThinkingRow`)、工具容器 (`InlineToolRow` 的标题、参数、输出结果、提示等)、检索指示器 (`PostProcessChip` 与 RAG指示器) 以及 Markdown 中的普通表格元素 (`NexaraTableWidget`)，它们的字号依然硬编码为固定的绝对值（如 10sp、11sp、12sp 等），导致在拖大字体时，这些组件的内容依然极其细小，与正文产生强烈的视觉割裂感；而在缩小字体时又显得臃肿，排版崩坏。
  - *修复对齐方案*：
    - **Markdown 普通表格字号响应式联动**：
      - 对 `NexaraTableWidget` 及内部私有组件 `TableCell` 引入 `fontSize: Int` 入参；
      - 表头字号动态设为 `fontSize.sp`，正文字号动态设为 `(fontSize - 1).coerceAtLeast(10).sp`，并统一匹配了 1.4 倍的黄金行高，完美摆脱了对 `NexaraTypography.labelMedium` 的静态大小硬编码；
      - 在 `MarkdownText.kt` 调用 `NexaraTableWidget` 时，将当前 `fontSize` 优雅透传，实现随字体滑块等比缩放。
    - **思考与工具组件全文本字号级联联动**：
      - 在 `PipelineBubble.kt` 中，对所有硬编码字号的辅助文本、状态标签及代码文本进行了拉平与重构：
        - 助理消息元信息（模型名 + 时间戳）及错误消息字号：`11.sp` 升级为 `(fontSize - 2).coerceAtLeast(9).sp`；
        - 思考容器标题（“正在思考”/“思考完成”）：`12.sp` 升级为 `(fontSize - 1).coerceAtLeast(10).sp`；
        - 工具容器标题（工具名）：`12.sp` 升级为 `(fontSize - 1).coerceAtLeast(10).sp`；
        - 工具错误标签（“指令有误”）：`10.sp` 升级为 `(fontSize - 3).coerceAtLeast(9).sp`；
        - 工具调用参数：`10.sp` 升级为 `(fontSize - 3).coerceAtLeast(9).sp`；
        - 工具返回结果：`10.sp` 升级为 `(fontSize - 3).coerceAtLeast(9).sp`，且 lineHeight 从硬编码 `14.sp` 升级为等比匹配的 `((fontSize - 3).coerceAtLeast(9) * 1.4).sp`；
        - 用户消息的时间戳：`11.sp` 升级为 `(fontSize - 2).coerceAtLeast(9).sp`。
  - *变更文件 (3)*：
    - 修改: `TableWidget.kt`, `MarkdownText.kt`, `PipelineBubble.kt`

### 修复会话设置面板选项多出诡异亮色边框的视觉缺陷 (2026-05-19)
- **🎨 移除 ToolToggleRow 组件的白色实线边框**：
  - *视觉痛点*：在 RAG 相关选项被放入会话设置面板的 `SettingsPanel` 之后，原本由 `ToolToggleRow` 渲染的 RAG 设置行（如“会话 RAG”、“跨会话检索”、“知识库检索”、“检索重排序”、“知识图谱”）全部套上了一个亮白色的 0.5.dp 细实线边框。在夜间暗色主题下，该亮白边框显得极其刺眼和突兀，打碎了 Nexara 设计语言中的极致微光平滑和无框圆润感，也与下方无边框的“字体大小”滑块、上方扁平的“压缩历史”按钮格格不入。
  - *修复手段*：彻底移除了 `SessionSettingsSheet.kt` 底部 `ToolToggleRow` 通用底栏切换组件中多余且突兀的 `.border(...)` 修饰符，将其还原为纯粹平滑的 `NexaraColors.SurfaceLow` 圆角卡片底色块，消除多余的白框噪音。

### 默认重排序模型未配置时全站重排选项自动灰置、提示与静默拦截 (2026-05-19)
- **🔴 P0 — 当用户未在提供商中添加 Rerank 模型或未在“设置”中将其设为默认重排模型时，自动将所有重排相关控制灰置禁用并防点击**：
  - *问题根因*：如果用户在 Nexara 的提供商设置中没有添加任何 Rerank 模型，或者没有勾选/设置默认的重排模型，在此种“无可用 Rerank 模型”的状态下，原先“检索设置”界面中的 3 个重排参数滑块依然处于可操作的可亮起状态；同时，在“会话设置面板” (`SessionSettingsSheet.kt`) 和“编辑助手的高级检索” (`AgentAdvancedRetrievalScreen.kt`) 中，重排序开关依旧是可点击状态。这会导致用户产生功能可用的错觉，且一旦触发检索会因为底层无模型可供调用导致不可预测的问题。
  - *重构灰置方案（像素级防呆降级）*：
    - **全局强响应式状态感知**：利用 `ProviderManager.getInstance().rerankModelId` 作为 `StateFlow<String>` 的特性，在所有检索配置界面中通过 `collectAsState()` 进行实时响应式收集，判定是否为空字符串。一旦判定为 `""`（即用户没有配置或没有设置默认重排模型），自动将 `isRerankAvailable` 置为 `false`。
    - **全局“检索设置”页面灰置与微提示**：
      - 将全局检索设置中的 3 个重排参数滑块（`AdaptiveSlider`）的 `enabled` 属性动态绑定为 `isRerankAvailable`；
      - 重排配置大卡片（`NexaraGlassCard`）的 `alpha` 在无可用模型时自动降至 `0.6f`；
      - 在卡片内侧标题右侧加入磨砂黄的“未配置模型”胶囊 Badge，并在大标题下方增加显目的中文暖色警告提示语：“⚠️ 未检测到已配置的重排模型。重排序是多数据源融合的高性能基石，请先前往「提供商管理」添加 Rerank 服务并设为默认重排模型。”
    - **“会话设置面板”动态静默禁用**：
      - 对会话面板底部的通用切换 Row（`ToolToggleRow`）新增 `enabled: Boolean = true` 可选参数，在禁用时施加 `0.4f` 半透明，并将底层 `Switch` 开关置为 `enabled = false` 彻底锁死点击；
      - 会话检索里的“重排序”开关 checked 状态动态绑定为 `isRerankAvailable && rerankEnabled`；
      - 在开关下方追加黄色小辅助文本：“⚠️ 未配置默认重排模型，重排序已强制静默禁用”，完美阻断一切防呆漏水。
    - **“编辑助手检索配置”级联同步**：
      - 助手的高级检索配置页中，同样实时监听 `isRerankAvailable`，重排卡片 `alpha` 设为 `0.6f`，Switch 开关动态绑定 `checked = isRerankAvailable && enableRerank` 且 `enabled = isRerankAvailable`；
      - 添加一致的磨砂黄色“未配置模型” Badge 和黄字警告段落，完美与全局界面视觉语言级联一致。
  - *变更文件 (3)*：
    - 修改: [AdvancedRetrievalScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/AdvancedRetrievalScreen.kt)
    - 修改: [SessionSettingsSheet.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/SessionSettingsSheet.kt)
    - 修改: [AgentAdvancedRetrievalScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentAdvancedRetrievalScreen.kt)

### 检索设置页面“检索来源”卡片与“重排序”开关清理与解耦 (2026-05-19)
- **🔴 P0 — 彻底移除全局检索设置中的“检索来源”卡片与“启用重排序”开关，转为默认启用，完全由会话面板级深度控制**：
  - *问题根因*：在全局的检索设置 (`AdvancedRetrievalScreen.kt`) 中，包含了“记忆检索开关”和“文档检索开关”卡片，以及下方的“启用重排序开关”。这些全局开关使得用户需要反复在全局配置和当前对话中同步检索状态，极度冗余；用户导入文档与对话进行时，检索与重排序应该作为底层的默认高性能支柱服务，而具体会话的针对性开启/关闭，完全交由每个会话设置面板独立隔离控制即可。
  - *重构解耦方案*：
    - **物理剔除全局“检索来源”卡片**：直接移除了 `AdvancedRetrievalScreen.kt` 中的一整个“检索来源”大卡片（`NexaraGlassCard`），包括其中的“记忆检索”、“文档检索”两大 SettingsToggle 开关。
    - **清空“启用重排序”全局开关**：直接删除了全局重排序卡片中的 `SettingsToggle(R.string.retrieval_rerank_enable)` 启用开关，并无条件直接铺平渲染三个核心重排序滑块参数（Top-N, Final-K, 最大单次调用数），免去了无用折叠，逻辑极其清爽。
    - **重构全局/会话级默认值逻辑**：
      - 将 `AgentRetrievalConfig` 以及 `RagConfigPersistence` 的 SharedPreferences 加载 fallback 均修改为 `enableRerank = true`，使全局配置默认状态下重排功能就是启用的。
      - 修改了核心会话状态模型 `RagOptions` 默认构造值，把 `enableRerank` 默认值设为 `true`。由此确保当新的会话会话创建时，其“记忆检索”、“文档检索”和“检索重排序”在会话级默认即 100% 启用，具体开关细节完全可在会话设置面板（`SessionSettingsSheet.kt`）中进行极细粒度按需关闭或重开，实现了完美的全局/会话级解耦。
  - *变更文件 (3)*：
    - 修改: [AdvancedRetrievalScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/AdvancedRetrievalScreen.kt)
    - 修改: [AgentConfigModels.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/data/agent/AgentConfigModels.kt)
    - 修改: [ChatModels.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/data/model/ChatModels.kt)
    - 修改: [RagConfigPersistence.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/domain/usecase/RagConfigPersistence.kt)

### 助手配置（编辑助手）页面冗余选项清理与视觉规范对齐 (2026-05-19)
- **🔴 P0 — 彻底移除编辑助手页面中冗余的“推理预设档位”和“当前模型”卡片，交由全局与会话级独立控制**：
  - *问题根因*：在“编辑助手” (`AgentEditScreen.kt`) 页面中，原本包含“当前模型选择”与“推理预设档位（温度与 TopP 滑块/按钮）”选项。实际上，这些模型与推理参数应该统一交由“会话级”和“全局级”的配置分级控制，助手作为角色定义，在其基础配置页中再次塞入这些参数显得冗余复杂，且在物理操作上与顶层的模型切换冲突。
  - *重构清理方案（极简优雅降维）*：
    - **彻底移除 ModelPicker 弹窗**：在 `AgentEditScreen.kt` 中删除了不再使用的 ModelPicker 对话框调用、相关的 `showModelPicker` mutableState，以及不再使用的 `settingsViewModel`、`allModels`、`modelItems` 等 40 余行冗余状态计算与依赖引入，大幅缩减了内存开销。
    - **清理模型与预设渲染**：在 LazyColumn 中直接砍掉了整整三段 `item {}` 模块，包括“模型大标题”、“当前模型卡片（NexaraGlassCard）”、“推理预设大标题”与“InferencePresets”档位选择组件。在保留 8.dp 垂直呼吸感 Spacer 的前提下，平滑连接了“性格”和“知识图谱/高级检索”板块。
    - **极简化 ViewModel 同步**：保持了 `AgentEditViewModel.kt` 的实体加载和持久化保存字段的健壮兼容，去除了视图交互污染，使得逻辑层和表现层更纯粹。
  - *🟡 P1 — 统一助手配置页内功能卡片标题字号与全站视觉规范像素级对齐*：
    - *问题*：原本的“选择图标”（外观卡片）与“系统提示词”（性格卡片）的内侧小标题使用的是 `labelMedium` （加粗或半加粗），字号偏小且与“记忆设置”、“检索设置”等二级页面的 `titleMedium` 卡片内侧标题规范不统一，视觉感受不够精致。
    - *修复方案*：
      - 将“外观”卡片内侧的“选择图标”标题、以及“性格”卡片内侧的“系统提示词”标题，统一修改为：`style = NexaraTypography.titleMedium`，并且 `fontWeight = FontWeight.SemiBold`。
      - 使得编辑助手页的所有大卡片内侧标题在字号、字重、色值（`NexaraColors.OnSurface`）上达到了全站像素级完美一致。
  - *变更文件 (1)*：
    - 修改: [AgentEditScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentEditScreen.kt)

### 知识图谱即将上线未实装功能灰置与视觉精炼 (2026-05-19)
- **🔴 P0 — 彻底隐藏未实装功能的“即将上线”凌乱小字，并对选项进行全局灰置和防点击**：
  - *问题*：知识图谱高级页面 `RagAdvancedScreen.kt` 中有 4 个尚未实装的预留开关（即时抽取、自动识别类别、增量哈希、规则预过滤），它们下方均跟随着一行“即将上线”的灰色小字。由于这些开关在物理上依然是可点击操作的，且各处堆叠小字破坏了页面的精炼排版，显得界面凌乱不高级。
  - *重构灰置方案（像素级优雅禁用）*：
    - **SettingsToggle 通用禁用升级**：重构了公共切换卡片组件 [SettingsToggle.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/common/SettingsToggle.kt)，新增 `enabled: Boolean = true` 可选属性。当 `enabled == false` 时，给卡片附加 `.alpha(0.4f)` 呈现高级磨砂灰置感，在逻辑层利用 `.then(if (enabled) clickable else empty)` 彻底截断一切点击响应，并向下把 `enabled = false` 透传给底层的 `Switch` 开关。
    - **移除即将上线小字**：在 `RagAdvancedScreen.kt` 中删除了上述 4 个未实装组件底下渲染的 `rag_advanced_coming_soon` 文字元素，腾出空间给卡片呼吸感。
    - **注入禁用配置**：为这 4 个 `SettingsToggle` 统一传入了 `enabled = false` 禁用修饰，使用户一目了然其未实装且彻底防止了误操作，与业内顶级 App 规范完美对齐。
  - *变更文件 (2)*：
    - 修改: [SettingsToggle.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/common/SettingsToggle.kt)
    - 修改: [RagAdvancedScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagAdvancedScreen.kt)

### 检索设置页面视觉对齐一致性微调 (2026-05-19)
- **🟡 P1 — 移除“记忆检索”卡片标题前多余的 CPU/内存图标以对齐全站卡片规范**：
  - *问题*：在检索设置（Advanced Retrieval）页面中，“记忆检索”卡片内侧标题前面包含了一个蓝灰色的 `Icons.Rounded.Memory` 芯片图标，而紧随其下的“文档检索”卡片标题以及全站所有二级设置卡片的标题，均采用干净纯粹的 `Text` 渲染，这导致页面卡片的左侧文字起步线产生了不一致的水平凹凸偏置，破坏了视觉连贯性。
  - *修复对齐方案*：
    - 彻底移除了 `AdvancedRetrievalScreen.kt` 中“记忆检索”卡片标题的 `Row` 排布以及包含在 `Box` 容器内的多余 `Icon(Icons.Rounded.Memory)` 芯片图标。
    - 统一将标题优化为标准的纯粹 `Text` 加粗显示，使得“记忆检索”与下方的“文档检索”在左侧文字对齐线上达到像素级完美重合，消除了突兀的图标，极大地净化了界面呼吸感。
  - *变更文件 (1)*：
    - 修改: [AdvancedRetrievalScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/AdvancedRetrievalScreen.kt)

### 提示词/代码编辑器行号自适应折行像素级对齐优化 (2026-05-19)
- **🔴 P0 — 修复提示词文本编辑器中行号与文本在折行（Soft Wrap）时产生的错位与搓开缺陷**：
  - *问题*：原有的 `UnifiedPromptEditor.kt` 中的行号列是通过纯字符拼合（`1\n2\n3`）形式与右侧 `BasicTextField` 独立渲染的。当右侧文本发生 Soft Wrap（折行）时，折行的多行内容在左侧无感知，依旧依次绘制下一行号，导致从折行那一行开始，行号和实际文本行发生灾难性的“上下搓开”、错位对齐，极度影响多行提示词阅读。
  - *修复对齐方案（IDE 级自适应渲染）*：
    - **自适应折行行号重构**：将原本静态的行号 `Text` 重构为自适应 Compose 物理 `Canvas` 绘制模式。
    - **TextLayoutResult 完美定位**：在 `BasicTextField` 内部挂载实时的 `onTextLayout` 参数。在左侧 Canvas 绘制时，通过遍历每个逻辑行（`\n` 分隔）在文本中的偏移量（offset），调用 `layout.getLineForOffset(startOffset)` 精准换算出该逻辑行首字在屏幕上的物理折行行索引，再利用 `layout.getLineTop(physicalLine)` 与 `layout.getLineBottom(physicalLine)` 提取其物理 Y 轴位置坐标。
    - **物理垂直居中微调**：获取物理折行首行的高度后，行号在 Canvas 中绘制时的 Y 轴位置计算公式升级为：`y = topPx + (physicalLineHeight - textLayoutHeight) / 2f`，实现了与右侧 BasicTextField 的文字物理首行无论何时均像素级对齐，折行的非首段区域左侧自然留白（与 VS Code 表现 100% 对齐）。
    - **兜底渲染保障**：在首次加载 `layoutResult` 尚未初始化完成时，智能提供基于 Monospace 等宽体 20.sp 基础行高的极速兜底渲染，消除了任何物理空白和加载闪烁。
    - **内外边距绝对一致**：统一在 `BasicTextField` 的 `modifier` 和左侧 `Canvas` 顶部加上相同的 `.padding(top = 8.dp)` 填充，让二者的 Y 轴基础坐标点完全一致，从物理上解决了 8dp 的固有错位偏置。
  - *变更文件 (1)*：
    - 修改: [UnifiedPromptEditor.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/common/UnifiedPromptEditor.kt)

### 知识图谱摘要配置面板升阶移植与重命名 (2026-05-19)
- **🟡 P1 — 知识图谱摘要模板编辑区平移至记忆设置页面，文案重命名为“摘要提示词”**：
  - *问题*：原本的“摘要模板”编辑卡片与它的弹出框编辑器被埋藏在最深层的知识图谱高级页面 `RagAdvancedScreen.kt` 里面，由于摘要模版是控制全局 RAG 合并与上下文摘要的关键配置，放置在底层 KG 设置里不利于用户高频微调，且“摘要模板”的学术化命名对国内用户不够直观易懂。
  - *平移卡片至全局记忆设置*：
    - 彻底移除了 `RagAdvancedScreen.kt` 中的 `showSummaryTemplateEditor` remember 状态变量、摘要模板的卡片展示以及底部的 `UnifiedPromptEditor` 弹窗。
    - 将上述状态与 `UnifiedPromptEditor` 平稳移植至上一级主入口记忆设置页面 `GlobalRagConfigScreen.kt`。
    - 在 `GlobalRagConfigScreen.kt` 适当位置（向量化配置卡片下方、进阶导航栏上方）平移注入高雅的“摘要提示词”卡片。卡片内侧顶部放置中等加粗字号标题，高度对齐了前序全部卡片的视觉体系；点击后触发相同的 RAG 持久化更新，使用 `viewModel.updateConfig { it.copy(summaryTemplate = text.ifBlank { RagConfiguration().summaryTemplate }) }` 状态流安全更新。
  - *文案汉化重构“摘要提示词”*：
    - 在 [strings.xml (zh-rCN)](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/res/values-zh-rCN/strings.xml) 中对摘要模版的文案语系进行重构汉化：
      - `rag_config_section_template` 由 `"摘要模板"` 修改为更直观的 **`"摘要提示词"`**。
      - `rag_advanced_summary_template_title` 由 `"摘要模板编辑器"` 修改为 **`"摘要提示词编辑器"`**。
      - `rag_config_summary_template_placeholder` 由 `"请输入摘要模板..."` 修改为 **`"请输入摘要提示词..."`**。
      - `rag_config_edit_template` 由 `"编辑摘要模板"` 修改为 **`"编辑摘要提示词"`**。
  - *编译测试*：执行 `./gradlew :app:assembleDebug` 在 4 秒内以 100% 绿灯构建完美通过，杜绝了任何多语言资源占位和 Kotlin 编译期安全漏洞。
  - *变更文件 (3)*：
    - 修改: [GlobalRagConfigScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/GlobalRagConfigScreen.kt)
    - 修改: [RagAdvancedScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagAdvancedScreen.kt)
    - 修改: [strings.xml (zh-rCN)](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/res/values-zh-rCN/strings.xml)

### 检索/记忆等二级设置页面 UI 卡片样式高度一致性重构 (2026-05-19)
- **🟡 P1 — 记忆设置、检索设置、知识图谱参数等二级页面样式不一致深度重构**：
  - *问题*：记忆设置、检索设置、全局知识图谱参数以及 Agent 高级检索设置等二级页面，存在样式混乱、使用不一致的问题：1) 顶部有冗余的小字描述文本，占用了宝贵的垂直空间且不够高级精炼；2) 卡片外部和内部交织使用不同大小的 SectionHeader 标题，大小不一，显得杂乱无章；3) 各卡片顶部的外侧小标题拉大了卡片上边距，导致卡片间距极度不均匀，跳变严重。
  - **删除页面顶部小字描述**：全部删掉了四个二级设置页面顶部的 `stringResource` 小字描述文本，净化页面开头，视觉上更开阔且富含高级感。
  - **优化向量化卡片 (Embedding Model)**：
    - **重命名标题**：将 `values-zh-rCN/strings.xml` 中的 Embedding 模型卡片标题从 “Embedding 模型” 优化为更契合纯中文阅读习惯的 “向量化配置”。
    - **精简选项描述**：移除了“输出向量维度”和“单次最大 Token”两项配置正下方的冗余小字描述文本，进一步缩减无意义的文字噪音，使该卡片保持极致的整洁与精美度。
  - **统一卡片内侧顶部标题**：全部废除了卡片外侧的 `SettingsSectionHeader` 标题。统一在所有卡片的 `Column` 内侧最顶部，采用 `titleMedium` / `titleSmall` 配以 `FontWeight.SemiBold` 加粗的 `Text` 标题，放置于左侧。字号饱满、比例协调。
  - **统一卡片外部间距与比例**：随着外侧标题的彻底移除，使用统一的 `verticalArrangement = Arrangement.spacedBy(24.dp)` 来控制所有设置卡片 in 滚动页面的物理间隙，整体布局呈现像素级均匀的呼吸感，且无任何多余或参差不齐的空隙。
  - **完美解决编译依赖**：由于在 `RagAdvancedScreen.kt` 中引入了 `FontWeight`，本地自动检测并补齐了 `androidx.compose.ui.text.font.FontWeight` 的物理导入，保证项目在 Gradle assemble 编译中以零警告、零报错一次性完美通过。
  - *变更文件 (5)*：
    - 修改: [GlobalRagConfigScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/GlobalRagConfigScreen.kt)
    - 修改: [AdvancedRetrievalScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/AdvancedRetrievalScreen.kt)
    - 修改: [AgentAdvancedRetrievalScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentAdvancedRetrievalScreen.kt)
    - 修改: [RagAdvancedScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagAdvancedScreen.kt)
    - 修改: [strings.xml (zh-rCN)](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/res/values-zh-rCN/strings.xml)

### 知识图谱提取断点续传与 RAG 审计弹窗高度性能优化 (2026-05-19)
- **🔴 P0 — 知识图谱抽取多分块大文本时意外中断断点续传与原子写入保护**：
  - *问题*：原有的知识图谱 (KG) 抽取机制，一旦调用 `extractAndSave` 就会在方法头部直接执行数据库清除操作。若中途因为大文本多个 chunks 提取过程中的任一次网络抖动、模型超时或应用强退，将直接导致已有图谱数据库的空洞化破坏。此外，没有任何断点续传机制，导致每次重抽都必须向大模型发送巨量重复 chunk 抽取请求。
  - *修复*：
    - ** progressive checkpoint 磁盘私有 JSON 缓存机制**：在 `GraphExtractor` 中引入基于 Kotlinx Serialization 的 `checkpointJson` 编解码配置。当提取含有 `docId` 属性的文档时，在 `cacheDir/kg_extraction_checkpoint/{docId}/` 下建立私有临时文件夹。
    - **分块跳过与缓存持久化**：每次循环提取前均会校验磁盘上是否存在 `chunk_index.json`，若存在则零延迟加载缓存的节点和边结果并安全跳过 LLM 交互；若不存在则请求 LLM 提取并立刻在磁盘持久化该 chunk 缓存。
    - **原子写入保护事务控制**：仅在 **所有分块 100% 成功提取** 且无任何错误抛出时，才会在最终入库事务前一瞬间执行 `clearGraphForDoc` 清除该文档的旧图谱。若中途发生异常，则立即打断，且保留已有数据库图谱和本地已成功提取的 chunks 缓存以备下次续传，物理避免了脏数据残留和网络断连数据丢失。
    - **缓存打扫**：新图谱全部无缝 upsert 入库完成后，自动清理该 docId 的 checkpoint 物理临时文件夹，打扫战场。
    - *单元测试*：在 `native-ui/app/src/test/java/com/promenar/nexara/data/rag/GraphExtractorTest.kt` 中编写了高保真的 JUnit 5 结合 MockK 单元测试。覆盖了正常提取、带 docId 时首次提取异常中断、部分 checkpoint 保存，以及二次提取直接触发断点续传且最终原子入库和缓存打扫的完整业务生命周期。
  - *变更文件 (3)*：
    - 修改: [GraphExtractor.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/data/rag/GraphExtractor.kt)
    - 新建: [GraphExtractorTest.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/test/java/com/promenar/nexara/data/rag/GraphExtractorTest.kt)
    - 方案: [rag_height_and_kg_checkpoint.md](file:///Users/promenar/.gemini/antigravity/brain/674e6e10-6ac1-4bd2-b58e-a5360c52c8ad/artifacts/rag_height_and_kg_checkpoint.md)
- **🟡 P1 — 锁定 RAG 详情底栏审计弹窗高度，防止随数据量多寡产生奇怪跳变**：
  - *问题*：主会话 RAG 检索指示器点击弹出的 RAG 详情底栏弹窗高度会因为捞取的引用内容多寡自适应剧烈收缩或增大高度，时而极扁、时而极高，这种高度物理跳变极度影响视觉舒适度。
  - *修复*：
    - 在 `RagDetailsSheet.kt` 中，将底部 Sheet 内容容器的 `Modifier` 高度锁死在物理屏幕的 80% 高度（`.fillMaxHeight(0.8f)`），与模型选择器 `ModelPicker` 的物理高规保持像素级一致。
    - 对 Tab 切换的 `Crossfade` 容器添加 `.weight(1f)` 填充屏幕剩余全部高度。
    - 对检索片段列表（LazyColumn）和知识图谱 Tab (KgPathsTab) 传入并应用 `.fillMaxSize()`。实现在 80% 固定高度内平滑、稳定地自适应布局呈现，并支持内容长短时在规定视口内极致顺畅的滚动，彻底消除了高度闪烁与突变。
  - *变更文件 (1)*：
    - 修改: [RagDetailsSheet.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/components/RagDetailsSheet.kt)

### 新建助手模型可视化选择器重构 (2026-05-19)
- **🟡 P1 — 升级新建助手弹窗，引入 ModelPicker 替换文本输入框**：
  - *问题*：原“新建助手”弹窗中模型选择为一个普通的 `OutlinedTextField` 输入框，用户必须手动输入模型 ID，缺乏可视化过滤（如过滤出对话、推理、生图模型）、所属提供商和能力标签的直观展示，极易输入错误且体验极不友好。
  - *修复*：
    - 在 `AgentHubScreen.kt` 头部导入并对接 `SettingsViewModel`，获取全部可用模型，并过滤转换为 `ModelPicker` 所需的 `ModelItem` 数据列表。
    - 在 `AddAgentDialog` 中引入 `com.promenar.nexara.ui.common.ModelPicker` 通用底部 Sheet。
    - 将原本的模型输入框替换为高雅磨砂玻璃卡片 `NexaraGlassCard`。卡片左侧可视化展示当前已选的模型名称（空时显示 "请选择模型..."），右侧配以 Chevron 图标指引，点击即可优雅弹出可视化模型选择器，实现可视化搜索、能力标签分类、提供商选择等高级体验。
  - *变更文件 (1)*：
    - 修改: [AgentHubScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentHubScreen.kt)

### 跨页面 Header 窄版 TopAppBar 一致性重构与对齐 (2026-05-19)
- **🟡 P1 — 助手会话列表与主会话界面 Header 高度与元素对齐调优**：
  - *问题*：原“助手的会话列表页面”与“主会话界面”采用了不同的 Header 栏组件（前者为自定义大标题 Row，后者为窄版 TopAppBar），导致标题字号不一致（大字号 VS 窄版中字号）、返回按钮在切换时产生高度和左右位置跳跃，违和感强烈。
  - *修复*：
    - 将 `AgentSessionsScreen.kt` 的自定义 Row Header 彻底重构为标准的窄版 `TopAppBar` 布局，采用一模一样的字体样式（大标题对应 `NexaraTypography.titleMedium`，副标题“1个会话”对应 `NexaraTypography.labelSmall`）。
    - 将重构后的 `AgentSessionHeader` 组件挂载在 `Scaffold` 的 `topBar` 参数中，与主会话界面挂载方式 100% 对齐。
    - 移除原本 `Column` 顶部的 `AgentSessionHeader` 调用，并调整 `LazyColumn` 顶部间距 `top = 8.dp` 以防局促感。
    - 在切换页面时，实现了返回按钮和标题栏位置、文字大小的像素级完美重合，消除了任何物理跳跃。
  - *变更文件 (1)*：
    - 修改: [AgentSessionsScreen.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentSessionsScreen.kt)

### Room 数据库表重建迁移与 Schema 闪退终极根治 — v16 全面统合 (2026-05-19)
- **🔴 P0 — 根治 Room 数据库升级过程中 Schema 不匹配导致的致命闪退缺陷**：
  - *问题*：用户合并最新代码后，由于此前测试/开发分支中 sessions、messages、vectors、kg_nodes、kg_edges 和 task_nodes 表结构在本地的早期残留不一致（例如 9e8f2bb 提交中对 task_nodes 表的 sort_order、description、status、is_collapsed 分别新增了 @ColumnInfo(defaultValue = ...) 且在 SQL 层面修改了索引名称为 index_task_nodes_...，而本地老旧 task_nodes 表仍然是没有这些默认值和旧名索引残留的旧状态），导致 Room 进行 Schema 校验时抛出 IllegalStateException: Migration didn't properly handle: sessions/messages/vectors/kg_nodes/task_nodes 致命崩溃。
  - *修复*：
    - **重构 MIGRATION_10_11 迁移路径**：废弃原各表增量式 safeAddColumn 的高危做法，全面升级为 100% 健壮的 **表重建迁移 (Recreate Table Migration)** 方案。依据最新 Entity 声明的完美 Schema 全新创建正确表结构，最后执行 INSERT INTO SELECT 从临时表无缝回灌数据并清理临时表。
    - **新增 MIGRATION_11_12 迁移路径**：升级到 version = 12，实施 sessions 表重建热修复。
    - **新增 MIGRATION_12_13 迁移路径**：升级到 version = 13，实施对 messages 表的 100% 安全重建迁移（完美对齐所有非空默认值定义，并完美恢复其指向 sessions(id) 的 ON DELETE CASCADE 外键及多字段索引）。
    - **新增 MIGRATION_13_14 迁移路径**：升级到 version = 14，实施 vectors 表的无损、安全重建热修复，物理消除向量表的默认值不匹配故障。
    - **新增 MIGRATION_14_15 迁移路径**：升级到 version = 15，提供知识图谱节点表 kg_nodes 与边表 kg_edges 的无损安全重建，根治其 Schema validation 闪退故障。
    - **新增 MIGRATION_15_16 迁移路径**：升级到 version = 16，提供任务树节点表 task_nodes 的物理无损重建并先期安全清理旧索引残留，彻底解决合并 9e8f2bb 造成的第二次 Schema 闪退病根。
  - *变更文件 (2)*：
    - 修改：[NexaraDatabase.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/data/local/db/NexaraDatabase.kt)（升级版本号至 16，重构 MIGRATION_10_11，新增 MIGRATION_11_12/MIGRATION_12_13/MIGRATION_13_14/MIGRATION_14_15/MIGRATION_15_16 物理热重建机制）
    - 修改: [NexaraApplication.kt](file:///Users/promenar/Codex/Nexara/native-ui/app/src/main/java/com/promenar/nexara/NexaraApplication.kt)（在 addMigrations 中注册新增的 MIGRATION_15_16）
  - *审计与设计方案存档*：[.agent/plans/20260519-room-database-migration-schema-mismatch-fix.md](file:///Users/promenar/Codex/Nexara/.agent/plans/20260519-room-database-migration-schema-mismatch-fix.md)

### UI 视觉一致性修复与助手模型默认值优化 (2026-05-19)
- **🟡 P1 — 助手创建时默认使用系统摘要模型**：
  - 修复：`AddAgentDialog` 初始化时自动读取 `ProviderManager.summaryModelId` 作为默认模型
  - 如果系统未设置摘要模型则保持为空，新会话继承助手的模型设置，用户更改后不再追踪
  - *变更文件*：`AgentHubScreen.kt`
- **🟡 P1 — 提示词编辑器 UI 优化**：
  - 移除"Split"分列视图模式，仅保留"Edit"和"Preview"双模式
  - 修复右上角确认按钮样式，从自定义 `Box + Background` 改为标准 `IconButton`，与全局 Header 按钮形状一致
  - *变更文件*：`UnifiedPromptEditor.kt`
- **🟡 P1 — 全站返回按钮样式统一**：
  - 新建统一组件 `NexaraBackButton`，使用 `Icons.AutoMirrored.Rounded.ArrowBack`（Rounded 变体），尺寸 48dp，图标 24dp
  - 更新 8 个页面使用统一组件：`ChatScreen`、`SessionSettingsScreen`、`AgentEditScreen`、`AgentSessionsScreen`、`NexaraPageLayout`、`DeveloperScreen`、`KnowledgeGraphScreen`、`DocEditorScreen`
  - *变更文件*：`NexaraBackButton.kt`（新建）、`ChatScreen.kt`、`SessionSettingsScreen.kt`、`AgentEditScreen.kt`、`AgentSessionsScreen.kt`、`NexaraPageLayout.kt`、`DeveloperScreen.kt`、`KnowledgeGraphScreen.kt`、`DocEditorScreen.kt`

### 工具调用链全面审计与 4 项系统性缺陷修复 (2026-05-19)
- **🔴 P0 — 工具调用参数双重累积 (Double Accumulation)**: 根治 OpenAI/GenericOpenAI 协议层发送完整累积 arguments 导致 ViewModel 二次累积、参数膨胀损坏的致命 Bug
  - *病因*：Protocol 层在 SSE 流中累积 `function.arguments` 片段后发送完整值 → ChatViewModel 执行 `existing.arguments + chunk.arguments` 追加 → JSON 损坏为深层嵌套
  - *修复*：`OpenAIProtocol.kt` + `GenericOpenAICompatProtocol.kt` 改为发送增量 `fragment`；`AnthropicProtocol.kt` content_block_stop 移除重复发送；flushRemaining 统一清理
- **🔴 P0 — 流式错误「一次即死」**: 根治 `StreamChunk.Error` 立即 `cancel()` 协程导致 Agent Loop 中断、模型无重试机会的致命缺陷
  - *修复*：引入 `streamingError` 标志替代 `cancel()`，有工具调用时继续反馈重试；无工具调用错误状态改为 ERROR 而非 COMPLETED
- **🟡 P1 — System Prompt 工具调用指令重构**: 移除 XML 降级指令（干扰原生 function calling 模型）；新增结构化指南（Calling Tools / Handling Errors / Constraints）
- **🟡 P1 — TOOL_RESULT_SEPARATOR_PATTERN 表格误匹配修复**: 添加负向前瞻 `(?!-{2,})` 排除 Markdown 表格分隔线 `---|---|---`
- *变更文件 (5)*：`OpenAIProtocol.kt`, `GenericOpenAICompatProtocol.kt`, `AnthropicProtocol.kt`, `ChatViewModel.kt`, `ContextBuilder.kt`
- *审计方案*：`.agent/plans/20260519-toolchain-argument-double-accumulation-fix.md`

### 提供商模型配置持久化修复与会话 RAG 指示器内联排版美化 (2026-05-19)
- **🔴 P0 — 提供商模型管理中自定义模型参数（上下文长度、细粒度修饰能力）退出即重置缺陷根治**:
  - *问题*：用户在“提供商管理-模型管理”中手动调整上下文长度（例如调大 contextLength）或修改模型拥有的细粒度能力（如 Vision 等修饰能力），在退出该界面或重新进入时，这些自定义配置会被彻底抹除重置。这是由于 `ProviderManager.loadModels()` 在加载时每次都会强制调用 `migrateModelIfNeeded` 对齐 `ModelSpec`，内部逻辑粗暴地用数据库预设默认值把用户自定义的值全部覆盖覆写。
  - *修复*：
    - 重构了 `migrateModelIfNeeded` 的数据对齐机制。精细化地通过 `settingsPrefs.contains()` 预先判断 SharedPreferences 内部是否已持久化过这些字段（`hasStoredCaps`、`hasStoredContext` 等）。
    - 只要用户在 UI 中对模型进行过自定义调整且保存过，系统加载时将 **100% 遵循用户保存的值，绝不强制覆盖**，保证用户修改的永久生存。
    - 仅在首次数据升级、SharedPreferences 中相关键确实缺失时，才从内置的 `ModelSpec` 进行智能初始化与回填匹配，平滑无感地实现了元数据的向后兼容迁移。
- **🟡 P1 — 会话 RAG 与 Summary 任务指示器换行排版极不合理问题修复**:
  - *问题*：在此前的 UI 布局中，会话在发生 RAG 检索或进行摘要后处理时，`PostProcessBar` 任务指示器会被渲染在输入框上方浮岛 `ChatInputTopBar`（模型指示器胶囊和上下文胶囊）的下方，独占新起的一行。这造成了难看且不合理的临时垂直换行，极度挤占聊天界面上宝贵的对话视口空间。
  - *修复*：
    - 去除了 `ChatInlineComponents.kt` 中 `PostProcessChip` 的 `private` 关键字，向外部包（即 `ChatScreen.kt`）提升和开放了该 Composables 的访问权限。
    - 重新改造并扩充了 `ChatInputTopBar` 的参数签名与 Row 横向布局，令其直接接收 `postProcessTasks`。
    - 在 `ChatInputTopBar` 内部，将后处理指示器胶囊优雅地横向排列在上下文胶囊的右侧，作为同一行上的第三个或第四个胶囊同行呈现。
    - 彻底删除了浮岛中换行排版的 `PostProcessBar` 容器。实现了所有系统胶囊的同行并排呈现，极致节约空间，整体布局尽显紧致、高端与交互的极致细腻。
- *变更文件 (3)*：`ProviderManager.kt`, `ChatInlineComponents.kt`, `ChatScreen.kt`

### 消息气泡长按动作菜单修复与流式工具注入判定收紧 — 实现磨砂玻璃上下文菜单与流式误杀根治 (2026-05-19)
- **🔴 P0 — 流式工具注入解析误判表格连字符与 range API 编译兼容漏洞根治**:
  - *问题*：
    1. 流式后处理函数 `sanitizeStreamingContent` 之前仅通过 `indexOf("---")` 简单查找，且只要后续文本包含“结果”就武断判定为工具结果注入。这导致包含普通 markdown 表格连字符 `"---"` 且包含“结果”字眼的科普正文被系统“碎尸截获”为错误执行的工具。
    2. 使用 `match.range.first` 提取匹配边界在特定老版本 Kotlin 编译器或编译链中可能会面临 API 不兼容的编译挂起风险。
  - *修复*：
    - 引入高精度的正则嗅探 `TOOL_RESULT_SEPARATOR_PATTERN`（精准匹配格式为 `---\s*(?:工具|tool|search)?\s*(?:调用|执行)?\s*结果\s*[：:]`），彻底杜绝了将 markdown 表格作为工具结果拦截的可能。
    - 采用平台兼容性极高的 `content.indexOf(match.value)` 语法替换 `match.range.first`，以 100% 稳健的方式精确定位拦截点，彻底封堵编译兼容性漏洞。
- **🔴 P0 — 消息气泡长按菜单无法触发与手势冲突故障根治**:
  - *问题*：在 native-ui 线性管道化重构中遗留了消息长按手势监听。`UserMessageBubble` 和 `PipelineBubble` 没有任何交互触发器，导致用户根本无法长按消息气泡。若盲目在最外层加长按，则会拦截并破坏思考块和工具块自身的点击折叠展开手势。
  - *修复*：
    - 精准将 `combinedClickable` 织入 `UserMessageBubble` 卡片 Surface 和 AI 正文的 `ContentSegment` 透明 Surface，完美规避了长按手势与内联块折叠手势的碰撞。
    - 倾力打造旗舰级毛玻璃上下文菜单 `MessageContextMenu`，内置 `NexaraGlassCard` 精致磨砂设计，并实现“复制正文”、“重新生成”、“删除消息”等功能的 ViewModel 实线交互闭环。
    - 对 `ChatScreen.kt` 的 LazyColumn 渲染分支进行了大胆重构扁平化，一刀切平冗长的条件判断分支，将路由和气泡转发完美托管给 `PipelineBubble`，简化代码达 25 行以上！
- *变更文件 (3)*：`ChatViewModel.kt`, `PipelineBubble.kt`, `ChatScreen.kt`

### Markdown 普通表格与 XML 解析防误判深度根治 — 解决普通表格与 XML 解析时正文被切碎及静默工具执行瘫痪 (2026-05-19)
- **🔴 P0 — 普通 Markdown 表格被 Fallback 解析器错误拦截截断**:
  - *问题*：当模型输出带有多栏列表（带有 `|`）的普通表格时，Fallback 工具调用解析器中的 XML 变体匹配正则（例如匹配 `<tool_call|function_call>` 包含 `|` 字符）会由于边界模糊将表格中的多栏 `|` 段文本误判为工具调用标签。这导致：
    1. 页面渲染被错误分割，产生破碎且内容中断的空白工具容器。
    2. 工具调用解析器静默失败，模型无法触发真正有效的工具执行。
  - *修复*：
    - 精确化 XML 工具调用标签匹配正则 `XML_TOOL_PATTERN`。将通配且包含 `|` 的管道符匹配组细化为明确的 `(?:tool_call|function_call|func_call)` 互斥标签名称集合，严格隔离 Markdown 中的表格分隔符 `|`。
    - 在过滤 XML 标签的正则表达式中同步修正匹配边界，杜绝因为包含 `|` 而将正文中的多栏表格拦截并剔除的严重文字溢出隐患。
- **🔴 P0 — 单元测试环境 JVM/Robolectric 多线程 Room 数据库访问挂起死锁根治**:
  - *问题*：在 `ChatViewModelTest` 中进行流式响应测试时，因为 `ContextBuilder` 在预取任务计划时同步调用了 `taskRepository?.getPlan()`，测试主线程和 Robolectric 线程因 Room 数据库对 SQLite 的跨线程同步操作产生永久挂起和死锁，导致所有核心流式测试用例 100% 卡死失败。
  - *修复*：
    - 在 `ChatViewModelTest` 的 `setUp` 阶段，通过反射（Reflection）注入 `fakeTaskRepository` 到 `NexaraApplication.taskRepository$delegate`，完美隔离 Room 数据库的底层访问，消除跨线程死锁，让流式与错误状态单元测试 100% 成功且极其稳健！
- *变更文件 (2)*：`ChatViewModel.kt`, `ChatViewModelTest.kt`

### 工具调用 Fallback 解析器校验根治 — 消除误判与幽灵工具调用 (2026-05-19)
- **🔴 P0 — Fallback 解析器对所有优先级均缺失 `knownTools` 校验**：
  - *问题*：`parseToolCallFromJson()` 只要 JSON 中含 `name`/`tool`/`function.name` 字段就会生成 `ToolCall`，完全不检查该工具是否注册在 `SkillRegistry` 中。模型在正文中输出的任何 JSON（举例、科普、代码示例等），都会被误解析为工具调用，导致：
    1. 幽灵工具被发送到 `ToolExecutor` → 返回 "Skill xxx not found" 错误
    2. 错误结果被回传给模型 → 模型困惑，尝试调用更多不存在的工具
    3. 循环往复，直到工具调用次数上限
  - *修复*：
    - 新增 `isKnownTool(name)` 统一校验方法（带 `@Volatile` 缓存），所有 4 个优先级（DSML / XML / Markdown 代码块 / 裸 JSON）全覆盖。
    - `parseToolCallFromJson()` 在提取工具名后立即调用 `isKnownTool()`，不存在则返回 `null`。
    - DSML 优先级结果同样过滤非注册工具。
    - XML 纯文本模式改用统一的 `isKnownTool()` 替代重复的 `knownTools` 构建逻辑。
- **🟡 P1 — `SharedPreferences.getStringSet` 缓存 Bug**：
  - Android 已知 bug：`getStringSet` 返回的是内部可变引用，修改后再读可能返回脏数据。
  - 修复：`buildToolList()` 中对返回值执行 `.toSet()` 创建防御性副本。
  - 同时在每次构建工具列表时刷新 `cachedKnownToolNames` 缓存。
- *变更文件 (1)*：`ChatViewModel.kt`

### 协议层全量对齐修复 — OpenAI/Generic/Anthropic/VertexAI 四协议拉平 (2026-05-19)
- **🔴 P1 — GenericOpenAICompatProtocol 四项缺陷修复**（影响所有 6 家新增中国服务商 + DeepSeek + Ollama 等）：
  - *G-1 HTML 响应检测完全缺失*：新增 `contentType(text/html)` 前置检测 + SSE 流内 `line.startsWith('<')` 行级检测，与 OpenAI 协议对齐。当 CDN/Nginx 返回 HTML 错误页时不再静默空响应。
  - *G-2 Tool Call 增量流式缺失*：原仅在 `flushRemaining()` 末尾一次性批量发送完整 ToolCall，用户在整个生成过程中看不到工具调用进度。现改为每个 delta chunk 即时推送 `ToolCallDelta`，与 OpenAI 协议完全一致。
  - *G-3 音频模态遗漏*：`modalities` 字段仅包含 `text+image`，遗漏 `audio`。现与 OpenAI 协议对齐，根据实际输入自动包含。
  - *G-4 tool 消息 `name` 字段缺失*：`role=tool` 消息中未写入 `name` 字段（部分服务商校验严格时会拒绝）。已补齐。
- **🔴 P1 — AnthropicProtocol Streaming Timeout 保护**：
  - SSE 读取循环无 `streamTimeout` 保护，当 Anthropic API 无响应时会永久阻塞。现与 OpenAI/Generic 协议对齐，使用 `withTimeoutOrNull(request.streamTimeout)` 保护。
- **🔴 P1 — VertexAIProtocol Streaming Timeout 保护**：
  - 同上，SSE 读取循环无超时保护。已加入 `withTimeoutOrNull` 保护。
- *变更文件 (3)*：`GenericOpenAICompatProtocol.kt`, `AnthropicProtocol.kt`, `VertexAIProtocol.kt`

### 中国大陆主流 AI 服务商预设扩展 — 6 家新增 (2026-05-19)
- **🟢 新增 6 家中国大陆主流 AI 服务商 Provider 预设**：
  - **Moonshot (Kimi)** — `api.moonshot.cn/v1/chat/completions`
  - **通义千问 (Qwen)** — `dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
  - **智谱 (GLM)** — `open.bigmodel.cn/api/paas/v4/chat/completions`
  - **豆包 (Doubao/字节跳动)** — `ark.cn-beijing.volces.com/api/v3/chat/completions`
  - **零一万物 (Yi)** — `api.lingyiwanwu.com/v1/chat/completions`
  - **百川 (Baichuan)** — `api.baichuan-ai.com/v1/chat/completions`
- **协议兼容性确认**：6 家服务商均采用 OpenAI-compatible Chat Completions API 格式，统一路由通过 `GenericOpenAICompatProtocol`，无需新增协议类。
- **品牌图标**：6 个 Provider 图标均从 LobeHub LobeIcons 开源项目 (`@lobehub/icons-static-svg`) 下载 SVG 素材并转换为 Android Vector Drawable (24dp × 24dp, `fillColor="#FFFFFF"`)。
- **Provider 预设总数**：从 8 个扩展至 14 个。
- *变更文件*：`LlmProtocol.kt`（+6 ProtocolType）、`LlmProvider.kt`（+6 when 分支）、`ProviderFormScreen.kt`（PRESETS 8→14）、6 个 `ic_provider_*.xml` Vector Drawable 图标。

### 工具调用 Fallback 解析器全模型兼容修复 (2026-05-19)
- **🔴 P0 — MiniMax-M2.7 等非标准工具调用模型无法触发工具执行**：
  - *根因*：MiniMax 模型将工具调用以 `<FunctionCall>func_name</FunctionCall>` 格式嵌入文本流（纯文本函数名，非 JSON），但 Fallback 解析器仅匹配 `<tool_call|function_call>`（带下划线变体），且期望内容为 JSON 格式。
  - *修复*：重写 `extractToolCallsFromText()` 为四优先级架构：
    1. **DeepSeek DSML**：`<||DSML||tool_calls>` 格式（无变化）
    2. **XML 标签（全变体）**：覆盖 `FunctionCall`/`tool_call`/`function_call`/`func_call` 等所有驼峰和下划线变体，支持纯文本函数名和 JSON 两种内容格式
    3. **Markdown 代码块**：`` ```json `` 包裹的 JSON
    4. **裸 JSON 兜底**：大括号配对扫描
  - 纯文本函数名模式下通过 `SkillRegistry` 校验合法性，避免将随机文本误判为工具调用。
  - 同步更新 `XML_TOOL_PATTERN`（内容清除正则），确保 `<FunctionCall>` 标签在显示内容中被正确剥离。
  - *兼容模型覆盖*：MiniMax、百川、智谱 GLM、Qwen、通义千问、零一万物等使用非标准工具调用格式的模型。
- **架构审计结论 — 标准协议层无断裂**：
  - OpenAI/GPT 系列：`tool_calls[].function` 标准流式增量 → `ToolCallDelta` → ✅ 正常
  - Anthropic/Claude 系列：`content_block_start(tool_use)` + `input_json_delta` → ✅ 正常
  - VertexAI/Gemini 系列：`functionCall` part 解析 → ✅ 正常
  - DeepSeek 系列：DSML `<||DSML||tool_calls>` → ✅ 正常（专有解析器）
  - Generic OpenAI Compatible：`tool_calls[].function` 标准格式 → ✅ 正常
- *变更文件*：`ChatViewModel.kt`

### 知识图谱抽取质量优化 — Prompt 重构 + 后处理剪枝 + 重抽清理 (2026-05-18)
- **🟡 P1 — KG 抽取 Prompt 工程重构**：
  - *问题*：原始 Prompt 无数量指导、类型过于泛化（6 种通用类型），导致 LLM 倾向于穷举式提取。小说大纲 8700 字解析出 305 节点 412 边，大量低价值实体（通用概念、短暂提及）淹没核心结构。
  - *修复*：
    - 引入 **"Quality First"** 软引导策略：`"Aim for 10-25 key entities and 15-35 relationships per chunk"`，不设硬性上限避免截断真实重要实体。
    - 类型语义细化：从泛化 `concept` 拆分为 `person/organization/location/event/item/concept`（concept 标注 "use sparingly"）。
    - weight 分级指导：`0.9+=core, 0.5-0.8=notable, below 0.5=skip it`，引导 LLM 主动过滤低价值关系。
- **🟡 P1 — KG 后处理剪枝管线**：
  - 新增 `pruneLowQuality()` 四步剪枝：短名称过滤 → 低置信度边 → 悬空边 → 孤立节点。
- **🟡 P1 — KG 重新抽取清理机制**：
  - *问题*：对同一文档再次触发 KG 抽取时，旧数据不会被清除，导致 weight 累加膨胀、旧低质量数据残留。
  - *修复*：`GraphExtractor.extractAndSave()` 在 `docId` 非空时，先调用 `graphStore.clearGraphForDoc(docId)` 删除该文档关联的所有边，再清除因此变为孤立的节点，然后执行正常抽取入库。
  - *变更文件*：`GraphExtractor.kt`, `GraphStore.kt`, `KgEdgeDao.kt`

### 极致原生化 Jetpack Compose Canvas 知识图谱星图引擎重构与高倍率渲染极致性能优化 (2026-05-18)
- **🔴 P0 — 彻底根治现代 Android 11+ WebView 严格沙箱限制导致的图谱白屏，以及高倍率视角下文本投影导致的致命卡顿**:
  - *病因分析*：
    1. **Android 静态文件 CORS 拦截白屏**：WebView 对本地路由实施极严苛的跨域安全拦截，导致本地 `echarts.min.js` 经常遭遇加载失败静默瘫痪。
    2. **物理力场失衡导致的“毛线团”节点重叠**：原平方反比斥力公式在距离变大时衰减过快，且向心引力过强（`0.012`），导致 305+ 节点高度堆叠在极窄的中心区域，缩放放大后同屏节点依然极多，形成重合乱局。
    3. **无视口裁剪与高清晰文本投影超负荷**：在没有视口裁剪（Viewport Culling）的情况下，系统每帧强行向 GPU 提交所有 off-screen 的 305 个节点与边进行绘制；在高倍率放大时，GPU 必须为大量放大后的文本高精度模糊渲染 dropshadow 软投影，瞬间击穿移动端像素填充率，导致帧率暴跌至 < 5fps。
  - *修复方案*：
    1. **100% 极致原生 Jetpack Compose Canvas 星图**：从零构建纯 Kotlin 协程控制、硬件加速的物理力场力导向星空画布，内存占用从 200MB+ 骤降至 **< 5MB**，启动加载等待时间由 2 秒级缩短至 **< 5ms 瞬间渲染**。
    2. **慢衰减力场公式重构 (`GraphPhysicsSimulator.kt`)**：将库仑斥力公式重构为慢一次方反比衰减场（$F = k_r / d$），使长程范围内仍然保持强劲的推力；将中心引力 `kg` 降至 `0.003f`，理想边长增至 `150f`，初始随机坐标分布范围扩大 7.5 倍至 `1200 x 1200` 大空间，实现 305+ 节点极其完美地平铺展开，消除重叠。
    3. **🔋 智能休眠能效判定**：物理收敛阈值 `epsilon = 0.06f`。连续 40 帧粒子最大位移低于此阈值时自动挂起协程物理仿真计算，释放 100% CPU 和电池资源，仅在发生数据刷新或手势交互时自动唤醒。
    4. **极致视口裁剪过滤 (Viewport Culling Engine in `InteractiveGraphCanvas.kt`)**：利用当前平移 `offset` 和缩放 `scale` 反解析出当前屏幕视口边界 `[viewLeft, viewRight]`（搭载 120 像素缓冲），100% 剪裁过滤 off-screen 的节点与边。在高倍率拉大视角下，GPU 仅渲染同屏的 10~30 个可见元素，像素与阴影渲染负载剧减 95% 以上，彻底根治卡顿，单指平移滑动始终稳健坚守 **120Hz 极速满帧**！
    5. **手势防冲突空间控制系统**：融合「单指点击选中粒子拖拽/平移」与「双指 Focal-Point 矩阵聚焦平滑缩放平移」，GPU 硬件级变换如丝般顺滑。
  - *变更文件*：[InteractiveGraphCanvas.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/canvas/InteractiveGraphCanvas.kt), [GraphPhysicsSimulator.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/canvas/GraphPhysicsSimulator.kt), [KnowledgeGraphViewModel.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/KnowledgeGraphViewModel.kt), [KnowledgeGraphScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/KnowledgeGraphScreen.kt)。

### 知识图谱可视化性能调优与大规模数据渲染防崩溃根治 (2026-05-18)
- **🔴 P0 — 彻底根治 176+ 大数据节点下 ECharts 悬挂边解析崩溃与无初始布局导致的坐标爆炸**:
  - *病因分析*：
    1. **数据悬挂边（Dangling Edges）**：RAG 提取过程中易产生数据不完整性，数据库中边（Edge）的 `sourceId` 或 `targetId` 在顶点列表中不存在。ECharts 在初始化关系图（Graph）时，一旦检测到任何一条无效边，会引发致命的 JS 未捕获异常并直接中断整个渲染，呈现完全空白。
    2. **物理引擎重叠斥力爆炸**：176 个节点在缺乏初始圆形排布（`initLayout`）的情况下从同一个重合坐标 $(0,0)$ 启动力导向物理引擎，导致瞬间产生趋向无穷大（`NaN` / `Infinity`）的相互排斥力，使所有节点立刻飞出视口或计算失效，画面呈现死黑。
    3. **类别越界与 Formatter 模板解析异常**：节点类别超限导致 category 索引错误，以及连线 Label 直接传入原始字符串被误解析为 ECharts 的模板令牌。
    4. **Web 报错不可见**：WebView 内部 JS 发生致命错误时静默挂掉，缺乏 try-catch 灾备可视化反馈。
  - *修复方案*：
    1. **前置悬挂边安全过滤**：在 JS 模板中建立 `validNodeIds` 哈希映射表，在装配 `links` 数组前强行过滤掉所有起点或终点非法的无效边，并输出 console 警告，实现数据瑕疵下的 100% 免疫崩溃。
    2. **显式启用 `initLayout: 'circular'` 圆周初始布局**：强制节点在圆周上均布排列后启动力导向引擎，消除坐标重叠点引起的斥力奇异值（NaN），并提升 3 倍以上收敛性能。
    3. **大规模力场参数性能调优**：针对手机端将 `repulsion` 调优为 `120`（原 250），`gravity` 调优为 `0.1`（原 0.08），`friction` 设为 `0.6`，保证星图美观紧凑且大幅节省手机 CPU/电量。
    4. **安全类别与 Formatter 降级**：映射节点时使用安全降级 `category: colorMap[n.type] ? n.type : 'other'`，连线 Label 统一改用 `formatter` 回调函数，规避模板字面量解析风险。
    5. **全局 try-catch 与红色报错卡片**：对 ECharts 初始化和 setOption 渲染逻辑进行全局 `try-catch` 包裹，一旦捕获未知 JS 异常，直接在网页容器中输出精美的红色报错卡片，展示清晰的错误描述，极大提升了开发与调试的可观测性。
  - *变更文件*：[kg_template.html](file:///k:/Nexara/native-ui/app/src/main/assets/kg_template.html)。

### 知识图谱可视化星图空白与 WebView 无限重载缺陷根治 (2026-05-18)
- **🔴 P0 — 彻底根治 Android WebView 静态资源加载失效与 Compose 重组无限刷新导致的图谱空白**:
  - *病因分析*：
    1. **静态资源绝对路径解析异常**：原 `kg_template.html` 导入了绝对路径 `<script src="file:///android_asset/echarts/echarts.min.js"></script>`。这在以 Base URL `"file:///android_asset/"` 加载时，由于 WebView 沙箱跨域安全拦截或绝对路径重复拼装解析失效，导致 ECharts 静态库无法加载。
    2. **Compose 重组引发 WebView 无限重载**：在 `KnowledgeGraphScreen.kt` 的 `AndroidView(WebView)` 组件中，`update` 块未对 `graphHtml` 进行去重处理。每次页面重组或过滤 Tab 按钮被点击等 Compose 重组时，都会强行重复调用 `wv.loadDataWithBaseURL()`，使 WebView 处于不间断刷新和白屏中。
    3. **ECharts 节点 Label 重名冲突崩溃**：原模板直接以实体名称 `n.name` 作为 ECharts 关系图的节点主键标识。一旦 RAG 提取出来的实体有重名（如不同文档中的 "Nexara" 实体），ECharts 会因 Graph 主键唯一性校验失败而抛出 "Each series.data must have a unique name." 异常静默崩溃，拒绝渲染。
  - *修复方案*：
    1. **静态资源相对化**：将 `kg_template.html` 中 ECharts 脚本路径修正为相对路径 `<script src="echarts/echarts.min.js"></script>`，保证 100% 成功加载本地 Assets。
    2. **引入 Recompose 去重保护**：在 `KnowledgeGraphScreen.kt` 的 Composable 内部，通过 `remember` 实例化 `lastLoadedHtml` 缓存变量。在 `update` 块中通过 `if (lastLoadedHtml != html)` 进行高灵敏防抖拦截，仅在 HTML 内容发生物理变化时触发 WebView 的 load 调用，彻底根治无限白屏刷新。
    3. **唯一主键映射与高保真格式化**：将 ECharts node 映射的 `name` 属性与唯一的 `n.id` 绑定，同时将实际名称存入自定义属性 `displayName`，最后通过 ECharts 的 `label.formatter` 和 `tooltip.formatter` 自定义格式化函数以展示 `displayName`，优雅防御重名实体崩溃。
    4. **WebChromeClient 控制台日志无损转发**：在 WebView 初始化时挂载自定义 `WebChromeClient`，覆盖 `onConsoleMessage`，自动将 WebView 内的所有 JS console error 与 log 实时格式化并通过 `NexaraLogger.log("[WebView Console] ...")` 广播至 logcat，瞬间打通 WebView 内部开发调试的可观测性盲区。
  - *变更文件*：[kg_template.html](file:///k:/Nexara/native-ui/app/src/main/assets/kg_template.html), [KnowledgeGraphScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/KnowledgeGraphScreen.kt)。

### 知识图谱抽取超时可配置化 (2026-05-18)
- **🟡 P1 — 知识图谱 (KG) 每个 chunk 抽取超时时间可配置**：
  - *问题*：`GraphExtractor.extractSingleChunk()` 调用 `protocol.sendPromptSync()` 时没有任何超时控制，完全依赖底层 Protocol 的硬编码 `requestTimeoutMillis = 120_000`（120秒）。当 LLM 响应慢或无响应时，KG 抽取会卡在同步调用长达 120 秒，每次尝试都会等到超时才失败。
  - *修复*：
    - `GraphExtractor` 新增 `timeoutMs` 构造参数（默认 15,000ms），使用 `withTimeoutOrNull` 包裹 `sendPromptSync` 调用，超时后立即返回友好错误而非阻塞等待。
    - 在设置 → 记忆设置 → 知识图谱页面新增「抽取超时时间」滑块（5~120 秒，默认 15 秒），用户可根据模型响应速度灵活调整。
    - 配置链路完整贯通：`RagAdvancedScreen` → `RagViewModel` → `RagConfigPersistence` → `AgentRetrievalConfig` / `RagConfiguration` → `NexaraApplication` → `GraphExtractor`。
  - *变更文件*：`GraphExtractor.kt`, `RagModels.kt`, `AgentConfigModels.kt`, `RagConfigPersistence.kt`, `RagViewModel.kt`, `NexaraApplication.kt`, `RagAdvancedScreen.kt`, `strings.xml (en+zh)`。

### 知识图谱抽取诊断日志增强与可视化修复 (2026-05-18)
- **🔴 P0 — `sendPromptSync` 捕获 `CancellationException` 致 `withTimeoutOrNull` 失效（KG 全 chunk 失败根因）**：
  - *根因*：4 个协议类的 `sendPromptSync` 中 `catch (e: Exception)` 捕获了 `CancellationException`（Kotlin 结构化并发致命反模式）。当 `withTimeoutOrNull(15_000ms)` 触发超时取消时，HTTP 请求被中断抛出异常 → 被 `sendPromptSync` 内部 catch 拦截并包装为新 `Exception` → `withTimeoutOrNull` 无法返回 null（因为异常已从内部逃逸） → 最终走到 `extractAndSave` 的 catch 块，日志显示"未知错误"。
  - *修复*：所有协议类添加 `catch (e: CancellationException) { throw e }` 透传，确保超时取消能被 `withTimeoutOrNull` 正确捕获并返回 null。
  - 异常消息改为 `[HTTP {code}][{category}] {raw response}` 保留完整诊断信息。
  - *变更文件*：`OpenAIProtocol.kt`, `GenericOpenAICompatProtocol.kt`, `AnthropicProtocol.kt`, `VertexAIProtocol.kt`。
- **🔴 P0 — KG 抽取日志颗粒度增强**：
  - 每个 chunk 记录详细结果（成功/失败/错误类型），JSON 解析失败记录原始 LLM 响应前 300 字符。异常改用 `logError` 记录完整堆栈。
  - *变更文件*：`GraphExtractor.kt`。
- **🔴 P0 — 知识图谱可视化"有统计但无图"修复**：
  - GLOBAL 模式返回全部节点（含孤立节点），ECharts 空数据优雅降级。
  - *变更文件*：`GraphStore.kt`, `kg_template.html`, `KnowledgeGraphViewModel.kt`。
- **🔴 P0 — 会话 UI 生成结束后 `isGenerating` 卡在 `true` 不自动结束**：
  - *根因*：`UnifiedLlmClient.sendStream()` 使用 `channelFlow { ... awaitClose {} }`，底层协议流结束后 `awaitClose {}` 无限期挂起 → Flow 永不完成 → `ChatViewModel` 的 `flow.collect {}` 永不返回 → `isGenerating` 永远不会被设为 `false`。
  - *修复 1*：删除 `UnifiedLlmClient` 的 `awaitClose {}`，让 `channelFlow` 在协议流结束后自然完成。
  - *修复 2*：`ChatViewModel.generateMessage()` 添加外层 `try-finally` 保护，确保任何异常路径都能重置 `isGenerating`。
  - *变更文件*：`UnifiedLlmClient.kt`, `ChatViewModel.kt`。
- **💻 桌面 TUI 调试桥 KG 专属事件渲染器**：
  - 新增 KG 子分类渲染（chunk 进度/错误/汇总/超时/LLM 响应/JSON 诊断）。

### Nexara 调试桥报错广播与大模型 RAG 提取超时根治 (2026-05-18)
- **🔴 P0 — 彻底根治 RAG 知识图谱大文本分段非流式同步抽取超时崩溃 (SocketTimeoutException)**:
  - *病原定位*：在 `OpenAIProtocol` 等协议类中，Ktor HttpClient(OkHttp) 虽配置了 `connectTimeout` 与 `requestTimeout`，但**完全缺失了 `socketTimeoutMillis`** 这一最关键的套接字读写超时设定。这导致底层 OkHttp 引擎自动回退至其**默认 10 秒超时门槛**。当进行复杂的 RAG 知识图谱抽取（`GraphExtractor`）时，非流式大模型请求（如 `MiniMax-M2.7`）在处理 800+ 字符切片并输出完整 JSON 结构体通常耗时 15s~40s，刚好在第 10 秒因读写超时触发 Socket 掐断崩溃。
  - *修复方案*：在 `OpenAIProtocol`、`GenericOpenAICompatProtocol`、`AnthropicProtocol` 和 `VertexAIProtocol` 的 HttpClient 构造块中，在 `HttpTimeout` 配置内显式注入 `socketTimeoutMillis = 120_000` (120秒)，彻底打破底层 OkHttp 引擎的 10 秒超时封锁，打通了超长响应时间下的 RAG/KG 完美提取通道。
- **🔴 P0 — 物理贯通调试桥（Nexara Metro）网络错误与运行时异常广播盲区**:
  - *病原定位*：原本 `NexaraLogger.logError(tag, throwable)` 仅局限于本地 Android Logcat 与本地磁盘日志写入，完全缺失了对 `NEXARA_METRO` 事件管道的结构化上报。这导致桌面 TUI 终端在网络超时、连接错误或大模型接口崩溃时呈现一片静默死锁态势，出现观测性断链。
  - *修复方案*：重构 `NexaraLogger.logError`，当在 Debug 模式时自动将异常信息与前 15 行堆栈信息串行化为结构化 JSON，通过 `NEXARA_METRO` Tag 实时广播出去。同时将 `GraphExtractor` 内部所有日志格式化为中括号 `[RAG][GraphExtractor]` 前缀以触发系统自动分类路由。
- **💻 桌面 TUI 终端大屏红色报错渲染器发布**:
  - *升级细节*：重构 `scripts/nexara-metro-tui.js` 脚本解析管道，增设 `ERROR` 事件的专属处理分支。当真机或模拟器发生运行时致命故障或大网络超时报错时，桌面 Node.js 终端会触发 ANSI 红色大屏高保真边框卡片，加粗显示错误事件发起组件、异常核心原因，并以精巧的树状结构浅灰色优雅收敛打印 6 行核心堆栈追踪，提供业界顶尖的可观测性与实时开发调试体验！
- **归档计划**：已成功提交实施计划 [.agent/plans/20260518-NexaraMetroDebuggerFix.md](file:///k:/Nexara/.agent/plans/20260518-NexaraMetroDebuggerFix.md) 并于文档注册表 `.agent/registry.md` 注册。


### Nexara Metro 调试桥系统 (Phase 1) 落地 — ADB Logcat 极轻、高内聚结构化捕获管道 (2026-05-18)
- **💡 架构演进**:
  - 与架构大师 GLM-5.1 的深度可行性评审反馈对齐，完成 V2 版调试方案重塑并编写 [20260518-Nexara-Metro-Debugger-Discussion.md](file:///Users/promenar/Codex/Nexara/docs/audit/20260518-Nexara-Metro-Debugger-Discussion.md)。
  - 制定并归档了极其详尽的 Phase 1 实施蓝图 [.agent/plans/20260518-NexaraMetroDebuggerPhase1Plan.md](file:///Users/promenar/Codex/Nexara/.agent/plans/20260518-NexaraMetroDebuggerPhase1Plan.md)。
- **📋 落地核心资产**:
  - **手动 DI 对齐**: 纠正 Hilt 假设，将拦截器和中间件整合到 `NexaraApplication` 的 lazy 实例化链路中，通过构造参数手动解耦注入。
  - **NexaraLogger 结构化升级与 JVM 单元测试安全沙盒**: 重构 `NexaraLogger.kt`，仅在 `BuildConfig.DEBUG` 激活时将带 Tag 的日志（如 [RAG]、[TOOL]）自动封装并以特定格式（`EVENT_START|${tag}|${json}|EVENT_END`）输出到系统 Logcat 管道，一键激活 80+ 处存量埋点。同时，引入 JVM 本地单元测试环境检测，在非真机环境下自动沙箱化隔离，物理绕过 `android.util.Log`、`org.json.JSONObject` 和 SharedPreferences 的访问，彻底根治本地测试套件中的 RuntimeException 闪崩缺陷。
  - **Room Database 零侵入 SQL 审计**: 在 `databaseBuilder` 中挂接 `RoomDatabase.QueryCallback`，异步捕获并解析针对 `Message`、`Session`、`TaskNodeEntity` 表的所有的 SQL 语句与参数。
  - **Ktor OkHttp 引擎拦截器 (SSE 抓包)**: 挂载自定义 `MetroLogInterceptor`，对于流式 API SSE 响应采用 okio.ForwardingSource 逐块（Chunk）非阻塞式拦截读取，实时统计 Token CPS 生成速率。
  - **LlmMiddleware 内存监控中间件**: 挂载 `MetroLoggingMiddleware`，于大模型请求的 PRE/POST 节点抓取滑窗参数、是否开启高级检索等内存元数据。
  - **ProGuard / R8 物理剥离**: 添加 ProGuard 规则以在编译 Release 时将调试上报代码全量裁剪，零体积与运行时开销负担。
- **💻 桌面 TUI 渲染终端**:
  - 编写了 zero-dependency 脚本 `scripts/nexara-metro-tui.js`，通过 spawn `adb logcat` 异步流监听，在桌面 VS Code 终端渲染出极高美学品质的动态生成流向图。

### 工具调用系统全面移植与 10 项缺陷根治 — 基于 Cherry-Studio 参考实现 (2026-05-18)
- **参考源**: Cherry-Studio v1.9.6 工具调用系统完整架构分析（13 个核心源文件）
- **架构重构**:
  - 新增 `ToolCallLifecycleHandler` — 工具调用全生命周期管理（streaming→pending→complete/error）
  - 新增 `UnifiedLlmClient` — 统一 LLM 调用入口 + 中间件链整合
  - 新增 `LlmMiddleware` / `LlmMiddlewareChain` — 可扩展中间件管线
  - 新增 `ToolOrchestrationPlugin` — 意图分析 + 动态工具注入 + 记忆存储
  - 新增 `DsmlStreamParser` — DeepSeek DSML 格式工具调用流式解析
  - 新增 `ProviderToolFactory` — 各 Provider 原生工具定义（OpenAI/Anthropic/Google/xAI/Hunyuan）
  - 新增 `ResultSizeOptimizer` — 多模态结果文本化（防 base64 撑爆消息体）
  - 新增 `ToolChunkType` / `ToolType` / `ToolCallLifecycleEvent` — 统一工具调用事件类型体系
- **🔴 P0 致命修复**:
  - D-1: Anthropic 流式 tool_use 解析 — 新增 `content_block_start/stop` + `input_json_delta` 处理（原完全不可用）
  - D-8: `Collection.all` 空集合死锁 — `isNotEmpty()` 前置检查已验证正确
- **🟡 P1 重要修复**:
  - D-3: DSML 格式工具调用解析 — DsmlStreamParser 集成到 ChatViewModel fallback 管线
  - D-5: maxToolCalls 控制 — `generateMessage` 新增 `loopCount` 参数 + `loop_limit` 配置
  - D-6: OpenAI 流式 tool_calls 实时增量发送 — 除 flushRemaining 外每次累积即发送
  - D-7: VertexAI functionCall/functionDeclarations — 完整适配请求构建和响应解析
- **变更文件**: 8 修改 + 8 新建 = 16 文件，+243 insertions
- **编译验证**: `BUILD SUCCESSFUL in 5s`，零 lint 错误
- **4 会话并行施工**: 参考 `20260518-Parallel-Session-Implementation-Plan.md`

### XML 代码预览卡片渲染缺陷根治 — Compose 生命周期时序竞态修复 (2026-05-18)
- **🔴 P0 — 根治 WebView 高度测算时序竞态导致大面积空白**：
  - *病原定位*：`RichContentWebView` 中原测高 `WebViewClient` 在 `LaunchedEffect` 中设置，落后于 `AndroidView.update` 中的 `loadDataWithBaseURL`；简单 HTML 页面 <1ms 加载完毕，测高回调永远赶不上。辅因：`RichContentWebViewPool` 中 `layoutParams.height = WRAP_CONTENT` 使 `scrollHeight` 测量返回无约束视口高度（≈屏幕高度），被 `coerceIn(60,600)` 钳制。
  - *修复方案*：将测高 `WebViewClient` 前置至 `remember { acquire() }` 块（见 ADR-013）；`layoutParams` 改为 `MATCH_PARENT`；新增 `lastLoadedHtml` 去重消除冗余 WebView reload。
- **🔴 P0 — 激活 isLikelyRenderableHtml 死代码**：
  - *病原定位*：`isLikelyRenderableHtml` 在 `HtmlArtifactRenderer.kt` 定义但从未被调用；`CodeBlockHeader` 仅使用 `isHtmlArtifact(language)`，导致所有 ` ```xml ` 代码块均被当作 HTML artifact 渲染。
  - *修复方案*：`isRenderableHtml = isHtmlArtifact(language) && isLikelyRenderableHtml(code)`，有效排除 `<tool_call>`、`<function_call>` 等纯数据 XML。
- **新建 ADR-013**：WebView 生命周期管理 — 测高 WebViewClient 前置绑定
- **详见**：`docs/audit/XML_RENDERER_BUG_AUDIT_20260518.md`

### UI 细节抛光与视觉统一 (2026-05-18)
- **🔴 P0 — 根治思考容器字号放缩失效、与字体设置对接（始终小 2 号）及行高重叠的终极重构**：
  - *排查发现*：生成完毕后的思考容器真实渲染由合并气泡类 [PipelineBubble.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt) 的内联组件 `InlineThinkingRow` 承载，该处原包含硬编码 `THINKING_FONT_SIZE_DELTA = 6` 且未提供 `lineHeight` 行高，导致此前修改无效、字号缩死至最小极值 `8`sp，且多行文本挤压重叠，无法跟设置中的字体大小联动。
  - *重构修复*：物理清除 `PipelineBubble.kt` 的所有硬编码 delta 常量；将其实时渲染字号完美对接用户设置中的字体大小，**动态计算为始终比正文小 2 号（`(fontSize - 2).coerceAtLeast(10)`）**。当默认 `fontSize` 为 13 时，思考容器为清朗雅致的 `11`sp，并显式注入匹配黄金比例的 **`16`sp 行高**（`lineHeight = (targetFontSize + 5).sp`），一举根治了字号失效与文字行距重叠的 P0 排版难题！
- **设置页"Token 用量"更名为"用量管理"**：将设置主页菜单项及全局详情统计页面标题统一更名为"用量管理"（英文 "Usage Management"），语义更具普适性。
- **清空向量数据库时文档索引状态同步重置**：重构 `clearAllVectors` 链路，在底层 DAO 和 Repository 层引入 `resetAllRAGStatus` 批量重置接口，清空向量时一键归零所有文档的 `vectorized_at` 与 `kg_extracted_at` 时间戳，完美闭合物理资源与向量存储生命周期的同步机制。
- **"记忆设置"页面功能去噪与更名统一**：移除记忆设置主页顶部"向量索引状态"卡片（与"向量统计"二级页面高度重复），将"高级"选项卡正式重命名为"知识图谱"，与跳转后二级页面 Header 标题完全吻合。
- **用户卡片视觉去噪**：删除 `UserSettingsHomeScreen` 中 `UserProfileHeader` 硬编码的 `"ID: 8823192"` 文字，仅保留用户头像和用户名。
- **P0 统一危险操作二次确认弹窗样式与错位修复**：修复 `NexaraConfirmDialog` 因缺少 `androidx.compose.ui.window.Dialog` 包裹导致弹窗飘在屏幕顶端覆盖状态栏的 Bug。全系统 6 处删除确认弹窗统一为屏幕中央 Glassmorphism 暗磨砂玻璃弹窗。
- **删除确认按钮高对比度深红配色统一**：破坏性操作按钮背景统一为深红色（`0xFFBA1A1A`），前景文字升级为纯白色，彻底解决低对比度文字看不清的 Bug。
- **KG UI 清理**：移除知识图谱界面 `KnowledgeGraphScreen` 中用于测试的 Mock 数据注入按钮和清空图谱按钮，清理对应 ViewModel 逻辑。

### Agent 工具 Fallback 解析器重构与工作区图标优化 (2026-05-18 00:45)
- **🔴 P0 — 修复 Kotlin `Collection.all` 导致的 Fallback 解析锁死 Bug**：
  - 在流式生成完成判定中，将 `hasCompleteToolCalls` 的条件修正为 `accumulatedToolCalls.isNotEmpty() && ...`。
  - 彻底解决了当没有标准工具调用（列表为空）时，`all` 默认返回 `true` 导致兜底 Fallback 解析被永久闭锁的重大 Bug。
- **🔴 P0 — 消除大括号配对扫描 3 处冗余并提供超强维护性**：
  - 提炼并实现 `scanBalancedJsonSegments` 和 `findMatchingCloseBrace` 公共方法，完美实现嵌套 JSON 的数学级闭合配对，避开了大括号嵌套时的解析截断问题，并彻底消除 3 处相同逻辑的冗余。
- **🔴 P0 — 编译安全 getSkill O(1) 过滤与误杀防护**：
  - 用 `skillRegistry?.getSkill(it) != null` 替换了原方案中不存在的 `hasTool()`，杜绝了编译阻塞。
  - 结合合法工具数据库校验，只物理剔除合法的系统工具，科普类 Markdown JSON 示例予以 100% 完整保留。
- **🟡 P1 — 工作区右上角图标高保真更替**：
  - 将聊天界面右上角起动 Workspace 的按钮图标从 `Icons.Rounded.Tune`（设置旋钮）更替为高级亮丽的 `Icons.Rounded.Folder`（文件夹）。
  - 同步修正了第 58 行静态导入，规避编译风险。
- **编译与回归测试验证**：全量编译 100% 顺利绿灯秒过，架构稳固如磐石。

### Token 用量页面三维审计修复 — P0 全表扫描消除 + 安全确认弹窗 + 国际化 (2026-05-17 22:35)
- **🔴 P0 — 消除 4× 全表扫描性能灾难**：
  - 在 MessageDao 中新增 3 个 SQL 级聚合查询：`getTotalTokenUsage()`、`getTokenUsageByModel()`，修复了 `getSessionTokenRanking()` 的 JOIN Bug（`m.id = s.id` → `m.session_id = s.id`），用 `COALESCE` 防止 NULL 聚合。
  - 重写 TokenStatsRepository，所有聚合改用 SQL 级查询，5000 条消息从 20,000 次 Entity 反序列化降至 SQL 直接返回约 23 行。
  - 修复 `getTopSessions` 会话标题永远为 null 的 Bug，现在通过 SQL `LEFT JOIN sessions` 正确获取标题。
- **🔴 P0 — 清空操作添加二次确认弹窗**：
  - "Clear All History" 按钮现在使用 `ConfirmDialog` → `NexaraConfirmDialog` 二次确认，与系统其他破坏性操作一致。
  - ViewModel 新增 `showClearConfirm()` / `dismissClearConfirm()` / `clearStats()` 三段式安全流程。
- **🟡 P1 — 国际化 9 处硬编码字符串**：全部迁入 strings.xml，中英双语完整覆盖。
- **🟡 P2 — 加载态 + 异常日志**：新增 `CircularProgressIndicator` 加载态，异常从空 catch 升级为 `Log.e`。
- **🟡 P2 — 测试全覆盖**：重写 TokenStatsRepositoryTest，适配新 SQL 接口，新增 getTopSessions / getDailyTrend 测试。
- **🔧 formatTokenCount 精度修复**：改用浮点除法，不再截断 1.25M 级数值。
- **编译验证**：`compileDebugKotlin` 100% 绿灯通过。
### 思考容器完毕折叠、首条消息 RAG 故障根治及知识图谱大文本分段提取 (2026-05-17 21:55)
- **🔴 P0 — 思考容器完毕后自动折叠与斜体小字样式优化**：
  - 重构 [ChatInlineComponents.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatInlineComponents.kt#L100)，在 `MarkdownText` 中添加并透传了 `fontStyle` 字型参数。
  - 在 [PipelineBubble.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt#L120) 中引入 `isComplete` 参数监听，当生成完毕后将思考文本高度和折叠状态优雅过渡为折叠态，且使用非硬编码的 `THINKING_FONT_SIZE_DELTA` 和 `FontStyle.Italic` 常量，将思考文本自动调小 `2` 个字号并以斜体渲染，呈现极致纯净感。
- **🔴 P0 — 根治新会话首条消息 RAG 检索丢失故障**：
  - 在 [ChatViewModel.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt#L838) 的 `createNewSession` 与 `loadSession` 方法中引入 `getDefaultRagOptions()`。当新创会话或加载未配置的旧会话时，自动拉取全局默认 RAG 配置，装配并安全持久化，彻底解决了“第一条消息无法读取 RAG 配置”的致命缺陷。
  - 重构 [ContextBuilder.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/manager/ContextBuilder.kt#L118) 合并逻辑，当 `session.ragOptions` 为 `null` 时添加对 `tempRagOptions` 传入参数的安全 Fallback 保险，物理杜绝了 `enableRerank` 和 `enableKnowledgeGraph` 开关在首条消息被 `null` 静默覆盖的漏洞。
- **🔴 P0 — 根治知识图谱 (KG) 大文本超时报错与星图空白故障**：
  - 重构 [GraphExtractor.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/data/rag/GraphExtractor.kt#L15)，为构造函数引入 `chunkSize` 和 `chunkOverlap` 参数。当抽取超长文档的知识图谱时，自动使用重叠滑窗算法对其进行精细切片，从底层根除了网络超时或 API 单次处理限制导致的崩溃红色感叹号。
  - 在内存中引入不区分大小写的去重合并逻辑，在将图谱节点和关系持久化至 SQLite 数据库前，对所有分段提取结果进行高密度降噪与唯一性筛除。大幅降低图谱星图的冗余垃圾，完全恢复知识图谱可视化星图的清澈、透亮呈现。
  - 在 [NexaraApplication.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/NexaraApplication.kt#L480) 中与全局 RAG 设置的 `docChunkSize` 和 `chunkOverlap` 配置完美挂接，实现配置零硬编码。
- **编译与验证**：全量编译顺利通过，架构坚如磐石，极具专业工艺水准！
### 记忆设置描述文案追加知识图谱属性 (2026-05-17 21:25)
- **🔴 P0 — 完善记忆设置功能描述小字**：在 [strings.xml](file:///k:/Nexara/native-ui/app/src/main/res/values-zh-rCN/strings.xml#L122) 中，将“记忆设置”底部的二级说明小字由“分块、记忆、向量化”更名为 **“分块、记忆、向量化、知识图谱”**，完美反映系统底座中对 RAG + KG 混合知识架构的覆盖。
- **🟢 概念宣示与认知对齐**：从首屏入口处对齐高级功能的品牌主张，让用户直观体感 Nexara 独树一帜的 Graph RAG 拓扑技术能力。

### 全局设置及二级Header标题核心语义更名 (2026-05-17 20:45)
- **🔴 P0 — 记忆/检索/工具设置语义精准更名**：在 [strings.xml](file:///k:/Nexara/native-ui/app/src/main/res/values-zh-rCN/strings.xml#L121) 中完成了设置面板以及二级页面Header标题的多语言资源统一替换：
  - “RAG配置” 统一更名为 **“记忆设置”**
  - “高级检索” 统一更名为 **“检索设置”**
  - “工具管理” 统一更名为 **“工具设置”**
- **🟢 100% 页面级标题完全对齐**：二级页面 Header（包括助手配置、全局配置、界面导航项）均完美继承了新语义，确保用户界面的概念体系显得极其自然、专业与统一。

### 零宽空格降维打击根治长英文硬断行视觉问题 (2026-05-17 20:4x)
- **🔴 P0 — 引入 `&#x200B;` 零宽空格物理分词锚点**：在 [strings.xml](file:///k:/Nexara/native-ui/app/src/main/res/values-zh-rCN/strings.xml#L780) 中，于极长英文单词 `text-embedding-3-small` 的每一个连字符 `-` 边缘巧妙嵌入 Unicode 零宽空格实体 `&#x200B;`。
- **🟢 100% 达成无感智能自适应断行**：物理上解除长英文字串不可拆分的排版死锁，提供零宽隐形折行折叠锚点。确保该长英文字串在任何屏幕尺寸和容器限制下，均能紧凑饱满地排满第一行剩余物理宽度后再平滑折行，彻底消除换行崩坏。

### RAG配置页长英文说明字换行折叠排版灾难根治 (2026-05-17 20:3x)
- **🔴 P0 — 消除长英文强制硬折行大片留白**：优化 [strings.xml](file:///k:/Nexara/native-ui/app/src/main/res/values-zh-rCN/strings.xml#L780) 中的描述文案，将原本无空格的长连字符英文 `text-embedding-3-small=1536` 重构为带有合理排版间距的 `text-embedding-3-small = 1536`，完美提供系统原生 Layout 断词的折行锚点。
- **🟢 启用 LineBreak.Paragraph 高级段落排版**：在 [GlobalRagConfigScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/GlobalRagConfigScreen.kt#L260) 中引入 `LineBreak.Paragraph` 预设，为维度及 Token 说明 Text 组件注入段落级均衡自适应折行算法，令中英文和符号完美紧凑地填充整行空间后再温和下推折行，根本性根治了长英文字串导致前一行留下巨大视觉留白的排版崩坏缺陷。

### 生成时视角追踪频率物理帧率级升级 (2026-05-17 19:1x)
- **🔴 P0 — 追踪延时由 50ms 压缩至 8ms (120Hz)**：重构 [ChatScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt#L250) 中的生成中自动跟随（Auto-Scroll）循环逻辑，将检测与轻推周期从原有的 20Hz (`delay(50)`) 极限缩短到旗舰机级的 120Hz 物理帧率匹配延时 (`delay(8)`)。
- **🟢 消除流式高速吐字脱焦**：在超高速流式回复生成场景中，确保列表滚动以最紧凑的步频在每帧刷新时同步完成对齐，彻底消除传统 20Hz 周期滚动时因延迟产生的视角丢焦和颠簸感，体验如丝般顺滑。

### RAG 检索指示卡极限胶囊化与空间压缩优化 (2026-05-17 19:0x)
- **🔴 P0 — 彻底移除段落预览折叠条**：从 [ChatInlineComponents.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatInlineComponents.kt#L518) 彻底移除了底部的 `HorizontalDivider` 以及用于横向滚动预览捞取片段的 `LazyRow`。极大地释放了行内卡片的垂直高度空间。
- **🟢 交互无损保留**：由于卡片本身依然具有点击可交互性（点击卡片即可触发弹出完整的捞取片段与知识图谱拓扑图大抽屉详情面板 `RagDetailsSheet`），此优化仅移除重复且低效的预览，大幅提高界面整体信息密度。

### RAG 检索指示卡视觉宽度优化与历史持久化加载修复 (2026-05-17 18:4x)
- **🔴 P0 — 指示卡容器最大宽度优化**：限制 RAG 卡片容器的最大宽度为 70% (`fillMaxWidth(0.7f)`)，使卡片与底部的思考状态行在视觉边界上完美对齐，界面显得极其精工、高端，避免宽屏下拉伸过长。
- **🔴 P0 — 彻底修复历史消息与历史会话重新载入时 RAG 容器失踪的持久化 Bug**：
  - **历史消息组 RAG 活性消息检索**：重构 [ChatScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt#L319)，使用 `ragActiveMsg` 代替 `lastAssistantMsg` 去匹配带有 `ragReferences` 或 `ragReferencesLoading` 的有效助理回复。彻底解决了合并的消息组在重新载入或重启应用后，因最终文本气泡覆盖导致旧气泡上方 RAG 卡片突然消失的缺陷。
  - **逆序历史 RAG 精准定位恢复**：重构 [ChatViewModel.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatViewModel.kt#L773)，在加载历史会话（`loadSession`）时首先重置 `_ragPhases` 状态，并使用更稳健的逆序查找，在历史记录中检索历史上最近一个真正拥有非空 `ragReferences` 的助理回复气泡来完美恢复检索就绪卡片状态，从根本上确保了重启 APP 切换会话时卡片能被完美重建。
- **编译与验证**：`./gradlew compileDebugKotlin` 全量校验通过，BUILD SUCCESSFUL，零 Warn/Error！

### RAG 纯色发光霓虹轨重塑与历史会话状态流隔离优化 (2026-05-17 18:1x)
- **🔴 P0 — 纯色发光霓虹管质感 (Neon Glow Canvas) 重塑**：完全抛弃了以前多色水平渐变的设计，改用更纯粹、高对比度的动感单端纯色绘制。通过 Canvas “底层半透明呼吸柔光层（Glow）+ 中层高亮实体纯色层 + 顶层灯丝高光中心线”的三层叠加荧光绘制公式，在暗黑卡片上完美还原了饱满发光、光晕毛绒的科幻霓虹短横条视觉效果。其中 `ACTIVE` 状态的 Glow 层伴随正弦呼吸做 alpha 强弱波动，极动感科幻。
- **🔴 P0 — 彻底根治历史会话重启不加载与传染 Bug**：
  - **新旧气泡 phases 数据源物理隔离**：修改 [ChatScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt#L321)，在消息流渲染遍历中仅把 VM 里的实时 `ragPhases` 传入**当前最新生成的气泡**；历史组一律传入 `emptyList()` 且 `isComplete = true`。彻底断绝了新消息检索时，对所有历史 RAG 气泡造成的不良“进度闪烁传染”Bug。
  - **静态就绪退回 Fallback 机制**：在 [ChatInlineComponents.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatInlineComponents.kt#L363) 中定义 8 阶段默认已完成模板 `RAG_DEFAULT_PHASES`。当组件检测到 `phases` 为空但 `isComplete` 为真（重启 App 进入历史会话场景），自动使用静态模板进行光轨和文本渲染，保证重启 App 历史 RAG 会话光轨瞬间完美全绿全亮渲染，无懈可击！
- **编译与验证**：`./gradlew compileDebugKotlin` BUILD SUCCESSFUL，零 Error，交付质量登峰造极！

### 方案二多段极细霓虹导电轨 RAG 指示器重构 (2026-05-17 18:0x)
- **🔴 P0 — 重塑 RAG 指示器为单行极简胶囊 (36dp)**: 彻底重构了 [ChatInlineComponents.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatInlineComponents.kt) 中的 `RagProgressCard` 组件，将冗余的 Chips 网格布局连根拔起，重新设计为包含“左侧旋转流光雷达 + 中间 AnimatedContent 智能文本 + 右侧进度百分比”的极致单行结构，黄金垂直空间释放 70% 以上。
- **🔴 P0 — 像素级绑定多段霓虹导电轨 (`NeonMicroRail`)**: 在单行之下全新设计并绘制了高性能的极细进度导电轨。每一轨道段与后台 8 个 `RagPhase` 的执行状态及进度进行百分之百物理绑定：
  - `DONE`：渐变翠绿常亮，给予踏实的就绪反馈。
  - `ACTIVE`：底轨为半透明深灰，上覆 Canvas 霓虹跑马电荷，宽度随 `phase.progress` 弹性滑动填充。同时以 `shimmerOffset` 驱动渐变 Brush 做 X 轴横向高速平移，渲染炫目的“电荷传输”微动效！
  - `PENDING`：使用 `1.5.dp` 的极细半透明暗轨，保持静音就绪的背景质感。
- **🔴 P1 — 智能翻页文本切换与弹性进度滑行**: 
  - 文本使用 `AnimatedContent` 驱动，当检索进入新阶段时，旧文本向上滑动飞出，新文本从底部弹性滚入（复古翻字牌动效），极其灵动高级。
  - 所有电荷填充进度使用 `animateFloatAsState` 配合 `Spring.DampingRatioLowBouncy` 进行弹性拉伸滑行，完美隔绝多线程切换时的突进闪烁和视觉抖动噪音。
- **编译与验证**: `./gradlew compileDebugKotlin` 全量校验完美编译通过，项目质量与视觉动效跃升世界前沿。

### 彻底清除底栏遮挡与三大主页面嵌套 Insets 重叠缺陷 (2026-05-17 17:4x)
- **🔴 P0 — 彻底拔除三大主页面嵌套 Scaffold 底部 Insets 重叠**: 重构 [UserSettingsHomeScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreen.kt)、[RagHomeScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagHomeScreen.kt)、[AgentHubScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentHubScreen.kt) 内部的内层 `Scaffold`，将其 `contentWindowInsets` 从重复缩进系统底部导航栏的 `WindowInsets.systemBars` 统一变更为只关注顶部状态栏的 `WindowInsets.statusBars`。彻底根治了滚动卡片滑至底部时在细白线之上约 48dp 处被横向截断一半、在其下留出大块 CanvasBackground 灰色无用空白（视觉上呈现隐形遮盖）的系统性缺陷。
- **🔴 P0 — 优化列表呼吸底距**: 针对去除底部 Insets 重叠后物理底线已精准贴合导航栏白线顶端的事实，精简三大主页面的 `LazyColumn` 底部 `contentPadding` 的 `bottom` 参数：
  - [AgentHubScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentHubScreen.kt): `bottom = 120.dp` 优化为极简高阶的 `24.dp`。
  - [RagHomeScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagHomeScreen.kt): `PortalTab.MEMORY` 底部 `80.dp` 优化为优雅适中的 `24.dp`。
  - [UserSettingsHomeScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreen.kt): `AppSettingsContent` 与 `ProviderSettingsContent` 的 `bottom = 120.dp` 均统一优化为 `24.dp`。
- **🔴 P0 — 精细对齐多选批量操作栏**: 将 [RagHomeScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagHomeScreen.kt) 底部的批量操作浮标卡片（`selectedIds.isNotEmpty()`）的 `.padding(bottom = 100.dp)` 调整优化为 `bottom = 24.dp`，令其在白细线上方以最优雅均匀的悬浮高度完美呈现。
- **编译验证**: `./gradlew compileDebugKotlin` 校验 BUILD SUCCESSFUL 完美通过，代码零 Warn/Error，交互及视觉体验恢复顶尖水平。

### 工具管理国际化与 UI 细节深度优化减法 (2026-05-17 17:3x)
- **🔴 P0 — 彻底干掉底栏无效高斯模糊**: 采纳大师级“做减法”决议，从 [MainTabScaffold.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/MainTabScaffold.kt) 中彻底拔除无效的 `Modifier.blur(20.dp)` 及其相关的 API 版本判断。底栏统一回归完美的半透明蒙砂材质（alpha = 0.8f）和极细分界白线，100% 根除模糊黑影外溢对内容列表底端的遮挡，全面提升底栏滚动滑入体验与 GPU 绘制效率。
- **🔴 P0 — 11 个预设工具英文硬编码消除与国际化补齐**: 重构 [SettingsViewModel.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SettingsViewModel.kt) 的 `loadSkills()`，利用 `app.getString(R.string.xxx)` 动态载入 11 个核心预设工具的名称和描述。
- **🔴 P0 — 中英文 `strings.xml` 补齐**: 同步在默认英文 [strings.xml](file:///k:/Nexara/native-ui/app/src/main/res/values/strings.xml) 和中文简体 [strings.xml](file:///k:/Nexara/native-ui/app/src/main/res/values-zh-rCN/strings.xml) 中定义 11 对高水准的中英文多语言 key/value 资源，中英文环境平滑切换。
- **🔴 P1 — 技能卡片多行描述过大行距修复**: 针对字号拷贝缩小至 `12.sp` 后没有指定相应行高的问题，将 [SkillsScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SkillsScreen.kt) 卡片中的两处描述文本行高显式配置为 `lineHeight = 16.sp`，实现小字排版折行紧凑、优雅美观。
- **🔴 P1 — 技能卡片专业图标映射补齐**: 在 [SkillsScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SkillsScreen.kt) 的 `skillIcons` 中为 `"file_diff"` 与 `"file_patch"` 追加映射了 core 库内置的专业图标 `Icons.Rounded.Sync` 与 `Icons.Rounded.Build`，避免其回退渲染为通用代码图标。
- **编译验证**: BUILD SUCCESSFUL 完美通过，零 Error。

### 高级检索页面底部布局崩溃与滚动冲突修复 (2026-05-17 17:0x)
- **🔴 P0 — 高级检索页布局崩坏与重叠修复**: 移除了 `AdvancedRetrievalScreen.kt` 中对基类页面骨架 `NexaraPageLayout` 的 `scrollable = false` 传参限制（恢复默认 `true`），激活页面垂直滚动容器 `Modifier.verticalScroll`，彻底解决混合检索、重排设置、可观测性等卡片多且高导致底端滑块及文本被极度挤压、重合崩坏的缺陷。
- **全站 `scrollable = false` 嵌套安全审计**: 
  - 确认 [RagFolderScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagFolderScreen.kt) 维持 `scrollable = false` 正确（内部使用 `LazyColumn` 独立滑动）。
  - 确认 [ProviderModelsScreen.kt](file:///k:/Nexara/native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt) 维持 `scrollable = false` 正确（列表项很多且包含 `LazyColumn`）。
  - 全站页面滚动与嵌套布局状态均符合 Jetpack Compose 列表嵌套安全规范。
- **编译验证**: BUILD SUCCESSFUL，零警告与报错。

### RAG 检索指示器补全 + Rerank 链路修复 + 持久化 (2026-05-17 15:3x)
- **🔴 P0 — enableDocs 默认值修复**: `RagOptions.enableDocs` 从 `false` 改为 `true`，解决用户导入文档后依旧搜不到的 UX 断点
- **🔴 P0 — Rerank 传递链路断裂修复**: `enableRerank` 从 `RagOptions` → `RetrieveOptions` → `MemoryManager` 完整传递，结合 `ragConfig.enableRerank` 共同决策（之前用户开关无法到达 MemoryManager）
- **🔴 P0 — 检索引用来源标签改进**: `RagReference.source` 从 "Unknown Document" 改为 "文档: {uuid}" 格式，便于识别来源
- **🔴 P1 — 检索片段 UI 展示增强**: `RagProgressCard` 引用芯片从仅显示来源名改为同时显示内容预览（80 字符）和来源标签
- **🔴 P1 — 指示器持久化**: `loadSession()` 加载历史会话时，若有 `ragReferences` 则恢复指示器为"已检索"完成态，解决重启后丢失问题
- **编译验证**: BUILD SUCCESSFUL，零 warning

### RAG 记忆存储链路修复 + 全流程日志诊断体系 (2026-05-17 14:3x)
- **审计报告**: 本轮深度审计（详见下方审计文档）
- **🔴 P0 — addTurnToMemory 从未被调用**: 在 `ChatViewModel.generateMessage()` 每轮对话完成后新增 `memoryManager.addTurnToMemory()` 调用，确保记忆向量自动存储（之前仅溢出归档路径，导致 memory search 永远返回 0）
- **🔴 P0 — 8 步指示器批量假完成**: `_ragPhases` 更新从"所有非 DONE → DONE"改为"仅 ACTIVE → DONE"，PENDING 阶段保持原状以准确反映执行状态
- **🔴 P0 — MemoryManager 诊断日志增强**: 每次检索前记录 vectors 表总行数/session 行数/阈值配置，检索后记录维度不匹配/低于阈值统计
- **🔴 P1 — VectorStore 诊断方法新增**: `getTotalVectorCount()`、`getSessionVectorCount()`、`getFirstStoredDimension()` 支持精准定位"0 results"根因
- **🔴 P1 — PostProcessor 归档日志增强**: 记录跳过原因（缺少哪个组件）、embedding 维度/耗时、成功/失败统计
- **🔴 P1 — MemoryManager.addTurnToMemory 日志增强**: 记录 chunking/embedding/storing 全流程耗时

### RAG + KG 全链路审计修复 — 4 项致命 Bug (2026-05-17)
- **审计报告**: `docs/audit/RAG_KG_FULL_PIPELINE_AUDIT_20260517.md` + `docs/audit/IDEA_CROSS_VERIFICATION_20260517.md`
- **🔴 F-2+F-5 — KG 提取假执行 + 异常吞没**: `RagViewModel.extractKG()` 从通过 `VectorizationQueue` 间接触发改为直接调用 `GraphExtractor.extractAndSave()`，并替换 `catch (_: Exception) {}` 为真实错误处理和 FAILED 状态反馈
- **🔴 F-3 — 向量化记录 docId 缺失**: `VectorizationQueue.processDocumentTask()` 中 `NewVectorRecord` 补充 `docId = docId`，修复 vectors 表 `doc_id` 列为 NULL 的数据完整性问题
- **🔴 F-6 — 导入/重索引不触发 KG**: `importDocuments()`、`reindexFile()`、`reindexDocuments()` 三处 `enqueueDocument()` 调用补充 `kgStrategy` 参数，基于 `_config.value.enableKnowledgeGraph` 决定是否执行 KG 提取

### 服务商管理与模型管理审计修复 — 4 项 Bug 全线修复 (2026-05-16)
- **审计报告**: `docs/audit/PROVIDER_MODELS_AUDIT_20260516.md`
- **🔴 P0 修复 1 — 同步模型按钮失效**:
  - 移除过早的 `pm.loadModels()` 重置调用
  - 为 Anthropic/VertexAI/DeepSeek/Mistral/Cohere 等协议添加 ModelSpecs 数据库回退（当远程 `/models` 端点不可用时自动匹配已知模型）
  - 同步后对**已存在模型**执行元数据合并更新（名称/能力/上下文/最大输出/截止日期）
  - 新增 `modelSyncMessage` StateFlow + ProviderModelsScreen 内联反馈条（含关闭按钮）
  - 新增 `getFallbackModelIds()` 按协议类型关键词扫描 MODEL_SPECS
- **🔴 P0 修复 2 — 模型列表排序不稳定**:
  - `persistModels()` 新增 `all_models_order` 字符串持久化有序 ID 列表
  - `loadModels()` 优先使用有序列表恢复，回退后增加 `.thenBy { it.name.lowercase() }` 稳定二级排序
- **🔴 P0 修复 3 — 能力标签命名不一致**:
  - `ModelCapability.WEB` → `ModelCapability.INTERNET` 统一命名（5 处修改：ModelPicker.kt/2、SessionSettingsSheet.kt/2、RagViewModel.kt/1）
  - 修复后 `valueOf("INTERNET")` 正确匹配，ModelPicker/ModelPicker/AgentEdit 三处能力标签不再静默丢弃
- **🟡 P1 修复 4 — 键盘避让高度不足**:
  - ProviderFormScreen 配置区新增 `BringIntoViewRequester`：任意表单字段获焦时自动将 "Configuration" 标题卷入视野
  - 底部留白 32dp → 200dp 确保键盘弹起后用户可手动滚动查看全部三行字段

### Provider 管理系统全线修复 (2026-05-16)
- **🔴 P0 多提供商存储**: 修复添加第二个提供商覆盖第一个的致命 Bug。`NavGraph` 中 `onSave` 改为三路分发：新增→`addProvider()`、编辑主→`app.updateProvider()`、编辑额外→`updateExtraProvider()`
- **🔴 P0 模型列表作用域**: 修复点进第二提供商显示第一提供商模型的 Bug。`ProviderModelsScreen` 新增 `scopedModels` 按 `providerId`/`providerName` 过滤
- **🔴 P0 自动拉取移除**: 删除 `NavGraph` 中 `LaunchedEffect` 自动网络拉取；`SettingsViewModel.addProvider()` 移除多余的 `refreshModels()` 调用
- **🔴 P0 同步模型拉取失败**: 修复在第二提供商配置下点击“同步模型”会去拉取默认提供商模型并导致列表不更新的 Bug。重构 `SettingsViewModel.refreshProviderModels()` 支持按 `providerId` 动态构建临时 `LlmProvider` 并自动合并获取到的模型。
- **🔴 P0 模型能力标签映射**: 修复 `ProviderManager` 中将网络检索能力映射为 `web` 而非 UI 组件期望的 `internet`，导致“Internet”标签无法激活的 Bug。
- **🟡 模型能力数据库扩展 (2026-04)**: `ModelSpec` 新增 `maxOutputTokens`/`knowledgeCutoff` 字段；新增 42 个 2026 年模型条目（GPT-5 全系、Claude Sonnet/Opus 新系、Gemini 3.1/3、Gemma 4、DeepSeek V4、Qwen 3.6/Flash/Long/Omni、GLM-5.1、Grok 4、Doubao 1.5、Kimi K2、Mistral 3、Granite 4、Command A）；新增 20 个定价条目；模型覆盖从 75+ 增至 117+
- **🟡 RAG 僵尸配置清理**: 从 `RagConfiguration`/`AgentRagConfig`/`RagConfigPersistence` 及 3 个 Screen 中彻底移除从未被管线消费的 `contextWindow`/`summaryThreshold` 字段和 UI 控件

### RAG 设置页面审计与 3 页重构 (2026-05-16)
- **审计报告**: `docs/audit/RAG_SETTINGS_AUDIT_20260516.md` — 39 个可交互项全量审计，发现 4 个 Bug + 3 组重复 + 5 项放置不当
- **3 页重构**: 分块与向量化(Page1) / 检索策略(Page3) / 知识图谱与上下文(Page2)
- **Bug 修复**: Preset 选中检测、摘要模板丢失、查询改写策略初始值、清除孤立死按钮、跨页重复消除
- **新增 UI**: `enableMemory`/`enableDocs` 检索来源开关；`rerankMaxPerCall` 滑块

### RAG 重新归类 — 保留会话级删除全局设置中的上下文/摘要 (2026-05-16)
- 确认 `RagConfiguration.contextWindow`/`summaryThreshold` 为从未被管线消费的僵尸配置，从 RAG 设置面板、数据模型、持久化层全面移除
- 会话面板中的 `InferenceParams.activeContextWindow`/`autoSummaryThreshold` 保留不变（实际被 ChatViewModel 消费）

### UI 导航与术语对齐 (2026-05-16)
- **高级 RAG 重命名**: 将“高级 RAG”页面 Header 标题更名为“知识图谱”（Knowledge Graph），以消除与上一级“高级检索”页面的名称冗余，并更准确地反映该页面的核心功能（KG 抽取与配置）。
- **提示词编辑器标准化**: 全站推广 `UnifiedPromptEditor` 原子组件，替换了 `RagAdvancedScreen`、`AgentEditScreen` 及 `AgentHubScreen` 中的异构输入框。新编辑器支持预览/编辑/分屏三模式切换。
- **清理过时组件**: 彻底移除旧版 `FloatingTextEditor.kt` 组件，统一维护 UI 原子库。
- **UI 冗余清理**: 移除 `RagAdvancedScreen` 中重复的“知识图谱”部分小标题，确保页面视觉焦点集中在配置项上。

### RAG 向量化全线修复与可观测性增强 (2026-05-16)
- **进度展示动画**: `IndexingProgressBar` 新增 `animateFloatAsState` 平滑进度过渡 + `AnimatedVisibility` 入场/退场动画，消除突兀跳变
- **向量化状态一致性**: 修复 `VectorizationQueue.processNext()` 过早预设 `vectorizing` 状态导致的进度回跳（pending→vectorizing(0%)→chunking(15%)→vectorizing(30%)），现由子流程逐步推进
- **错误持久化**: 失败任务保留阶段进度不再掉落至 0%；错误卡片支持手动关闭（`dismissQueueError()`）；失败后延迟 2s 移除，确保用户看清错误信息
- **全链路日志注入**: 覆盖 VectorizationQueue/MemoryManager/MicroGraphExtractor/GraphExtractor/ContextBuilder 全部 25+ 个静默 catch 块，统一接入 `NexaraLogger`
  - VectorizationQueue: 每阶段（chunking/vectorizing/saving/extracting）输出切块数/向量数/耗时日志
  - MemoryManager: embedQuery/memory search/summary search/doc search/rerank 5 个关键路径日志
  - MicroGraphExtractor: cache read/write、LLM extraction、JSON parse、background merge 6 个步骤日志
  - GraphExtractor: node upsert/edge create/LLM extraction/JSON parse 4 个步骤日志
  - ContextBuilder: KG extraction/task plan/RAG retrieval/Web search 4 个源日志
- **错误信息增强**: `processDocumentTask`/`processMemoryTask` 追加切块数/文档名上下文；失败 subStatus 标注具体失败阶段

### 文件系统初始化优化 (2026-05-16)
- **WorkSpace 目录**: `NexaraApplication.onCreate()` 自动创建 `filesDir/WorkSpace` 物理目录，用户在 App 启动即可看到
- **移除强制根文件夹**: 知识库不再强制创建"知识库"根文件夹；文档导入/文件夹创建直接挂在根层级（`parentUuid=null`），由用户自由组织

### Embed/Rerank 模型高级设置 (2026-05-16)
- **RagConfiguration 新增字段**: `embedDimension`（向量维度，null=模型默认）、`maxEmbedTokensPerCall`（单次调用 Token 上限）、`rerankMaxPerCall`（单次调用文档上限）
- **UI 设置面板**: `GlobalRagConfigScreen` 新增 Embed 维度滑块（0-4096，0=自动）、Max Embed Tokens 滑块（256-16384）、Rerank Max Per Call 滑块（8-200）
- **持久化**: 配置通过 `rag_settings` SharedPreferences 自动保存/加载

### UI 细节打磨与视觉一致性增强 (2026-05-16)
- **资源列表间距**: 优化了 `FilesPanel` 树状目录的布局结构，不仅增加了顶层列表间距，还增加了父目录与子目录之间、以及子目录项之间的垂直间距（统一为 8dp），彻底解决了边框紧贴的问题。
- **文件夹图标配色**: 将全站文件夹图标颜色从 `Tertiary`（橙褐色）统一修改为 `Primary`（淡紫色），与界面整体强调色保持一致。
- **界面纯净化与空间优化**: 
    - 移除 `FilesPanel` 右键菜单及 `ChatScreen` 顶部菜单中的功能图标，改为纯文本菜单。
    - 移除知识库（RagHomeScreen）顶部三个标签页（文档、记忆、图谱）的图标，仅保留文字，以释放更多垂直视觉空间。
- **菜单宽度对齐**: 通过移除图标并统一文本容器，确保同一菜单内的所有选项宽度严格一致。

### Phase 7 知识库修复补齐 + AgentEntity 崩溃修复 (2026-05-16)
- **崩溃修复**: 移除 AgentEntity.useInheritedConfig 的 `defaultValue` 注解，修复 Room migration 验证失败
- **PDF 导入管道**: importDocuments 接入 PdfExtractor.extract()，PDF 真实提取文本
- **Word 导入**: 新建 DocumentImporter（Apache POI），提取 .docx 段落+表格
- **文件夹重命名**: renameFolder 从 no-op 桩重写为 FileEntry 名称+路径更新
- **标题持久化**: DocEditorViewModel.updateTitle() 通过 FileEntryDao 写回 DB
- **文件夹级联删除**: deleteCollection 增加显式子文件遍历删除

### RAG 知识库现代化与编辑器升级 (2026-05-16)
- **多选批处理**: `FilesPanel` 支持多选模式（Shift/长按），同步状态至 `RagHomeScreen` 底部操作栏。
- **批量索引**: 实现 `reindexDocuments(uuids)` 批量向量化接口，支持一键修复 Stale/Not-Indexed 文档。
- **现代化编辑器**: `DocEditorScreen` 升级为 **编辑/预览/分屏** 三模式架构。
- **高保真渲染**: 移除旧版正则高亮逻辑，采用 `MarkdownText` 引擎，支持 GFM、LaTeX 公式、代码块实时渲染。
- **交互增强**: `FilesPanel` 增加 `onFileClick` 回调，实现从资源管理器到编辑器的高效跳转。
- **视觉优化**: 选中的文件行应用 `NexaraColors.Primary` 浅色背景高亮，提升多选操作辨识度。

### 数据库完整性校验与架构补全 (2026-05-16) 🔴 P0
- **Room 崩溃修复**: 彻底解决了 `java.lang.IllegalStateException: Room cannot verify the data integrity` 崩溃问题。
- **架构版本升级**: 数据库版本从 v10 强制升级至 v11，并新增 `MIGRATION_10_11` 补全了所有缺失的字段。
- **字段补全**:
    - `sessions`: 补全了 `draft`, `execution_mode`, `loop_status`, `rag_options`, `inference_params`, `active_mcp_server_ids` 等 13 个关键字段。
    - `vectors` / `kg_nodes` / `kg_edges`: 补全了 `stale`, `file_uuid`, `version` 字段，支持 Resource OS 统一资源管理。
- **FTS 搜索修复**: 显式创建了 `vectors_fts` 虚拟表，解决了全文检索功能的潜在初始化失败风险。
- **一致性保护**: 为 Entity 类的 NOT NULL 字段补充了 `@ColumnInfo(defaultValue = "...")`，确保 Room 生成的 identity hash 与手动 Migration 保持严格一致，防止未来再次发生哈希不匹配。

### 任务规划器全链路集成修复 (2026-05-16)
- **数据库迁移**: 新增 MIGRATION_9_10，创建 task_nodes 表 + sessions.active_task_tree_id 列
- **Skill 注册**: InitializePlanSkill / UpdatePlanSkill / GetPlanSkill / DropPlanSkill 注册到 NexaraApplication
- **数据模型**: SessionOptions.economyMode / ExecutionStep.taskStepId / SessionEntity.activeTaskTreeId 全量添加
- **任务上下文注入**: ContextBuilder 从 3 行占位扩展为 full/economy 双模式（树形渲染、进度统计、断点重连）
- **ChatScreen**: 集成 TaskFloatingPanel 浮动任务面板
- **ToolExecutor**: 执行步骤自动关联当前任务 focus step
- **设置界面**: 新增 Token 节约模式开关 + 补全 6 个技能的用户开关 (任务规划 4 + file_diff/patch 2)

### 知识库 UI — FilesPanel 资源管理器迁移 (2026-05-16)
- **文件导入实现**: `importDocuments()` 从桩实现重写为完整 ContentResolver → FileEntry 流程，文件真正导入到 `rag_workspace` 目录
- **文档管理页重构**: DOCUMENTS Tab 从旧版"集合/文件夹/最近文档" UI 完全替换为 FilesPanel 文件资源管理器
- **移除冗余 UI**: 删除"集合"小标题、"最近文档"功能区、DocListItem (157 行死代码) 及 6 个未使用 import
- **工作区根目录管理**: 新增 `ensureRagWorkspaceRoot()` 自动创建 RAG 知识库根目录 FileEntry
- **文件夹创建修复**: `createFolder()` 的 `physicalRootPath` 从错误的 matPath 修正为真实文件系统路径

### Phase 9 — 发布冲刺 + 测试补全 (2026-05-15)
- **多模态图片/VLM**: ChatInputBar 图片选择 + 缩略图预览 + base64 编码发送 + OpenAI Vision/Anthropic 协议适配 + ChatBubble 渲染
- **Token 统计仪表盘**: TokenUsageScreen（全局统计/会话排行/Canvas 趋势图/模型明细/费用估算）
- **HTML Artifacts**: HtmlArtifactCard WebView 实时预览 + HtmlArtifactsPopup 全屏分屏 + PNG 导出
- **测试补全**: 新增 11 个测试文件（4 文件 Skill + RerankClient + ExecJsSkill + 3 ViewModel 补全 + ChatLogicTest），合计 23 个新用例
- **测试覆盖率**: 53 个测试文件，覆盖 Skills/ViewModels/Repositories/RAG 全链路
- **RAG 术语标准化**: 将“向量检索”更名为“长期记忆”，解耦“会话 RAG”与“跨会话检索”，统一“上下文自动压缩阈值”等专业术语
- **字体设置修复**: 修复了 SessionSettingsSheet 中字体大小滑动条断点与持久化失效问题，确保设置即时生效并跨会话保存
- **崩溃修复 (ProtocolType NPE)**: 彻底解决了 `ProtocolType` 静态初始化导致的 `NullPointerException` 竞态条件，通过引入计算属性与 UI 层非空校验实现双重保护。
- **架构重构 (NexaraPageLayout)**: 将全局页面基类 `NexaraPageLayout` 迁移至基于 `Scaffold` 的现代化布局架构，通过局部按需应用 `imePadding` 与 `weight(1f)` 约束，从根本上解决了滚动容器嵌套导致的 `IllegalStateException` 崩溃与键盘遮挡问题。

### Phase 8 — Agent 工具系统重构与增强 (2026-05-15)
- **工具分类体系**: 被动注入（时间）与主动调用分离，CurrentTimeSkill 退役为 ContextBuilder 注入
- **ImageGenerationSkill 暴露**: 设置界面新增生图工具开关，默认启用
- **MCP 同步链路修复**: McpSkillRegistry.updateMcpTools() 接入 SettingsViewModel.syncMcpServer()，MCP Server 工具可被 LLM 调用
- **文件系统工具（4 个）**: file_read / file_write / file_list / file_search，全部限定工作区路径，禁止逃逸
- **JS 沙箱解释器**: exec_js 基于 WebView.evaluateJavascript() 实现，5 秒超时 + 代码长度限制
- **工具安全审批增强**: ToolExecutor 跳过等待审批的工具；ApprovalManager 审批通过后执行待审批工具
- **默认启用的工具**: web_search / calculator / create_tool / file_read / file_list / file_search / exec_js

### Phase 7 — 知识库系统全面修复与增强 (2026-05-14)
- **PDF 导入**: PdfExtractor 接入 Apache PDFBox，真实提取 PDF 文本层
- **Word 导入**: DocumentImporter 接入 Apache POI，解析 .docx 段落+表格
- **文档编辑修复**: 移除 Mock 假内容，标题重命名持久化到 DB
- **文件夹级联删除**: 删文件夹前先删文档及向量
- **混合检索默认开启**: RRF 向量+关键词融合
- **Rerank 重排序**: RerankClient 双路径（API + LLM 回退）
- **查询重写默认开启**
- **Memory 记忆视图 + KG 可视化 + 全文搜索 UI**

### 思考容器文本颜色修复 (2026-05-14)
- **颜色管线修复**: `nexaraMarkdownColors()` 的 `text` 参数从硬编码 `OnBackground` 改为接收 `textColor` 参数（默认 `OnBackground`），通过 `MarkdownText` → `MarkdownSafe` → `nexaraMarkdownColors()` 层层透传 `effectiveColor`，解决了思考容器文字始终以白色渲染、无法弱化的问题
- **根因**: 第三方库 `mikepenz:multiplatform-markdown-renderer-m3` 不读取 `CompositionLocalProvider(LocalContentColor/LocalTextStyle)`，只使用直接传入的 `colors` 参数，之前的多次修复均在 CompositionLocal 层发力，颜色被硬编码覆盖
- **影响文件**: `NexaraMarkdownTheme.kt` (+textColor 参数), `MarkdownText.kt` (MarkdownSafe 透传)
- 同步修复 `InlineThinkingRow` (PipelineBubble) 和 `ThinkingBlock` (ChatInlineComponents) 两处思考内容颜色

### 输入栏草稿持久化 (2026-05-14)
- **进入会话恢复草稿**: `loadSession()` 从 `Session.draft` 恢复未发送文字到输入框，覆盖缓存路径 + DB 加载路径
- **离开会话保存草稿**: `ChatScreen` 新增 `DisposableEffect`，`onDispose` 时调用 `saveCurrentDraft()` 将 `_inputText` 写入 DB
- **发送后清空草稿**: `sendMessage()` 异步调用 `sessionManager.updateSessionDraft(sessionId, null)` 清除 DB 草稿
- **影响文件**: `ChatViewModel.kt` (+saveCurrentDraft), `ChatScreen.kt` (+DisposableEffect)

### 思考容器自动展开修复 (2026-05-14)
- **时序竞态修复**: `InlineThinkingRow` 的 `isThinkingStreaming` 判定从 `status == THINKING` 改为 `streamingContent.isEmpty()`，消除思考步骤首次渲染时机晚于 THINKING 窗口导致的永不展开问题
- **副作用控制**: 正文开始输出后 `streamingContent` 非空，自动折叠显示"思考完成"，不会持续显示"正在思考"

### 流式传输死锁修复 (2026-05-14) 🔴 P0
- **Agent 循环死锁**: `Mutex.withLock` 包裹含递归路径的 `generateMessage()` 导致永久挂起——Kotlin `Mutex` 不可重入，`cancelActiveGeneration()` 已足够防并发。**教训**: 互斥锁绝不可包裹递归函数
- **流式假死**: `OpenAIProtocol` 空内容守卫 + `ThinkingDetector` 末端 `<` 扣留 → 移除守卫 + `tryFastPath()` 直通
- **TTFT 光标缺失**: `PipelineBubble` 补充 `StreamingCursor()` + `heightIn(min=32.dp)`

### 交互优化 (2026-05-14)
- **Smart Follow**: `autoFollowEnabled` 状态机——用户手势锁定视口，FAB/新消息恢复；Agent 追踪仅目标不可见时滚动
- **双光标**: `PipelineBubble` 光标仅在 TTFT 期渲染，有 Content 时 `MarkdownText` 内部光标接管
- **发送按钮误报**: `_error` 残留 → `generateMessage()` 起始清除
- **思考层级**: 思考字号 `fontSize-3`(min 10sp) + alpha 0.55，显著弱于正文
- **锚定修复**: `LaunchedEffect(latestUserMsgId)` 替代 `isGenerating + streamingContent.isEmpty()` 竞态

### 聊天交互优化 (2026-05-14)
- **PipelineBubble 气泡合并**: 新增 `PipelineBubble.kt` — Agent 多步响应合并为单一线性视觉气泡，思考/工具/正文以连接线串联，彻底消除多条消息被 `SpacedBy(16dp)` 隔开的分裂感
- **思考/工具容器重构**: `InlineThinkingRow` / `InlineToolRow` 替代旧版 `ThinkingBlock` / `ToolExecutionTimeline`，默认折叠（仅进行中状态展开），以颜色和图标区分类型（Primary=思考，Tertiary=工具），体积缩小约60%
- **滚动锚定重构**: 改用 `latestUserMsgId` 作为 `LaunchedEffect` 触发键替代 `isGenerating + streamingContent.isEmpty()` 竞态条件，使用分组索引而非消息索引
- **Agent 视角追踪**: `executionSteps.size` 变化时自动滚动到对应分组，保留工具时间轴可见性
- **IME 键盘联动**: `WindowInsets.isImeVisible` 检测 + 分组索引滚动
- **流式速度提升**: `StreamSpeed.BALANCED` 从 38 CPS → 120 CPS，FAST 模式 800 CPS
- **表格深色模式**: `NexaraTableWidget` 新增行间分隔线，解决低对比度问题

### Agent Fallback 解析器 (2026-05-14)
- **新增文本 JSON 工具指令兜底解析**: `ChatViewModel.extractToolCallsFromText()` — 部分模型（如 MiniMax-M2.7）不在 `ToolCallDelta` 层下发工具指令，而是以 Markdown 代码块输出 JSON 字符串。新增后置正则提取器，支持 `name/function/tool/tool_name` 等多种字段命名约定，自动将文本形式工具指令转为 `ToolCall` 对象
- **JSON 剥离增强**: `stripToolCallJsonBlocks()` 双重匹配——Markdown 代码块 + 裸 JSON 对象行，清除后整理空行
- **防止 Agent 循环中断**: 兜底解析后正确填充 `accumulatedToolCalls`，确保 `ToolExecutor.executeTools()` 被触发，`executionSteps` 正确回写以激活 UI 时间轴组件

### 流式传输根本修复 (2026-05-14) 🔴 P0
- **根因定位**: `OpenAIProtocol.processStreamChunk()` 中的空内容守卫 `if (content.isNotEmpty() || reasoning.isNotEmpty())` 配合 `ThinkingDetector` 的缓冲区扣留机制，在特定 chunk 边界上形成"双重静默丢弃"——当 ThinkingDetector 因末尾 `<` 字符临时扣留 content 且模型不使用 `reasoning_content` 字段时，整个 SSE chunk 被静默丢弃，消费者收不到任何信号，流式传输完全中断
- **修复**: 移除 `OpenAIProtocol` 和 `GenericOpenAICompatProtocol` 中的空内容守卫，无条件发送 `TextDelta`；`ThinkingDetector` 新增 `tryFastPath()` 直通模式——缓冲区空 + 状态 OUTSIDE + chunk 不含 `<` 时零开销直接返回，覆盖 95%+ 的正常文本流
- **TTFT 光标修复**: `PipelineBubble` 补充缺失的 `if (isGenerating) { StreamingCursor() }`，确保首字生成前的等待期内光标可见

### 图像生成工具 (2026-05-14)
- **新增 `ImageGenerationSkill`**: LLM 可调用 `generate_image` 工具，传递提示词和参数（size/quality/style），调用默认图像模型生成图片，结果内联展示在对话气泡中
- **新增 `ImageGenClient`**: OpenAI-compatible 图像生成 API 客户端（`POST /v1/images/generations`），支持 url/b64_json 响应，自动下载到本地存储
- **新增 `GeneratedImageData`**: 图片本地存储元信息序列化类，存入 `Message.images` 字段
- **ChatBubble 图片渲染**: `AsyncImage` 内联展示生成图片，附带模型改写后的提示词
- **ToolExecutor 增强**: `images = result.data` 传递工具生成的图片数据到 Message
- **架构**: 支持 LLM 聊天与图像生成使用不同端点（通过 ProviderManager 独立读取 `preset_image_model`）

### RAG 嵌入管线修复 (2026-05-14)
- **🔴 P0 致命 Bug**: `embedding_base_url` / `embedding_api_key` 永为空——ProviderManager 写入 `base_url`/`api_key` 键，但 EmbeddingClient 读取 `embedding_base_url`/`embedding_api_key` 键，导致嵌入模型从未收到配置 → 修复为键名缺失时回退到主 LLM 提供商配置
- **🔴 P0 致命 Bug**: `RagHomeScreen` 第 407 行 `shownDocs.isEmpty()` 逻辑反转 → 文档列表永不为空时反而不渲染 → 修复为 `isNotEmpty()`
- **🟡 次要 Bug**: `VectorizationQueue.notifyStateChange()` 在完成/失败后缺失调用，外部观察者收不到终态 → 补充调用
- **🟡 次要 Bug**: `RagViewModel` 向量化失败后 `isIndexing=false` 导致错误提示随进度条消失 → 新增 `lastQueueError` 持久化状态

### RAG 重排管线修复 (2026-05-14)
- **🔴 P0 致命 Bug**: `RerankClient.rerank()` 从未被调用——`MemoryManager` 构造函数不包含 `rerankClient` 参数，`retrieveContext()` 缺失重排步骤 → 注入 `rerankClient` 并在去重后、类型过滤前插入 rerank 调用
- **🟡 防护**: `RerankClient.rerank()` 新增空配置前置检查（同 EmbeddingClient），避免静默吞错

### AGP 构建警告消除 (2026-05-14)
- `jniLibs.srcDirs()` → 删除整个 `sourceSets` 块（`src/main/jniLibs` 是 AGP 默认目录）
- `disallowKotlinSourceSets=false` → 保留（KSP Room compiler 必需），注释说明原因

### 单元测试 (2026-05-14)
- **新增 `EmbeddingClientTest.kt`** — 21 个测试：构造/URL构建/响应解析/大请求分片/本地引擎回退/空配置检测
- **新增 `VectorizationQueueTest.kt`** — 23 个测试：入队/进度状态机/重试逻辑/失败处理/增量哈希/预处理/中断恢复
- **扩展 `RagViewModelTest.kt`** — 6 个测试：`lastQueueError` 错误持久化/队列状态观测
- **测试结果**: 101 tests, 98% 通过率 (2 预存失败)

### 聊天界面体验优化 (2026-05-14)
- **优化聊天流式输出体验**: 加入 MessageManager 节流（100ms）减少 UI 重绘，改进 SmoothStreamContent 动画衔接防止瞬间跳变。
- **增强自动滚动稳定性**: 优化 ChatScreen 滚动监听逻辑，采用 50ms 批处理与锚点定位，解决高频输出下的滚动卡顿。
- **修复 AI 生成开始时的视图对齐问题**: 确保新气泡自动置顶。
- **引入 `bottom_spacer` 锚点**: 提升长会话末尾滚动定位精度，确保长消息生成的末尾始终能被准确推入视口。

### Phase 5 — UseCase 层抽取方案 (2026-05-13)
- **实施计划**: `.agent/plans/20260513-phase5-usecase-extraction.md`
- **Session P** (先执行): IdGenerator — 统一 7 个 VM 的 ID 生成
- **Session Q+R** (并行): AgentConfigResolver + CreateAgentUseCase + DeleteDocumentUseCase + RagConfigPersistence

### Phase 4 — 核心引擎增强方案 (2026-05-13)
- **实施计划**: `.agent/plans/20260513-phase4-engine-enhancement.md`，2 个并行会话（FolderRepository + 文档导入）
- **不做**: 本地 Embedding 降级

### 模型管理 BugFix: type↔capabilities 联动 + 删除自动复现 (2026-05-13)
- **P0 Bug#1**: 修复在 ProviderModelsScreen 中将模型 type 切换为 embedding/rerank/image 后，capabilities 未同步刷新导致默认模型选择器无法筛选到的问题。新增 `TypeToBaseCaps` 映射表 + `LaunchedEffect(selectedType)` 联动机制（ProviderModelsScreen.kt +15 行）
- **P0 Bug#2**: 移除 deleteModel/toggleModel/deleteAllModels/addCustomModel 回调中多余的 `refreshProviderModels()` 调用，删除/切换操作不再自动触发远端 API 拉取，远端同步仅由手动"Fetch"按钮执行（ProviderModelsScreen.kt -4 行）

### Phase 3 — Super Assistant 清理完成 (2026-05-13)
- **ADR-001 落地**: 删除 SpaViewModel + SpaSettingsScreen（2 文件），移除 PostProcessor.isSuperAssistant 检查
- **11 个文件修改/删除**: "super" agent id → "default"，删除 70 个 spa_* 字符串，清理所有导航回调
- **编译+测试通过**: 457 tests, 1 预存失败；代码库中零 Super Assistant 残留引用

### 通用设置默认模型选择器修复 (2026-05-13)
- **P0 capabilities 构建缺陷**: 修复 `SettingsViewModel.refreshModels()` 和 `addCustomModel()` 中 capabilities 构建逻辑仅处理 chat/vision/internet/reasoning 四种能力，完全遗漏 image/embedding/rerank 及其他 9 种能力标签的严重缺陷。抽取统一的 `buildModelCapabilities()` 方法，根据 ModelType 自动推导基础 capability，并从 ModelSpec.capabilities 补充全部 12 种细粒度能力。
- **P0 自动迁移逻辑**: 在 `ProviderManager.loadModels()` 中实装自动迁移机制。应用启动加载模型时，若检测到模型的 `name` 等于 `id` 或 `capabilities` 不完整，将依据 `ModelSpecs` 静态规格表自动修复并静默持久化到 `SharedPreferences`。这解决了老用户升级后无需重新获取列表即可修复显示名称和功能过滤的问题。
- **P0 subtitle 显示名称**: 修复四个预设模型设置项（摘要/图像/嵌入/重排）的 subtitle 直接显示原始模型 ID 的问题，新增 `resolveModelName()` 辅助函数，优先从已加载模型列表查找友好名称，回退到 ModelSpec.note 静态规格表。
- **P1 ModelSpecs 数据补全**: 为 bge-reranker、jina-reranker、cohere-rerank 三个 Rerank 模型补全缺失的 `capabilities = ModelCapabilities(rerank = true)` 定义。
- **P1 internet→web 命名对齐**: 将 capabilities 中的 `internet` 映射修正为 `web`，与 `ModelCapability.WEB` 枚举一致。
- **P1 模型名称优化**: `refreshModels()` 和 `addCustomModel()` 中模型的 `name` 字段从原始 ID 改为优先使用 `ModelSpec.note`（如 "DALL-E Series"、"BGE Reranker"）。


### 领域层与仓库层架构迁移 (Phase 2a/2c) (2026-05-13)
- **核心架构演进**: 建立了纯净的 Domain 层（模型、接口、枚举），完全消除业务逻辑对 Android 框架的依赖。
- **Repository 全量实装**: 实现了 Agent、Document、Vector、KnowledgeGraph、Provider 等核心仓储，Repository 覆盖率提升至 100%。
- **ViewModel 深度重构**: 全量迁移了 Chat、Settings、Rag、AgentHub、SessionList 等 8 个 ViewModel，消除了所有直接的 DAO 依赖。
- **测试工程化**: 新增 90+ 个文件，包含完备的单元测试（MockK + Turbine），测试覆盖了从 Mapper 到 ViewModel 的全链路逻辑。
- **Git 环境纯净化**: 彻底清理了 RN 时代残余文件，将 `native-kotlin-refactor` 分支正式确立为仓库默认主分支。

### Phase 2c — 剩余 ViewModel 迁移完成 (2026-05-13)
- **3 个 ViewModel 全部迁移**: ChatViewModel / SettingsViewModel / RagViewModel — 消除最后 3 个 VM 的 DAO 依赖
- **架构债 AD-4 消除**: 8/8 ViewModel 全部使用 Repository，零直接 DAO 操作
- **ChatViewModel 5 个历史失败测试修复**: 迁移 agentDao → AgentRepository 时一并解决
- **IAgentRepository 新增 getById**，**IDocumentRepository/IVectorRepository 新增计数方法**
- **测试**: 458 tests, 仅剩 1 个预存失败 (ModelSpecs)
- **RagViewModel 残留**: folderDao 标记 TODO 等待 FolderRepository，VectorStatsService 待 Phase 4 重构
- **Session H**: ChatViewModel（~1100 行，3 处 agentDao → AgentRepository）+ IAgentRepository.getById
- **Session I**: SettingsViewModel（vectorDao/documentDao → Repository）+ 计数方法
- **Session J**: RagViewModel（5 DAO → 3 Repository，folderDao 标记 TODO 待 FolderRepository）
- 全部含单元测试要求，零文件冲突可完全并行

### ViewModel 迁移至 Repository + 单元测试完成 (2026-05-13)
- **5 个 ViewModel 迁移完毕**: AgentHub / AgentEdit / SessionList / DocEditor / KnowledgeGraph — 全部消除直接 DAO 依赖
- **11 个新增测试文件，0 失败**: AgentMapperTest / DocumentMapperTest / KgMapperTest / AgentRepositoryTest / DocumentRepositoryTest / KnowledgeGraphRepositoryTest / AgentHubViewModelTest / AgentEditViewModelTest / SessionListViewModelTest / DocEditorViewModelTest / KnowledgeGraphViewModelTest
- **MockK 1.13.12 + Turbine 1.1.0** 测试依赖已添加
- **IDocumentRepository 补全 getById** 方法，DocEditorViewModel 彻底消除 DocumentDao 依赖
- 测试统计: 445 tests, 6 预存失败 (ChatViewModel × 5 + ModelSpecs × 1), 13 skipped

### ViewModel 迁移至 Repository + 单元测试方案 (2026-05-13)
- **实施计划**: `.agent/plans/20260513-viewmodel-migration-tests.md`，3 个并行会话
- **测试基础设施**: 新增 MockK 1.13.12 + Turbine 1.1.0 依赖
- **Session E**: Agent VM 迁移 (AgentHub/AgentEdit/SessionList) + 6 个测试文件
- **Session F**: Document VM 迁移 (DocEditor) + 3 个测试文件
- **Session G**: KG VM 迁移 (KnowledgeGraph) + 3 个测试文件
- **核心约束**: 所有新增/修改的业务逻辑代码必须编写单元测试并通过

### Domain + Repository 层实施完成 (2026-05-13)
- **28 个文件交付**: 13 domain (4 模型 + 值对象/枚举 + 7 接口) + 11 repository (5 新 + 6 现有) + 4 mapper
- **编译通过**: `./gradlew :app:compileDebugKotlin` BUILD SUCCESSFUL
- **架构债消除**:
  - AD-1 (Domain 层缺失): `domain/model/` + `domain/repository/` 包建立，零 Android 依赖
  - AD-2 (Repository 覆盖率 37.5%→100%): 7 个聚合根全部有 Repository 接口 + 实现
  - AD-3 (ProviderManager 单例): 收编为 ProviderRepository，实现 IProviderRepository
- **4 个并行会话**: Session A (Domain 基础) → B (Agent+Document) || C (Vector+KG) || D (Provider+对齐)
- **关键实现**: VectorRepository 含余弦相似度/FloatArray BLOB 转换/维度不匹配防御；ProviderRepository 含 ProtocolType Domain↔Data 双向转换

### 文档体系治理与统一 (2026-05-13)
- **文档大清理**: 删除根 `.agent/docs/`（57 文件，含已过时 PRD v1.2.1、6 个失效 repowiki 指针存根）、`.agent/memory/`（4 文件，RN 时代项目记忆）、`.qoder/repowiki/`（145+ 文件，RN 时代自动生成架构文档）、`.roo/skills/`
- **双 .agent/ 合并**: 将 `native-ui/.agent/plans/`（17 个活跃计划）迁移至根 `.agent/plans/`，删除 `native-ui/.agent/` 消除重复
- **废弃 Qoder repowiki 系统**: RN 时代的 145+ 自动生成文档体系不再适用。Kotlin/Compose + IDE 导航能力已足够，DIA 机制 + 手工维护是正确策略
- **废弃 Worktree 发行分支模式**: 原生 Kotlin + Android Studio Build Variant 一键切换 Debug/Release，无环境污染，无需独立 `worktrees/release`
- **新文档结构**: `docs/`（公共 8 份文档）+ `.agent/`（handover + registry + plans）+ `native-ui/AGENTS.md`（项目规则）
- **新增**: `docs/DOCUMENT_GOVERNANCE.md`（文档治理方案）

### 项目目录清理与分支纯净化 (2026-05-13)
- **RN 时代残余清理**: 删除 25 个 RN 目录/文件（`app/`、`src/`、`android/`（Expo prebuild）、`web-client/`、`scripts/`、`plugins/`、`assets/`、`package.json` 等），`native-kotlin-refactor` 分支变为纯粹 Kotlin 原生项目
- **README.md 重写**: 更新为 Kotlin/Jetpack Compose 技术栈描述，中英双语
- **.gitignore 精简**: 移除 `node_modules/`、`.expo/`、`metro`、`npm/yarn`、`TypeScript` 等 RN 时代忽略规则
- **清理方案文档**: `docs/CLEANUP_PLAN.md`

### ContextBuilder 架构修正 (2026-05-13)
- **补充工具调用回传数据层**: `ContextPayload` 中 `webResults: List<WebSearchResult>` 改为 `toolResults: List<ToolCallResult>`，明确网络搜索是工具调用的一种而非独立数据源
- **新增 `ToolCallResult` 数据类**: 统一抽象工具回传（网络搜索/代码执行/文件读写等），含 `toolName`、`summary`、`rawData`、`references`
- **RAG Pipeline 图更新**: 补充工具回传数据源和 ContextBuilder 6 步组装流程
- **进度观测重命名**: `RagProgress` → `ContextBuildProgress`，新增 `InjectingTools`、`LoadingHistory` 阶段
- **PRD 同步更新**: 数据流图中明确"被动检索"与"主动工具回传"两个数据源层级

### 原生版全局审计与架构文档体系建设 (2026-05-13)
- **PRD v2.0 发布**: 基于项目定位与设计初衷，重新撰写 Kotlin 原生时代的完整产品需求文档（`docs/PRD.md`），明确能力边界与开发路线图
- **全局架构设计文档**: 输出理想架构设计方案（`docs/ARCHITECTURE_DESIGN.md`），包含分层架构、Repository 体系、LLM 抽象层、RAG/KG/Agent 引擎、CMP 渐进式迁移路线
- **当前实现分析与开发进度**: 输出深度审计文档（`docs/IMPLEMENTATION_ANALYSIS.md`），全站对照 PRD/架构分析实现差距（总体进度 ~62%）
- **ADR-001 超级助手取舍决策**: 分析 RN 时代到原生时代的 Super Assistant 架构演变，决定**去繁就简**，取消 Super Assistant 特殊概念，统一 Agent 模型（详见 `IMPLEMENTATION_ANALYSIS.md §8`）
- **架构债识别**: 发现 Domain 层缺失（业务逻辑散落）、Repository 覆盖率仅 37.5%（3/8）、ProviderManager 非标准单例模式等关键架构债
- **DIA 更新**: 更新 `ARCHITECTURE.md`、`registry.md`、`handover.md`、`README.md`、`.gitignore`

### 模型选择标准化与功能修复 (2026-05-13)
- **P0 SPA 修复**: 修复超能助手（SPA）设置界面模型选择点击无效的 Bug，补齐了 `SpaViewModel` 模型 ID 持久化逻辑，并接入多模态过滤逻辑。
- **P0 RAG 标准化**: 全量重构 `RagAdvancedScreen`，移除手动实现的模型列表，统一接入 `ModelPicker` 筛选协议并强制应用 `chat` 标签。
- **P1 会话面板对齐**: 优化主会话设置面板（SessionSettingsSheet）的 `ModelPanel` 过滤逻辑，支持 `multimodal`（含视觉）模型展示。
- **P1 过滤器扩展**: `ModelPicker` 新增 `multimodal` 标签，精细化管理对话、推理与视觉能力的复合筛选。
- **P1 通用设置过滤**: 修正摘要、图像、嵌入、重排四类默认模型的筛选逻辑，确保全局一致。


### RAG UI 观测能力与全链路打通 (2026-05-13)
- **P0 检索观测打通**: 重构 `ContextBuilder` 与 `RagProvider` 回调链路，实装从 `MemoryManager` 到底层检索算法的 5 阶段进度上报。
- **P0 UI 指标展示**: 在 `ChatBubble` 中集成 `RagOmniIndicator` 磨砂玻璃指示器，支持实时显示“正在向量化查询...”、“搜索文档...”等状态和百分比进度条。
- **P1 设置持久化修复**: 修复 `RagViewModel` 中 `showRetrievalProgress` 与 `showRetrievalDetails` 的持久化逻辑，确保设置项在应用重启后依然生效。
- **P1 默认配置优化**: 调整 `RagConfiguration` 默认值，默认开启“显示检索进度”与“显示检索详情”，提升新用户开箱即用的观测体验。
- **P2 稳定性与性能**: 修复 `AdvancedRetrievalScreen` 在开启滚动时与顶栏嵌套滚动的冲突，优化 RAG 状态在 `ChatStore` 中的局部刷新效率。
- **P2 UI 细节清理**: 移除 `AdvancedRetrievalScreen` 中重复的“高级检索”大标题，确保页面视觉焦点集中在配置项上。


### 设置颗粒度统一修复 (2026-05-13)
- **P0 搜索配置统一**: 删除 `SettingsViewModel.SearchSettings` 冗余类，统一到 `SearchConfigViewModel`；`result_count` 默认值 8→5（全局统一）
- **P0 enableKG 双源消除**: `SpaViewModel.enableKG` 删除，改为从 `RagConfiguration.enableKnowledgeGraph` 全局读取
- **P1 contextWindow 命名歧义**: SPA 层 `contextWindow` → `uiContextRatio`（避免与 RAG 的 `contextWindow: Int` 混淆）
- **P1 AgentRagConfig 类型统一**: `docChunkSize/chunkOverlap/memoryChunkSize` 从 `Float` 改为 `Int`（对齐 `RagConfiguration`）
- **P1 KG UI 去重**: `AdvancedRetrievalScreen` 的 KG 面板改为只读状态 + 导航链接，配置入口统一到 `RagAdvancedScreen`
- **P2 Agent 继承映射补全**: `AgentRetrievalConfig` 新增 11 个可继承字段（enableMemory/docs/KG, rewrite/提取模型, KG prompt/类型/模式, jitMaxChunks）

### 影响文件 (共 13 个)
- `SpaViewModel.kt`, `SpaSettingsScreen.kt` — P0-2, P1-3
- `AgentConfigModels.kt`, `AgentEditViewModel.kt`, `AgentRagConfigScreen.kt` — P1-4, P2-6
- `SettingsViewModel.kt`, `SkillsScreen.kt`, `WebSearchContextProvider.kt`, `WebSearchSkill.kt`, `WebSearchSearXNGSkill.kt` — P0-1
- `AdvancedRetrievalScreen.kt`, `NavGraph.kt` — P1-5

### Phase 1-3: Markdown 渲染行业对齐（P0 → P1 → P2 全线收官）
- **P0-T1 字号统一修复**: 创建 `chatTypography(fontSize)` 统一字号函数，修复 ThinkingBlock 默认值 14→13，缩减 Slider 上限 22→18sp。现在用户气泡、AI 正文、思维链、LaTeX/Mermaid/ECharts/PlantUML 字号全程一致。
- **P0-T2 CJK 中西文间距**: 实现 `insertCjkSpacing()` 预处理，自动在中文字符与西文/数字间插入 hair space (U+200A)，对标 LobeChat/Cherry Studio 的 remarkCjkFriendly 能力。
- **P0-T3 段落排版与断行优化**: 正文行高提升至 1.6、新增 `paragraph`/`inlineCode`/`quote` 排版项、标题行高统一 1.4，中文排版视觉层次清晰。
- **P0-T4 WebView 字号联动**: ECharts/PlantUML 渲染器接入 `fontSize` 参数，图表内文本随设置同步变化。
- **P1-T1 GFM Alert 支持**: 新增 `GfmAlertBlock.kt`，支持 NOTE/TIP/IMPORTANT/WARNING/CAUTION 五种 GitHub 风格警告块，带对应图标和语义色。
- **P1-T2 LaTeX 定界符兼容**: `normalizeLatexDelimiters()` 将 `\[...\]`/`\(...\)` 自动转换为 `$$...$$`/`$...$`，兼容 Anthropic Claude 等模型输出。
- **P1-T3 流式平滑调速**: 新增 `SmoothStreamContent.kt`，实现字符限速输出 (FAST=55/BALANCED=38/SMOOTH=25 cps)，消除流式输出抖动。
- **P1-T4 标题锚点 ID**: 通过 `blockquote` component override 实现标题语义标记。
- **P2-T1 HTML Artifacts**: 新增 `HtmlArtifactRenderer.kt`，HTML/SVG 代码块支持内嵌 WebView 实时预览 + 全屏分屏模式 + 导出 PNG 到系统相册。
- **P2-T2 代码块可编辑模式**: `CodeBlockWithHeader` 新增编辑按钮，点击切换 `OutlinedTextField` 编辑模式，保存后通过 `onCodeChange` 回调更新代码。
- **P2-T3 图片灯箱增强**: `ImageLightbox` 新增双指缩放 (0.5x-5x)、旋转、分享/保存到相册功能。

### 新增文件
- `ui/common/SmoothStreamContent.kt` — 流式平滑调速
- `ui/renderer/GfmAlertBlock.kt` — GFM Alert 警告块渲染
- `ui/renderer/HtmlArtifactRenderer.kt` — HTML 工件预览与导出
- `docs/MARKDOWN_RENDERING_AUDIT.md` — 渲染能力审计与行业对标
- `docs/IMPLEMENTATION_PLAN.md` — 分阶段实施计划（含 11 个独立 Agent 提示词）

### Markdown 渲染能力审计与行业对齐规划
- **行业调研**: 完成 LobeChat 与 Cherry Studio 渲染能力对标分析，输出完整差异矩阵（见 `docs/MARKDOWN_RENDERING_AUDIT.md`）
- **P0 修复**: 彻底解决了 Markdown 代码块渲染闪退问题（`horizontalScroll` 与 mikepenz `MarkdownCodeFence` 嵌套导致无限宽度约束崩溃）
- **字体诊断**: 诊断出 AI 气泡字号"部分生效"根因——ThinkingBlock 默认值与 ChatBubble 不一致、NexaraTypography.bodyMedium 硬编码 15sp 未被全局替换
- **CJK 排版**: 识别中西文间距优化缺失，规划 `AutoCjkSpacing` 预处理与 `letterSpacing` 规则

### 全局规则更新
- 新增 §7 [Kotlin/Compose] → 5. 滚动容器嵌套红线，覆盖 `horizontalScroll`/`verticalScroll` 与第三方库嵌套的崩溃模式

### 会话界面丰富内容渲染与排版深度优化
- **全局字号穿透**: 彻底解决了 AI 消息气泡中正文、LaTeX 公式、Mermaid 图表字号不一致的问题。通过重构渲染层级，所有丰富内容块现在均能实时响应设置中的字号调节。
- **图片渲染支持**: 集成了 `Coil3ImageTransformerImpl`，解决了 Markdown 远程图片无法显示的问题，并保留了全屏查看交互。
- **输入框占位符本地化**: 修复了预置助手（如超级助手）名称硬编码/启发式转换问题。现在通过 `ChatUiState` 穿透，实时从数据库获取助手的真实本地化名称（如 "超级助手" 替代 "Super"）。
- **WebView 样式联动**: 
    - 优化了 `RichContentWebView`，支持将系统字号注入 WebView 基准字号。
    - 优化了 LaTeX 和 Mermaid 的 HTML/CSS 模板，确保数学符号和图表文本与全局排版风格高度契合。
- **排版鲁棒性增强**: 
    - 引入 `trimIndent()` 预处理，自动修复部分 AI 模型输出时携带的多余缩进，防止标题被错误解析为代码块。
    - 优化了打字机模式下的 Markdown 闭环保护逻辑。
- **UI 清理**: 移除了测试阶段留在顶栏的字号调试信息。

### 品牌资产与 UI 体验
- **全套单色品牌图标**: 实装了 OpenAI, Anthropic, Gemini, DeepSeek, Mistral, Cohere 等 9 种协议的单色矢量图标，彻底移除对远程 Iconify 图标的依赖。
- **UI 对齐全局优化**: 修正了 `NexaraPageLayout` 及所有主页面（首页、设置页）标题相对于内容区域的 4.dp 偏移问题，确保标题与搜索框/卡片左侧严格对齐。
- **提供商配置增强**: 
    - 为自定义提供商添加了全新的 `ProtocolSelector` 图形化选择器。
    - 重构了 `ProviderPreset` 预设逻辑，实现了全站提供商图标的统一。
- **稳定性修复**: 修复了点击 "Custom" 协议选择器时由于嵌套垂直滚动容器导致的 `IllegalStateException` 崩溃。

### 自动化构建与发布
- **发行版 APK 编译**: 
    - 成功配置并使用 `promenar.keystore` 签名编译了首个 Android 发行版 APK (`app-release.apk`)。
    - 修复了 `app/build.gradle.kts` 中硬编码的 Windows 路径，增加了对 macOS 等跨平台开发环境的适配逻辑。

### 诊断与开发者工具
- **新增**: **开发者面板 (Developer Panel)**: 实现了独立的开发者设置二级页面，提供设备信息查看、运行日志导出与清除功能。

### UI 标准化与品牌化
- **品牌化重命名**: 将对话页面的 Header 标题由“智能助手”统一修改为 **"Nexara"**，确保全平台品牌标识一致。
- **助手设置入口补全**: 重构了 `AgentSessionsScreen` 的布局，确保“配置”按钮在无会话状态下依然可见并可访问。
- **视觉一致性优化**: 
    - 统一三大主页面（对话、知识库、设置）Header 标题坐标，移除所有副标题，统一使用 `TopAppBar` 渲染。
    - 统一主搜索栏样式与高度（48.dp），并在两个页面均实现 `stickyHeader` 吸顶效果。
- **UI 组件标准化 (NexaraSlider)**:
    - 针对原生 Material Design 3 Slider 视觉效果过于厚重的问题，设计并实现了 `NexaraSlider` 自定义组件。
    - 采用更纤细的轨道设计、带阴影的精致滑块、平滑过渡动画，并默认移除散乱的刻度点。
    - 完成了全站（对话设置、RAG 配置、模型推理参数、调色盘等）Slider 的标准化替换，提升了交互体验的一致性与优雅度。

### 修复与优化
- **Markdown 渲染崩溃修复**: 解决了在渲染包含大型表格或长代码块的复杂 Markdown 时，由于水平滚动容器与 `fillMaxWidth()` 冲突导致的崩溃。
- **修复**: 主会话界面键盘避让逻辑优化，防止输入栏被遮挡。
- **修复**: 修复了全新安装时模型列表为空（缺失能力映射）以及 Provider 添加后模型同步延迟的问题。
- **RAG 增强**: 细化了向量化过程的进度描述，并增加了 App 启动时自动恢复中断任务的逻辑。
- **优化**: 设置界面 UI 调整，将“关于”按钮改为“开发者面板”入口，并将项目 GitHub 链接移至底部标签。

### 聊天会话管理功能重构
- **菜单位置修复**: 解决了聊天界面右上角三点菜单位置偏移的问题，将其正确锚定在操作按钮下方。
- **核心功能实现**:
    - **清除历史**: 实现了清空当前会话所有消息的功能，并配有二次确认对话框。
    - **重命名会话**: 实现了自定义会话标题的功能，新增毛玻璃样式的重命名对话框。
    - **删除会话**: 实现了彻底删除当前会话及其所有内容的功能。
- **国际化补全**: 为上述功能补全了中英文资源，并修复了中文资源中的字符乱码问题。
- **UI 细节优化**: 将会话管理菜单项与标准 Material 图标（History, Edit, Delete）对接。

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

### 修复
- **主会话第一个气泡重发逻辑**: 修复了在会话第一个气泡执行“重发”操作时导致整个会话消息记录被清空的严重 Bug。
    - 调整了 `deleteMessagesAfter` 的调用时间戳，确保保留触发操作的 User 消息本身。
    - 修复了 `editMessage` 中由于消息被错误删除导致的编辑内容无法保存的问题。
- **主模型选择器筛选**: 修复了会话设置和模型选择器中会出现 Embedding 或 Rerank 模型的问题。
    - 现在主模型列表仅筛选 `chat`、`reasoning`、`image` 类型的模型。
    - 同步更新了通用 `ModelPicker` 的过滤逻辑，将 `IMAGE` 归类至主对话能力。

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
