# Nexara Material 3 交互、设置与主题总收敛实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Nexara 的主导航、附件入口、搜索、模型选择、完整设置层级和深浅主题收敛为稳定、克制、可访问的 Android Material 3 产品语言。

**Architecture:** 先建立可持久化的主题状态与双 ColorScheme，再建立共享列表/搜索/操作区原语，随后串行迁移手机导航、会话交互和设置树。业务 ViewModel、Repository、Provider、模型元数据和 RAG 数据契约保持不变；视觉状态通过显式 UI state 和共享组件消费现有数据。最终以深浅色截图、API 31/35/36 设备、500 模型性能和真机 TalkBack 闭合。

**Tech Stack:** Kotlin 2.2、Jetpack Compose、Compose BOM `2026.06.00`、稳定 Material 3 `1.4.0`、StateFlow、SharedPreferences、JUnit4/JUnit5、Compose UI Test、Compose Preview Screenshot Test、FrameMetricsAggregator、Gradle Managed/本地 AVD。

## Global Constraints

- 唯一施工工作树：`/Users/promenar/Codex/Nexara/.worktrees/codex-v0.2-beta`，分支 `codex/md3-redesign`。
- 禁止在 `/Users/promenar/Codex/Nexara` 主工作树实施。
- 禁止 reset、clean、覆盖 checkout、回滚用户改动；忽略并保护 `artifacts/`、`secure_env/`、签名文件、API Key 和所有 `.nexara-workspace-*`。
- 保持 Compose BOM `2026.06.00` 和稳定 Material 3 `1.4.0`；禁止引入 `1.5.0-alpha*`、Expressive Alpha、第三方 UI 框架或模糊/玻璃依赖。
- 业务数据、导航结果、Provider 安全、模型三态元数据、RAG、备份和会话契约不得因视觉迁移改变。
- 普通列表行不成卡；卡片不套卡片；不新增 glow、blur、装饰渐变或永久描边。
- 触控目标至少 48dp；覆盖中文、英文、2.0x、横屏、平板、IME、系统栏、减少动效和 TalkBack。
- `ChatScreen.kt`、`MainTabScaffold.kt`、`UserSettingsHomeScreen.kt`、`ProviderModelsScreen.kt`、`Theme.kt`、`Color.kt`、`MainActivity.kt`、`CHANGELOG.md`、registry 和 handover 为共享热点，写入任务串行。
- 每项核心状态/持久化任务使用 TDD，且只在所属任务开始时直接引用新生产符号，避免未来任务测试阻断整个 unit-test 源集编译。
- 每个任务完成后由主控复核 diff、关键源码、测试和 actual；Critical/Important 关闭后再提交并推送当前分支。
- 禁止目录级 `git add`。每次提交只逐项暂存该 Task 的 `Files` 清单；截图基线也必须在逐张审阅后按完整文件路径逐项暂存，并用 `git diff --cached --name-only` 确认没有夹带保护区或用户改动。
- Tasks 4–13 开始前必须断言 `git status --short -- native-ui/app/src/screenshotTestDebug/reference` 为空。更新截图后，把该干净根下本 Task 产生的 changed golden 完整路径写入对应 `/tmp/nexara-md3-taskN-goldens.txt`，逐张审阅，再逐路径暂存；任何施工前差异或清单外图片都阻断提交。
- 不自动 tag、创建 PR 或 GitHub Release；整体发行保持 NO-GO，直到原发行门禁全部闭合。
- 没有显式 `cd` 的 `./gradlew` 命令块以 `native-ui/` 为 cwd；Git、静态清单和 HLG 命令块以仓库根为 cwd。包含 `cd` 的块按块内命令执行。所有 Git 路径与静态清单使用仓库根相对路径。

## Device Protocol

- API 31 使用 AVD `Nexara_API_31` 和固定串号 `emulator-5554`。
- API 35 使用 AVD `Nexara_API_35` 和固定串号 `emulator-5556`。
- API 36 使用 AVD `Pixel_7` 和固定串号 `emulator-5572`；启动后必须断言 `ro.build.version.sdk=36`，不能只凭 AVD 名称认定版本。
- 每台设备在独立终端以前台进程启动：

  ```bash
  "$ANDROID_HOME/emulator/emulator" -avd Nexara_API_31 -port 5554 -no-snapshot-load
  "$ANDROID_HOME/emulator/emulator" -avd Nexara_API_35 -port 5556 -no-snapshot-load
  "$ANDROID_HOME/emulator/emulator" -avd Pixel_7 -port 5572 -no-snapshot-load
  ```

- 测试前执行并记录真实 API；任一断言失败立即停止对应矩阵，不得换标签或复用旧结果：

  ```bash
  adb -s emulator-5554 wait-for-device
  adb -s emulator-5556 wait-for-device
  adb -s emulator-5572 wait-for-device
  test "$(adb -s emulator-5554 shell getprop ro.build.version.sdk | tr -d '\r')" = "31"
  test "$(adb -s emulator-5556 shell getprop ro.build.version.sdk | tr -d '\r')" = "35"
  test "$(adb -s emulator-5572 shell getprop ro.build.version.sdk | tr -d '\r')" = "36"
  ```

- 减少动效门禁使用系统 Animator duration scale，不新增应用内偏好；只在对应测试前设置，并在测试后恢复：

  ```bash
  adb -s emulator-5572 shell settings put global animator_duration_scale 0
  adb -s emulator-5572 shell settings put global transition_animation_scale 0
  adb -s emulator-5572 shell settings put global window_animation_scale 0
  ANDROID_SERIAL=emulator-5572 ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.chat.AttachmentActionMenuTest
  adb -s emulator-5572 shell settings put global animator_duration_scale 1
  adb -s emulator-5572 shell settings put global transition_animation_scale 1
  adb -s emulator-5572 shell settings put global window_animation_scale 1
  ```

## Planning Baseline

本规格、实施计划、registry 与本次 HLG 记录由规划会话在任何实施 Task 之前逐路径提交并推送；它们不是 Task 1 的未跟踪输入。Task 1 的 `git ls-files --error-unmatch` 是硬门禁：若规划基线未被当前分支跟踪，立即停止，不创建 manifest、不运行施工测试。

## Task 1：冻结当前基线和视觉清单

**Files:**
- Read: `docs/superpowers/specs/2026-07-20-nexara-md3-convergence-design.md`
- Read: `native-ui/app/src/main/java/com/promenar/nexara/ui/MainTabScaffold.kt`
- Read: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt`
- Read: `native-ui/app/src/main/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreen.kt`
- Read: `native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentSessionsScreen.kt`
- Read: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/ModelPicker.kt`
- Read: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/GlobalRagConfigScreen.kt`
- Read: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SearchConfigScreen.kt`
- Read: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderFormScreen.kt`
- Read: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt`
- Create: `native-ui/app/src/test/resources/md3-theme-surface-patterns.txt`
- Create: `.agent/plans/20260720-md3-convergence-task13-manifest.txt`
- Modify: `docs/release/v0.2-beta-validation.md` only when recording measured baseline counts; do not rewrite historical PASS rows.

**Interfaces:**
- Consumes: current HEAD, current screenshot references and existing test suite.
- Produces: a measured baseline for Tasks 2–14; no future production symbol is introduced.

- [x] **Step 1：确认工作树和保护区**

  Run:

  ```bash
  cd /Users/promenar/Codex/Nexara/.worktrees/codex-v0.2-beta
  test "$(git rev-parse --show-toplevel)" = "/Users/promenar/Codex/Nexara/.worktrees/codex-v0.2-beta"
  test "$(git branch --show-current)" = "codex/md3-redesign"
  git ls-files --error-unmatch \
    docs/superpowers/specs/2026-07-20-nexara-md3-convergence-design.md \
    docs/superpowers/plans/2026-07-20-nexara-md3-convergence.md \
    .agent/registry.md
  git diff --cached --quiet
  git status --short
  git log -1 --oneline
  git diff --check
  ```

  Expected: branch is `codex/md3-redesign`; only known `artifacts/` and `.nexara-workspace-*` may remain untracked; no secret or generated workspace is staged.

- [x] **Step 2：运行施工前 JVM、截图、Lint 和 AndroidTest 编译基线**

  Run:

  ```bash
  cd native-ui
  ./gradlew :app:testDebugUnitTest
  ./gradlew :app:validateDebugScreenshotTest
  ./gradlew :app:lintDebug
  ./gradlew :app:compileDebugAndroidTestKotlin
  ```

  Expected: record exact test/failure/error/skip counts; skip is recorded as skip, not PASS. Any failure is triaged before Task 2 and is not hidden with disabled tests.

- [x] **Step 3：冻结遗留视觉扫描**

  Create `native-ui/app/src/test/resources/md3-theme-surface-patterns.txt` as the single scan-rule source with these regex lines:

  ```text
  NexaraGlassCard|GlassSurface|GlassBorder
  drawBehind|setShadowLayer
  NexaraColors\.
  Color\(0x[0-9A-Fa-f]+
  Color\.(Black|White|Gray|DarkGray|LightGray)
  #[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?
  ```

  Run:

  ```bash
  rg -n -f native-ui/app/src/test/resources/md3-theme-surface-patterns.txt \
    native-ui/app/src/main/java/com/promenar/nexara/ui
  rg -n "NexaraSearchBar\\(" native-ui/app/src/main/java/com/promenar/nexara/ui
  {
    rg -l -f native-ui/app/src/test/resources/md3-theme-surface-patterns.txt \
      native-ui/app/src/main/java/com/promenar/nexara/ui
    printf '%s\n' \
      native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/EChartsRenderer.kt \
      native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/GfmAlertBlock.kt \
      native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/HtmlArtifactRenderer.kt \
      native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/InlineLatexSpan.kt \
      native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/LatexRenderer.kt \
      native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/MermaidRenderer.kt \
      native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/PlantUmlRenderer.kt \
      native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/RichContentWebView.kt \
      native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/TableWidget.kt \
      native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Color.kt \
      native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Theme.kt
  } | LC_ALL=C sort -u > .agent/plans/20260720-md3-convergence-task13-manifest.txt
  ```

  Expected: save counts and the complete `ui/**` file list, including `welcome/` and all explicit renderer/theme call sites, in the committed manifest; do not bulk-edit from this scan. Tasks 2–12 may not introduce a matched static token in a new production UI file; the Task 13 full-tree contract enforces this.

- [x] **Step 4：人工检查当前管理 actual**

  Inspect every rendered settings, Provider, model, RAG and navigation screenshot under:

  ```text
  native-ui/app/build/outputs/screenshotTest-results/preview/debug/rendered/
  ```

  Expected: classify each screen as `keep`, `migrate-list`, `migrate-form`, `theme-blocked` or `missing-baseline`; passing screenshot validation alone is not visual approval.

- [x] **Step 5：提交和推送基线记录（仅在文档真实变化时）**

  ```bash
  git add native-ui/app/src/test/resources/md3-theme-surface-patterns.txt \
    .agent/plans/20260720-md3-convergence-task13-manifest.txt
  if ! git diff --quiet -- docs/release/v0.2-beta-validation.md; then
    git add docs/release/v0.2-beta-validation.md
  fi
  git diff --cached --name-only
  git ls-files --error-unmatch \
    native-ui/app/src/test/resources/md3-theme-surface-patterns.txt \
    .agent/plans/20260720-md3-convergence-task13-manifest.txt
  git commit -m "docs: record Material 3 convergence baseline"
  git push origin codex/md3-redesign
  ```

## Task 2：建立真实主题状态、持久化和双 ColorScheme

**Files:**
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/NexaraThemePreferences.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/ThemePreferenceStore.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/theme/ThemePreferenceStoreTest.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/NexaraApplication.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/MainActivity.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SettingsViewModel.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Theme.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Color.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/data/backup/BackupPreferencePolicy.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/theme/NexaraThemeTokenTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/SettingsViewModelTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/data/backup/BackupPreferenceInventoryTest.kt`

**Interfaces:**
- Consumes: application SharedPreferences and `isSystemInDarkTheme()`.
- Produces: `NexaraThemeMode`, `NexaraColorSource`, `NexaraThemePreferences`, `ThemePreferenceStore.state`, `NexaraLightColorScheme`, `NexaraDarkColorScheme`.

- [x] **Step 1：写主题持久化 RED**

  Add tests that directly assert:

  ```kotlin
  @Test fun `missing preference defaults to dark Nexara colors`()
  @Test fun `mode and color source persist and restore`()
  @Test fun `unknown persisted values fall back safely`()
  @Test fun `external preference restore updates state without restart`()
  @Test fun `settings view model theme compatibility delegates to store`()
  @Test fun `backup UI whitelist contains both theme keys`()
  ```

  Run:

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*ThemePreferenceStoreTest' \
    --tests '*SettingsViewModelTest' \
    --tests '*BackupPreferenceInventoryTest'
  ```

  Expected: RED because the new types/store and `theme_color_source` whitelist do not exist.

- [x] **Step 2：实现冻结接口**

  Implement exactly:

  ```kotlin
  enum class NexaraThemeMode { SYSTEM, LIGHT, DARK }
  enum class NexaraColorSource { NEXARA, DYNAMIC }

  data class NexaraThemePreferences(
      val mode: NexaraThemeMode = NexaraThemeMode.DARK,
      val colorSource: NexaraColorSource = NexaraColorSource.NEXARA,
  )
  ```

  `ThemePreferenceStore` exposes `StateFlow<NexaraThemePreferences>` and synchronous `setMode` / `setColorSource`; persisted keys are exactly `theme_mode` and `theme_color_source`. It registers a SharedPreferences change listener for the application-owned lifetime so transactional backup restore updates the StateFlow immediately.

- [x] **Step 3：接入应用根主题**

  Add one application-owned `ThemePreferenceStore`; collect it with lifecycle in `MainActivity`; call:

  ```kotlin
  NexaraTheme(
      preferences = themePreferences,
  ) { /* existing root */ }
  ```

  Theme selection order is:

  ```kotlin
  when {
      preferences.colorSource == DYNAMIC && supportsDynamic && useDark -> dynamicDarkColorScheme(context)
      preferences.colorSource == DYNAMIC && supportsDynamic -> dynamicLightColorScheme(context)
      useDark -> NexaraDarkColorScheme
      else -> NexaraLightColorScheme
  }
  ```

  Replace `SettingsViewModel`'s independent `_themeMode` and direct SharedPreferences writer with a temporary compatibility projection/delegation to the application Store. There must be only one mutable owner of both theme keys.

- [x] **Step 4：补齐浅色语义角色和系统栏**

  Define complete light/dark surface container roles. Derive status/navigation/icon colors from `MaterialTheme.colorScheme`; set system bar icon appearance from resolved light/dark mode, not a hard-coded `false`.

- [x] **Step 5：运行 GREEN 和编译**

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*ThemePreferenceStoreTest' \
    --tests '*NexaraThemeTokenTest' \
    --tests '*SettingsViewModelTest' \
    --tests '*BackupPreferenceInventoryTest'
  ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
  ```

  Expected: all selected tests PASS and no existing backup key is removed.

- [x] **Step 6：独立复审、提交和推送**

  Review migration defaults, corrupted values, process restart and backup whitelist. Then:

  ```bash
  git add native-ui/app/src/main/java/com/promenar/nexara/NexaraApplication.kt \
    native-ui/app/src/main/java/com/promenar/nexara/MainActivity.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SettingsViewModel.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/theme/NexaraThemePreferences.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/theme/ThemePreferenceStore.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Theme.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Color.kt \
    native-ui/app/src/main/java/com/promenar/nexara/data/backup/BackupPreferencePolicy.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/theme/ThemePreferenceStoreTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/theme/NexaraThemeTokenTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/settings/SettingsViewModelTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/data/backup/BackupPreferenceInventoryTest.kt
  git commit -m "feat: establish adaptive Material 3 themes"
  git push origin codex/md3-redesign
  ```

## Task 3：收敛共享页面、列表、搜索和操作区原语

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraPageLayout.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraSettingsItem.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/SettingsSectionHeader.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraSettingsSection.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraSearchTopBar.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/common/Md3ConvergencePrimitivesContractTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/common/ManagementMaterialContractTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/hub/UserSettingsAccessibilityTest.kt`

**Interfaces:**
- Consumes: `MaterialTheme`, `NexaraSpacing`, existing page routes.
- Produces: `NexaraSettingsSection`, `NexaraSearchTopBar`, transparent settings rows and a standard page action slot.

- [ ] **Step 1：写共享组件 RED**

  Contract tests assert:

  ```text
  NexaraSettingsSection uses no NexaraGlassCard and no per-row Surface.
  NexaraSettingsItem remains a transparent ListItem with 48dp semantics.
  NexaraSearchTopBar exposes normal/search state, clear and exit actions.
  NexaraPageLayout uses MaterialTheme roles and owns system/IME insets.
  ```

  Run:

  ```bash
  ./gradlew :app:testDebugUnitTest --tests '*Md3ConvergencePrimitivesContractTest' --tests '*ManagementMaterialContractTest'
  ```

  Expected: RED for missing `NexaraSettingsSection` / `NexaraSearchTopBar`.

- [ ] **Step 2：实现最小共享原语**

  `NexaraSettingsSection` accepts `title` and content, uses a small semantic section label and a divider aligned with the row text. `NexaraSearchTopBar` accepts:

  ```kotlin
  @Composable
  fun NexaraSearchTopBar(
      title: String,
      query: String,
      searchActive: Boolean,
      onQueryChange: (String) -> Unit,
      onSearchActiveChange: (Boolean) -> Unit,
      onBack: (() -> Unit)? = null,
      actions: @Composable RowScope.() -> Unit = {},
  )
  ```

  It handles focus, IME Search, clear, back precedence and reduced-motion state without owning business filtering.

- [ ] **Step 3：验证语义和 2.0x 布局**

  Extend `UserSettingsAccessibilityTest` with 48dp, unique click targets, search traversal and long-title assertions.

  ```bash
  ./gradlew :app:testDebugUnitTest --tests '*Md3ConvergencePrimitivesContractTest' --tests '*ManagementMaterialContractTest'
  ./gradlew :app:compileDebugAndroidTestKotlin
  ```

- [ ] **Step 4：复审、提交和推送**

  ```bash
  git add native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraPageLayout.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraSettingsItem.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/common/SettingsSectionHeader.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraSettingsSection.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/common/NexaraSearchTopBar.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/common/Md3ConvergencePrimitivesContractTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/common/ManagementMaterialContractTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/hub/UserSettingsAccessibilityTest.kt
  git commit -m "refactor: establish Material 3 management primitives"
  git push origin codex/md3-redesign
  ```

## Task 4：使用标准 Material 导航构建手机浮动导航坞

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/MainTabScaffold.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/AdaptiveNavigationTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

**Interfaces:**
- Consumes: `AppTab`, `shouldUseNavigationRail(width)` and Material theme.
- Produces: phone `Surface + NavigationBar + NavigationBarItem`; existing `NavigationRail` remains for `>= 600dp`.

- [ ] **Step 1：写导航 RED**

  Add assertions for three semantic tabs, selected state, minimum targets, phone navigation tag, tablet rail, and content bottom clearance. Add dark/light screenshot previews.

  ```bash
  ./gradlew :app:compileDebugAndroidTestKotlin :app:compileDebugScreenshotTestKotlin
  ./gradlew :app:validateDebugScreenshotTest
  ```

  Expected: test sources compile, then screenshot validation is RED because the new dark/light navigation goldens do not exist or differ from the current custom Row.

- [ ] **Step 2：替换自绘 Row**

  Remove `drawBehind`, `setShadowLayer`, glow and custom scale-only feedback. Wrap transparent `NavigationBar` in one tonal `Surface`, and render each destination with `NavigationBarItem`.

- [ ] **Step 3：验证 phone/rail 和系统手势区**

  ```bash
  ./gradlew :app:compileDebugAndroidTestKotlin
  ANDROID_SERIAL=emulator-5572 ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.AdaptiveNavigationTest
  ```

  Expected: compact width shows exactly one navigation bar; expanded width shows exactly one rail; content remains reachable above gesture navigation.

- [ ] **Step 4：逐张审阅截图后更新基线**

  Run `:app:updateDebugScreenshotTest`, inspect dark/light phone and tablet actual, then run `:app:validateDebugScreenshotTest`. Do not bulk accept without visual review.

- [ ] **Step 5：提交和推送**

  ```bash
  git add native-ui/app/src/main/java/com/promenar/nexara/ui/MainTabScaffold.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/AdaptiveNavigationTest.kt \
    native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt
  git ls-files --modified --deleted --others --exclude-standard -- native-ui/app/src/screenshotTestDebug/reference > /tmp/nexara-md3-task4-goldens.txt
  cat /tmp/nexara-md3-task4-goldens.txt
  while IFS= read -r path; do git add -- "$path"; done < /tmp/nexara-md3-task4-goldens.txt
  git commit -m "feat: adopt adaptive Material 3 navigation"
  git push origin codex/md3-redesign
  ```

## Task 5：实现锚定式附件动作簇

**Files:**
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/AttachmentActionMenu.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt`
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/AttachmentActionMenuTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/ChatScreenContentStateTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/AccessibilitySmokeTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

**Interfaces:**
- Consumes: `onPickImage`, `onPickDocument`, generation/importing state.
- Produces: `AttachmentActionMenu` with explicit expanded state and anchored actions.

- [ ] **Step 1：写交互 RED**

  Tests cover open, close, outside/back dismissal, selecting each action, generation/import disable, 48dp targets, state description, complete visibility outside the clipped composer background, IME/2.0x/window-edge placement and no action overlap with send/model/token controls.

  ```bash
  ./gradlew :app:compileDebugAndroidTestKotlin
  ANDROID_SERIAL=emulator-5572 ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.chat.AttachmentActionMenuTest
  ```

  Expected: RED because the anchored menu tags/behavior do not exist.

- [ ] **Step 2：实现稳定 Compose 动效**

  Move the action cluster and a full-parent transparent dismiss layer into the parent overlay above `ChatInputBar`; clip only the composer background. Use `AnimatedVisibility`, `animateFloatAsState`, `scaleIn/out`, `fadeIn/out` and measured vertical placement anchored to the button. Do not use `DropdownMenu`, Popup offset guesses or alpha Material APIs.

- [ ] **Step 3：处理状态和减少动效**

  Close the menu when generation/import starts. `+` rotates to close semantics while expanded. Compose transitions must respect the system duration scale; at scale 0 no custom timer may delay visibility, semantics or action availability.

- [ ] **Step 4：设备和 actual 验收**

  Run the attachment and existing chat tests on API 31/35/36; inspect normal, 2.0x and IME-open screenshots. Menu must visually grow from the `+` control and not cover model/token metadata.

- [ ] **Step 5：提交和推送**

  ```bash
  git add native-ui/app/src/main/java/com/promenar/nexara/ui/chat/AttachmentActionMenu.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/AttachmentActionMenuTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/ChatScreenContentStateTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/AccessibilitySmokeTest.kt \
    native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt
  git ls-files --modified --deleted --others --exclude-standard -- native-ui/app/src/screenshotTestDebug/reference > /tmp/nexara-md3-task5-goldens.txt
  cat /tmp/nexara-md3-task5-goldens.txt
  while IFS= read -r path; do git add -- "$path"; done < /tmp/nexara-md3-task5-goldens.txt
  git commit -m "feat: animate anchored chat attachment actions"
  git push origin codex/md3-redesign
  ```

## Task 6：将助手会话和低频列表搜索迁移到顶栏搜索模式

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentSessionsScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentHubScreen.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/hub/SessionListViewModelTest.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/hub/AgentSessionsMaterialContractTest.kt`
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/hub/AgentSessionsMaterialTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

**Interfaces:**
- Consumes: `NexaraSearchTopBar`, existing session filtering and swipe actions.
- Produces: contextual search state and continuous session/Agent rows.

- [ ] **Step 1：写状态与 UI RED**

  Lock query filtering, back precedence, scroll preservation, empty search state, whole-row navigation, independent pin/delete/menu semantics and no `NexaraGlassCard` in session rows.

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*SessionListViewModelTest' \
    --tests '*AgentSessionsMaterialContractTest'
  ```

  Expected: RED because the screen still contains fixed `NexaraSearchBar` and Glass session rows.

- [ ] **Step 2：迁移 AgentSessionsScreen**

  Replace sticky fixed search and `SessionCard` with top-bar search mode and transparent `ListItem` rows. Keep `SwipeableItem`, pin/delete confirmation, timestamps and last-message preview.

- [ ] **Step 3：迁移 AgentHubScreen 的低频搜索入口**

  Keep existing continuous Agent list and actions; move fixed search to the same top-bar mode without altering Agent creation or navigation.

- [ ] **Step 4：运行 GREEN 与截图**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests '*SessionListViewModelTest' --tests '*AgentSessionsMaterialContractTest'
  ./gradlew :app:compileDebugAndroidTestKotlin
  ./gradlew :app:validateDebugScreenshotTest
  ```

  Execute the new device test on API 31/35/36 and visually inspect empty, two-session, long-title, 2.0x and search-active actual.

- [ ] **Step 5：提交和推送**

  ```bash
  git add native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentSessionsScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentHubScreen.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/hub/SessionListViewModelTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/hub/AgentSessionsMaterialContractTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/hub/AgentSessionsMaterialTest.kt \
    native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt
  git ls-files --modified --deleted --others --exclude-standard -- native-ui/app/src/screenshotTestDebug/reference > /tmp/nexara-md3-task6-goldens.txt
  cat /tmp/nexara-md3-task6-goldens.txt
  while IFS= read -r path; do git add -- "$path"; done < /tmp/nexara-md3-task6-goldens.txt
  git commit -m "feat: streamline Material 3 session search"
  git push origin codex/md3-redesign
  ```

## Task 7：统一模型选择器为标准 Sheet 连续列表

**Files:**
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/ModelSelectionUiModel.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/ModelSelectionListItem.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/ModelPicker.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/SessionSettingsSheet.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreen.kt` only for temporary callers; final IA is Task 8.
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/common/ModelPickerMaterialContractTest.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/common/ModelSelectionUiModelTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/chat/SessionSettingsModelFilterTest.kt`
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/common/ModelPickerAccessibilityTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

**Interfaces:**
- Consumes: `ModelInfo`, `ModelMetadataResolver.resolve(remoteModelId, providerId)` and selected stable model ID.
- Produces: frozen `ModelSelectionUiModel`, shared `ModelSelectionListItem` and one visual selection contract; removes the old UI-only `ModelItem` / `ModelCapability` contract.

- [ ] **Step 1：写模型列表 RED**

  Assert continuous `ListItem`, stable key, selected semantics, selected tonal color, exact friendly name, real provider/context, at most two summary capabilities, no `NexaraGlassCard`, no hard-coded capability colors and no unknown-to-chat coercion. Projection tests cover supported/unsupported/unknown; user-edited name, type, capabilities, context and output override conversion; `CHAT_ENDPOINT` exclusion from `capabilityStates`; and `chatEndpointCompatible` remaining independent from workload/reasoning.

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*ModelSelectionUiModelTest' \
    --tests '*ModelPickerMaterialContractTest' \
    --tests '*SessionSettingsModelFilterTest'
  ```

  Expected: RED because `ModelSelectionUiModel` does not exist and the current empty-capability fallback manufactures `CHAT`.

- [ ] **Step 2：实现共享模型行**

  Implement the exact projection frozen by the design spec. Keep search/debounce and filtering behavior. Use title/supporting/trailing slots, a divider aligned with the row text and a check icon for selected state. Preserve capability unknown and endpoint compatibility separation.

- [ ] **Step 3：替换两个消费端**

  `ModelPicker` and `SessionSettingsSheet` must render the same shared row. Delete the local empty-capability-to-`CHAT` fallback. Do not create a top-level metadata facade; use `ModelMetadataResolver.resolve(...)` through the frozen projection factory.

- [ ] **Step 4：运行测试和视觉门禁**

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*ModelPickerMaterialContractTest' \
    --tests '*ModelSelectionUiModelTest' \
    --tests '*SessionSettingsModelFilterTest' \
    --tests '*ModelDisplayNameResolverTest'
  ./gradlew :app:compileDebugAndroidTestKotlin
  ./gradlew :app:validateDebugScreenshotTest
  ```

  Inspect 100+ model list, long names, unknown capability, 2.0x and light/dark actual.

- [ ] **Step 5：提交和推送**

  ```bash
  git add native-ui/app/src/main/java/com/promenar/nexara/ui/common/ModelSelectionListItem.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/common/ModelSelectionUiModel.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/common/ModelPicker.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/chat/SessionSettingsSheet.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreen.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/common/ModelPickerMaterialContractTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/common/ModelSelectionUiModelTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/chat/SessionSettingsModelFilterTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/common/ModelPickerAccessibilityTest.kt \
    native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt
  git ls-files --modified --deleted --others --exclude-standard -- native-ui/app/src/screenshotTestDebug/reference > /tmp/nexara-md3-task7-goldens.txt
  cat /tmp/nexara-md3-task7-goldens.txt
  while IFS= read -r path; do git add -- "$path"; done < /tmp/nexara-md3-task7-goldens.txt
  git commit -m "refactor: unify Material 3 model selection"
  git push origin codex/md3-redesign
  ```

## Task 8：重建设置首页信息架构和 Provider 二级入口

**Files:**
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderListScreen.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/DefaultModelsScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SettingsViewModel.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/navigation/NavGraph.kt`
- Modify: `native-ui/app/src/main/res/values/strings.xml`
- Modify: `native-ui/app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreenContractTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/SettingsViewModelTest.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/navigation/SettingsNavigationContractTest.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/DefaultModelsScreenContractTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/hub/UserSettingsAccessibilityTest.kt`
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/DefaultModelsInteractionTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

**Interfaces:**
- Consumes: existing SettingsViewModel state/actions and Provider routes.
- Produces: one settings hierarchy and a dedicated Provider list route.

- [ ] **Step 1：写 IA RED**

  Contract tests require no `PrimaryTabRow`, no embedded Provider tab, one appearance route, one Provider management route, one default-model route, and ordered section labels from the design spec. `DefaultModelsScreenContractTest` requires exactly four roles (`summary`, `image`, `embedding`, `rerank`), the shared model picker, immediate `setPresetModel(type, modelId)` persistence and no second save-on-return state. `DefaultModelsInteractionTest` selects each role, verifies immediate row update and picker dismissal, leaves/re-enters and recreates the Activity to verify persistence, then asserts Back causes no second write.

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*UserSettingsHomeScreenContractTest' \
    --tests '*SettingsNavigationContractTest' \
    --tests '*DefaultModelsScreenContractTest'
  ./gradlew :app:compileDebugAndroidTestKotlin
  ANDROID_SERIAL=emulator-5572 ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.settings.DefaultModelsInteractionTest
  ```

  Expected: unit contracts and the API 36 interaction test are RED because the dedicated Provider/default-model routes and `DefaultModelsScreen` do not exist.

- [ ] **Step 2：抽取 ProviderListScreen**

  Reuse existing Provider state/actions; render add in Top App Bar, continuous Provider `ListItem`, status subtitle and overflow edit/delete. Preserve whole-row model navigation and delete confirmation.

- [ ] **Step 3：重建设置首页**

  Render account, general, AI/model, knowledge/retrieval, tools/data and about sections. Profile becomes an ordinary row. Remove the Provider tab and four inline model picker rows; add destination rows with concise current-value summaries. Replace the temporary raw-string `themeMode` compatibility property with a typed projection from `ThemePreferenceStore`; no independent theme state or direct theme preference writer remains in `SettingsViewModel`.

- [ ] **Step 4：实现单一默认模型页面**

  `DefaultModelsScreen` renders four continuous summary rows for summary/image/embedding/rerank. Selecting a row opens the shared Task 7 model picker with the existing role filter; choosing a model immediately calls `setPresetModel(type, modelId)`, updates the row and dismisses the picker. Back only navigates away and does not own a draft or second save action.

- [ ] **Step 5：补齐导航与双语**

  Add stable route constants and navigation tests. Do not change existing Provider Form/Models routes; the new list route composes them.

- [ ] **Step 6：运行 GREEN、设备和 actual**

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*UserSettingsHomeScreenContractTest' \
    --tests '*SettingsViewModelTest' \
    --tests '*SettingsNavigationContractTest' \
    --tests '*DefaultModelsScreenContractTest'
  ./gradlew :app:compileDebugAndroidTestKotlin
  ./gradlew :app:validateDebugScreenshotTest
  ```

  Execute `DefaultModelsInteractionTest` and normal/2.0x/landscape/tablet settings navigation on API 31/35/36. Inspect that density improves without shrinking typography.

- [ ] **Step 7：提交和推送**

  ```bash
  git add native-ui/app/src/main/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderListScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/DefaultModelsScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SettingsViewModel.kt \
    native-ui/app/src/main/java/com/promenar/nexara/navigation/NavGraph.kt \
    native-ui/app/src/main/res/values/strings.xml \
    native-ui/app/src/main/res/values-zh-rCN/strings.xml \
    native-ui/app/src/test/java/com/promenar/nexara/ui/hub/UserSettingsHomeScreenContractTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/settings/SettingsViewModelTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/settings/DefaultModelsScreenContractTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/navigation/SettingsNavigationContractTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/hub/UserSettingsAccessibilityTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/DefaultModelsInteractionTest.kt \
    native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt
  git ls-files --modified --deleted --others --exclude-standard -- native-ui/app/src/screenshotTestDebug/reference > /tmp/nexara-md3-task8-goldens.txt
  cat /tmp/nexara-md3-task8-goldens.txt
  while IFS= read -r path; do git add -- "$path"; done < /tmp/nexara-md3-task8-goldens.txt
  git commit -m "feat: rebuild Material 3 settings hierarchy"
  git push origin codex/md3-redesign
  ```

## Task 9：迁移记忆、索引和检索设置

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/GlobalRagConfigScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SearchConfigScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/AdvancedRetrievalScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagAdvancedScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentAdvancedRetrievalScreen.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/SearchConfigViewModelTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/domain/usecase/RagConfigPersistenceTest.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/rag/RagSettingsMaterialContractTest.kt`
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/RagSettingsAccessibilityTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

**Interfaces:**
- Consumes: existing RAG/Search ViewModels and persistence.
- Produces: standard segmented preset, parameter sections, navigation rows and destructive footer actions.

- [ ] **Step 1：写业务保持和视觉 RED**

  Existing ViewModel/persistence tests lock each parameter. New contract test rejects `NexaraGlassCard`, `GlassBorder`, nested cards and hard-coded page colors in all five screens.

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*SearchConfigViewModelTest' \
    --tests '*RagConfigPersistenceTest' \
    --tests '*RagSettingsMaterialContractTest'
  ```

  Expected: RED in `RagSettingsMaterialContractTest` because the five target screens still contain Glass/card nesting.

- [ ] **Step 2：迁移 GlobalRagConfigScreen**

  Use standard single-choice segmented controls for presets; unframed slider sections; a summary-template navigation row; advanced/debug list rows; a bottom destructive text action with confirmation.

- [ ] **Step 3：迁移 SearchConfigScreen 和高级检索页**

  Use `ListItem + Switch`, radio/dropdown engine selection, slider rows and continuous domain lists. Reuse the same visual primitives across global, RAG and Agent scopes without merging their state owners.

- [ ] **Step 4：验证参数、无障碍和主题**

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*SearchConfigViewModelTest' \
    --tests '*RagConfigPersistenceTest' \
    --tests '*RagSettingsMaterialContractTest'
  ./gradlew :app:compileDebugAndroidTestKotlin
  ./gradlew :app:validateDebugScreenshotTest
  ```

  Verify slider values, preset changes, secret field behavior, domain add/remove, clear confirmation, 2.0x, landscape and both themes.

- [ ] **Step 5：提交和推送**

  ```bash
  git add native-ui/app/src/main/java/com/promenar/nexara/ui/rag/GlobalRagConfigScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/rag/AdvancedRetrievalScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/rag/RagAdvancedScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SearchConfigScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/hub/AgentAdvancedRetrievalScreen.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/settings/SearchConfigViewModelTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/domain/usecase/RagConfigPersistenceTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/rag/RagSettingsMaterialContractTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/rag/RagSettingsAccessibilityTest.kt \
    native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt
  git ls-files --modified --deleted --others --exclude-standard -- native-ui/app/src/screenshotTestDebug/reference > /tmp/nexara-md3-task9-goldens.txt
  cat /tmp/nexara-md3-task9-goldens.txt
  while IFS= read -r path; do git add -- "$path"; done < /tmp/nexara-md3-task9-goldens.txt
  git commit -m "refactor: converge Material 3 retrieval settings"
  git push origin codex/md3-redesign
  ```

## Task 10：收敛 Provider 表单和密钥交互

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderFormScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/SettingsInput.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/common/ProtocolSelector.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/ProviderModelReleaseBlockersTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/ProviderFormInteractionTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

**Interfaces:**
- Consumes: existing Provider save/test/security actions.
- Produces: unframed Material form sections and one primary save action.

- [ ] **Step 1：写交互和视觉 RED**

  Keep masked/reveal/timeout/focus/leave-page behavior, HTTPS validation, test cancellation, local Provider flow and save behavior. Add contract assertions rejecting the large configuration `Surface` and stacked full-width primary buttons.

  ```bash
  ./gradlew :app:testDebugUnitTest --tests '*ProviderModelReleaseBlockersTest'
  ```

  Expected: RED in the new Provider form layout assertions while existing security/behavior assertions remain GREEN.

- [ ] **Step 2：重排表单**

  Place fields directly in the LazyColumn under small section labels. Keep exposed dropdowns. Move test/save into a responsive bottom action area: test is outlined/tonal, save is the only filled action.

- [ ] **Step 3：验证 IME、错误和横屏**

  Execute `ProviderFormInteractionTest` on API 31/35/36 with add/edit/local, masked/reveal, invalid URL, failed test, 2.0x and landscape IME scenarios.

- [ ] **Step 4：运行回归并提交**

  ```bash
  ./gradlew :app:testDebugUnitTest --tests '*ProviderModelReleaseBlockersTest'
  ./gradlew :app:compileDebugAndroidTestKotlin
  ./gradlew :app:validateDebugScreenshotTest
  git add native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderFormScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/common/SettingsInput.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/common/ProtocolSelector.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/settings/ProviderModelReleaseBlockersTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/ProviderFormInteractionTest.kt \
    native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt
  git ls-files --modified --deleted --others --exclude-standard -- native-ui/app/src/screenshotTestDebug/reference > /tmp/nexara-md3-task10-goldens.txt
  cat /tmp/nexara-md3-task10-goldens.txt
  while IFS= read -r path; do git add -- "$path"; done < /tmp/nexara-md3-task10-goldens.txt
  git commit -m "refactor: streamline Material 3 provider forms"
  git push origin codex/md3-redesign
  ```

## Task 11：将 Provider Models 拆为摘要列表和独立编辑表面

**Files:**
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ModelEditorSheet.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ModelSyncNotice.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/ProviderModelsScreenContractTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/ModelSyncNoticeTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/ProviderModelsAccessibilityTest.kt`
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/ProviderModelsPerformanceTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

**Interfaces:**
- Consumes: existing `ModelInfo`, update/toggle/test/delete actions and metadata source tracking.
- Produces: summary model row and `ModelEditorSheet` with the existing edit contract.

- [ ] **Step 1：写摘要/编辑分离 RED**

  Contract requires no expanded full form inside list rows, persistent search, sync in top actions, add as primary, destructive bulk actions in overflow, stable keys and unchanged user-edit source recording.

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*ProviderModelsScreenContractTest' \
    --tests '*ModelSyncNoticeTest'
  ```

  Expected: RED because the current list still embeds the full editor and exposes actions at one hierarchy.

- [ ] **Step 2：实现摘要列表**

  Each row shows friendly name, exact remote model ID, at most two capabilities, remaining count and Switch. Row click opens editor; Switch does not also open the row.

- [ ] **Step 3：移动编辑字段到 ModelEditorSheet**

  Move type, capabilities, display name, context, test, delete and source details from `EnhancedModelCard` into the Sheet. Preserve test cancellation, error notice and user-edit recording.

- [ ] **Step 4：实现 500 模型性能夹具**

  Reuse the project `FrameMetricsAggregator` pattern: 3 warm-up rounds, 5 measured rounds, deterministic 500-model fixture, repeated search/scroll/toggle/open/close. Gate total frame p95 `<= 50ms`, max `<= 150ms`, stable PSS increase `<= 64MiB`; fixture variance `> 20%` is a fixture failure, not permission to relax thresholds.

- [ ] **Step 5：运行回归、性能和 actual**

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*ProviderModelsScreenContractTest' \
    --tests '*ModelSyncNoticeTest' \
    --tests '*ProviderModelMetadataMigrationTest'
  ./gradlew :app:compileDebugAndroidTestKotlin
  ANDROID_SERIAL=emulator-5572 ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.settings.ProviderModelsPerformanceTest
  ./gradlew :app:validateDebugScreenshotTest
  ```

- [ ] **Step 6：独立复审、提交和推送**

  ```bash
  git add native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ModelEditorSheet.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ProviderModelsScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ModelSyncNotice.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/settings/ProviderModelsScreenContractTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/settings/ModelSyncNoticeTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/ProviderModelsAccessibilityTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/ProviderModelsPerformanceTest.kt \
    native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt
  git ls-files --modified --deleted --others --exclude-standard -- native-ui/app/src/screenshotTestDebug/reference > /tmp/nexara-md3-task11-goldens.txt
  cat /tmp/nexara-md3-task11-goldens.txt
  while IFS= read -r path; do git add -- "$path"; done < /tmp/nexara-md3-task11-goldens.txt
  git commit -m "feat: simplify Material 3 model management"
  git push origin codex/md3-redesign
  ```

## Task 12：迁移其余设置二三级页面并接通主题页面

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ThemeScreen.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ThemeViewModel.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/ThemeViewModelTest.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/BackupSettingsScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SkillsScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/TokenUsageScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/LocalModelsScreen.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/settings/DeveloperScreen.kt`
- Modify: `native-ui/app/src/main/res/values/strings.xml`
- Modify: `native-ui/app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/settings/BackupViewModelTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/BackupSettingsScreenTest.kt`
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/ThemeSettingsInteractionTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

**Interfaces:**
- Consumes: `ThemePreferenceStore`, existing settings state/actions.
- Produces: functional appearance screen and one settings visual language across remaining pages.

- [ ] **Step 1：写 ThemeViewModel RED**

  Tests assert immediate persistence, process recreation, dynamic-color availability state and root `StateFlow` propagation. Theme screen must not use local `remember` as source of truth.

  ```bash
  ./gradlew :app:testDebugUnitTest --tests '*ThemeViewModelTest'
  ```

  Expected: RED because `ThemeViewModel` does not exist and the current Theme screen owns local fake state.

- [ ] **Step 2：实现外观页面**

  Use standard single-choice rows for System/Light/Dark and a Switch/ListItem for dynamic color. Remove the nonfunctional accent preset palette. Unsupported dynamic color is visibly disabled with localized supporting text; Android 12+ enabled and pre-12 disabled states are both covered.

- [ ] **Step 3：逐页清除普通设置卡片化**

  For Backup, Skills, Token, Local Models and Developer: keep only genuinely independent tool cards; convert ordinary options to settings sections/ListItems; remove nested cards and static dark colors. Do not alter backup encryption, Skill enablement, usage accounting or local inference behavior.

- [ ] **Step 4：运行业务、无障碍和截图回归**

  ```bash
  ./gradlew :app:testDebugUnitTest \
    --tests '*ThemeViewModelTest' \
    --tests '*BackupViewModelTest'
  ./gradlew :app:compileDebugAndroidTestKotlin
  ./gradlew :app:validateDebugScreenshotTest
  ```

  Run `BackupSettingsScreenTest` and `ThemeSettingsInteractionTest` on API 31/35/36 and inspect every migrated page in dark/light, English/Chinese and 2.0x.

- [ ] **Step 5：提交和推送**

  ```bash
  git add native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ThemeScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/ThemeViewModel.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/BackupSettingsScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/SkillsScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/TokenUsageScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/LocalModelsScreen.kt \
    native-ui/app/src/main/java/com/promenar/nexara/ui/settings/DeveloperScreen.kt \
    native-ui/app/src/main/res/values/strings.xml \
    native-ui/app/src/main/res/values-zh-rCN/strings.xml \
    native-ui/app/src/test/java/com/promenar/nexara/ui/settings/ThemeViewModelTest.kt \
    native-ui/app/src/test/java/com/promenar/nexara/ui/settings/BackupViewModelTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/BackupSettingsScreenTest.kt \
    native-ui/app/src/androidTest/java/com/promenar/nexara/ui/settings/ThemeSettingsInteractionTest.kt \
    native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt
  git ls-files --modified --deleted --others --exclude-standard -- native-ui/app/src/screenshotTestDebug/reference > /tmp/nexara-md3-task12-goldens.txt
  cat /tmp/nexara-md3-task12-goldens.txt
  while IFS= read -r path; do git add -- "$path"; done < /tmp/nexara-md3-task12-goldens.txt
  git commit -m "feat: complete Material 3 settings surfaces"
  git push origin codex/md3-redesign
  ```

## Task 13：完成全站浅色语义色和富文本渲染迁移

**Files:**
- Read: `native-ui/app/src/test/resources/md3-theme-surface-patterns.txt`
- Read: `.agent/plans/20260720-md3-convergence-task13-manifest.txt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Color.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Theme.kt`
- Modify all production UI files returned by the frozen Task 1 static-color scan under the complete `native-ui/app/src/main/java/com/promenar/nexara/ui/**` tree, including `welcome/` and renderer call sites.
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/EChartsRenderer.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/GfmAlertBlock.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/HtmlArtifactRenderer.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/InlineLatexSpan.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/LatexRenderer.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/MermaidRenderer.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/PlantUmlRenderer.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/RichContentWebView.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/TableWidget.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/theme/ThemeSurfaceContractTest.kt`
- Modify: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`

**Interfaces:**
- Consumes: resolved `MaterialTheme.colorScheme`.
- Produces: no static deep-dark dependency in formal UI; theme-aware native and WebView renderers.

- [ ] **Step 1：写全站主题 RED**

  `ThemeSurfaceContractTest` reads `md3-theme-surface-patterns.txt`, scans the complete formal `ui/**` tree and rejects matched static roles outside theme token definitions. `Color.kt` may define ColorScheme/domain status tokens, but production pages and renderers may not consume direct constants. The migration allowlist must be empty at Task completion.

  ```bash
  ./gradlew :app:testDebugUnitTest --tests '*ThemeSurfaceContractTest'
  ```

  Expected: RED with every remaining static dark surface, including `WelcomeScreen` and inline LaTeX.

- [ ] **Step 2：按目录串行迁移语义色**

  Before editing, require every path in `.agent/plans/20260720-md3-convergence-task13-manifest.txt` to be clean; any pre-existing diff blocks the Task until ownership is resolved. Compare a fresh full-tree static scan using the shared pattern file to the committed manifest and fail on unregistered paths. Replace static surface/text/accent colors with `MaterialTheme.colorScheme`. Preserve domain status colors only where they encode real success/warning/error/info state, and map their foregrounds to accessible on-colors.

  ```bash
  while IFS= read -r path; do
    test -z "$(git status --short -- "$path")"
  done < .agent/plans/20260720-md3-convergence-task13-manifest.txt
  rg -l -f native-ui/app/src/test/resources/md3-theme-surface-patterns.txt \
    native-ui/app/src/main/java/com/promenar/nexara/ui \
    | LC_ALL=C sort -u > /tmp/nexara-md3-task13-fresh-scan.txt
  test -z "$(comm -23 /tmp/nexara-md3-task13-fresh-scan.txt .agent/plans/20260720-md3-convergence-task13-manifest.txt)"
  ```

- [ ] **Step 3：迁移 Markdown/WebView/图表 CSS**

  Inject background, foreground, surface, outline, primary, error and code colors from current theme. Theme changes must invalidate or update renderer content without Activity restart.

- [ ] **Step 4：清除兼容桥**

  Delete `NexaraGlassCard` when no production consumer remains. Reduce `NexaraColors` to domain status constants only or remove it if all roles are available from MaterialTheme. Run the static contract until allowlist is empty.

- [ ] **Step 5：运行全量 JVM、Lint、截图和编译**

  ```bash
  ./gradlew :app:testDebugUnitTest
  ./gradlew :app:lintDebug
  ./gradlew :app:validateDebugScreenshotTest
  ./gradlew :app:compileDebugAndroidTestKotlin
  ```

  Expected: exact counts recorded; 0 failure/error, Lint 0 Error/Fatal, screenshot validation all PASS, skips reported separately.

- [ ] **Step 6：独立主题审阅、提交和推送**

  Require one code reviewer and one multimodal visual reviewer. Critical/Important and P0/P1 visual findings must be zero.

  ```bash
  while IFS= read -r path; do
    if ! git diff --quiet -- "$path"; then git add -- "$path"; fi
  done < .agent/plans/20260720-md3-convergence-task13-manifest.txt
  git add native-ui/app/src/test/java/com/promenar/nexara/ui/theme/ThemeSurfaceContractTest.kt \
    native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt
  git ls-files --modified --deleted --others --exclude-standard -- native-ui/app/src/screenshotTestDebug/reference > /tmp/nexara-md3-task13-goldens.txt
  cat /tmp/nexara-md3-task13-goldens.txt
  while IFS= read -r path; do git add -- "$path"; done < /tmp/nexara-md3-task13-goldens.txt
  git diff --cached --name-only
  git commit -m "feat: complete adaptive Material 3 color migration"
  git push origin codex/md3-redesign
  ```

  The cached production paths must be a subset of the committed manifest plus the explicit new test. Stage each approved screenshot reference separately by its complete generated path before committing.

## Task 14：执行最终设备、视觉、性能、发行和治理门禁

**Files:**
- Read: `scripts/ci/android-device-core-e2e.sh`
- Read: `scripts/ci/android-release-apk-smoke.sh`
- Read: `scripts/verify-release-apk.py`
- Modify: `CHANGELOG.md`
- Modify: `docs/release/v0.2-beta.md`
- Modify: `docs/release/v0.2-beta-validation.md`
- Modify: `.agent/registry.md` only if registration is missing or status needs a new entry.
- Append: `.agent/handover.md`
- Regenerate: `.agent/handover-index.md`

**Interfaces:**
- Consumes: Tasks 1–13 current candidate.
- Produces: verified delivery evidence and a resumable HLG record; does not create tag/PR/Release.

- [ ] **Step 1：运行全量本地门禁**

  ```bash
  cd native-ui
  ./gradlew :app:testDebugUnitTest
  ./gradlew :app:lintDebug
  ./gradlew :app:validateDebugScreenshotTest
  ./gradlew :app:compileDebugAndroidTestKotlin
  ```

  Record exact test/failure/error/skip, Lint Error/Fatal/warning, screenshot count and build output.

- [ ] **Step 2：运行 API 31/35/36 设备矩阵**

  Run this closed class list on each fixed AVD; do not reuse a prior candidate result:

  ```bash
  TEST_CLASSES="com.promenar.nexara.ui.AccessibilitySmokeTest,com.promenar.nexara.ui.AdaptiveNavigationTest,com.promenar.nexara.ui.chat.AttachmentActionMenuTest,com.promenar.nexara.ui.chat.ChatScreenContentStateTest,com.promenar.nexara.ui.hub.AgentSessionsMaterialTest,com.promenar.nexara.ui.hub.UserSettingsAccessibilityTest,com.promenar.nexara.ui.common.ModelPickerAccessibilityTest,com.promenar.nexara.ui.settings.DefaultModelsInteractionTest,com.promenar.nexara.ui.settings.ProviderFormInteractionTest,com.promenar.nexara.ui.settings.ProviderModelsAccessibilityTest,com.promenar.nexara.ui.settings.BackupSettingsScreenTest,com.promenar.nexara.ui.settings.ThemeSettingsInteractionTest,com.promenar.nexara.ui.rag.RagSettingsAccessibilityTest"
  mkdir -p native-ui/app/build/reports/md3-device
  for matrix in emulator-5554:31 emulator-5556:35 emulator-5572:36; do
    serial="${matrix%%:*}"
    api="${matrix##*:}"
    test "$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" = "$api"
    (
      cd native-ui
      ANDROID_SERIAL="$serial" ./gradlew :app:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class="$TEST_CLASSES"
    )
    rm -rf "native-ui/app/build/reports/md3-device/api-$api"
    cp -R native-ui/app/build/outputs/androidTest-results/connected/debug \
      "native-ui/app/build/reports/md3-device/api-$api"
  done
  ```

  Each copied result records test/failure/error/skip separately. Then run the existing broader device harness on each API with the device's real ABI and build-only report output, never the protected root `artifacts/`:

  ```bash
  for matrix in emulator-5554:31 emulator-5556:35 emulator-5572:36; do
    serial="${matrix%%:*}"
    api="${matrix##*:}"
    abi="$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
    ANDROID_SERIAL="$serial" \
      NEXARA_DEVICE_E2E_ABI="$abi" \
      NEXARA_DEVICE_E2E_SCOPE=full \
      ANDROID_E2E_ARTIFACT_DIR="native-ui/app/build/reports/device-e2e-api-$api" \
      scripts/ci/android-device-core-e2e.sh
    test "$(cat "native-ui/app/build/reports/device-e2e-api-$api/exit-code.txt")" = "0"
  done
  ```

- [ ] **Step 3：人工检查全部 actual**

  Required combinations:

  ```text
  360x640 phone: dark/light, zh/en, normal/2.0x
  standard phone: dark/light, search/IME/attachment/model sheet
  landscape phone: settings/provider/model/RAG
  >=840dp tablet: navigation rail, settings, provider form/model editor
  Android 12+: Nexara colors and dynamic colors
  reduced motion: attachment/search/navigation state changes
  ```

  Inspect every actual for crop, overlap, hierarchy, contrast, touch reachability, focus order and unified MD3 language. Screenshot PASS without inspection is insufficient.

- [ ] **Step 4：复跑 500 模型性能**

  Run `ProviderModelsPerformanceTest` twice on the fixed API 36 AVD. All raw samples remain in separate logs; use the worse run. Do not replace or discard slow samples.

  ```bash
  mkdir -p native-ui/app/build/reports/provider-models-performance
  for run in 1 2; do
    (
      cd native-ui
      set -o pipefail
      ANDROID_SERIAL=emulator-5572 ./gradlew :app:connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.settings.ProviderModelsPerformanceTest \
        | tee "app/build/reports/provider-models-performance/run-$run.log"
    )
  done
  ```

- [ ] **Step 5：构建当前 release APK 并检查签名链**

  Rebuild from current source, then verify R8 output, zipalign, package identity, signing certificate and SHA-256. Sensitive signing material is used only by the main controller and never written to logs, reports or Agent prompts.

  ```bash
  : "${NEXARA_KEYSTORE_PATH:?missing NEXARA_KEYSTORE_PATH}"
  : "${NEXARA_STORE_PASSWORD:?missing NEXARA_STORE_PASSWORD}"
  : "${NEXARA_KEY_ALIAS:?missing NEXARA_KEY_ALIAS}"
  : "${NEXARA_KEY_PASSWORD:?missing NEXARA_KEY_PASSWORD}"
  : "${NEXARA_SIGNING_CERT_SHA256:?missing NEXARA_SIGNING_CERT_SHA256}"
  test -r "$NEXARA_KEYSTORE_PATH"
  (cd native-ui && ./gradlew --rerun-tasks :app:assembleRelease)
  test -s native-ui/app/build/outputs/mapping/release/mapping.txt
  python3 scripts/verify-release-apk.py \
    native-ui/app/build/outputs/apk/release/nexara-v0.2-beta.apk \
    --expected-package com.promenar.nexara.native \
    --expected-version-code 2 \
    --expected-version-name 0.2-beta \
    --expected-cert-sha256 "$NEXARA_SIGNING_CERT_SHA256" \
    --checksum-output native-ui/app/build/outputs/apk/release/SHA256SUMS
  ```

  Expected: every command exits 0; mapping and checksum files are non-empty; verifier reports one expected signer and no sensitive-pattern finding.

- [ ] **Step 6：冷安装和真机边界**

  Cold-install the exact current APK on API 35/36 and retain reports outside protected `artifacts/`:

  ```bash
  ANDROID_SERIAL=emulator-5556 \
    ANDROID_RELEASE_SMOKE_ARTIFACT_DIR=native-ui/app/build/reports/release-smoke-api-35 \
    scripts/ci/android-release-apk-smoke.sh \
    native-ui/app/build/outputs/apk/release/nexara-v0.2-beta.apk
  ANDROID_SERIAL=emulator-5572 \
    ANDROID_RELEASE_SMOKE_ARTIFACT_DIR=native-ui/app/build/reports/release-smoke-api-36 \
    scripts/ci/android-release-apk-smoke.sh \
    native-ui/app/build/outputs/apk/release/nexara-v0.2-beta.apk
  test "$(cat native-ui/app/build/reports/release-smoke-api-35/exit-code.txt)" = "0"
  test "$(cat native-ui/app/build/reports/release-smoke-api-36/exit-code.txt)" = "0"
  ```

  Then smoke theme persistence, Provider/default-model navigation, knowledge settings and chat attachment on the installed package and record screenshots/logs in the same build report roots. Final signed APK still requires user-operated physical-device TalkBack full traversal and core business acceptance; these remain PENDING until the user supplies evidence.

- [ ] **Step 7：DIA/HLG 收口**

  Update user-visible CHANGELOG/release docs and validation ledger. Append a new ISO handover record with `continuity-key: nexara-md3-redesign`; rebuild index using:

  ```bash
  python3 /Users/promenar/.codex/skills/handover-lifecycle-governance/scripts/hlg-handover.py \
    index --root /Users/promenar/Codex/Nexara/.worktrees/codex-v0.2-beta --days 7
  ```

- [ ] **Step 8：最终双复审**

  One independent specification reviewer checks every completion item; one code-quality/visual reviewer checks diff, tests, performance and actual. Critical/Important and P0/P1 must be zero; Minor/P2 is recorded, not silently dropped.

- [ ] **Step 9：提交和推送阶段收口**

  ```bash
  git add CHANGELOG.md docs/release/v0.2-beta.md docs/release/v0.2-beta-validation.md \
    .agent/registry.md .agent/handover.md .agent/handover-index.md
  git commit -m "docs: close Material 3 convergence phase"
  git push origin codex/md3-redesign
  ```

## Completion Definition

- [ ] 手机主导航使用 `NavigationBarItem`，大屏使用同目的地 `NavigationRail`，无 glow 和自绘选中圆。
- [ ] 附件动作锚定 `+` 展开，支持关闭、返回、外部 dismiss、减少动效和 48dp/TalkBack。
- [ ] Agent 会话列表和 Agent 首页低频搜索使用顶栏搜索模式，列表为连续 `ListItem`。
- [ ] Model Picker 和会话设置使用同一连续模型选择行，不改变元数据三态、友好名称或 endpoint 契约。
- [ ] 设置首页取消双 Tab；Provider、默认模型、记忆、检索等进入清晰二级层级。
- [ ] 记忆、检索、Provider 表单、Provider Models 和全部正式设置页清除 Glass、卡套卡和无规则描边。
- [ ] Provider Models 以摘要列表 + 独立编辑 Sheet 工作，500 模型性能达到 p95/max/PSS 门禁。
- [ ] 系统、浅色、深色和 Android 12+ 动态色真实生效、持久恢复、备份恢复后立即生效。
- [ ] UI、Markdown、Mermaid、ECharts、LaTeX、PlantUML、HTML 和表格在深浅色下可读。
- [ ] 全量 JVM 0 failure/error；skip 独立记录；Lint 0 Error/Fatal；全部截图 PASS 并逐张人工审阅。
- [ ] API 31/35/36 相关设备矩阵通过；当前 release APK 完成 R8/zipalign/签名/checksum 和 API 35/36 冷安装。
- [ ] 真机 TalkBack、核心业务人工验收、远端 CI、tag workflow 和 GitHub Release 未闭合时，发行继续 NO-GO。
- [ ] DIA、HLG、registry、release validation 与当前实现同步；每个完成任务已提交并推送，未触碰保护目录和敏感材料。
