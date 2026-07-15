# Nexara Material 3 第二阶段管理母版实施计划

> **执行要求：** 使用 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans` 按任务逐项实施；每个任务坚持 RED → GREEN → REFACTOR → 独立复核。任务使用复选框跟踪。

**目标：** 在不改变设置、Provider 与模型管理业务契约的前提下，将设置首页、Provider 列表、Provider 表单和 Provider Models 统一为稳定 Material 3 管理母版，清除卡片墙、玻璃嵌套、四按钮矩阵和标签云，并完成窄屏、大字体、横屏和 TalkBack 验收。

**架构：** 保留既有 Route/Content 状态提升 seam、`UserSettingsHomeScreenState/Actions`、`ProviderModelsScreenState/Actions`、`SettingsViewModel` 与 `ProviderManager`。UI 只消费现有状态并派发现有动作；设置使用 `TabRow + 分组列表 + ListItem`，Provider 使用单层对象行，表单使用官方 M3 字段和按钮，模型管理通过 Top App Bar/搜索/overflow 分级操作，并用折叠详情渐进披露高级能力。

**技术栈：** Kotlin、Jetpack Compose、Material 3 `1.4.0`、JUnit、Compose UI Test、Compose Preview Screenshot Test、Gradle。

## 全局约束

- 视觉必须遵循已批准的 [全站设计规格](../specs/2026-07-16-nexara-md3-redesign-design.md) 与方案 3：深色、低噪、内容优先、单层 tonal surface。
- 不修改 ViewModel、Repository、Provider/模型持久化、导航目的地、API、模型 ID、密钥备份契约或本地推理业务行为。
- 保持 API Key 默认 `****` 安全显示、可选瞬时显示完整 Key、可选完整进入备份；禁止把真实密钥写入源码、测试、日志或截图。
- 设置普通条目不得各自成卡；Provider 和模型只允许一个对象级容器层，不得卡片套字段卡片。
- 页面只有一个主操作；批量禁用与删除进入 overflow/确认流程，不得与添加、同步形成四个同权重大按钮。
- 所有可操作目标至少 `48dp`；文本区域必须可回流，禁止用固定高度、固定宽度或 `10sp/11sp` 维持密度。
- 大字体 `2.0x`、360dp 窄屏、横屏、840dp 平板和中英文必须可用；键盘不得由固定 `200dp Spacer` 回避。
- 纯视觉代码可按项目规则豁免业务单测，但每个用户可见结构变化都必须有 Compose 语义测试或截图证据。
- `artifacts/`、`secure_env/`、签名文件、API Key 与临时 Agent 报告不得读取、修改或加入提交。

---

### Task 1：锁定管理母版公共契约

**Files:**
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/common/ManagementMaterialContractTest.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraSettingsItem.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraSearchBar.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraPageLayout.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/hub/UserSettingsAccessibilityTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`
- Modify: all affected golden files under `native-ui/app/src/screenshotTestDebug/reference/`

**接口边界：** 保持 `NexaraSettingsItem`、`NexaraSearchBar` 与 `NexaraPageLayout` 的现有公开参数兼容；三者被 Agent、RAG、Session Settings 等页面复用，因此本任务只建立 M3 语义、尺寸与 token 基线，不改变调用方业务。`NexaraPageLayout` 仍是 system bars 与 IME insets 的唯一所有者。

- [ ] **Step 1：写入失败契约**

  断言 `NexaraSettingsItem` 使用 `ListItem`/`Surface` 而非 `NexaraGlassCard`，标题区域可伸缩，操作语义为 Button；断言搜索清除操作使用至少 48dp 的可点击容器，源码不再固定 `24.dp` 点击区域；断言 `NexaraPageLayout` 使用 `MaterialTheme.colorScheme`、`titleLarge`/`headlineSmall` 级别的小型顶栏、`NexaraSpacing` 与不透明 tonal surface，不再使用 `headlineLarge`、`CanvasBackground.copy(alpha = 0.8f)` 或硬编码 20/24dp 页面节奏。

- [ ] **Step 2：运行 RED**

  `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.common.ManagementMaterialContractTest'`

  预期：因旧玻璃卡和 24dp 清除按钮失败。

- [ ] **Step 3：实施最小公共基线**

  - `NexaraSettingsItem` 改为 M3 `ListItem` 或等价单层 `Surface` 行，使用 `MaterialTheme.typography`、`NexaraSpacing` 与 `MinimumTouchTarget`。
  - 标题/副标题容器 `weight(1f)` 并允许自然增高；尾部图标不抢占文本空间。
  - `NexaraSearchBar` 保持现有 API 和搜索行为，只把 leading/trailing action 调整为标准 `IconButton` 48dp 目标与语义色。
  - `NexaraPageLayout` 保持 `scrollable`、`imePadding`、actions 和最大宽度接口，只把顶栏、容器色、排版与页面 padding 迁移到已批准的 M3 token；不得在调用方重复增加 `imePadding`/`navigationBarsPadding`。

- [ ] **Step 4：补设备断言并跑 GREEN**

  在 `UserSettingsAccessibilityTest` 覆盖普通条目 48dp、长标题回流、清除按钮 48dp 和点击派发；运行 JVM 测试，并在已启动的 API 36 设备上执行：

  `ANDROID_SERIAL=<api36-serial> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.hub.UserSettingsAccessibilityTest`

- [ ] **Step 5：共享组件回归**

  `cd native-ui && ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:validateDebugScreenshotTest`

  三个共享组件可能影响约 15 个调用页面。必须对所有变化的 actual/reference/diff 逐张检查并更新受影响 golden，使截图门禁恢复 GREEN 后才能提交；禁止批量无审阅接受。

- [ ] **Step 6：提交**

  `git commit -m "refactor: establish Material 3 management primitives"`

---

### Task 2：重建设置首页 App 列表母版

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreen.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreenContractTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/hub/UserSettingsAccessibilityTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`
- Modify: matching golden files under `native-ui/app/src/screenshotTestDebug/reference/`

**接口边界：** 保持 `UserSettingsHomeScreenState/Actions` 和 `UserSettingsHomeScreenContent` 参数不变；不改变任何设置入口、路由或保存动作。

- [ ] **Step 1：先改契约测试为新规格并确认 RED**

  断言页面使用标准 Top App Bar、`PrimaryTabRow`/`TabRow`、分组标题和列表；契约只截取 `UserSettingsHomeScreenContent`、`TabBar`、`AppSettingsContent` 与 `UserProfileHeader`，禁止这些区块中的 `NexaraGlassCard`、头像渐变、手写 2dp tab 指示线及普通条目逐项卡片化；不得因 Provider 区块尚未迁移而误报。

- [ ] **Step 2：实现 App 设置列表**

  - 页面使用 edge-to-edge `Scaffold` 与标准小型 Top App Bar。
  - `TabBar` 改为 M3 TabRow，保留原有标签、选择状态和稳定 test tag。
  - `UserProfileHeader` 改为单层个人资料 ListItem/Surface，不使用渐变或玻璃描边。
  - `AppSettingsContent` 以使用 Material typography 的新管理分组标题 + 连续 ListItem 表达模型、检索、记忆、备份、语言、关于等入口；不得复用仍含 10sp/旧字体的遗留 `SettingsSectionHeader`。
  - Switch/尾部值/导航箭头按 M3 角色呈现，普通状态色不作为装饰。

- [ ] **Step 3：验证业务 seam 与无障碍**

  运行 `UserSettingsHomeScreenContractTest`；在 API 36 指定 serial 上按 class 执行 `UserSettingsAccessibilityTest`，覆盖 Tab 切换、入口点击、长副标题、语言 RadioButton 语义、2.0x 字体和滚动可达性。

- [ ] **Step 4：更新并人工对照截图**

  保留中文 840dp 基线，并新增或扩展 360dp 2.0x App 设置基线。把 actual 与 reference 并排检查：标题、分组间距、条目对齐、触控区、底部滚动边界均无裁切后才更新 golden。

- [ ] **Step 5：提交**

  `git commit -m "feat: rebuild settings with Material 3 lists"`

---

### Task 3：重建 Provider 单层列表母版

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreen.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/hub/UserSettingsAccessibilityTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`
- Modify: matching golden files under `native-ui/app/src/screenshotTestDebug/reference/`

**接口边界：** Provider 管理、编辑、删除动作保持原样；保留每个 Provider 的唯一 test tag，但消除“整卡主点击 + 三个重复子操作”的 TalkBack 冗余。

- [ ] **Step 1：写入 Provider 行失败测试**

  新测试要求：Provider 名称、类型、Base URL、启用状态和凭证是否已配置可扫描；管理模型是清晰主入口；编辑和删除位于标准 trailing/overflow；每个动作唯一、48dp、具备正确 Role；整行不再与子操作重复暴露同一动作。不得显示当前 `ProviderListItem` 不提供的“连接状态”或“模型数”。

- [ ] **Step 2：实现单层 Provider 列表**

  - `AddProviderButton` 改为页面唯一 Filled/Extended FAB 或顶栏主操作，禁止玻璃大卡。
  - `ProviderCard` 改为单层 ListItem/Surface；只使用 `ProviderListItem` 已有的名称、类型、Base URL、enabled、hasApiKey/hasVertexCredentials 等字段构成主次信息。
  - 管理模型、编辑、删除按主次层级分配；删除保留现有确认对话框与错误色语义。
  - 空态提供明确说明和添加操作，不制造空卡片。

- [ ] **Step 3：验证动作与大字体**

  Compose 测试逐一点击管理、编辑、删除并验证回调；覆盖两个长名称 Provider、360dp、2.0x 和空态。使用指定 API 36 serial 执行 `UserSettingsAccessibilityTest`，不得只编译 androidTest。

- [ ] **Step 4：更新 Provider 截图并人工审阅**

  更新既有英文 360dp/2.0x 基线；检查 trailing 操作、换行、分隔线、底部滚动与危险操作层级。

- [ ] **Step 5：提交**

  `git commit -m "feat: simplify provider management list"`

---

### Task 4：将 Provider 表单迁移到标准 Material 3

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderFormScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/SecretField.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/SecureSecretUiContractTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/SecureSecretFieldTest.kt`
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/ProviderFormInteractionTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`
- Create or Modify: matching Provider form golden files

**接口边界：** API Key 安全显示、瞬时完整显示、离开页面清零、备份选择和本地 Provider 探测行为必须保持；不读取真实 Key。为稳定测试和截图，提取纯 `ProviderFormContent` 及可构造的 `ProviderFormUiState/Actions`，Route 继续负责现有 ViewModel、`NexaraApplication`、本地推理 gate 与副作用装配，不改变保存/导航业务。

- [ ] **Step 1：写入表单视觉与安全 RED**

  断言表单与 `SecretField` 不再使用 `NexaraGlassCard`、`GlassInputField`、`GlassBorder`、未使用的 `PresetItem`、手写 Box 按钮和固定 `Spacer(height = 200.dp)`；同时保留密钥遮罩、显示切换和清零契约。

- [ ] **Step 2：迁移字段与操作**

  - Provider 预设使用 `ExposedDropdownMenuBox` + M3 TextField。
  - 名称、Base URL、API Key 等使用 `OutlinedTextField`/`SecretField`，错误通过 `isError`、supporting text 与语义 live region 表达。
  - `SecretField` 本身迁移到 M3 语义颜色、形状与字段容器，同时保持其安全状态机；回归 Provider Form、Backup 等共享调用方。
  - 本地/云 Provider 分组使用标题与单层 `surfaceContainerLow`，不套玻璃卡。
  - 测试连接使用次要按钮，保存使用唯一主按钮；2.0x 下允许纵向重排。
  - 继续由 `NexaraPageLayout(imePadding = true)` 统一处理 system bars/IME；表单内部只使用 Lazy/可滚动布局替代固定键盘 Spacer，不重复调用 `imePadding` 或 `navigationBarsPadding`。

- [ ] **Step 3：验证三分支与键盘**

  覆盖新增、编辑、本地 Provider；HTTPS 校验、测试连接 loading/success/error、保存禁用与 IME 避让，并锁定以下业务：新增保存后返回、编辑保存后进入 Models、`onboardingMode` 的 `onSaved/onConnectionVerified`、本地探测失败、`CredentialUpdate.Preserve/Replace/Clear`、Vertex 与 API Key 切换的 `credentialKindMismatch`。

  复跑 `SecureSecretUiContractTest`、`SecureSecretFieldTest` 与 `ProviderFormInteractionTest`。完整 Key 允许在用户明确点击显示后短暂进入 Compose 状态/语义树；失焦、离页、重建或显示期限到期后必须从状态与语义树移除并清零敏感数组。测试只使用伪造 Key，且 Key 不进入日志、持久状态、golden 或提交。

  API 36 设备命令：

  `ANDROID_SERIAL=<api36-serial> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.settings.SecureSecretFieldTest,com.promenar.nexara.ui.settings.ProviderFormInteractionTest`

- [ ] **Step 4：新增完整表单视觉基线**

  通过纯 `ProviderFormContent` fixture 稳定构造新增、编辑、本地/凭证类型状态，无真实存储或网络副作用。至少覆盖手机普通字体和 360dp/2.0x 两张；截图中只使用伪造遮罩值，不出现完整 Key。人工检查字段标签、supporting text、按钮重排和键盘前后可达性。

- [ ] **Step 5：提交**

  `git commit -m "feat: migrate provider form to Material 3"`

---

### Task 5：重构 Provider Models 顶部操作与页面状态

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/ProviderModelsScreenContractTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/ProviderModelsAccessibilityTest.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt` if stable tags need semantic renaming

**接口边界：** 保持 `ProviderModelsScreenState/Actions`、同步、添加、全部禁用、全部删除和 Add Sheet 回调契约；只改变入口层级和布局。

- [ ] **Step 1：移除锁死旧矩阵的测试，建立新 RED**

  将 `providerActionsStayTwoByTwo...` 改为：搜索持续可见；同步与添加为主要可见操作；批量禁用/删除位于 overflow；360dp/2.0x 下所有操作可达且无水平越界。删除旧契约中对 `ActionChip`、36dp 视觉和 2×2 布局的肯定断言。

- [ ] **Step 2：实现标准操作层级**

  - 搜索栏保持首层；同步使用 IconButton/tonal action，添加使用页面唯一主要操作。
  - 全部禁用、全部删除进入 overflow；删除继续使用确认对话框。
  - 加载、同步成功/失败、空态与搜索空态使用 M3 语义 surface/list message；保留 assertive live region 和 48dp dismiss。
  - Add Custom Model 不再依赖固定 `fillMaxHeight(0.7f)` 的共享 `NexaraBottomSheet`；本页使用可滚动的标准 `ModalBottomSheet` + `LazyColumn`/`verticalScroll`，在 360dp/2.0x + IME 下所有字段和提交按钮可达，不改变失败保持输入的契约。

- [ ] **Step 3：验证完整顶部操作流**

  Compose 测试验证同步禁用态、添加 Sheet、提交失败保留内容、overflow 批量操作、删除确认、loading/error/empty/search-empty、名称/真实 ID 双字段搜索与 live region。使用指定 API 36 serial 执行：

  `ANDROID_SERIAL=<api36-serial> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.settings.ProviderModelsAccessibilityTest`

- [ ] **Step 4：提交**

  `git commit -m "feat: simplify provider model actions"`

---

### Task 6：将模型卡改为渐进披露单层列表

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/ProviderModelsScreenContractTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/ProviderModelsAccessibilityTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`
- Modify: matching Provider Models golden files

**接口边界：** 模型名称、真实 ID、enabled、type、capabilities、context、max output、knowledge cutoff 和测试状态继续来自现有 `ModelInfo`；编辑/测试/删除/启用回调不变。

- [ ] **Step 1：建立折叠/展开 RED**

  首层只要求名称、真实 ID、启用状态、主要能力和展开动作；高级字段默认不暴露。展开后才显示名称编辑、类型、能力、Context、输出 Token、知识截止、测试和危险操作。测试不再要求固定 112dp Context、五种类型同排或 36dp 标签。

- [ ] **Step 2：实现单层模型行**

  - 用一个 Card/Surface 表达一个模型，不嵌套另一张卡或名称输入框外壳。
  - 首层用 typography/spacing 建立层级；模型 ID 单行 ellipsis 但可通过语义完整读取。
  - 主要能力最多显示少量 Filter/Assist Chip，剩余数量以文本汇总；不使用彩色标签墙。
  - 展开区使用 `AnimatedVisibility` 或标准展开容器；尊重减少动效设置。
  - 类型允许流式换行/下拉选择；Context 使用自适应 `OutlinedTextField`，不固定宽度；大字体时操作纵向排列。
  - 单模型删除增加标准确认对话框；确认后继续调用既有 `onDelete(modelId)`，取消不产生业务动作。

- [ ] **Step 3：验证业务动作与状态同步**

  覆盖折叠/展开、同 ID 刷新草稿、类型联动、能力多选、Context 数字输入、测试/取消、单模型删除确认/取消、启用与长模型 ID。确认折叠后高级字段不在可访问树中，展开后焦点顺序稳定；在指定 API 36 serial 上再次按 class 执行 `ProviderModelsAccessibilityTest`。

- [ ] **Step 4：更新三组 Provider Models 截图**

  覆盖英文手机、中文 840dp/2.0x、中文横屏，并明确至少一张展开态。逐张与方案 3/Phase 1 的 tonal surface、圆角、间距和排版并排审阅后更新 golden。

- [ ] **Step 5：提交**

  `git commit -m "feat: add progressive model management rows"`

---

### Task 7：第二阶段视觉、设备与发布门禁收口

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `.agent/handover.md`
- Modify: `.agent/registry.md`
- Modify: this plan checkboxes/status as needed

- [ ] **Step 1：静态与目标测试**

  `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.hub.*' --tests 'com.promenar.nexara.ui.settings.*' :app:lintDebug :app:assembleDebug :mainactivity-e2e:assembleDeviceTest`

- [ ] **Step 2：完整截图门禁与人工对照**

  `cd native-ui && ./gradlew :app:validateDebugScreenshotTest`

  对每张变化基线生成 actual/reference/diff 同尺寸对照，逐张检查：裁切、重叠、错误 padding/margin、字体角色、描边、圆角、危险操作层级与状态色。禁止只凭 36/36 退出码验收。

- [ ] **Step 3：相关设备测试**

  先用 `adb devices -l` 枚举设备，并对每个 serial 执行 `adb -s <serial> shell getprop ro.build.version.sdk`，建立 API 31/35/36 对应表；缺少目标 API 时，启动本机已有相应 AVD，若本机确无镜像则记录为外部阻塞，禁止口头宣称矩阵完成。

  在 API 36 先运行 `UserSettingsAccessibilityTest`、`ProviderModelsAccessibilityTest`、`SecureSecretFieldTest` 与 `ProviderFormInteractionTest`；再在 API 31/35 的指定 serial 上按相同 class 集合执行。每次通过 `ANDROID_SERIAL=<serial>` 锁定设备，测试前 force-stop 并确认没有残留动画/输入法状态污染。

- [ ] **Step 4：人工交互抽样**

  在模拟器走通：设置 App/Provider Tab、添加/编辑 Provider、密钥显示后离开清零、模型同步/搜索/添加/批量操作、模型折叠/展开/编辑/测试/删除。覆盖普通字体、2.0x、横屏、键盘和减少动效。

- [ ] **Step 5：完整回归门禁**

  `cd native-ui && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :mainactivity-e2e:assembleDeviceTest`

  随后运行 bulk suite，明确排除 `com.promenar.nexara.background.generation.GenerationForegroundServiceColdStartDeviceTest`：

  `ANDROID_SERIAL=<api36-serial> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.promenar.nexara.background.generation.GenerationForegroundServiceColdStartDeviceTest`

  再对 `coldTrackDuringStartupRecoveryStopsGracefullyWithoutProcessCrash` 与 `coldStopDuringStartupRecoveryStopsGracefullyWithoutCoordinator` 分别 force-stop 后独立执行；bulk + 两方法合并计算完整结果。

- [ ] **Step 6：独立视觉/代码复核**

  由未实施本任务的 Agent 只读检查最终 diff、测试证据和截图 montage；P0/P1 必须清零，P2 记录到 handover。

- [ ] **Step 7：DIA/HLG 收口**

  - `CHANGELOG.md` 记录设置、Provider、模型管理的 Material 3 用户可见变化。
  - 核验 `.agent/registry.md` 已登记本计划；仅缺失时修改，禁止重复条目。
  - `.agent/handover.md` 追加标准时间戳记录：Done、Validation、Next、Risks、DIA、HLG。
  - 使用 HLG 脚本重建索引；不手工编辑派生索引。

- [ ] **Step 8：提交阶段收口**

  `git commit -m "docs: close Material 3 management phase"`

## 完成定义

- 设置首页不再是卡片墙，Tab、分组、列表、Switch/Radio/入口语义符合 M3。
- Provider 列表为单层对象行，主次操作和 TalkBack 焦点不重复。
- Provider 表单不再含玻璃字段/容器或固定键盘 Spacer，密钥安全契约完整保留。
- Provider Models 不再有四按钮矩阵、卡套卡、五类型硬塞一行、彩色能力标签墙或固定 112dp Context。
- 360dp/2.0x、840dp/2.0x、横屏、双语、IME、减少动效和 API 31/35/36 相关门禁通过。
- 所有变化截图已与 reference/方案 3 同尺寸并排人工审阅；P0/P1 为零。
- `git status` 只允许保留用户既有 `artifacts/` 未跟踪目录；不得提交敏感文件。
