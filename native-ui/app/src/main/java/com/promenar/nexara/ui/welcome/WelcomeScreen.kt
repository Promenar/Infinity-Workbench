package com.promenar.nexara.ui.welcome

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.onboarding.OnboardingState
import com.promenar.nexara.onboarding.OnboardingStep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.promenar.nexara.data.model.ModelInfo
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun WelcomeScreen(
    onLanguageSelected: (String) -> Unit,
    state: OnboardingState = OnboardingState(),
    models: List<ModelInfo> = emptyList(),
    isWorking: Boolean = false,
    errorMessage: String? = null,
    onOpenProvider: () -> Unit = {},
    onOpenConnection: () -> Unit = {},
    onModelSelected: (String) -> Unit = {},
    onRefreshModels: () -> Unit = {},
    onBackToConnection: () -> Unit = {},
    onCreateAgent: (String) -> Unit = {},
    onOpenFirstChat: () -> Unit = {},
) {
    if (state.step != OnboardingStep.LANGUAGE) {
        OnboardingProgressScreen(
            state = state,
            models = models,
            isWorking = isWorking,
            errorMessage = errorMessage,
            onOpenProvider = onOpenProvider,
            onOpenConnection = onOpenConnection,
            onModelSelected = onModelSelected,
            onRefreshModels = onRefreshModels,
            onBackToConnection = onBackToConnection,
            onCreateAgent = onCreateAgent,
            onOpenFirstChat = onOpenFirstChat,
        )
        return
    }
    // Scaffold provides the true immersive edge-to-edge canvas
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .testTag("onboarding_step_language")
        ) {
            // Background Atmosphere (The massive blurs)
            AtmosphereBackground()

            // Main Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Header Area
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 32.dp) // mb-xl
                ) {
                    // Logo representation
                    Text(
                        text = stringResource(R.string.welcome_brand),
                        style = NexaraTypography.headlineLarge.copy(
                            fontSize = 48.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.05).sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp) // mb-sm
                    )
                    Text(
                        text = stringResource(R.string.welcome_slogan),
                        style = NexaraTypography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f),
                        letterSpacing = 0.1.sp
                    )
                }

                // Action Area (Language Selection)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp), // mt-lg
                    verticalArrangement = Arrangement.spacedBy(16.dp) // space-y-md
                ) {
                    LanguageButton(
                        icon = Icons.Rounded.Language,
                        text = stringResource(R.string.welcome_lang_english),
                        enabled = !isWorking,
                        onClick = { onLanguageSelected("en") }
                    )
                    LanguageButton(
                        icon = Icons.Rounded.Translate,
                        text = stringResource(R.string.welcome_lang_chinese),
                        enabled = !isWorking,
                        onClick = { onLanguageSelected("zh") }
                    )
                }
            }

            // Bottom decorative line (from HTML spec)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp) // bottom-12
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(96.dp)
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.outline,
                                    Color.Transparent
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
private fun OnboardingProgressScreen(
    state: OnboardingState,
    models: List<ModelInfo>,
    isWorking: Boolean,
    errorMessage: String?,
    onOpenProvider: () -> Unit,
    onOpenConnection: () -> Unit,
    onModelSelected: (String) -> Unit,
    onRefreshModels: () -> Unit,
    onBackToConnection: () -> Unit,
    onCreateAgent: (String) -> Unit,
    onOpenFirstChat: () -> Unit,
) {
    var agentName by rememberSaveable { mutableStateOf("") }
    val stepNumber = (state.step.ordinal + 1).coerceIn(1, 6)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AtmosphereBackground()
            if (state.step == OnboardingStep.MODEL) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .testTag("onboarding_step_model"),
                    contentPadding = PaddingValues(vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { OnboardingStepHeader(state.step, stepNumber) }
                    items(models, key = { it.id }) { model ->
                        OnboardingActionButton(
                            text = if (model.type.equals("unknown", ignoreCase = true)) {
                                stringResource(R.string.onboarding_model_pending_label, model.name)
                            } else {
                                model.name
                            },
                            enabled = !isWorking,
                            modifier = Modifier.testTag("onboarding_model_option"),
                            onClick = { onModelSelected(model.id) },
                        )
                    }
                    if (models.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.onboarding_model_empty),
                                style = NexaraTypography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("onboarding_model_empty"),
                            )
                        }
                        item {
                            OnboardingActionButton(
                                text = stringResource(R.string.onboarding_model_refresh),
                                enabled = !isWorking,
                                modifier = Modifier.testTag("onboarding_model_refresh"),
                                onClick = onRefreshModels,
                            )
                        }
                        item {
                            OnboardingActionButton(
                                text = stringResource(R.string.onboarding_model_back_connection),
                                enabled = !isWorking,
                                modifier = Modifier.testTag("onboarding_model_back_connection"),
                                onClick = onBackToConnection,
                            )
                        }
                    }
                    errorMessage?.let { message -> item { OnboardingError(message) } }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 32.dp)
                        .testTag("onboarding_step_${state.step.name.lowercase()}"),
                    verticalArrangement = Arrangement.Center,
                ) {
                    OnboardingStepHeader(state.step, stepNumber)
                    when (state.step) {
                    OnboardingStep.PROVIDER -> OnboardingActionButton(
                        text = stringResource(R.string.onboarding_provider_action),
                        enabled = !isWorking,
                        onClick = onOpenProvider,
                    )
                    OnboardingStep.CONNECTION -> OnboardingActionButton(
                        text = stringResource(R.string.onboarding_connection_action),
                        enabled = !isWorking,
                        onClick = onOpenConnection,
                    )
                    OnboardingStep.MODEL -> Unit
                    OnboardingStep.AGENT -> {
                        OutlinedTextField(
                            value = agentName,
                            onValueChange = { agentName = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.onboarding_agent_name)) },
                        )
                        Spacer(Modifier.height(16.dp))
                        val fallbackName = stringResource(R.string.onboarding_agent_default_name)
                        OnboardingActionButton(
                            text = stringResource(R.string.onboarding_agent_action),
                            enabled = !isWorking,
                            onClick = { onCreateAgent(agentName.ifBlank { fallbackName }) },
                        )
                    }
                    OnboardingStep.FIRST_CHAT -> OnboardingActionButton(
                        text = stringResource(R.string.onboarding_first_chat_action),
                        enabled = !isWorking && state.sessionId != null,
                        onClick = onOpenFirstChat,
                    )
                    OnboardingStep.LANGUAGE, OnboardingStep.COMPLETED -> Unit
                }

                    if (errorMessage != null) {
                    Spacer(Modifier.height(16.dp))
                        OnboardingError(errorMessage)
                    }
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepHeader(step: OnboardingStep, stepNumber: Int) {
    Text(
        text = stringResource(R.string.onboarding_progress, stepNumber),
        style = NexaraTypography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(step.titleResource()),
        style = NexaraTypography.headlineLarge,
        color = MaterialTheme.colorScheme.onBackground,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(step.descriptionResource()),
        style = NexaraTypography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun OnboardingError(message: String) {
    Text(
        text = message,
        style = NexaraTypography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun OnboardingActionButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(text = text, style = NexaraTypography.labelMedium)
    }
}

private fun OnboardingStep.titleResource(): Int = when (this) {
    OnboardingStep.PROVIDER -> R.string.onboarding_provider_title
    OnboardingStep.CONNECTION -> R.string.onboarding_connection_title
    OnboardingStep.MODEL -> R.string.onboarding_model_title
    OnboardingStep.AGENT -> R.string.onboarding_agent_title
    OnboardingStep.FIRST_CHAT -> R.string.onboarding_first_chat_title
    OnboardingStep.LANGUAGE, OnboardingStep.COMPLETED -> R.string.welcome_brand
}

private fun OnboardingStep.descriptionResource(): Int = when (this) {
    OnboardingStep.PROVIDER -> R.string.onboarding_provider_desc
    OnboardingStep.CONNECTION -> R.string.onboarding_connection_desc
    OnboardingStep.MODEL -> R.string.onboarding_model_desc
    OnboardingStep.AGENT -> R.string.onboarding_agent_desc
    OnboardingStep.FIRST_CHAT -> R.string.onboarding_first_chat_desc
    OnboardingStep.LANGUAGE, OnboardingStep.COMPLETED -> R.string.welcome_slogan
}

@Composable
private fun BoxScope.AtmosphereBackground() {
    // Top-left glow using radial gradient to avoid blur clipping issues
    Box(
        modifier = Modifier
            .align(Alignment.TopStart)
            .offset(x = (-100).dp, y = (-100).dp)
            .size(400.dp)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        Color.Transparent
                    )
                )
            )
    )
    
    // Bottom-right glow using radial gradient
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 100.dp, y = 100.dp)
            .size(400.dp)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f),
                        Color.Transparent
                    )
                )
            )
    )
}

@Composable
private fun LanguageButton(
    icon: ImageVector,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Stitch explicitly demands a 0.95 scale down on active press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "ButtonScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null, // Disable default ripple because the scale is the intended feedback
                onClick = onClick
            ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp), // px-lg py-md
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp) // gap-md
            ) {
                // Icon circular container
                Box(
                    modifier = Modifier
                        .size(32.dp) // w-8 h-8
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), shape = androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                
                Text(
                    text = text,
                    style = NexaraTypography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
