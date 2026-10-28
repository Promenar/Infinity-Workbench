package com.promenar.infinity.ui.welcome

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.onboarding.OnboardingStep
import com.promenar.infinity.ui.chat.ParticleWordmark
import com.promenar.infinity.ui.common.network.rememberAnimationsEnabled
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.hierarchicalTransition
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.delay

/** 宽于此值（折叠屏内屏、平板）时左侧常驻小字标与纵向进度，右侧内容限宽。 */
private val WideLayoutMinWidth = 600.dp
private val ContentMaxWidth = 560.dp
/** 步骤内元素依次上浮的错峰间隔。 */
private const val RiseStaggerMillis = 45L

/**
 * 首次引导：语言 → Linux（仅侧载 arm64）→ 连接提供商 → 选择模型 → 创建 Agent → 准备就绪。
 * 页面切换沿用共享 X 轴；系统“移除动画”时粒子直接落位、元素不上浮、没有呼吸与流动。
 */
@Composable
internal fun OnboardingScreen(
    state: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    var linuxSheetOpen by rememberSaveable { mutableStateOf(false) }
    // 静态预览没有返回分发器；运行时系统返回与左上角返回按钮一致。
    if (LocalOnBackPressedDispatcherOwner.current != null) {
        when (state.step) {
            OnboardingStep.MODEL -> BackHandler(enabled = !state.busy, onBack = actions.onBackToProvider)
            OnboardingStep.AGENT -> BackHandler(enabled = !state.busy, onBack = actions.onBackToModel)
            else -> Unit
        }
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize()) {
        OnboardingAura()
        AnimatedContent(
            targetState = state.step == OnboardingStep.LANGUAGE,
            transitionSpec = { InfinityMotion.sharedAxisX(forward = !targetState) },
            label = "onboarding_intro",
            modifier = Modifier.fillMaxSize(),
        ) { intro ->
            if (intro) {
                OnboardingIntro(onLanguageSelected = actions.onLanguageSelected, busy = state.busy)
            } else {
                OnboardingFrame(state, actions, onOpenLinux = { linuxSheetOpen = true })
            }
        }
      }
    }
    val linux = state.linux
    if (linuxSheetOpen && linux != null) {
        OnboardingLinuxSheet(
            status = linux,
            actions = actions,
            onDismiss = { linuxSheetOpen = false },
        )
    }
}

@Composable
private fun OnboardingFrame(state: OnboardingUiState, actions: OnboardingActions, onOpenLinux: () -> Unit) {
    val railSteps = onboardingRailSteps(state.linux != null)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val wide = maxWidth >= WideLayoutMinWidth
        val ready = state.step == OnboardingStep.FIRST_CHAT
        Row(Modifier.fillMaxSize()) {
            if (wide && !ready) {
                Column(
                    Modifier
                        .width(260.dp)
                        .fillMaxHeight()
                        .padding(start = InfinitySpacing.XLarge, top = InfinitySpacing.XLarge, bottom = InfinitySpacing.XLarge),
                ) {
                    ParticleWordmark(
                        dispersed = false,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        centerFraction = 0.5f,
                        maxTextSizeDp = 40f,
                        logoLimit = 420,
                        ambientCount = 70,
                    )
                    Spacer(Modifier.height(InfinitySpacing.XLarge))
                    OnboardingVerticalRail(railSteps, state.step)
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                OnboardingTopBar(
                    state = state,
                    railSteps = railSteps,
                    showRail = !wide && !ready,
                    actions = actions,
                    onOpenLinux = onOpenLinux,
                )
                AnimatedContent(
                    targetState = state.step,
                    transitionSpec = { hierarchicalTransition { it.ordinal } },
                    label = "onboarding_step",
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) { step ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(OnboardingTags.step(step)),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxSize()) {
                            when (step) {
                                OnboardingStep.LINUX -> state.linux?.let { OnboardingLinuxStep(it, actions) }
                                OnboardingStep.PROVIDER -> OnboardingProviderStep(state, actions, wide)
                                OnboardingStep.MODEL -> OnboardingModelStep(state, actions)
                                OnboardingStep.AGENT -> OnboardingAgentStep(state, actions)
                                OnboardingStep.FIRST_CHAT -> OnboardingReadyStep(state, actions)
                                OnboardingStep.LANGUAGE, OnboardingStep.COMPLETED -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingTopBar(
    state: OnboardingUiState,
    railSteps: List<OnboardingStep>,
    showRail: Boolean,
    actions: OnboardingActions,
    onOpenLinux: () -> Unit,
) {
    val back = when (state.step) {
        OnboardingStep.MODEL -> actions.onBackToProvider
        OnboardingStep.AGENT -> actions.onBackToModel
        else -> null
    }
    val index = railSteps.indexOf(state.step)
    Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Small, vertical = InfinitySpacing.Small)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(visible = back != null, enter = fadeIn(), exit = fadeOut()) {
                    IconButton(
                        onClick = { back?.invoke() },
                        enabled = !state.busy,
                        modifier = Modifier.testTag(OnboardingTags.BACK),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                }
            }
            Box(Modifier.weight(1f).padding(horizontal = InfinitySpacing.XSmall)) {
                if (showRail) OnboardingRail(railSteps, state.step)
            }
            OnboardingLinuxCapsule(
                status = state.linux?.takeIf { state.step != OnboardingStep.LINUX },
                onClick = onOpenLinux,
                modifier = Modifier.padding(end = InfinitySpacing.Small),
            )
        }
        if (index >= 0) {
            AnimatedContent(
                targetState = index,
                transitionSpec = { InfinityMotion.sharedAxisY(forward = targetState > initialState) },
                label = "onboarding_step_label",
            ) { shown ->
                Text(
                    text = stringResource(R.string.onboarding_step_of, shown + 1, railSteps.size) + " · " +
                        stringResource(railSteps[shown].railLabel()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 56.dp, top = InfinitySpacing.XSmall),
                )
            }
        }
    }
}

private fun OnboardingStep.railLabel(): Int = when (this) {
    OnboardingStep.LINUX -> R.string.onboarding_rail_linux
    OnboardingStep.PROVIDER -> R.string.onboarding_rail_provider
    OnboardingStep.MODEL -> R.string.onboarding_rail_model
    else -> R.string.onboarding_rail_agent
}

/** 横向进度：节点与连线随步骤点亮；已完成节点显示对勾。 */
@Composable
private fun OnboardingRail(steps: List<OnboardingStep>, current: OnboardingStep) {
    val currentIndex = if (current == OnboardingStep.FIRST_CHAT) steps.size else steps.indexOf(current)
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, _ ->
            RailNode(index = index, currentIndex = currentIndex)
            if (index < steps.lastIndex) {
                RailLink(lit = index < currentIndex, modifier = Modifier.weight(1f).padding(horizontal = InfinitySpacing.XSmall))
            }
        }
    }
}

@Composable
private fun OnboardingVerticalRail(steps: List<OnboardingStep>, current: OnboardingStep) {
    val currentIndex = steps.indexOf(current)
    Column {
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RailNode(index = index, currentIndex = currentIndex)
                Spacer(Modifier.width(InfinitySpacing.Medium))
                Text(
                    stringResource(step.railLabel()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (index == currentIndex) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (index <= currentIndex) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (index < steps.lastIndex) {
                val lit = index < currentIndex
                val color by animateColorAsState(
                    if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    tween(InfinityMotion.DurationMedium),
                    label = "rail_vertical_link",
                )
                Box(
                    Modifier
                        .padding(start = 11.dp, top = InfinitySpacing.XSmall, bottom = InfinitySpacing.XSmall)
                        .width(2.dp)
                        .height(24.dp)
                        .background(color, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

@Composable
private fun RailNode(index: Int, currentIndex: Int) {
    val done = index < currentIndex
    val active = index == currentIndex
    val primary = MaterialTheme.colorScheme.primary
    val fill by animateColorAsState(
        when {
            done -> primary
            active -> MaterialTheme.colorScheme.primaryContainer
            else -> Color.Transparent
        },
        tween(InfinityMotion.DurationMedium),
        label = "rail_node_fill",
    )
    val border by animateColorAsState(
        if (done || active) primary else MaterialTheme.colorScheme.outlineVariant,
        tween(InfinityMotion.DurationMedium),
        label = "rail_node_border",
    )
    Box(
        Modifier
            .size(24.dp)
            .background(fill, CircleShape)
            .border(1.5.dp, border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
        } else {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RailLink(lit: Boolean, modifier: Modifier) {
    val progress by animateFloatAsState(
        if (lit) 1f else 0f,
        tween(InfinityMotion.DurationMedium * 2, easing = InfinityMotion.EmphasizedDecelerate),
        label = "rail_link",
    )
    val track = MaterialTheme.colorScheme.outlineVariant
    val litColor = MaterialTheme.colorScheme.primary
    Canvas(modifier.height(2.dp)) {
        val y = size.height / 2f
        drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = size.height, cap = StrokeCap.Round)
        if (progress > 0f) {
            drawLine(litColor, Offset(0f, y), Offset(size.width * progress, y), strokeWidth = size.height, cap = StrokeCap.Round)
        }
    }
}

/** 顶部 Linux 胶囊：安装中显示进度环与百分比，中断显示警示，就绪显示对勾；点按打开 Linux 面板。 */
@Composable
private fun OnboardingLinuxCapsule(status: OnboardingLinuxStatus?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val runtime = status?.runtime
    val visible = status != null && !status.hidden && (
        runtime is LinuxRuntimeState.Installing || runtime is LinuxRuntimeState.InstallInterrupted ||
            runtime is LinuxRuntimeState.Ready
        )
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(InfinityMotion.DurationMedium)) + scaleIn(initialScale = 0.85f),
        exit = fadeOut(tween(InfinityMotion.DurationShort)) + scaleOut(targetScale = 0.85f),
        modifier = modifier,
    ) {
        val shown = remember { arrayOfNulls<LinuxRuntimeState>(1) }
        if (runtime != null && visible) shown[0] = runtime
        val current = shown[0] ?: return@AnimatedVisibility
        val label = linuxCapsuleLabel(current)
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .heightIn(min = 36.dp)
                .testTag(OnboardingTags.LINUX_CAPSULE)
                .semantics { contentDescription = label },
        ) {
            Row(
                Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LinuxCapsuleIcon(current)
                Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun linuxCapsuleLabel(runtime: LinuxRuntimeState): String = when (runtime) {
    is LinuxRuntimeState.Installing -> runtime.progress.fraction?.let {
        stringResource(R.string.onboarding_linux_capsule_running, (it * 100).toInt())
    } ?: stringResource(R.string.onboarding_linux_capsule_preparing)
    is LinuxRuntimeState.InstallInterrupted -> stringResource(R.string.onboarding_linux_capsule_stopped)
    else -> stringResource(R.string.onboarding_linux_capsule_ready)
}

@Composable
private fun LinuxCapsuleIcon(runtime: LinuxRuntimeState) {
    val success = MaterialTheme.infinityDomainColors.success
    when (runtime) {
        is LinuxRuntimeState.Installing -> OnboardingProgressRing(runtime.progress.fraction, Modifier.size(16.dp))
        is LinuxRuntimeState.InstallInterrupted -> Box(
            Modifier.size(16.dp).background(MaterialTheme.colorScheme.error, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.PriorityHigh, null, tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(12.dp))
        }
        else -> Box(Modifier.size(16.dp).background(success, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(12.dp))
        }
    }
}

/** 进度环；[fraction] 为 null 时画一段静止的弧表示进行中。 */
@Composable
internal fun OnboardingProgressRing(fraction: Float?, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(fraction ?: 0.25f, tween(InfinityMotion.DurationMedium), label = "linux_ring")
    val track = MaterialTheme.colorScheme.outlineVariant
    val value = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val inset = stroke / 2f
        val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(value, -90f, 360f * animated.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** 背景的两团柔光，与原欢迎页一致。 */
@Composable
private fun BoxScope.OnboardingAura() {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset(x = (-100).dp, y = (-100).dp)
            .size(400.dp)
            .background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), Color.Transparent))),
    )
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 100.dp, y = 100.dp)
            .size(400.dp)
            .background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f), Color.Transparent))),
    )
}

/** 步骤页骨架：可滚动的正文加固定在底部的操作区。 */
@Composable
internal fun OnboardingStepPage(
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
            content = content,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            content = footer,
        )
    }
}

/** 长列表单选步骤页：正文惰性排布，数百个候选也只组合可见项。 */
@Composable
internal fun OnboardingLazyStepPage(
    listTag: String,
    footer: @Composable ColumnScope.() -> Unit,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().selectableGroup().testTag(listTag),
            contentPadding = PaddingValues(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            content = content,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            content = footer,
        )
    }
}

@Composable
internal fun OnboardingHeading(title: String, description: String, centered: Boolean = false, index: Int = 0) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    RiseIn(index) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = align,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
    }
    RiseIn(index + 1) {
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 进入步骤时内容按 [index] 错峰上浮淡入；关闭动画或静态预览时直接显示。 */
@Composable
internal fun RiseIn(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    if (!animate) {
        Box(modifier) { content() }
        return
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * RiseStaggerMillis)
        progress.animateTo(1f, tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate))
    }
    val distance = with(LocalDensity.current) { 16.dp.toPx() }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * distance
        },
    ) { content() }
}
