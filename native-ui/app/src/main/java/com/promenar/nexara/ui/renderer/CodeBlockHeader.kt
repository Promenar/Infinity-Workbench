package com.promenar.nexara.ui.renderer

import android.webkit.WebView
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import kotlinx.coroutines.delay

private val CodeBlockActionTouchTarget = 48.dp
private val CodeBlockActionIconSize = 20.dp

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
    val languageDescription = language
        ?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.code_block_plain_text)
    val blockDescription = pluralStringResource(
        R.plurals.code_block_description,
        lineCount,
        languageDescription,
        lineCount,
    )
    val fullScreenDescription = stringResource(R.string.code_block_full_screen)
    val exportPngDescription = stringResource(R.string.code_block_export_png)
    val editDescription = stringResource(R.string.code_block_edit)
    val saveDescription = stringResource(R.string.code_block_save)
    val copyDescription = stringResource(R.string.code_block_copy)
    val copiedDescription = stringResource(R.string.code_block_copied)
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
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .semantics {
                contentDescription = blockDescription
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language?.uppercase() ?: stringResource(R.string.code_block_language_fallback),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clearAndSetSemantics { },
            )
            if (!isEditing && isRenderableHtml) {
                IconButton(
                    onClick = { showFullScreen = true },
                    modifier = Modifier.size(CodeBlockActionTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Fullscreen,
                        contentDescription = fullScreenDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(CodeBlockActionIconSize),
                    )
                }
                IconButton(
                    onClick = { webView?.let { exportHtmlArtifactPng(it, context) } },
                    modifier = Modifier.size(CodeBlockActionTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = exportPngDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(CodeBlockActionIconSize),
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
                    modifier = Modifier.size(CodeBlockActionTouchTarget),
                ) {
                    Icon(
                        imageVector = if (isEditing) Icons.Rounded.Check else Icons.Rounded.Edit,
                        contentDescription = if (isEditing) saveDescription else editDescription,
                        tint = if (isEditing) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(CodeBlockActionIconSize),
                    )
                }
            }
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                },
                modifier = Modifier.size(CodeBlockActionTouchTarget),
            ) {
                AnimatedContent(targetState = copied, label = "code-block-copy-state") { isCopied ->
                    Icon(
                        imageVector = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                        contentDescription = if (isCopied) copiedDescription else copyDescription,
                        tint = if (isCopied) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(CodeBlockActionIconSize),
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
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    cursorColor = MaterialTheme.colorScheme.primary,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                ),
                shape = MaterialTheme.shapes.small,
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(start = 8.dp, end = 6.dp, top = 16.dp, bottom = 16.dp)
                        .clearAndSetSemantics { },
                ) {
                    lines.forEachIndexed { index, _ ->
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
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
                        .background(MaterialTheme.colorScheme.outlineVariant)
                        .clearAndSetSemantics { },
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
