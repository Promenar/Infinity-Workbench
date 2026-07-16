# Nexara Material 3 第三阶段：知识库与资源管理实施计划

> 日期：2026-07-16
> 分支：`codex/md3-redesign`
> 依据：`docs/superpowers/specs/2026-07-16-nexara-md3-redesign-design.md`
> 状态：已完成（独立终审 C0/I0；UI P0/P1/P2 清零）
> 范围：RAG 状态原语、文件树、知识库首页/记忆、文件夹、RAG 详情、资源管理器与回收站；DocEditor 延后到独立阶段

## 目标与边界

第三阶段把知识库与资源管理链路统一到已经在会话页和管理页验证过的稳定 Material 3 方言：列表优先、单层 tonal surface、标准状态反馈、渐进披露、48dp 操作目标，并忠实呈现现有状态契约已经提供的错误与恢复动作。业务数据、导航 route、导入/索引/删除回调及 Repository 契约保持兼容。

本阶段不得借视觉迁移新增业务状态、改写 ViewModel/Repository、改写索引算法或改变 RAG 检索策略。当前 Memory 只有列表、异常会被静默映射为空态，因此 loading/error/retry 属于已确认的独立产品可靠性 P1 债务，不在本视觉阶段伪造；后续必须以单独功能修复任务处理。`DocEditorScreen.kt` 超过千行且已有独立截图体系，也不与本阶段并发修改。

统一验收约束：

- 禁止新增 `NexaraGlassCard`、`GlassSurface`、`GlassBorder`、装饰性渐变、发光、固定 9/10/11sp 或 `RoundedCornerShape(50)`。
- 现有契约能表达的状态至少由文案或图标+文案呈现，不得只靠颜色；TalkBack 需获得加载、可用、失败、选中、展开和操作结果。
- 360dp、2.0x 字体与横屏下不得靠不可恢复裁切维持布局；目录和列表必须可滚动到最后一项。
- 禁止在同轴滚动容器中嵌套无界 `LazyColumn`/`verticalScroll`；长列表必须懒加载并提供稳定 key。
- 每个实现块先写 RED，再做最小实现，更新 golden 前必须生成 reference/actual 同尺寸对照并人工审阅。
- 不读取或提交 `artifacts/`、`secure_env/`、签名材料或真实凭证。

---

### Task 1：冻结第三阶段契约与测试入口

**Files:**
- Create: `docs/superpowers/plans/2026-07-16-nexara-md3-phase3-rag-resources.md`
- Modify: `.agent/registry.md`
- Test: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/RagHomeScreenContractTest.kt`
- Test: `native-ui/app/src/test/java/com/promenar/nexara/ui/chat/components/FilesPanelStateTest.kt`
- Test: `native-ui/app/src/test/java/com/promenar/nexara/ui/common/IndexStatusBadgeTest.kt`

- [x] **Step 1：登记计划与设计边界**

  在 registry 的计划区登记本文件；确认设计规格、Phase 1、Phase 2 记录继续作为视觉事实源，不复制新的 token 或品牌规范。

- [x] **Step 2：建立静态遗留扫描**

  对本阶段生产文件建立聚焦契约，禁止迁移完成的函数继续出现 Glass 容器、固定微字号、呼吸发光和硬编码状态色；测试按函数/文件片段定位，避免误报尚未进入当前任务的 DocEditor。

- [x] **Step 3：提交计划**

  `git commit -m "docs: plan Material 3 RAG and resources phase"`

---

### Task 2：统一 RAG 搜索、索引状态与减少动效原语

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/IndexStatusBadge.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/components/IndexingProgressBar.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraSearchBar.kt`（仅确有缺口时）
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/common/IndexStatusBadgeTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/components/IndexingProgressBarStateTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/RagReleaseAccessibilityTest.kt`

- [x] **Step 1：写入状态与动效 RED**

  断言未开始、索引中、可用、失败分别具有本地化文案和语义；`NOT_STARTED` 不再使用看似可点击的单选图标；系统动画倍率为 0 时不得启动无限呼吸动画，状态仍可理解。

- [x] **Step 2：实现单层 Material 状态原语**

  使用 `MaterialTheme.colorScheme`、Material typography 和标准 progress/icon；移除无限循环 pulse 与装饰性渐变。`IndexingProgressBar` 保持现有进度与取消回调，不新增网络或索引逻辑。

- [x] **Step 3：验证共享调用方**

  运行目标 JVM 测试和 API 36 `RagReleaseAccessibilityTest`；检查会话 RAG、知识库与文件夹调用方没有语义漂移。若实际修改 `NexaraSearchBar`，必须先用 `rg` 枚举全部生产调用方，并至少复跑 Phase 1/2 已批准的 Agent Hub、会话、Provider/模型截图及相关交互测试，不得只验证本阶段页面。

- [x] **Step 4：提交**

  `git commit -m "refactor: unify Material 3 RAG status components"`

---

### Task 3：把 FilesPanel 改为稳定的单层文件树

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/components/FilesPanel.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/components/FilesPanelState.kt`（若现有投影函数需要扩展）
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/chat/components/FilesPanelStateTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/RagFilesPanelNavigationTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

- [x] **Step 1：为树状态、稳定 key 与动作分流写 RED**

  覆盖深层目录、同名不同 UUID、重排/筛选后的展开状态、多选、目录导航、文件打开、overflow 与最后一项可达。断言生产 `FilesPanel` 不再每节点一张 Glass 卡，也不暴露伪单选操作。

- [x] **Step 2：建立可见节点投影与稳定身份**

  将展开后的可见树投影为可被 `LazyColumn.items(key = uuid)` 消费的节点序列，或采用等价的稳定 keyed 懒加载结构；`rememberSaveable` 状态必须绑定 UUID。不得继续对子树使用无 key 的递归 `Column + forEach`。

- [x] **Step 3：实现单层响应式列表行**

  文件/目录使用单层 `ListItem`/tonal `Surface`，缩进设置上限并保留层级语义；选择、索引/KG 状态、主点击和 overflow 不互相抢焦点。大字体下尾部操作允许换行或降级到菜单。

- [x] **Step 4：目录选择器可滚动**

  把 FilesPanel 内移动目录选择器改为有界 `LazyColumn`，覆盖 20 个目录并能滚动到最后一项；不得在 Sheet 中创建无限高度嵌套滚动。

- [x] **Step 5：真实 FilesPanel 截图与设备验收**

  将 RAG 文档首页 golden 从测试专用假列表切到生产 `FilesPanel` fixture；新增 360dp/2.0x 深层树状态，运行截图门禁和 API 36 导航测试并人工对照。

- [x] **Step 6：提交**

  `git commit -m "feat: rebuild RAG file tree with Material 3"`

---

### Task 4：重建知识库首页与 Memory 列表

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagHomeScreen.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/RagHomeScreenContractTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/RagHomeInteractionTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/RagReleaseAccessibilityTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

- [x] **Step 1：写入页面语义和 Memory 列表 RED**

  搜索只在文档任务中出现；图谱使用导航动作而非伪 Tab；Memory 忠实区分现有契约可表达的 content/empty。记忆项有可见展开和删除操作，不再把删除只藏在长按中。

- [x] **Step 2：收敛首页信息层级**

  使用小型 Top App Bar、标准 Tab/导航动作、一个主要新增/上传入口和单层状态反馈；去除卡片墙。保留现有文档选择、创建、上传、移动、删除及图谱导航回调。

- [x] **Step 3：实现列表式 Memory 项**

  使用单层可展开列表项，公开展开状态和删除按钮语义；长内容可阅读、删除仍经过确认，2.0x 下操作可达。

- [x] **Step 4：截图与设备矩阵**

  至少覆盖 360dp content/展开、360dp/2.0x content、840dp empty 和删除确认；运行 JVM、API 36 交互测试及截图门禁，人工检查滚动边界。计划和 handover 必须保留 Memory loading/error/retry 独立 P1 债务，不把 empty golden 当作失败恢复证据。

- [x] **Step 5：提交**

  `git commit -m "feat: rebuild RAG home and memory list"`

---

### Task 5：重建 RagFolder 响应式文件夹页面

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagFolderScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/components/RagDocItem.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/components/RagStatusChip.kt`
- Delete: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/components/FolderItem.kt`（确认无生产引用后）
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/RagFolderUiStateTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/RagReleaseAccessibilityTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

- [x] **Step 1：写入选择栏、大字体与目录滚动 RED**

  覆盖空态、内容、选择、移动、删除确认、20 个目录和 360dp/2.0x；禁止固定 176dp 预留高度、文本塞入 `IconButton` 以及不可滚动 `Column + forEach`。

- [x] **Step 2：实现标准 Top App Bar 与列表行**

  全选/取消全选使用菜单或标准文字动作；文档行在窄屏/大字体下重排选择框、正文、状态和 overflow，保持导航与多选分流。

- [x] **Step 3：实现上下文选择操作栏**

  选择栏高度由内容决定并正确为列表提供 inset，不用魔数占位；移动目录 Sheet 使用有界懒列表，最后一项可达。

- [x] **Step 4：截图、设备与删除遗留组件**

  新增普通/2.0x/横屏 golden；在 `RagReleaseAccessibilityTest` 新增 RagFolder 360dp/2.0x、横屏及 20 项目录滚动到末项用例。确认 `FolderItem` 全仓无引用后删除，并跑编译、设备测试和截图门禁；不得用 golden 代替设备交互验证。

- [x] **Step 5：提交**

  `git commit -m "feat: migrate RAG folder to Material 3"`

---

### Task 6：统一 RAG 详情与会话入口

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/components/RagDetailsSheet.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatInlineComponents.kt`（仅 RAG 入口和状态轨迹）
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/RagDetailsAccessibilityTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

- [x] **Step 1：建立详情页 RED**

  覆盖 Web、文档、KG 三个 Tab 的 Role/选中态、链接动作、48dp、长标题/文件名/关系、360dp/2.0x 和失败反馈；禁止 Glass 卡套卡、固定微字号、硬编码状态色与三列强挤。

- [x] **Step 2：实现标准 ModalBottomSheet 与单层内容**

  引用内容使用列表/分组 surface，分数与来源是辅助文本而非彩色胶囊墙；长关系在窄屏改为纵向语句。外部链接失败提供可恢复反馈，非操作图标不生成冗余焦点。

- [x] **Step 3：会话 RAG 入口视觉同源**

  将 `NeonMicroRail` 与 Primary→Tertiary 装饰渐变收敛为会话页已批准的思考/工具轨迹；保持展开详情和生成状态回调。

- [x] **Step 4：截图与设备测试**

  新增三个详情 Tab 的普通/2.0x/长内容基线；`RagDetailsAccessibilityTest` 必须覆盖 360dp/2.0x 与横屏长内容、滚动末项和操作语义，再运行该类和会话既有交互测试。

- [x] **Step 5：提交**

  `git commit -m "feat: unify RAG details with chat timeline"`

---

### Task 7：重建 Resource Explorer 与 Recycle Bin

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ResourceExplorerSheet.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/components/RecycleBinPanel.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraBottomSheet.kt`（仅通用高度/inset 契约确需调整时）
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/ResourceExplorerInteractionTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/ResourceExplorerRecycleBinInteractionTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

- [x] **Step 1：写入上下文动作和长错误 RED**

  回收站 Tab 不得显示导入动作或导入结果；导入失败原因可完整阅读且重试可达；长文件名、运行/失败、批量操作、删除确认和 2.0x 下底部内容均可滚动到达。

- [x] **Step 2：实现标准 Sheet 层级**

  移除固定 70% 高度依赖或为其建立响应式上限；标题、Tab、上下文主操作、运行状态和内容区不重复占层级。导入动作只属于文件 Tab。

- [x] **Step 3：重排导入结果与回收站条目**

  失败原因允许多行，重试/恢复/彻底删除使用独立 48dp 目标；回收站条目和状态提示改为单层 M3 列表/notice，不再逐项 Glass 卡。

- [x] **Step 4：截图与设备验证**

  覆盖文件/导入成功/导入失败、回收站运行/失败/删除确认、中文 2.0x 与横屏；在 `ResourceExplorerInteractionTest` 和 `ResourceExplorerRecycleBinInteractionTest` 新增横屏及 Sheet 最后一项可达用例，运行两个交互类并人工对照。

- [x] **Step 5：提交**

  `git commit -m "feat: rebuild resource explorer with Material 3"`

---

### Task 8：第三阶段完整门禁与 DIA/HLG 收口

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `.agent/handover.md`
- Modify: `.agent/handover-index.md`（仅由 HLG 脚本生成）
- Modify: this plan

- [x] **Step 1：静态、JVM、Lint 与构建门禁**

  `cd native-ui && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :mainactivity-e2e:assembleDeviceTest`

- [x] **Step 2：完整截图与同尺寸人工对照**

  `cd native-ui && ./gradlew :app:validateDebugScreenshotTest`

  对所有变化 reference/actual 生成同尺寸 montage，检查裁切、重叠、间距、排版、描边、圆角、状态色、最后一项可达和大字体密度。

- [x] **Step 3：API 31/35/36 设备矩阵**

  先执行 `adb devices -l`，并对每个 serial 运行 `adb -s <serial> shell getprop ro.build.version.sdk`；缺少目标 API 时启动本机已有 `Nexara_API_31` / `Nexara_API_35` AVD，本机也没有对应镜像时必须记录为外部阻塞，不得宣称矩阵完成。修改动画倍率、字体或方向前记录原值；对每个 serial 将 `window_animation_scale`、`transition_animation_scale`、`animator_duration_scale` 设为 0，然后锁定运行：

  `ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.rag.RagReleaseAccessibilityTest,com.promenar.nexara.ui.rag.RagFilesPanelNavigationTest,com.promenar.nexara.ui.chat.RagDetailsAccessibilityTest,com.promenar.nexara.ui.chat.ResourceExplorerInteractionTest,com.promenar.nexara.ui.chat.ResourceExplorerRecycleBinInteractionTest`

  每档记录 tests/failures/errors/skipped。Task 5/6/7 新增的设备方法必须随上述 class 集合实际覆盖普通字体、2.0x、横屏和 Sheet/目录滚动末项，不得仅由 screenshot golden 代替。每个 serial 验收后恢复并验证原动画倍率、字体与方向；临时 AVD 测试后关闭。

- [x] **Step 4：API 36 完整设备回归**

  先运行常规 bulk suite：

  `ANDROID_SERIAL=<api36-serial> ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.promenar.nexara.background.generation.GenerationForegroundServiceColdStartDeviceTest`

  `connectedDebugAndroidTest` 可能在任务结束后卸载目标包，因此两项冷启动证据不得只看 Gradle 退出码。每个方法都必须重新安装 Debug 与 androidTest APK，启动目标包并确认 PID 非空，再执行 `adb -s <api36-serial> shell am force-stop com.promenar.nexara.native.debug` 并确认 PID 为空，最后不经过重装直接运行：

  `adb -s <api36-serial> shell am instrument -w -r -e class com.promenar.nexara.background.generation.GenerationForegroundServiceColdStartDeviceTest#<method> com.promenar.nexara.native.debug.test/androidx.test.runner.AndroidJUnitRunner`

  两个 `<method>` 分别为 `coldTrackDuringStartupRecoveryStopsGracefullyWithoutProcessCrash` 与 `coldStopDuringStartupRecoveryStopsGracefullyWithoutCoordinator`。

  任何失败均须定位而非用重跑掩盖；bulk 与两个方法合并计算完整结果。

- [x] **Step 5：独立最终复核**

  由未实施对应代码的 Agent 审查最终 diff、状态/导航数据流、截图 montage、无障碍和性能风险；P0/P1 清零后才收口。

- [x] **Step 6：DIA/HLG 与提交**

  更新 CHANGELOG、计划状态和标准时间戳 handover，使用 HLG 脚本重建索引；不手工维护索引。最后按实施前登记的基线 diff 清单核对并显式提交本阶段文件，不纳入任何用户或非本阶段改动。

  `git commit -m "docs: close Material 3 RAG and resources phase"`

## 完成定义

- 知识库、Memory、文件夹、RAG 详情、资源管理器和回收站使用同一套 Material 3 列表/状态/Sheet 语言。
- 对生产代码真实暴露的空、加载、处理中、可用、失败与重试状态进行准确呈现；Memory 当前仅覆盖 content/empty，其 loading/error/retry P1 另案跟踪，不在视觉夹具中伪造。操作不只靠颜色或长按发现。
- 文件树、目录选择器和大字体 Sheet 最后一项可达，稳定 key 和选择/展开状态不因重排错位。
- 不残留本阶段范围内的 Glass 卡墙、微字号、发光、伪单选图标、固定高度选择栏或固定 70% Sheet 依赖。
- API 31/35/36、JVM、Lint、构建、完整截图与 API 36 bulk/cold-start 门禁通过；独立复核 P0/P1 为零。
- `DocEditorScreen.kt` 明确留待下一独立阶段，不把第三阶段结果误报为全站重设计完成。
