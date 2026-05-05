package com.promenar.nexara.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

private val keywordColors = mapOf(
    "fun" to Color(0xFFC084FC),
    "val" to Color(0xFFC084FC),
    "var" to Color(0xFFC084FC),
    "class" to Color(0xFFC084FC),
    "interface" to Color(0xFFC084FC),
    "object" to Color(0xFFC084FC),
    "if" to Color(0xFFC084FC),
    "else" to Color(0xFFC084FC),
    "when" to Color(0xFFC084FC),
    "for" to Color(0xFFC084FC),
    "while" to Color(0xFFC084FC),
    "return" to Color(0xFFC084FC),
    "import" to Color(0xFFC084FC),
    "package" to Color(0xFFC084FC),
    "const" to Color(0xFF60A5FA),
    "let" to Color(0xFF60A5FA),
    "function" to Color(0xFF60A5FA),
    "async" to Color(0xFF60A5FA),
    "await" to Color(0xFF60A5FA),
    "true" to Color(0xFFFBBF24),
    "false" to Color(0xFFFBBF24),
    "null" to Color(0xFFFBBF24),
    "undefined" to Color(0xFFFBBF24),
    "def" to Color(0xFF34D399),
    "print" to Color(0xFF34D399),
    "self" to Color(0xFF34D399),
    "None" to Color(0xFFFBBF24),
    "True" to Color(0xFFFBBF24),
    "False" to Color(0xFFFBBF24),
)

private fun highlightCode(code: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        val tokens = code.split("(\\s+|(?=[{}()\\[\\],;:=<>!\\+\\-*/&|]))".toRegex())
        tokens.forEachIndexed { index, token ->
            if (token.isNotEmpty()) {
                val color = keywordColors[token]
                if (color != null) {
                    pushStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium))
                    append(token)
                    pop()
                } else if (token.startsWith("\"") || token.startsWith("'")) {
                    pushStyle(SpanStyle(color = Color(0xFFA5D6FF)))
                    append(token)
                    pop()
                } else if (token.startsWith("//") || token.startsWith("#")) {
                    pushStyle(SpanStyle(color = Color(0xFF6B7280)))
                    append(token)
                    pop()
                } else if (token.matches("-?\\d+(\\.\\d+)?".toRegex())) {
                    pushStyle(SpanStyle(color = Color(0xFFFBBF24)))
                    append(token)
                    pop()
                } else {
                    append(token)
                }
            }
            if (index < tokens.size - 1) {
                val pos = tokens.take(index + 1).sumOf { it.length }
                if (pos < code.length) {
                    append(code[pos])
                }
            }
        }
    }
}

@Composable
fun FloatingCodeEditor(
    show: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    title: String,
    initialCode: String = "",
    language: String = "json"
) {
    if (!show) return

    var code by remember(initialCode) { mutableStateOf(initialCode) }
    val lines = remember(code) { code.lines() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xE00E0E10))
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.common_cd_back),
                        tint = NexaraColors.OnSurface
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = title,
                    style = NexaraTypography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = NexaraColors.OnSurface,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = { onSave(code) }) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NexaraColors.Primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = stringResource(R.string.common_cd_save),
                            tint = NexaraColors.OnPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        lines.forEachIndexed { i, _ ->
                            Text(
                                text = "${i + 1}",
                                style = NexaraTypography.bodySmall.copy(fontSize = 12.sp),
                                color = NexaraColors.Outline.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }

                    BasicTextField(
                        value = code,
                        onValueChange = { code = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        textStyle = NexaraTypography.bodySmall.copy(
                            color = NexaraColors.OnSurface
                        ),
                        cursorBrush = SolidColor(NexaraColors.Primary)
                    )
                }
            }
        }
    }
}
