package com.promenar.nexara.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.domain.linux.LinuxOptionalPackage
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxStepStatus
import com.promenar.nexara.onboarding.OnboardingStep
import com.promenar.nexara.ui.chat.ParticleWordmark
import com.promenar.nexara.ui.chat.WordmarkPointer
import com.promenar.nexara.ui.chat.trackWordmarkPointer
import com.promenar.nexara.ui.common.network.rememberAnimationsEnabled
import com.promenar.nexara.ui.linux.settings.LINUX_BUNDLED_ALPINE_VERSION
import com.promenar.nexara.ui.linux.settings.LinuxCallout
import com.promenar.nexara.ui.linux.settings.LinuxFailureCard
import com.promenar.nexara.ui.linux.settings.LinuxInstallStepRow
import com.promenar.nexara.ui.linux.settings.LinuxOutputBox
import com.promenar.nexara.ui.linux.settings.linuxBytes
import com.promenar.nexara.ui.linux.settings.linuxOptionalPackageSubtitle
import com.promenar.nexara.ui.linux.settings.linuxOptionalPackageTitle
import com.promenar.nexara.ui.linux.settings.linuxRequiredBytes
import com.promenar.nexara.ui.theme.NexaraMonospace
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.nexaraDomainColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 选中语言后等粒子散开再推进；语言切换可能重建 Activity。 */
private const val LanguageDisperseMillis = 420L
private const val LinuxOutputLines = 6

@Composable
internal fun OnboardingIntro(onLanguageSelected: (String) -> Unit, busy: Boolean) {
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    var selecting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pointer = remember { WordmarkPointer() }
    val select: (String) -> Unit = { code ->
        if (!selecting && !busy) {
            selecting = true
            scope.launch {
                if (animate) delay(LanguageDisperseMillis)
                onLanguageSelected(code)
                // 语言未能保存时引导停在本页，粒子重新聚拢后可再次选择。
                delay(1_000)
                selecting = false
            }
        }
    }
    val wordmark: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.trackWordmarkPointer(pointer), contentAlignment = Alignment.Center) {
            ParticleWordmark(dispersed = selecting, pointer = pointer, modifier = Modifier.fillMaxSize(), centerFraction = 0.5f)
            Text(
                stringResource(R.string.welcome_slogan),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                modifier = Modifier.offset(y = 56.dp),
            )
        }
    }
    val choices: @Composable (Modifier) -> Unit = { modifier ->
        Column(
            modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(horizontal = NexaraSpacing.XLarge, vertical = NexaraSpacing.XLarge),
            verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
        ) {
            RiseIn(0) {
                Text(
                    stringResource(R.string.onboarding_choose_language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            RiseIn(1) {
                LanguageButton("文", stringResource(R.string.welcome_lang_chinese), "zh", enabled = !selecting && !busy, onClick = select)
            }
            RiseIn(2) {
                LanguageButton("A", stringResource(R.string.welcome_lang_english), "en", enabled = !selecting && !busy, onClick = select)
            }
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(OnboardingTags.step(OnboardingStep.LANGUAGE)),
    ) {
        if (maxWidth > maxHeight && maxHeight < 480.dp) {
            // 横屏矮窗口：字标与语言按钮左右并排，按钮列可滚动，放大字号也能点到。
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                wordmark(Modifier.weight(1f).fillMaxHeight())
                Box(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) { choices(Modifier) }
            }
        } else {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                wordmark(Modifier.weight(1f).fillMaxWidth())
                choices(Modifier)
            }
        }
    }
}

@Composable
private fun LanguageButton(glyph: String, label: String, code: String, enabled: Boolean, onClick: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.97f else 1f, label = "language_press")
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(enabled = enabled, interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button) {
                onClick(code)
            }
            .testTag(OnboardingTags.LANGUAGE_PREFIX + code),
    ) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = NexaraSpacing.Large, vertical = NexaraSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
internal fun OnboardingLinuxStep(status: OnboardingLinuxStatus, actions: OnboardingActions) {
    val runtime = status.runtime
    val freeBytes = (runtime as? LinuxRuntimeState.NotInstalled)?.freeBytes
    val required = linuxRequiredBytes(status.packages)
    val enoughSpace = freeBytes == null || freeBytes >= required
    OnboardingStepPage(
        footer = {
            RiseIn(6) {
                Button(
                    onClick = actions.onInstallLinux,
                    enabled = enoughSpace,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(OnboardingTags.LINUX_INSTALL),
                ) { Text(stringResource(R.string.onboarding_linux_install), style = MaterialTheme.typography.titleSmall) }
            }
            RiseIn(7) {
                TextButton(
                    onClick = actions.onSkipLinux,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(OnboardingTags.LINUX_SKIP),
                ) { Text(stringResource(R.string.onboarding_linux_skip)) }
            }
            RiseIn(8) {
                Text(
                    stringResource(R.string.onboarding_linux_background_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        RiseIn(0) { LinuxTerminalPreview() }
        OnboardingHeading(
            stringResource(R.string.linux_install_hero_title),
            stringResource(R.string.linux_install_hero_body),
            index = 1,
        )
        RiseIn(3) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column {
                    LinuxPackageRow(
                        icon = Icons.Rounded.Inventory2,
                        title = stringResource(R.string.linux_install_base_title, LINUX_BUNDLED_ALPINE_VERSION),
                        subtitle = stringResource(R.string.linux_install_base_subtitle),
                        checked = true,
                        onCheckedChange = null,
                    )
                    LinuxOptionalPackage.entries.forEach { item ->
                        HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        LinuxPackageRow(
                            icon = Icons.Rounded.Code,
                            title = stringResource(linuxOptionalPackageTitle(item)),
                            subtitle = linuxOptionalPackageSubtitle(item),
                            checked = item in status.packages,
                            onCheckedChange = { actions.onSetLinuxPackage(item, it) },
                        )
                    }
                }
            }
        }
        if (freeBytes != null) {
            RiseIn(4) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
                    Icon(
                        if (enoughSpace) Icons.Rounded.SdStorage else Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = if (enoughSpace) MaterialTheme.nexaraDomainColors.success else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (enoughSpace) stringResource(R.string.onboarding_linux_space, linuxBytes(freeBytes), linuxBytes(required))
                        else stringResource(R.string.linux_install_space_low, linuxBytes(freeBytes), linuxBytes(required)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enoughSpace) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        RiseIn(5) { LinuxCallout(stringResource(R.string.linux_install_warning)) }
    }
}

@Composable
private fun LinuxPackageRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(toggle)
            .heightIn(min = 64.dp)
            .padding(horizontal = NexaraSpacing.Large, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Checkbox(checked = checked, onCheckedChange = null, enabled = onCheckedChange != null)
    }
}

private val TerminalLines = listOf("~ $ uname -sm", "Linux aarch64", "~ $ apk add git python3", "OK")

/** 终端预览：逐字打出几行命令；关闭动画时直接显示全文。纯装饰。 */
@Composable
private fun LinuxTerminalPreview() {
    val animate = rememberAnimationsEnabled() && !LocalInspectionMode.current
    val full = remember { TerminalLines.joinToString("\n") }
    var shown by remember { mutableIntStateOf(if (animate) 0 else full.length) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        delay(300)
        while (shown < full.length) {
            delay(if (full[shown] == '\n') 260 else 38)
            shown++
        }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
    ) {
        Column(Modifier.padding(horizontal = NexaraSpacing.Large, vertical = NexaraSpacing.Medium)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(MaterialTheme.colorScheme.error, MaterialTheme.nexaraDomainColors.warning, MaterialTheme.nexaraDomainColors.success).forEach {
                    Box(Modifier.size(8.dp).background(it.copy(alpha = 0.8f), CircleShape))
                }
            }
            Spacer(Modifier.height(NexaraSpacing.Small))
            Text(
                // 预留全文高度，逐字出现时卡片不跳动。
                text = full.substring(0, shown) + "▍" + "\n".repeat(full.count { it == '\n' } - full.substring(0, shown).count { it == '\n' }),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = NexaraMonospace),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Linux 面板：分步进度、实时输出尾部与取消、重试、跳过。安装在应用级作用域进行，关闭面板不影响。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnboardingLinuxSheet(status: OnboardingLinuxStatus, actions: OnboardingActions, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        OnboardingLinuxSheetContent(
            runtime = status.runtime,
            onCancel = actions.onCancelLinux,
            onRetry = actions.onRetryLinux,
            onSkip = {
                actions.onHideLinux()
                close()
            },
            onClose = close,
        )
    }
}

@Composable
internal fun OnboardingLinuxSheetContent(
    runtime: LinuxRuntimeState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
) {
    val progress = when (runtime) {
        is LinuxRuntimeState.Installing -> runtime.progress
        is LinuxRuntimeState.InstallInterrupted -> runtime.progress
        else -> null
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = NexaraSpacing.XLarge)
            .padding(bottom = NexaraSpacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
    ) {
        Text(
            stringResource(R.string.onboarding_linux_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        when (runtime) {
            is LinuxRuntimeState.Installing -> {
                val steps = runtime.progress.steps
                val index = steps.indexOfFirst { it.status == LinuxStepStatus.RUNNING }.coerceAtLeast(0)
                val head = stringResource(R.string.linux_status_installing, index + 1, steps.size)
                val fraction = runtime.progress.fraction
                Text(
                    if (fraction != null) stringResource(R.string.onboarding_linux_sheet_progress, head, (fraction * 100).toInt()) else head,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val label = stringResource(R.string.linux_progress_label)
                val bar = Modifier.fillMaxWidth().semantics { contentDescription = label }
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = bar) else LinearProgressIndicator(modifier = bar)
            }
            is LinuxRuntimeState.InstallInterrupted -> {
                Text(
                    stringResource(if (runtime.failure != null) R.string.linux_status_failed else R.string.linux_status_interrupted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                runtime.failure?.let { LinuxFailureCard(it) }
            }
            is LinuxRuntimeState.Ready -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.nexaraDomainColors.success)
                Text(
                    stringResource(R.string.linux_status_ready, runtime.info.version),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> Unit
        }
        if (progress != null) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 6.dp)) { progress.steps.forEach { LinuxInstallStepRow(it) } }
            }
            if (progress.outputTail.isNotBlank()) {
                LinuxOutputBox(progress.outputTail.trimEnd().lines().takeLast(LinuxOutputLines).joinToString("\n"))
            }
            Text(
                stringResource(
                    if (runtime is LinuxRuntimeState.Installing) R.string.onboarding_linux_sheet_note_running
                    else R.string.onboarding_linux_sheet_note_stopped,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium)) {
            val buttonModifier = Modifier.weight(1f).heightIn(min = 48.dp)
            when (runtime) {
                is LinuxRuntimeState.Installing -> {
                    FilledTonalButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.filledTonalButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = buttonModifier,
                    ) { Text(stringResource(R.string.linux_install_cancel)) }
                    Button(onClick = onClose, modifier = buttonModifier) { Text(stringResource(R.string.onboarding_linux_hide)) }
                }
                is LinuxRuntimeState.InstallInterrupted -> {
                    TextButton(onClick = onSkip, modifier = buttonModifier) { Text(stringResource(R.string.onboarding_linux_skip_install)) }
                    Button(onClick = onRetry, modifier = buttonModifier) { Text(stringResource(R.string.onboarding_linux_retry)) }
                }
                else -> Button(onClick = onClose, modifier = buttonModifier) { Text(stringResource(R.string.onboarding_linux_done)) }
            }
        }
    }
}
