package com.promenar.nexara.ui.rag

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocEditorScreen(
    docId: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: DocEditorViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            context.applicationContext as android.app.Application
        )
    )

    val document by viewModel.document.collectAsState()
    val content by viewModel.content.collectAsState()
    val isLargeFile by viewModel.isLargeFile.collectAsState()
    val isEditing by viewModel.isEditing.collectAsState()
    val isDirty by viewModel.isDirty.collectAsState()
    val warningDismissed by viewModel.warningDismissed.collectAsState()

    var titleText by remember(document) { mutableStateOf(document?.title ?: "") }

    androidx.compose.runtime.LaunchedEffect(docId) {
        viewModel.loadDocument(docId)
    }

    val wordCount = remember(content) { content.split("\\s+".toRegex()).count { it.isNotBlank() } }
    val charCount = remember(content) { content.length }
    val lineCount = remember(content) { content.lines().size }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Description,
                                contentDescription = null,
                                tint = NexaraColors.Primary,
                                modifier = Modifier.size(24.dp)
                            )
                            BasicTextField(
                                value = titleText,
                                onValueChange = {
                                    titleText = it
                                    viewModel.updateTitle(it)
                                },
                                textStyle = NexaraTypography.headlineMedium.copy(
                                    color = NexaraColors.OnSurface
                                ),
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                decorationBox = { innerTextField ->
                                    if (titleText.isEmpty()) {
                                        Text(
                                            stringResource(R.string.doc_editor_title_placeholder),
                                            style = NexaraTypography.headlineMedium,
                                            color = NexaraColors.OnSurfaceVariant
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                formatFileSize(document?.fileSize ?: 0),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.OnSurfaceVariant
                            )
                            Box(
                                modifier = Modifier
                                    .size(3.dp)
                                    .background(NexaraColors.OutlineVariant, CircleShape)
                            )
                            Text(
                                document?.type?.uppercase() ?: "TEXT",
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.Primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_cd_back),
                            tint = NexaraColors.OnSurface
                        )
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(NexaraColors.SurfaceContainer)
                            .border(0.5.dp, NexaraColors.OutlineVariant, RoundedCornerShape(8.dp))
                    ) {
                        Row {
                            val previewBg by animateColorAsState(
                                if (!isEditing) NexaraColors.SurfaceBright else Color.Transparent,
                                label = "previewBg"
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(previewBg)
                                    .then(
                                        if (!isEditing) Modifier else Modifier
                                    )
                                    .clickableNoRipple { if (isEditing) viewModel.toggleEditMode() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Visibility,
                                        contentDescription = null,
                                        tint = if (!isEditing) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        stringResource(R.string.doc_editor_preview),
                                        style = NexaraTypography.labelMedium,
                                        color = if (!isEditing) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant
                                    )
                                }
                            }
                            val editBg by animateColorAsState(
                                if (isEditing) NexaraColors.SurfaceBright else Color.Transparent,
                                label = "editBg"
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(editBg)
                                    .then(
                                        if (isEditing) Modifier else Modifier
                                    )
                                    .clickableNoRipple { if (!isEditing) viewModel.toggleEditMode() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = null,
                                        tint = if (isEditing) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        stringResource(R.string.doc_editor_edit),
                                        style = NexaraTypography.labelMedium,
                                        color = if (isEditing) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { viewModel.saveDocument() },
                        enabled = isDirty
                    ) {
                        Icon(
                            Icons.Rounded.Save,
                            contentDescription = stringResource(R.string.common_cd_save),
                            tint = if (isDirty) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f)
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLargeFile && !warningDismissed) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(NexaraColors.ErrorContainer.copy(alpha = 0.2f))
                        .border(0.5.dp, NexaraColors.Error.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = NexaraColors.Error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.doc_editor_large_file_desc, formatFileSize(document?.fileSize ?: 0)),
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.dismissWarning() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.doc_editor_dismiss),
                            tint = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxSize(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    if (isEditing) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .width(40.dp)
                                    .fillMaxSize()
                                    .background(Color(0xFF151517))
                                    .padding(end = 8.dp, top = 12.dp),
                                horizontalAlignment = Alignment.End
                            ) {
                                for (i in 1..lineCount.coerceAtLeast(1)) {
                                    Text(
                                        "$i",
                                        style = NexaraTypography.bodySmall.copy(
                                            fontSize = 12.sp,
                                            color = NexaraColors.OutlineVariant
                                        ),
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            BasicTextField(
                                value = content,
                                onValueChange = { viewModel.onContentChanged(it) },
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp,
                                    color = NexaraColors.OnSurface
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .horizontalScroll(rememberScrollState())
                                    .padding(12.dp),
                                decorationBox = { innerTextField ->
                                    if (content.isEmpty()) {
                                        Text(
                                            stringResource(R.string.doc_editor_typing_placeholder),
                                            style = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 14.sp,
                                                color = NexaraColors.OnSurfaceVariant
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                    } else {
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                        ) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .width(40.dp)
                                        .fillMaxSize()
                                        .background(Color(0xFF151517))
                                        .padding(end = 8.dp, top = 12.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    for (i in 1..lineCount.coerceAtLeast(1)) {
                                        Text(
                                            "$i",
                                            style = NexaraTypography.bodySmall.copy(
                                                fontSize = 12.sp,
                                                color = NexaraColors.OutlineVariant
                                            ),
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        buildHighlightedText(content),
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 14.sp,
                                            lineHeight = 22.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NexaraColors.SurfaceLow)
                    .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.doc_editor_utf8), style = NexaraTypography.bodySmall.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
                    Box(modifier = Modifier.size(3.dp).background(NexaraColors.OutlineVariant, CircleShape))
                    Text(stringResource(R.string.doc_editor_words, wordCount), style = NexaraTypography.bodySmall.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
                    Box(modifier = Modifier.size(3.dp).background(NexaraColors.OutlineVariant, CircleShape))
                    Text(stringResource(R.string.doc_editor_chars, charCount), style = NexaraTypography.bodySmall.copy(fontSize = 12.sp), color = NexaraColors.OnSurfaceVariant)
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(NexaraColors.SurfaceContainer)
                        .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (isEditing) NexaraColors.StatusWarning else NexaraColors.StatusSuccess,
                                CircleShape
                            )
                    )
                    Text(
                        if (isEditing) stringResource(R.string.doc_editor_editing) else stringResource(R.string.doc_editor_readonly),
                        style = NexaraTypography.bodySmall.copy(fontSize = 11.sp),
                        color = NexaraColors.OnSurface
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceIn(0, units.size - 1)
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "${"%.1f".format(value)} ${units[digitGroups]}"
}

@Composable
private fun buildHighlightedText(content: String) = buildAnnotatedString {
    val headingColor = NexaraColors.Primary
    val subheadingColor = Color(0xFF4ADE80)
    val boldColor = Color(0xFFFBBF24)
    val codeKeywordColor = Color(0xFF60A5FA)
    val stringColor = Color(0xFFF472B6)
    val commentColor = Color(0xFF6B7280)

    for (line in content.lines()) {
        when {
            line.startsWith("# ") -> withStyle(SpanStyle(color = headingColor, fontWeight = FontWeight.Bold)) {
                append(line)
            }
            line.startsWith("## ") -> withStyle(SpanStyle(color = subheadingColor, fontWeight = FontWeight.SemiBold)) {
                append(line)
            }
            line.startsWith("### ") -> withStyle(SpanStyle(color = subheadingColor)) {
                append(line)
            }
            line.startsWith("```") -> withStyle(SpanStyle(color = stringColor)) {
                append(line)
            }
            line.startsWith("- **") -> {
                val parts = line.split("**:")
                if (parts.size >= 2) {
                    append("- ")
                    withStyle(SpanStyle(color = boldColor, fontWeight = FontWeight.Bold)) {
                        append(parts[0].removePrefix("- **"))
                    }
                    append("**:")
                    withStyle(SpanStyle(color = NexaraColors.OnSurface)) {
                        append(parts[1])
                    }
                } else {
                    append(line)
                }
            }
            line.trimStart().startsWith("|") -> withStyle(SpanStyle(color = NexaraColors.OnSurfaceVariant)) {
                append(line)
            }
            else -> withStyle(SpanStyle(color = NexaraColors.OnSurface)) {
                append(line)
            }
        }
        append("\n")
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    return this.then(
        clickable(
            indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        ) { onClick() }
    )
}
