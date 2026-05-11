package com.promenar.nexara.ui.renderer

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.delay

@Composable
fun CodeBlockWithHeader(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    codeContent: @Composable () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val lines = remember(code) { code.lines() }
    val lineCount = lines.size
    val gutterWidth = when {
        lineCount >= 100 -> 44.dp
        lineCount >= 10 -> 36.dp
        else -> 28.dp
    }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(NexaraShapes.medium)
            .background(NexaraColors.SurfaceLowest)
            .semantics {
                contentDescription = "Code block in ${language ?: "plain text"}, $lineCount lines"
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NexaraColors.SurfaceContainer)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language?.uppercase() ?: "CODE",
                style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                color = NexaraColors.OnSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                },
                modifier = Modifier.size(28.dp)
            ) {
                AnimatedContent(targetState = copied, label = "copy") { isCopied ->
                    Icon(
                        imageVector = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = if (isCopied) "Copied" else "Copy",
                        tint = if (isCopied) NexaraColors.StatusSuccess else NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .horizontalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 16.dp)
            ) {
                lines.forEachIndexed { index, _ ->
                    Text(
                        text = "${index + 1}",
                        style = NexaraTypography.bodySmall.copy(
                            fontSize = 12.sp,
                            color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.4f),
                            textAlign = TextAlign.End
                        ),
                        modifier = Modifier.widthIn(min = gutterWidth)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 16.dp)
                    .width(0.5.dp)
                    .background(NexaraColors.OutlineVariant)
            )
            Box(modifier = Modifier.padding(start = 12.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
                codeContent()
            }
        }
    }
}
