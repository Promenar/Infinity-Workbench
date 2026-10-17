package com.promenar.nexara.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NexaraInlineStepper(
    title: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    canDecrease: Boolean = true,
    canIncrease: Boolean = true,
    enabled: Boolean = true,
) {
    val decreaseLabel = stringResource(R.string.p0_decrease_setting, title)
    val increaseLabel = stringResource(R.string.p0_increase_setting, title)
    Row(
        modifier.height(36.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp))
            .semantics {
                stateDescription = value
                customActions = buildList {
                    if (enabled && canDecrease) add(CustomAccessibilityAction(decreaseLabel) { onDecrease(); true })
                    if (enabled && canIncrease) add(CustomAccessibilityAction(increaseLabel) { onIncrease(); true })
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperButton(Icons.Rounded.Remove, decreaseLabel, enabled && canDecrease, onDecrease)
        Text(value, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.widthIn(min = 32.dp))
        StepperButton(Icons.Rounded.Add, increaseLabel, enabled && canIncrease, onIncrease)
    }
}

@Composable
private fun StepperButton(icon: ImageVector, label: String, enabled: Boolean, onStep: () -> Unit) {
    val currentStep by rememberUpdatedState(onStep)
    val currentEnabled by rememberUpdatedState(enabled)
    Box(
        Modifier.width(48.dp).requiredHeight(48.dp).minimumInteractiveComponentSize()
            .semantics {
                contentDescription = label
                role = Role.Button
                if (enabled) onClick { currentStep(); true } else disabled()
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var repeated = false
                detectTapGestures(
                    onTap = { if (!repeated && currentEnabled) currentStep() },
                    onPress = {
                        repeated = false
                        coroutineScope {
                            val repeat = launch {
                                delay(400)
                                while (currentEnabled) {
                                    repeated = true
                                    currentStep()
                                    delay(80)
                                }
                            }
                            try { tryAwaitRelease() } finally { repeat.cancel() }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp).alpha(if (enabled) 1f else 0.38f), MaterialTheme.colorScheme.primary)
        }
    }
}
