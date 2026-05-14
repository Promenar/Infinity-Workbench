package com.promenar.nexara.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

enum class EditorMode { DIALOG, SHEET }

private enum class EditorTab(val label: String) {
    EDITOR("Editor"),
    PREVIEW("Preview"),
    SPLIT("Split")
}

@Composable
fun UnifiedPromptEditor(
    show: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    initialText: String = "",
    title: String = "Edit Prompt",
    placeholder: String = "Enter your system prompt...",
    mode: EditorMode = EditorMode.DIALOG,
    fontSize: Int = 13
) {
    if (!show) return

    var text by remember(initialText) { mutableStateOf(initialText) }
    var selectedTab by remember { mutableIntStateOf(EditorTab.EDITOR.ordinal) }

    val lines = text.lines()
    val lineCount = lines.size
    val charCount = text.length
    val wordCount = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size

    @Composable
    fun TopBar() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = NexaraColors.OnSurface
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = title,
                style = NexaraTypography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = NexaraColors.OnSurface,
                modifier = Modifier.weight(1f)
            )

            IconButton(onClick = { onSave(text) }) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(NexaraColors.Primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Save",
                        tint = NexaraColors.OnPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun TabBar() {
        val tabs = EditorTab.entries.map { it.label }
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = NexaraColors.SurfaceContainer,
            contentColor = NexaraColors.OnSurface,
            indicator = { tabPositions ->
                TabRowDefaults.Indicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    height = 2.dp,
                    color = NexaraColors.Primary
                )
            },
            divider = {}
        ) {
            tabs.forEachIndexed { index, label ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = label,
                            style = NexaraTypography.labelMedium.copy(
                                fontWeight = if (selectedTab == index) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = if (selectedTab == index) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                        )
                    }
                )
            }
        }
    }

    @Composable
    fun EditorPane(modifier: Modifier = Modifier) {
        val scrollState = rememberScrollState()
        val lineNumbers = remember(text) {
            buildAnnotatedString {
                for (i in 1..lineCount) {
                    withStyle(SpanStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace)) {
                        append(String.format("%3d", i))
                    }
                    if (i < lineCount) append("\n")
                }
            }
        }

        Row(modifier = modifier) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .fillMaxHeight()
                    .background(NexaraColors.SurfaceLowest)
                    .verticalScroll(scrollState)
                    .padding(end = 4.dp, top = 8.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Text(
                    text = lineNumbers,
                    color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
                    style = NexaraTypography.bodySmall.copy(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    textStyle = NexaraTypography.bodySmall.copy(
                        color = NexaraColors.OnSurface,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(NexaraColors.Primary),
                    decorationBox = { innerTextField ->
                        if (text.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = NexaraTypography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp
                                ),
                                color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        innerTextField()
                    }
                )
            }
        }
    }

    @Composable
    fun PreviewPane(modifier: Modifier = Modifier) {
        Box(
            modifier = modifier
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            MarkdownText(
                markdown = text,
                isStreaming = false,
                fontSize = fontSize,
                showCursor = false
            )
        }
    }

    @Composable
    fun BottomBar() {
        Column {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = NexaraColors.OutlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexaraColors.SurfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$wordCount words",
                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "$lineCount lines",
                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "$charCount chars",
                    style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
            }
        }
    }

    @Composable
    fun Content() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NexaraColors.CanvasBackground)
                .statusBarsPadding()
        ) {
            TopBar()
            TabBar()

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (EditorTab.entries[selectedTab]) {
                    EditorTab.EDITOR -> {
                        EditorPane(
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    EditorTab.PREVIEW -> {
                        PreviewPane(
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    EditorTab.SPLIT -> {
                        Row(modifier = Modifier.fillMaxSize()) {
                            EditorPane(
                                modifier = Modifier
                                    .weight(0.5f)
                                    .fillMaxHeight()
                            )
                            HorizontalDivider(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight(),
                                thickness = 1.dp,
                                color = NexaraColors.OutlineVariant
                            )
                            PreviewPane(
                                modifier = Modifier
                                    .weight(0.5f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }

            BottomBar()
        }
    }

    when (mode) {
        EditorMode.DIALOG -> {
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Content()
            }
        }
        EditorMode.SHEET -> {
            // TODO: ModalBottomSheet implementation
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Content()
            }
        }
    }
}
