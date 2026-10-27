package com.smartdialerpro.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Small SIM-card glyph for call rows: a SIM outline with a chip and a bold
 * slot numeral. SIM 1 uses the primary colour, SIM 2 the tertiary colour,
 * so the slot is recognisable at a glance without any text label.
 */
@Composable
fun SimSlotIcon(
    slot: Int,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    /** Overrides the slot colour (e.g. inside a selected chip whose background is the primary colour). */
    color: androidx.compose.ui.graphics.Color? = null
) {
    val slotColor = if (slot == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    val color = color ?: slotColor
    Box(
        modifier = modifier
            .size(size, size * 1.28f)
            .semantics { contentDescription = "SIM $slot" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val w = this.size.width
            val h = this.size.height
            val stroke = 1.6.dp.toPx()
            // SIM card outline: rounded rect with the classic cut top-right corner.
            val cut = w * 0.28f
            val radius = 3.dp.toPx()
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, radius)
                lineTo(0f, h - radius)
                quadraticTo(0f, h, radius, h)
                lineTo(w - radius, h)
                quadraticTo(w, h, w, h - radius)
                lineTo(w, cut)
                lineTo(w - cut, 0f)
                lineTo(radius, 0f)
                quadraticTo(0f, 0f, 0f, radius)
                close()
            }
            drawPath(path, color = color, style = Stroke(width = stroke))
            // Chip: small rounded rect with two contact lines, upper third.
            val chipW = w * 0.46f
            val chipH = h * 0.26f
            val chipLeft = (w - chipW) / 2f
            val chipTop = h * 0.12f
            drawRoundRect(
                color = color,
                topLeft = Offset(chipLeft, chipTop),
                size = Size(chipW, chipH),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                style = Stroke(width = 1.1.dp.toPx())
            )
            val midY = chipTop + chipH / 2f
            drawLine(color, Offset(chipLeft, midY), Offset(chipLeft + chipW, midY), strokeWidth = 1.1.dp.toPx())
            drawLine(color, Offset(chipLeft + chipW / 2f, chipTop), Offset(chipLeft + chipW / 2f, chipTop + chipH), strokeWidth = 1.1.dp.toPx())
        }
        Text(
            text = "$slot",
            fontSize = (size.value * 0.48f).sp,
            fontWeight = FontWeight.ExtraBold,
            color = color,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 1.dp)
        )
    }
}
