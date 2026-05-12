package com.promenar.nexara.ui.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.ui.theme.NexaraColors

enum class GfmAlertType(val label: String, val emoji: String) {
    NOTE("NOTE", "\uD83D\uDCDD"),
    TIP("TIP", "\uD83D\uDCA1"),
    IMPORTANT("IMPORTANT", "\u2757"),
    WARNING("WARNING", "\u26A0\uFE0F"),
    CAUTION("CAUTION", "\uD83D\uDD25"),
}

private val alertTypeMap = GfmAlertType.entries.associateBy { it.label.uppercase() }

internal fun parseGfmAlert(text: String): Pair<GfmAlertType, String>? {
    val regex = Regex("""^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\s*""", RegexOption.IGNORE_CASE)
    val match = regex.find(text) ?: return null
    val type = alertTypeMap[match.groupValues[1].uppercase()] ?: return null
    val content = text.substring(match.range.last + 1).trim()
    return type to content
}

@Composable
fun GfmAlertBlock(
    quoteContent: String,
    fontSize: Int = 13,
    modifier: Modifier = Modifier,
) {
    val parsed = parseGfmAlert(quoteContent) ?: return

    val (type, content) = parsed
    val accentColor = when (type) {
        GfmAlertType.NOTE -> Color(0xFF0969DA)
        GfmAlertType.TIP -> Color(0xFF1A7F37)
        GfmAlertType.IMPORTANT -> Color(0xFF8250DF)
        GfmAlertType.WARNING -> Color(0xFF9A6700)
        GfmAlertType.CAUTION -> Color(0xFFCF222E)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accentColor.copy(alpha = 0.08f))
            .padding(start = 12.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Text(
            text = "${type.emoji} ${type.label}",
            fontWeight = FontWeight.SemiBold,
            fontSize = (fontSize - 1).sp,
            color = accentColor,
        )
        if (content.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = content,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.5).sp,
                color = NexaraColors.OnBackground,
            )
        }
    }
}
