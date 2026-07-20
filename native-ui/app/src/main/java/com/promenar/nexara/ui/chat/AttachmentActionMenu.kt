package com.promenar.nexara.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import com.promenar.nexara.R
import com.promenar.nexara.ui.testing.UiTags

private const val MenuAnimationDurationMillis = 160

/** 锚定在父布局内的附件动作菜单；位置参数均使用父布局局部坐标。 */
@Composable
internal fun AttachmentActionMenu(
    expanded: Boolean,
    anchorBounds: Rect,
    maximumBottom: Float,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val visibilityState = remember { MutableTransitionState(expanded) }
    visibilityState.targetState = expanded
    val interceptTouches = visibilityState.currentState || visibilityState.targetState
    val layoutDirection = LocalLayoutDirection.current
    val placementBottom = minOf(anchorBounds.top, maximumBottom)
    val anchorTravel = (anchorBounds.top - placementBottom).toInt().coerceAtLeast(0)
    val transformOrigin = TransformOrigin(
        pivotFractionX = if (layoutDirection == LayoutDirection.Ltr) 0f else 1f,
        pivotFractionY = 1f,
    )

    BackHandler(enabled = expanded, onBack = onDismiss)

    Box(
        modifier = modifier
            .fillMaxSize(),
    ) {
        if (interceptTouches) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(UiTags.CHAT_ATTACHMENT_DISMISS_LAYER)
                    .pointerInput(onDismiss) {
                        detectTapGestures { onDismiss() }
                    },
            )
        }

        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                AnimatedVisibility(
                    visibleState = visibilityState,
                    enter = fadeIn(tween(MenuAnimationDurationMillis)) + scaleIn(
                        animationSpec = tween(MenuAnimationDurationMillis),
                        transformOrigin = transformOrigin,
                    ) + slideInVertically(tween(MenuAnimationDurationMillis)) { anchorTravel },
                    exit = fadeOut(tween(MenuAnimationDurationMillis)) + scaleOut(
                        animationSpec = tween(MenuAnimationDurationMillis),
                        transformOrigin = transformOrigin,
                    ) + slideOutVertically(tween(MenuAnimationDurationMillis)) { anchorTravel },
                ) {
                    Surface(
                        modifier = Modifier
                            .widthIn(min = 220.dp, max = 320.dp)
                            .testTag(UiTags.CHAT_ATTACHMENT_MENU),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shadowElevation = 6.dp,
                        tonalElevation = 3.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            AttachmentAction(
                                label = stringResource(R.string.chat_menu_attach_image),
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Image,
                                        contentDescription = null,
                                    )
                                },
                                enabled = enabled,
                                modifier = Modifier.testTag(UiTags.CHAT_ATTACH_MENU_IMAGE),
                                onClick = {
                                    onDismiss()
                                    onPickImage()
                                },
                            )
                            AttachmentAction(
                                label = stringResource(R.string.chat_menu_attach_document),
                                icon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Description,
                                        contentDescription = null,
                                    )
                                },
                                enabled = enabled,
                                modifier = Modifier.testTag(UiTags.CHAT_ATTACH_MENU_DOCUMENT),
                                onClick = {
                                    onDismiss()
                                    onPickDocument()
                                },
                            )
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            if (measurables.isEmpty()) {
                layout(constraints.maxWidth, constraints.maxHeight) {}
            } else {
                val margin = 8.dp.roundToPx()
                val gap = 8.dp.roundToPx()
                val maxMenuWidth = (constraints.maxWidth - margin * 2).coerceAtLeast(0)
                val maxMenuHeight = (placementBottom.toInt() - margin - gap).coerceAtLeast(0)
                val placeable = measurables.first().measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxWidth = maxMenuWidth,
                        maxHeight = minOf(constraints.maxHeight, maxMenuHeight),
                    )
                )
                val maxX = (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin)
                val anchoredX = if (layoutDirection == LayoutDirection.Ltr) {
                    anchorBounds.left.toInt()
                } else {
                    anchorBounds.right.toInt() - placeable.width
                }
                val x = anchoredX.coerceIn(margin, maxX)
                val y = (placementBottom.toInt() - placeable.height - gap).coerceAtLeast(margin)

                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(x, y)
                }
            }
        }
    }
}

@Composable
private fun AttachmentAction(
    label: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
