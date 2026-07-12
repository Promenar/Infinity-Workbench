package com.promenar.nexara.ui.startup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.backup.BackupStartupState

object StartupGateTags {
    const val ROOT = "startup-gate"
    const val RECOVERING = "startup-recovering"
    const val BLOCKED = "startup-blocked"
    const val RETRY = "startup-retry"
}

@Composable
fun StartupGate(
    state: BackupStartupState,
    onRetry: () -> Unit,
    readyContent: @Composable () -> Unit,
) {
    when (state) {
        BackupStartupState.Ready -> readyContent()
        BackupStartupState.Recovering -> StartupStatusSurface(
            stateTag = StartupGateTags.RECOVERING,
            icon = {
                CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    strokeWidth = 3.dp,
                )
            },
            title = stringResource(R.string.startup_recovering_title),
            message = stringResource(R.string.startup_recovering_message),
        )
        BackupStartupState.Blocked -> StartupStatusSurface(
            stateTag = StartupGateTags.BLOCKED,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = stringResource(R.string.startup_blocked_icon_description),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = stringResource(R.string.startup_blocked_title),
            message = stringResource(R.string.startup_blocked_message),
            action = {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .testTag(StartupGateTags.RETRY)
                        .heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.startup_retry))
                }
            },
        )
    }
}

@Composable
private fun StartupStatusSurface(
    stateTag: String,
    icon: @Composable () -> Unit,
    title: String,
    message: String,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .testTag(StartupGateTags.ROOT),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = 32.dp)
                .testTag(stateTag),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            icon()
            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null) {
                Spacer(Modifier.height(28.dp))
                action()
            }
        }
    }
}
