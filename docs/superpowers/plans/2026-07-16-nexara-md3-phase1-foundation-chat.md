# Nexara Material 3 Phase 1 Foundation and Chat Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变会话业务、导航、数据与 API 契约的前提下，将 Nexara 迁移到稳定 Material 3 设计基线，并把主会话页实现为经用户批准的“方案 3”视觉母版。

**Architecture:** 先以 Compose BOM、语义色、形状、间距、层级和字体令牌建立可复用的稳定设计系统，再只迁移主会话页的输入区、用户消息和思考轨迹。会话继续消费既有 `ChatScreenState`、`ChatScreenActions`、`PipelineGroup` 和 `buildPipelineRenderPlan`，新增代码仅负责视觉、布局测量和无障碍语义；其余页面仍可暂时使用旧公共组件，留待后续阶段逐页迁移。

**Tech Stack:** Kotlin、Jetpack Compose、Material 3 `1.4.0`（Compose BOM `2026.06.00`）、JUnit4/JUnit5、Compose UI Test、Compose Preview Screenshot Test、Gradle。

## Global Constraints

- 只使用 Compose BOM `2026.06.00` 对应的稳定 Material 3 `1.4.0`；禁止引入 Material 3 `1.5.0-alpha*` 与任何实验性 Expressive API。
- 保持深色主题；`dynamicColor` 默认值继续为 `false`，本阶段不启用动态取色。
- 不修改 `ChatViewModel`、Repository、数据库、网络协议、模型路由、导航目的地或任何业务数据结构。
- 保持 `ChatScreenState`、`ChatScreenActions`、`ChatInputBar`、`PipelineBubble`、`PipelineGroup` 与 `buildPipelineRenderPlan` 的外部接口兼容。
- 旧的 `NexaraGlassCard`、`GlassSurface`、`GlassBorder` 本阶段不得全局删除；主会话页停止使用它们，其他页面在后续阶段迁移。
- 使用 Material 语义角色：页面 `surface`，弱分组 `surfaceContainerLow`，用户消息 `secondaryContainer`，主强调 `primary`，危险状态 `error`。
- 形状阶梯固定为 `4dp / 8dp / 12dp / 16dp / 24dp`；间距遵循 `4dp / 8dp` 节律；所有可点击目标至少 `48dp`。
- 字体使用真实系统族名 `NexaraSans = FontFamily.SansSerif` 与 `NexaraMonospace = FontFamily.Monospace`，不再在新代码中使用伪装的品牌字体名。
- 截图基线只有在人工逐张查看新增实际图、确认无裁剪、遮挡、错误间距和错误圆角后才能更新。
- `artifacts/`、`secure_env/`、签名文件、API Key 与临时 Agent 报告不得加入提交。

---

### Task 1: 锁定稳定 Compose Material 3 依赖基线

**Files:**
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/theme/ComposeMaterialBaselineContractTest.kt`
- Modify: `native-ui/app/build.gradle.kts:225`
- Modify: `native-ui/mainactivity-e2e/build.gradle.kts:49`

**Interfaces:**
- Consumes: Gradle Version Catalog 与两个模块现有 Compose BOM 声明。
- Produces: 两个模块统一的 Compose BOM `2026.06.00`；运行时解析出的 `androidx.compose.material3:material3:1.4.0`。

- [ ] **Step 1: 写入会失败的依赖契约测试**

```kotlin
package com.promenar.nexara.ui.theme

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class ComposeMaterialBaselineContractTest {
    private fun repositoryRoot(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(userDir) { it.parentFile }
            .first { it.resolve("native-ui/app/build.gradle.kts").isFile }
    }

    @Test
    fun `app 与 e2e 使用同一稳定 Material3 基线`() {
        val root = repositoryRoot()
        val app = root.resolve("native-ui/app/build.gradle.kts").readText()
        val e2e = root.resolve("native-ui/mainactivity-e2e/build.gradle.kts").readText()

        assertThat(app).contains("compose-bom:2026.06.00")
        assertThat(e2e).contains("compose-bom:2026.06.00")
        assertThat(app).doesNotContain("compose-bom:2026.05.00")
        assertThat(e2e).doesNotContain("compose-bom:2026.05.00")
        assertThat(app).doesNotContain("1.5.0-alpha")
        assertThat(e2e).doesNotContain("1.5.0-alpha")
    }
}
```

- [ ] **Step 2: 运行测试并确认它因旧 BOM 失败**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.theme.ComposeMaterialBaselineContractTest'`

Expected: FAIL，断言显示源码仍包含 `compose-bom:2026.05.00`。

- [ ] **Step 3: 将两个模块 BOM 同步升级**

在两个文件中使用同一声明，不额外硬编码 `material3` 版本：

```kotlin
val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
implementation(composeBom)
androidTestImplementation(composeBom)
implementation("androidx.compose.material3:material3")
```

`mainactivity-e2e` 保持其原有 configuration，只替换 BOM 字符串，不新加产品依赖。

- [ ] **Step 4: 验证契约测试和依赖解析**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.theme.ComposeMaterialBaselineContractTest'`

Expected: PASS。

Run: `cd native-ui && ./gradlew :app:dependencyInsight --dependency androidx.compose.material3:material3 --configuration debugRuntimeClasspath`

Expected: 输出包含 `androidx.compose.material3:material3:1.4.0`，且不包含 `1.5.0-alpha`。

- [ ] **Step 5: 提交依赖基线**

```bash
git add native-ui/app/build.gradle.kts \
  native-ui/mainactivity-e2e/build.gradle.kts \
  native-ui/app/src/test/java/com/promenar/nexara/ui/theme/ComposeMaterialBaselineContractTest.kt
git commit -m "build: align Compose Material 3 baseline"
```

---

### Task 2: 建立可测试的 Material 3 主题令牌

**Files:**
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Spacing.kt`
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Elevation.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/theme/NexaraThemeTokenTest.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Color.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Theme.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Shape.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/theme/Type.kt`

**Interfaces:**
- Consumes: `NexaraColors` 的现有深色调色板。
- Produces: `NexaraSpacing`、`NexaraElevation`、`NexaraShapeTokens`、`NexaraSans`、`NexaraMonospace`、`internal val NexaraDarkColorScheme`。

- [ ] **Step 1: 写入令牌失败测试**

```kotlin
package com.promenar.nexara.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NexaraThemeTokenTest {
    @Test
    fun `间距触控与层级令牌符合批准契约`() {
        assertThat(NexaraSpacing.XSmall.value).isEqualTo(4f)
        assertThat(NexaraSpacing.Small.value).isEqualTo(8f)
        assertThat(NexaraSpacing.Medium.value).isEqualTo(12f)
        assertThat(NexaraSpacing.Large.value).isEqualTo(16f)
        assertThat(NexaraSpacing.XLarge.value).isEqualTo(24f)
        assertThat(NexaraSpacing.XXLarge.value).isEqualTo(32f)
        assertThat(NexaraSpacing.MinimumTouchTarget.value).isEqualTo(48f)
        assertThat(NexaraElevation.Level0.value).isEqualTo(0f)
        assertThat(NexaraElevation.Level3.value).isEqualTo(6f)
    }

    @Test
    fun `深色主题完整映射 tonal surface 层级`() {
        assertThat(NexaraDarkColorScheme.surfaceDim).isEqualTo(NexaraColors.SurfaceDim)
        assertThat(NexaraDarkColorScheme.surfaceBright).isEqualTo(NexaraColors.SurfaceBright)
        assertThat(NexaraDarkColorScheme.surfaceContainerLowest).isEqualTo(NexaraColors.SurfaceLowest)
        assertThat(NexaraDarkColorScheme.surfaceContainerLow).isEqualTo(NexaraColors.SurfaceLow)
        assertThat(NexaraDarkColorScheme.surfaceContainer).isEqualTo(NexaraColors.SurfaceContainer)
        assertThat(NexaraDarkColorScheme.surfaceContainerHigh).isEqualTo(NexaraColors.SurfaceHigh)
        assertThat(NexaraDarkColorScheme.surfaceContainerHighest).isEqualTo(NexaraColors.SurfaceHighest)
    }
}
```

- [ ] **Step 2: 运行测试并确认缺失符号失败**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.theme.NexaraThemeTokenTest'`

Expected: FAIL，编译器报告 `NexaraSpacing`、`NexaraElevation` 或 `NexaraDarkColorScheme` 尚不存在。

- [ ] **Step 3: 新增间距与层级对象**

`Spacing.kt`：

```kotlin
package com.promenar.nexara.ui.theme

import androidx.compose.ui.unit.dp

object NexaraSpacing {
    val XSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val XLarge = 24.dp
    val XXLarge = 32.dp
    val ScreenHorizontal = 16.dp
    val MinimumTouchTarget = 48.dp
}
```

`Elevation.kt`：

```kotlin
package com.promenar.nexara.ui.theme

import androidx.compose.ui.unit.dp

object NexaraElevation {
    val Level0 = 0.dp
    val Level1 = 1.dp
    val Level2 = 3.dp
    val Level3 = 6.dp
}
```

- [ ] **Step 4: 完整映射深色 ColorScheme**

将 `DarkColorScheme` 重命名并放宽为同模块测试可见，同时补齐稳定 Material 3 的 tonal surface 参数：

```kotlin
internal val NexaraDarkColorScheme = darkColorScheme(
    primary = NexaraColors.Primary,
    onPrimary = NexaraColors.OnPrimary,
    primaryContainer = NexaraColors.PrimaryContainer,
    onPrimaryContainer = NexaraColors.OnPrimaryContainer,
    inversePrimary = NexaraColors.InversePrimary,
    secondary = NexaraColors.Secondary,
    onSecondary = NexaraColors.OnSecondary,
    secondaryContainer = NexaraColors.SecondaryContainer,
    onSecondaryContainer = NexaraColors.OnSecondaryContainer,
    tertiary = NexaraColors.Tertiary,
    onTertiary = NexaraColors.OnTertiary,
    tertiaryContainer = NexaraColors.TertiaryContainer,
    onTertiaryContainer = NexaraColors.OnTertiaryContainer,
    background = NexaraColors.CanvasBackground,
    onBackground = NexaraColors.OnBackground,
    surface = NexaraColors.SurfaceDim,
    onSurface = NexaraColors.OnSurface,
    surfaceDim = NexaraColors.SurfaceDim,
    surfaceBright = NexaraColors.SurfaceBright,
    surfaceContainerLowest = NexaraColors.SurfaceLowest,
    surfaceContainerLow = NexaraColors.SurfaceLow,
    surfaceContainer = NexaraColors.SurfaceContainer,
    surfaceContainerHigh = NexaraColors.SurfaceHigh,
    surfaceContainerHighest = NexaraColors.SurfaceHighest,
    surfaceVariant = NexaraColors.SurfaceVariant,
    onSurfaceVariant = NexaraColors.OnSurfaceVariant,
    outline = NexaraColors.Outline,
    outlineVariant = NexaraColors.OutlineVariant,
    error = NexaraColors.Error,
    onError = NexaraColors.OnError,
    errorContainer = NexaraColors.ErrorContainer,
    onErrorContainer = NexaraColors.OnErrorContainer,
    surfaceTint = NexaraColors.SurfaceTint,
    inverseSurface = NexaraColors.InverseSurface,
    inverseOnSurface = NexaraColors.InverseOnSurface,
)
```

`NexaraTheme` 的非动态分支改为 `NexaraDarkColorScheme`；`dynamicColor` 默认值保持 `false`。

- [ ] **Step 5: 收敛形状与真实字体命名**

`Shape.kt` 新增并复用明确阶梯：

```kotlin
object NexaraShapeTokens {
    val XSmall = RoundedCornerShape(4.dp)
    val Small = RoundedCornerShape(8.dp)
    val Medium = RoundedCornerShape(12.dp)
    val Large = RoundedCornerShape(16.dp)
    val XLarge = RoundedCornerShape(24.dp)
}

val NexaraShapes = Shapes(
    extraSmall = NexaraShapeTokens.XSmall,
    small = NexaraShapeTokens.Small,
    medium = NexaraShapeTokens.Medium,
    large = NexaraShapeTokens.Large,
    extraLarge = NexaraShapeTokens.XLarge,
)
```

`Type.kt` 使用真实名称，并让旧名称暂时成为无弃用警告的兼容别名，避免扩大首阶段改动面；普通辅助文案继续使用系统无衬线，只有模型 ID、Token 与代码在局部显式使用等宽字体：

```kotlin
val NexaraSans = FontFamily.SansSerif
val NexaraMonospace = FontFamily.Monospace

val Manrope = NexaraSans

val Inter = NexaraSans

val SpaceGrotesk = NexaraMonospace
```

将 `NexaraTypography` 内部的新引用替换成 `NexaraSans`，包括 `bodySmall`；需要等宽的模型 ID、Token 与代码片段在组件局部显式使用 `NexaraMonospace`。字号、行高和字体粗细保持现状，避免视觉与可读性同时产生不可归因变化。

- [ ] **Step 6: 验证主题测试与编译**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.theme.NexaraThemeTokenTest' :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。

- [ ] **Step 7: 提交主题令牌**

```bash
git add native-ui/app/src/main/java/com/promenar/nexara/ui/theme \
  native-ui/app/src/test/java/com/promenar/nexara/ui/theme/NexaraThemeTokenTest.kt
git commit -m "feat: establish Material 3 theme tokens"
```

---

### Task 3: 用测试固定会话输入区布局与测量契约

**Files:**
- Create: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatComposerInsets.kt`
- Create: `native-ui/app/src/test/java/com/promenar/nexara/ui/chat/ChatComposerInsetsTest.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/chat/ChatRenderStateContractTest.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt`

**Interfaces:**
- Consumes: Task 2 的 `NexaraSpacing`。
- Produces: `internal data class ChatComposerInsets`、`internal fun chatComposerInsets(composerHeight: Dp): ChatComposerInsets`，以及稳定语义标签 `CHAT_COMPOSER`、`CHAT_THINKING_TRACE`、`CHAT_THINKING_TOGGLE`、`CHAT_THINKING_CONTENT`。

- [ ] **Step 1: 写入输入区 inset 失败测试**

```kotlin
package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import androidx.compose.ui.unit.dp
import org.junit.Test

class ChatComposerInsetsTest {
    @Test
    fun `输入区高度统一驱动列表流式尾部与回底按钮避让`() {
        val insets = chatComposerInsets(132.dp)

        assertThat(insets.contentBottom).isEqualTo(156.dp)
        assertThat(insets.streamingOverlap).isEqualTo(148.dp)
        assertThat(insets.fabBottom).isEqualTo(148.dp)
    }

    @Test
    fun `未完成首次测量时仍提供安全避让`() {
        val insets = chatComposerInsets(0.dp)

        assertThat(insets.contentBottom).isEqualTo(120.dp)
        assertThat(insets.streamingOverlap).isEqualTo(112.dp)
        assertThat(insets.fabBottom).isEqualTo(112.dp)
    }
}
```

- [ ] **Step 2: 运行测试并确认函数缺失失败**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.chat.ChatComposerInsetsTest'`

Expected: FAIL，编译器报告 `chatComposerInsets` 未定义。

- [ ] **Step 3: 实现唯一的布局避让计算入口**

```kotlin
package com.promenar.nexara.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.theme.NexaraSpacing

internal data class ChatComposerInsets(
    val contentBottom: Dp,
    val streamingOverlap: Dp,
    val fabBottom: Dp,
)

internal fun chatComposerInsets(composerHeight: Dp): ChatComposerInsets {
    val measuredHeight = if (composerHeight > 0.dp) composerHeight else 96.dp
    return ChatComposerInsets(
        contentBottom = measuredHeight + NexaraSpacing.XLarge,
        streamingOverlap = measuredHeight + NexaraSpacing.Large,
        fabBottom = measuredHeight + NexaraSpacing.Large,
    )
}
```

- [ ] **Step 4: 将旧视觉契约测试改成新结构契约**

把 `ChatRenderStateContractTest` 的首个测试替换为：

```kotlin
@Test
fun `会话输入区使用语义化 M3 控件和实测高度`() {
    val moduleRoot = File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }
    val source = moduleRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt",
    ).readText()
    val tags = moduleRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/testing/UiTags.kt",
    ).readText()

    assertThat(source).doesNotContain("CompactInputChip(")
    assertThat(source).doesNotContain("visualHeight = 34.dp")
    assertThat(source).doesNotContain("NexaraGlassCard(")
    assertThat(source).doesNotContain("NexaraColors.GlassSurface")
    assertThat(source).doesNotContain("NexaraColors.GlassBorder")
    assertThat(source).contains("onSizeChanged")
    assertThat(source).contains("chatComposerInsets(")
    assertThat(tags).contains("chat_composer")
    assertThat(tags).contains("chat_thinking_trace")
}
```

在 `UiTags` 中加入：

```kotlin
const val CHAT_COMPOSER = "chat_composer"
const val CHAT_THINKING_TRACE = "chat_thinking_trace"
const val CHAT_THINKING_TOGGLE = "chat_thinking_toggle"
const val CHAT_THINKING_CONTENT = "chat_thinking_content"
```

保留 `CHAT_INPUT_ISLAND`、`CHAT_MODEL_SELECTOR_VISUAL` 与 `CHAT_TOKEN_INDICATOR_VISUAL` 兼容标签到 Phase 1 完成；实现层不再需要挂载旧视觉标签。

- [ ] **Step 5: 验证纯逻辑测试通过、结构契约按预期失败**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.chat.ChatComposerInsetsTest'`

Expected: PASS。

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.chat.ChatRenderStateContractTest'`

Expected: FAIL，失败项指向 `ChatScreen.kt` 尚有 `CompactInputChip` 或尚未使用 `onSizeChanged`。

- [ ] **Step 6: 提交会话布局契约**

```bash
git add native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatComposerInsets.kt \
  native-ui/app/src/main/java/com/promenar/nexara/ui/testing/UiTags.kt \
  native-ui/app/src/test/java/com/promenar/nexara/ui/chat/ChatComposerInsetsTest.kt \
  native-ui/app/src/test/java/com/promenar/nexara/ui/chat/ChatRenderStateContractTest.kt
git commit -m "test: define Material 3 chat layout contract"
```

---

### Task 4: 重构会话底部输入区为单层 Material 3 composer

**Files:**
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/AccessibilitySmokeTest.kt`
- Modify: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/ChatImeInteractionTest.kt`

**Interfaces:**
- Consumes: `chatComposerInsets`、`NexaraSpacing`、`NexaraShapeTokens`、既有 `ChatInputBar` 与 `ChatInputTopBar` 回调。
- Produces: 单层 `Surface` composer、标准 `FilterChip` 模型/上下文控件、基于真实高度的列表与 IME 避让；不改变输入、发送、停止、选图、摘要和模型选择行为。

- [ ] **Step 1: 先更新无障碍测试为完整 48dp 目标**

将 `chatInputTopChipsKeep48DpTargetsWith34DpVisualSurfaces` 替换为：

```kotlin
@Test
fun chatInputControlsUseComplete48DpMaterialTargets() {
    composeRule.setContent {
        NexaraTheme {
            ChatScreenContent(
                state = ChatScreenState(),
                actions = ChatScreenActions(),
            )
        }
    }

    composeRule.onNodeWithTag(UiTags.CHAT_MODEL_SELECTOR)
        .assertHasClickAction()
        .assertHeightIsAtLeast(48.dp)
    composeRule.onNodeWithTag(UiTags.CHAT_TOKEN_INDICATOR)
        .assertHasClickAction()
        .assertHeightIsAtLeast(48.dp)
    composeRule.onNodeWithTag(UiTags.CHAT_COMPOSER)
        .assertIsDisplayed()
}
```

删除该文件不再使用的 `assertHeightIsEqualTo` import。

- [ ] **Step 2: 用测量状态替代三个硬编码高度**

在 `ChatScreenContent` 的 `Box` 范围建立测量值：

```kotlin
var composerHeightPx by remember { mutableIntStateOf(0) }
val composerHeight = with(density) { composerHeightPx.toDp() }
val composerInsets = chatComposerInsets(composerHeight)
```

将列表、流式校正和 FAB 的现有 `200.dp / 180.dp / 150.dp` 分别改为：

```kotlin
contentPadding = PaddingValues(
    start = NexaraSpacing.Large,
    end = NexaraSpacing.Large,
    top = NexaraSpacing.Large,
    bottom = composerInsets.contentBottom,
)
```

```kotlin
val inputOverlapPx = with(density) { composerInsets.streamingOverlap.roundToPx() }
```

```kotlin
modifier = Modifier
    .align(Alignment.BottomCenter)
    .padding(bottom = composerInsets.fabBottom)
```

- [ ] **Step 3: 把大浮岛重构为单层 composer**

保留现有图片、任务面板、模型提示与 `ChatInputBar` 内容，把外层实现改为：

```kotlin
Surface(
    modifier = Modifier
        .align(Alignment.BottomCenter)
        .widthIn(max = 960.dp)
        .fillMaxWidth()
        .onSizeChanged { composerHeightPx = it.height }
        .testTag(UiTags.CHAT_COMPOSER),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = NexaraElevation.Level0,
    shadowElevation = NexaraElevation.Level0,
) {
    Column(
        modifier = Modifier.padding(
            horizontal = NexaraSpacing.Large,
            vertical = NexaraSpacing.Small,
        ),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        ChatInputTopBar(
            modelName = modelDisplayName,
            tokenState = tokenState,
            postProcessTasks = state.postProcessTasks,
            onRemovePostProcessTask = actions.onRemovePostProcessTask,
            onModelClick = actions.onOpenSettings,
            onManualSummary = actions.onManualSummary,
        )
    }
}
```

此代码块定义要替换的外层结构；在 `ChatInputTopBar` 之后按当前源码顺序移动现有 `selectedImageUris` 的 `LazyRow`、`taskPanel()`、包含 `ChatInputBar` 与模型提示的 `Box`，其函数调用与回调逐字符保持不变。删除外层 `BorderStroke`、`shadowElevation = 6.dp`、`RoundedCornerShape(24.dp)` 与 `CHAT_INPUT_ISLAND` 挂载。

- [ ] **Step 4: 用稳定 M3 FilterChip 替换自绘胶囊**

删除 `CompactInputChip`，在 `ChatInputTopBar` 中直接使用：

```kotlin
FilterChip(
    selected = true,
    onClick = onModelClick,
    modifier = Modifier
        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
        .testTag(UiTags.CHAT_MODEL_SELECTOR),
    label = {
        Text(
            text = modelName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    },
    leadingIcon = {
        Icon(Icons.Rounded.Memory, contentDescription = null)
    },
)
```

上下文控件同样使用 `FilterChip`，`onClick = onManualSummary`，挂载 `CHAT_TOKEN_INDICATOR`，label 保留既有 token 文案与状态颜色。两者使用 `FlowRow` 或自适应 `Row`，在窄屏和 2 倍字体下允许换行，禁止固定 `34.dp` 视觉高度和 `RoundedCornerShape(50)`。

- [ ] **Step 5: 将 token 下拉表面切换到标准 M3 Surface**

把 `ChatInputTopBar` 内承载 token 明细的 `NexaraGlassCard` 替换为：

```kotlin
Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surfaceContainer,
    shape = MaterialTheme.shapes.large,
    tonalElevation = NexaraElevation.Level2,
) {
    Column(modifier = Modifier.padding(NexaraSpacing.Large)) {
        Text(
            stringResource(R.string.chat_context_usage_title),
            style = NexaraTypography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(NexaraSpacing.Medium))
        TokenDetailRow(stringResource(R.string.chat_context_label_system), state.systemTokens)
        TokenDetailRow(stringResource(R.string.chat_context_label_summary), state.summaryTokens)
        TokenDetailRow(stringResource(R.string.chat_context_label_active), state.activeTokens)
        TokenDetailRow(stringResource(R.string.chat_context_label_rag), state.ragTokens)
        HorizontalDivider(modifier = Modifier.padding(vertical = NexaraSpacing.Medium))
        Button(
            onClick = {
                onManualSummary()
                showTooltip = false
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.chat_context_btn_compress))
        }
    }
}
```

实际提交必须保留全部现有 `TokenDetailRow`、摘要按钮和任务移除回调，不得以省略参数的示例替换生产代码。

- [ ] **Step 6: 将会话重命名弹窗迁移到稳定 M3 组件**

先把同文件中的 `RenameDialog` 从 `Dialog + NexaraGlassCard + BasicTextField` 迁移到稳定 M3 组件，确保主会话源码彻底停止使用 Glass 公共组件：

```kotlin
@Composable
fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_dialog_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(stringResource(R.string.chat_dialog_rename_placeholder))
                },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.common_btn_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_btn_cancel))
            }
        },
    )
}
```

清理 `ChatScreen.kt` 不再使用的 `NexaraGlassCard`、`Dialog`、`BasicTextField`、`SolidColor` 等 imports；如果同文件其他代码仍消费其中某项，则保留该项。

- [ ] **Step 7: 强化 IME 测试对实测 composer 的约束**

在 `inputBar_staysAboveIme_afterTyping` 中增加：

```kotlin
val composerBottom = rule.onNodeWithTag(UiTags.CHAT_COMPOSER)
    .fetchSemanticsNode().boundsInRoot.bottom
assertThat(composerBottom).isAtMost(visibleBottomPx + tolerancePx)
```

现有 `lastMessage_remainsVisible_whenImeShows`、`openingIme_doesNotStealDeliberateUpScroll` 和返回键测试保持不变。

- [ ] **Step 8: 运行会话单测、编译和 instrumentation**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.chat.ChatComposerInsetsTest' --tests 'com.promenar.nexara.ui.chat.ChatRenderStateContractTest' :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。

Run（连接 API 31+ 模拟器或真机）: `cd native-ui && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.AccessibilitySmokeTest,com.promenar.nexara.ui.chat.ChatImeInteractionTest`

Expected: 所有目标测试 PASS；若本机无设备，记录为设备门禁待执行，不得伪称通过。

- [ ] **Step 9: 提交单层 composer**

```bash
git add native-ui/app/src/main/java/com/promenar/nexara/ui/chat/ChatScreen.kt \
  native-ui/app/src/androidTest/java/com/promenar/nexara/ui/AccessibilitySmokeTest.kt \
  native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/ChatImeInteractionTest.kt
git commit -m "feat: rebuild chat composer with Material 3"
```

---

### Task 5: 实现 Nexara 专属思考轨迹与 M3 消息表面

**Files:**
- Create: `native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/ThinkingTraceTest.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt`
- Modify: `native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/NexaraMarkdownTheme.kt`
- Modify: `native-ui/app/src/test/java/com/promenar/nexara/ui/chat/PipelineBubbleTest.kt`

**Interfaces:**
- Consumes: 既有 reasoning、`isGenerating`、`fontSize`、`streamingReasoningPreview` 与思考展开状态。
- Produces: `internal fun ThinkingTrace(...)`；完整宽度 M3 tonal header、细竖线与节点、可折叠 reasoning 内容；用户消息使用 `secondaryContainer`。

- [ ] **Step 1: 写入 Pipeline 源码契约测试**

在 `PipelineBubbleTest` 加入：

```kotlin
@Test
fun `会话表面使用 M3 语义角色并保留思考轨迹`() {
    val moduleRoot = java.io.File(System.getProperty("user.dir") ?: ".").let { root ->
        if (root.resolve("src/main").isDirectory) root else root.resolve("app")
    }
    val source = moduleRoot.resolve(
        "src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt",
    ).readText()

    assertThat(source).contains("internal fun ThinkingTrace(")
    assertThat(source).contains("MaterialTheme.colorScheme.surfaceContainerLow")
    assertThat(source).contains("MaterialTheme.colorScheme.secondaryContainer")
    assertThat(source).contains("MaterialTheme.colorScheme.onSecondaryContainer")
    assertThat(source).doesNotContain("private fun InlineThinkingRow(")
    assertThat(source).doesNotContain("import com.promenar.nexara.ui.common.NexaraGlassCard")
}
```

- [ ] **Step 2: 运行契约测试并确认旧实现失败**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.chat.PipelineBubbleTest'`

Expected: FAIL，断言显示仍存在 `InlineThinkingRow` 且语义色未迁移。

- [ ] **Step 3: 将 InlineThinkingRow 重命名并重构为思考轨迹**

保持调用参数与内部自动展开/延迟折叠逻辑，函数声明收敛为：

```kotlin
@Composable
internal fun ThinkingTrace(
    reasoning: String,
    isGenerating: Boolean,
    fontSize: Int,
    modifier: Modifier = Modifier,
) {
    var internalExpanded by remember { mutableStateOf(isGenerating) }
    var collapsePending by remember { mutableStateOf(false) }

    LaunchedEffect(isGenerating) {
        if (isGenerating) {
            collapsePending = false
            internalExpanded = true
        } else {
            collapsePending = true
            delay(300L)
            if (collapsePending) internalExpanded = false
        }
    }

    val visibleReasoning = remember(reasoning, isGenerating) {
        if (isGenerating) streamingReasoningPreview(reasoning) else reasoning
    }
    val targetFontSize = (fontSize - 2).coerceAtLeast(10)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.CHAT_THINKING_TRACE),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .clickable {
                    collapsePending = false
                    internalExpanded = !internalExpanded
                }
                .testTag(UiTags.CHAT_THINKING_TOGGLE),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = NexaraSpacing.Large,
                    vertical = NexaraSpacing.Small,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                if (isGenerating) {
                    val transition = rememberInfiniteTransition(label = "thinking_trace")
                    val alpha by transition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                        label = "thinking_trace_alpha",
                    )
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .alpha(alpha)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                } else {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(
                        if (isGenerating) R.string.chat_status_thinking
                        else R.string.chat_status_thought,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = if (internalExpanded) {
                        Icons.Rounded.ExpandLess
                    } else {
                        Icons.Rounded.ExpandMore
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = internalExpanded && reasoning.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.CHAT_THINKING_CONTENT),
            ) {
                Box(
                    Modifier
                        .padding(start = NexaraSpacing.XLarge)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
                MarkdownText(
                    markdown = visibleReasoning,
                    isStreaming = isGenerating,
                    fontSize = targetFontSize,
                    showCursor = false,
                    overrideColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    compactSpacing = true,
                    modifier = Modifier.padding(
                        start = NexaraSpacing.Medium,
                        top = NexaraSpacing.Small,
                        bottom = NexaraSpacing.Small,
                    ),
                )
            }
        }
    }
}
```

实际提交应完整迁移原实现的 `LaunchedEffect`、`collapsePending`、生成动画、Markdown 和 300ms 折叠行为。移除 `fillMaxWidth(0.7f)`、描边、斜体强制和玻璃卡导入；折叠条全宽，竖线与节点仅作为 Nexara 的 10% 品牌表达。

- [ ] **Step 4: 将用户消息迁移到 secondaryContainer**

`UserMessageBubble` 的 Surface 改为：

```kotlin
Surface(
    shape = NexaraCustomShapes.ChatBubbleUser,
    color = MaterialTheme.colorScheme.secondaryContainer,
    modifier = Modifier
        .widthIn(max = 320.dp)
        .combinedClickable(
            onClick = {},
            onLongClick = {
                pressOffset = DpOffset.Zero
                showMenu = true
            },
        ),
) {
    Column {
        if (!message.userImages.isNullOrEmpty()) {
            Column(
                modifier = Modifier.padding(
                    start = NexaraSpacing.Small,
                    end = NexaraSpacing.Small,
                    top = NexaraSpacing.Small,
                ),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
            ) {
                message.userImages.orEmpty().forEach { dataUrl ->
                    AsyncImage(
                        model = dataUrl,
                        contentDescription = stringResource(R.string.chat_cd_attached_image),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
        }
        if (message.content.isNotBlank()) {
            Text(
                text = message.content,
                style = NexaraTypography.bodyMedium.copy(
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.5).sp,
                ),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(NexaraSpacing.Large),
            )
        }
    }
}
```

正文颜色改为 `MaterialTheme.colorScheme.onSecondaryContainer`，移除气泡描边。助手正文继续直接落在页面 `surface` 上，不新增外层卡片。

- [ ] **Step 5: 对齐 Markdown 正文语义色和行距**

在 `NexaraMarkdownTheme.kt` 中仅把硬编码正文颜色替换为 `MaterialTheme.colorScheme.onSurface`、次要信息替换为 `onSurfaceVariant`、代码块表面替换为 `surfaceContainerLow`。保持 CommonMark 换行修复、表格、链接、代码块与现有 `MarkdownText` 数据处理不变。

- [ ] **Step 6: 添加真实 Compose 交互测试**

```kotlin
package com.promenar.nexara.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraTheme
import org.junit.Rule
import org.junit.Test

class ThinkingTraceTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun completedThinkingTraceCanExpandAndCollapse() {
        rule.setContent {
            NexaraTheme {
                ThinkingTrace(
                    reasoning = "先检查输入，再组织答案。",
                    isGenerating = false,
                    fontSize = 14,
                )
            }
        }

        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsNotDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).performClick()
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_THINKING_TOGGLE).performClick()
        rule.onNodeWithTag(UiTags.CHAT_THINKING_CONTENT).assertIsNotDisplayed()
    }
}
```

- [ ] **Step 7: 验证单测、编译和思考轨迹交互**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest --tests 'com.promenar.nexara.ui.chat.PipelineBubbleTest' :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL。

Run（连接设备）: `cd native-ui && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.promenar.nexara.ui.chat.ThinkingTraceTest`

Expected: PASS。

- [ ] **Step 8: 提交消息与思考轨迹**

```bash
git add native-ui/app/src/main/java/com/promenar/nexara/ui/chat/PipelineBubble.kt \
  native-ui/app/src/main/java/com/promenar/nexara/ui/renderer/NexaraMarkdownTheme.kt \
  native-ui/app/src/test/java/com/promenar/nexara/ui/chat/PipelineBubbleTest.kt \
  native-ui/app/src/androidTest/java/com/promenar/nexara/ui/chat/ThinkingTraceTest.kt
git commit -m "feat: add Nexara Material 3 thinking trace"
```

---

### Task 6: 完成会话状态、截图与多尺寸视觉验收

**Files:**
- Modify only if fixture semantics need adjustment: `native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt`
- Update after visual approval: the six chat PNG files under `native-ui/app/src/screenshotTestDebug/reference/com/promenar/nexara/ui/ReleasePreviewScreenshotTestKt/`

**Interfaces:**
- Consumes: Tasks 1-5 的稳定主题和会话 UI。
- Produces: 六个经过人工对照方案 3 的会话截图基线，以及空白、流式、错误、审批、横屏和大字体状态的回归证据。

- [ ] **Step 1: 先运行不改基线的截图验证**

Run: `cd native-ui && ./gradlew :app:validateDebugScreenshotTest`

Expected: 仅会话相关截图因批准的视觉变化产生差异；其他页面若只因全局语义色补齐产生差异，也必须逐张审阅，不得自动接受。

- [ ] **Step 2: 生成候选实际截图**

Run: `cd native-ui && ./gradlew :app:updateDebugScreenshotTest`

Expected: 更新或生成实际 PNG；重点范围为：

```text
emptyChatReleasePreview
streamingChatReleasePreview
chatErrorLargeFontReleasePreview
chatApprovalTabletReleasePreview
emptyChatEnglishLandscapeReleasePreview
streamingChatChineseLandscapeReleasePreview
```

- [ ] **Step 3: 逐张进行参考图并排视觉审阅**

主控必须把用户批准的方案 3 参考图与每张候选截图放入同一次视觉比较输入，检查：

```text
1. 顶栏遵循稳定 MD3，图标与标题无裁剪。
2. 用户消息为 secondaryContainer，助手正文直接落在背景上。
3. 思考条全宽、低对比，展开后有细竖线和节点，不出现嵌套卡片。
4. 模型/上下文控件至少 48dp，不拥挤、不异常增高。
5. composer 只有一层表面；输入法、横屏、平板和 2 倍字体下不遮挡消息。
6. 所有文字基线、行距、边距、圆角和图标光学对齐合理。
```

任何一项不满足时，回到对应实现任务修复，重新生成并再次比较。

- [ ] **Step 4: 重新验证截图基线**

Run: `cd native-ui && ./gradlew :app:validateDebugScreenshotTest`

Expected: BUILD SUCCESSFUL，0 个 screenshot diff。

- [ ] **Step 5: 执行 Phase 1 完整本地门禁**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :mainactivity-e2e:assembleDebug`

Expected: BUILD SUCCESSFUL。

Run（连接设备）: `cd native-ui && ./gradlew :app:connectedDebugAndroidTest`

Expected: PASS；至少覆盖 `AccessibilitySmokeTest`、`ChatScreenContentStateTest`、`ChatImeInteractionTest`、`ThinkingTraceTest`。

- [ ] **Step 6: 提交经审阅的视觉基线**

```bash
git add native-ui/app/src/screenshotTest/kotlin/com/promenar/nexara/ui/ReleasePreviewScreenshotTest.kt \
  native-ui/app/src/screenshotTestDebug/reference/com/promenar/nexara/ui/ReleasePreviewScreenshotTestKt
git commit -m "test: approve Material 3 chat visual baselines"
```

若 fixture 无需修改，不要为提交而改动 `ReleasePreviewScreenshotTest.kt`；只加入实际变化且已人工审阅的 PNG。

---

### Task 7: 文档治理、独立复核与 Phase 1 收口

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `.agent/handover.md`
- Regenerate: `.agent/handover-index.md`
- Modify if responsibilities changed: `.agent/registry.md`

**Interfaces:**
- Consumes: 所有 Phase 1 commits、测试报告、截图对照结论与未完成设备门禁。
- Produces: 可恢复的 HLG 记录、DIA 结论、独立代码审阅结论和明确的 Phase 2 入口。

- [ ] **Step 1: 运行改动范围与敏感文件检查**

Run: `git status --short && git diff --stat 38fc1db...HEAD && git diff --name-only 38fc1db...HEAD`

Expected: 改动仅包括计划列出的主题、会话、测试和治理文件；不包含 `artifacts/`、`secure_env/`、`.agent/tmp-agent-reports/`、签名文件或密钥。

- [ ] **Step 2: 委派独立规格符合性与代码质量复核**

审阅者必须逐项核对：

```text
- Stable M3 1.4.0 / BOM 2026.06.00，无 alpha Expressive API。
- 未修改 ViewModel、数据、导航和外部会话接口。
- 主会话无 GlassSurface、GlassBorder、CompactInputChip 与嵌套输入浮岛。
- 48dp 触控、IME、流式追尾、用户主动上滚、TalkBack 语义无回归。
- 思考轨迹保留 Nexara 特征，但使用 M3 semantic surface。
- 所有变更截图已由主控与方案 3 并排检查。
```

审阅发现必须按严重度修复后重跑相应门禁；审阅 Agent 的“通过”不能替代本地主控验证。

- [ ] **Step 3: 更新 CHANGELOG 与 HLG**

`CHANGELOG.md` 在未发布区加入：

```markdown
- 建立稳定 Material 3 1.4.0 主题基线，统一深色 tonal surface、形状、间距、层级与字体令牌。
- 主会话页采用单层 Material 3 composer、语义化用户消息表面及 Nexara 思考轨迹，并保留既有业务与数据契约。
- 补充会话布局、无障碍、IME、思考折叠与多尺寸截图回归门禁。
```

在 `.agent/handover.md` 追加带 ISO 时间戳的新记录，包含 `type`、`scope`、`status`、`tags`、`continuity`、`continuity-key: nexara-md3-redesign` 以及 `Summary / Changed / Validation / Next / Risks / DIA / HLG`。不得改写已有记录。

- [ ] **Step 4: 重建并验证 handover index**

使用 `handover-lifecycle-governance` Skill 的项目检查与索引命令；要求 `invalid continuity = 0`，且 `nexara-md3-redesign` Current Workstream 指向最新记录。

- [ ] **Step 5: 执行最终验证摘要**

Run: `cd native-ui && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :mainactivity-e2e:assembleDebug :app:validateDebugScreenshotTest`

Expected: BUILD SUCCESSFUL。

若设备可用，再运行：`cd native-ui && ./gradlew :app:connectedDebugAndroidTest`

Expected: PASS；若设备不可用，最终说明必须明确列出未执行项，不得把它计入已通过。

- [ ] **Step 6: 提交治理收口**

```bash
git add CHANGELOG.md .agent/registry.md .agent/handover.md .agent/handover-index.md
git commit -m "docs: record Material 3 chat phase completion"
```

完成后保持在 `codex/md3-redesign`，不要合并、推送或改写已验证的 `codex/v0.2-beta`，除非用户另行授权。
