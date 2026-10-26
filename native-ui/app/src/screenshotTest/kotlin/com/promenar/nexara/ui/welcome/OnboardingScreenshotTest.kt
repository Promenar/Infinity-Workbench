package com.promenar.nexara.ui.welcome

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.data.remote.provider.ProviderConnectionProbeFailure
import com.promenar.nexara.domain.linux.LinuxEnvironmentInfo
import com.promenar.nexara.domain.linux.LinuxFailure
import com.promenar.nexara.domain.linux.LinuxFailureCode
import com.promenar.nexara.domain.linux.LinuxInstallProgress
import com.promenar.nexara.domain.linux.LinuxInstallStep
import com.promenar.nexara.domain.linux.LinuxInstallStepState
import com.promenar.nexara.domain.linux.LinuxMirrorId
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxStepDetail
import com.promenar.nexara.domain.linux.LinuxStepStatus
import com.promenar.nexara.onboarding.OnboardingStep
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

private const val PHONE_WIDTH_DP = 360
private const val PHONE_HEIGHT_DP = 800
private const val FOLD_WIDTH_DP = 814
private const val FOLD_HEIGHT_DP = 900
private const val MB = 1024L * 1024

private fun installing(failed: Boolean = false) = LinuxInstallProgress(
    steps = listOf(
        LinuxInstallStepState(LinuxInstallStep.UNPACK, LinuxStepStatus.DONE, 1_800, LinuxStepDetail.Unpacked(10 * MB, true)),
        LinuxInstallStepState(LinuxInstallStep.NETWORK, LinuxStepStatus.DONE, 400, LinuxStepDetail.Network(listOf("223.5.5.5"), viaVpn = false, proxyConfigured = false)),
        LinuxInstallStepState(
            LinuxInstallStep.COMMON_TOOLS,
            if (failed) LinuxStepStatus.FAILED else LinuxStepStatus.RUNNING,
            detail = LinuxStepDetail.Downloading(LinuxMirrorId.TUNA, 21 * MB, 34 * MB),
        ),
        LinuxInstallStepState(LinuxInstallStep.WORKSPACE, LinuxStepStatus.PENDING),
        LinuxInstallStepState(LinuxInstallStep.DIAGNOSTICS, LinuxStepStatus.PENDING),
    ),
    fraction = 0.42f,
    outputTail = "(12/31) Installing libcurl\n(13/31) Installing git\n  Fetching … 21.4 MB / 34.0 MB",
)

private val Installing = OnboardingLinuxStatus(LinuxRuntimeState.Installing(installing()))
private val Interrupted = OnboardingLinuxStatus(
    LinuxRuntimeState.InstallInterrupted(installing(failed = true), LinuxFailure(LinuxFailureCode.NETWORK, "fetch failed: DNS")),
)

private val PreviewModels = listOf(
    ModelInfo(name = "GPT-5.1", id = "p::gpt-5.1", description = "", enabled = true, type = "chat", providerId = "p", remoteModelId = "gpt-5.1"),
    ModelInfo(name = "o5", id = "p::o5", description = "", enabled = true, type = "reasoning", providerId = "p", remoteModelId = "o5"),
    ModelInfo(name = "my-tuned-assistant", id = "p::ft", description = "", enabled = true, type = "unknown", providerId = "p", remoteModelId = "ft:gpt-5.1-mini:acme::9xQ2"),
)

@Composable
private fun OnboardingPreviewSurface(dark: Boolean = true, content: @Composable () -> Unit) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }
}

@PreviewTest
@Preview(name = "Onboarding language Chinese", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun onboardingLanguagePreview() {
    OnboardingPreviewSurface { OnboardingScreen(OnboardingUiState(OnboardingStep.LANGUAGE), OnboardingActions()) }
}

@PreviewTest
@Preview(name = "Onboarding language English light foldable", widthDp = FOLD_WIDTH_DP, heightDp = FOLD_HEIGHT_DP, locale = "en")
@Composable
fun onboardingLanguageFoldLightPreview() {
    OnboardingPreviewSurface(dark = false) { OnboardingScreen(OnboardingUiState(OnboardingStep.LANGUAGE), OnboardingActions()) }
}

@PreviewTest
@Preview(name = "Onboarding Linux Chinese", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun onboardingLinuxPreview() {
    OnboardingPreviewSurface {
        OnboardingScreen(
            OnboardingUiState(OnboardingStep.LINUX, linux = OnboardingLinuxStatus(LinuxRuntimeState.NotInstalled(41L * 1024 * MB))),
            OnboardingActions(),
        )
    }
}

@PreviewTest
@Preview(name = "Onboarding provider English large font", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, locale = "en", fontScale = 1.3f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun onboardingProviderLargeFontPreview() {
    OnboardingPreviewSurface {
        OnboardingScreen(OnboardingUiState(OnboardingStep.PROVIDER, linux = Installing), OnboardingActions())
    }
}

@PreviewTest
@Preview(name = "Onboarding provider failure Chinese light", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, locale = "zh-rCN")
@Composable
fun onboardingProviderFailureLightPreview() {
    OnboardingPreviewSurface(dark = false) {
        OnboardingScreen(
            OnboardingUiState(
                OnboardingStep.PROVIDER,
                connect = OnboardingConnectStatus.Problem(
                    OnboardingConnectOutcome.Failed(ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED, 401),
                ),
            ),
            OnboardingActions(),
        )
    }
}

@PreviewTest
@Preview(name = "Onboarding provider Chinese foldable", widthDp = FOLD_WIDTH_DP, heightDp = FOLD_HEIGHT_DP, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun onboardingProviderFoldPreview() {
    OnboardingPreviewSurface {
        OnboardingScreen(OnboardingUiState(OnboardingStep.PROVIDER, linux = Installing), OnboardingActions())
    }
}

@PreviewTest
@Preview(name = "Onboarding model list Chinese", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun onboardingModelPreview() {
    OnboardingPreviewSurface {
        OnboardingScreen(
            OnboardingUiState(
                OnboardingStep.MODEL,
                linux = Interrupted,
                models = PreviewModels,
                hiddenModelCount = 6,
                failedModelIds = setOf("p::ft"),
            ),
            OnboardingActions(),
        )
    }
}

@PreviewTest
@Preview(name = "Onboarding model empty English", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun onboardingModelEmptyPreview() {
    OnboardingPreviewSurface {
        OnboardingScreen(OnboardingUiState(OnboardingStep.MODEL), OnboardingActions())
    }
}

@PreviewTest
@Preview(name = "Onboarding agent Chinese light", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, locale = "zh-rCN")
@Composable
fun onboardingAgentLightPreview() {
    OnboardingPreviewSurface(dark = false) {
        OnboardingScreen(
            OnboardingUiState(OnboardingStep.AGENT, linux = Installing, modelName = "GPT-5.1"),
            OnboardingActions(),
        )
    }
}

@PreviewTest
@Preview(name = "Onboarding ready English", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, locale = "en", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun onboardingReadyPreview() {
    OnboardingPreviewSurface {
        OnboardingScreen(
            OnboardingUiState(
                OnboardingStep.FIRST_CHAT,
                linux = Interrupted,
                providerName = "OpenAI",
                modelName = "GPT-5.1",
                agentName = "Research aide",
            ),
            OnboardingActions(),
        )
    }
}

@PreviewTest
@Preview(name = "Onboarding ready Chinese foldable", widthDp = FOLD_WIDTH_DP, heightDp = FOLD_HEIGHT_DP, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun onboardingReadyFoldPreview() {
    OnboardingPreviewSurface {
        OnboardingScreen(
            OnboardingUiState(
                OnboardingStep.FIRST_CHAT,
                linux = OnboardingLinuxStatus(LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "3.24.3", "aarch64", "5.4.0", 0L, null))),
                providerName = "DeepSeek",
                modelName = "DeepSeek Chat",
                agentName = "编程搭档",
            ),
            OnboardingActions(),
        )
    }
}

@PreviewTest
@Preview(name = "Onboarding Linux sheet failure Chinese", widthDp = PHONE_WIDTH_DP, heightDp = PHONE_HEIGHT_DP, locale = "zh-rCN", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun onboardingLinuxSheetFailurePreview() {
    OnboardingPreviewSurface {
        // 模拟底部弹层的容器色与拖动条留白。
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box(Modifier.padding(top = 32.dp)) {
                OnboardingLinuxSheetContent(Interrupted.runtime, onCancel = {}, onRetry = {}, onSkip = {}, onClose = {})
            }
        }
    }
}
