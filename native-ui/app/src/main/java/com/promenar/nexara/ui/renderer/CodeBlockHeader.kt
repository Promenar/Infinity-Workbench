package com.promenar.nexara.ui.renderer

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.delay
import android.webkit.WebView


@Composable
fun CodeBlockWithHeader(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
    onCodeChange: ((String) -> Unit)? = null,
    codeContent: @Composable () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(false) }
    var editedCode by remember(code) { mutableStateOf(code) }
    val lines = remember(code) { code.lines() }
    val lineCount = lines.size
    val gutterWidth = when {
        lineCount >= 100 -> 28.dp
        lineCount >= 10 -> 20.dp
        else -> 12.dp
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var showFullScreen by remember { mutableStateOf(false) }
    // 双重判定：语言必须是 HTML artifact + 内容必须包含可渲染标记
    // 有效排除 <tool_call>、<function_call> 等纯数据 XML
    val isRenderableHtml = remember(language, code) {
        isHtmlArtifact(language) && isLikelyRenderableHtml(code)
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
            if (!isEditing && isRenderableHtml) {
                IconButton(
                    onClick = { showFullScreen = true },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Fullscreen,
                        contentDescription = "Full screen",
                        tint = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { webView?.let { exportHtmlArtifactPng(it, context) } },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Export PNG",
                        tint = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (onCodeChange != null) {
                IconButton(
                    onClick = {
                        if (isEditing) {
                            onCodeChange(editedCode)
                        }
                        isEditing = !isEditing
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (isEditing) Icons.Rounded.Check else Icons.Rounded.Edit,
                        contentDescription = if (isEditing) "Save" else "Edit code",
                        tint = if (isEditing) NexaraColors.StatusSuccess else NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
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
        if (isEditing) {
            OutlinedTextField(
                value = editedCode,
                onValueChange = { editedCode = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .heightIn(min = 100.dp),
                textStyle = NexaraTypography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NexaraColors.Primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = NexaraColors.OutlineVariant.copy(alpha = 0.3f),
                    cursorColor = NexaraColors.Primary,
                    focusedTextColor = NexaraColors.OnBackground,
                    unfocusedTextColor = NexaraColors.OnBackground
                ),
                shape = NexaraShapes.small
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(start = 8.dp, end = 6.dp, top = 16.dp, bottom = 16.dp)
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 16.dp)
                ) {
                    codeContent()
                }
            }
        }
        if (!isEditing && isRenderableHtml) {
            HtmlArtifactCard(
                htmlCode = code,
                language = language,
                fontSize = fontSize,
                onWebViewCreated = { wv -> webView = wv },
                modifier = Modifier.padding(8.dp)
            )
        }
    }

    if (showFullScreen) {
        HtmlArtifactsPopup(
            htmlCode = code,
            fontSize = fontSize,
            onDismiss = { showFullScreen = false }
        )
    }
}
