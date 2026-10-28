package com.smartdialerpro.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one SIM indicator used everywhere: the SIM glyph from the call
 * screen plus its label, in a pill. Call screen, Recents rows, profile
 * history and the fake-call sheet all render this same badge so the
 * SIM always looks identical no matter where it appears.
 */
@Composable
fun SimBadge(
    slot: Int,
    label: String,
    modifier: Modifier = Modifier,
    glyphSize: Dp = 13.dp,
    textSize: TextUnit = 13.sp,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 6.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
    contentColor: Color = Color.White,
    glyphColor: Color? = null
) {
    Row(
        modifier = modifier
            .background(containerColor, RoundedCornerShape(50.dp))
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center
    ) {
        SimSlotIcon(
            slot = slot,
            modifier = Modifier.padding(end = 7.dp),
            size = glyphSize,
            // Null keeps the slot colours (SIM 1 primary, SIM 2 tertiary);
            // the call screen passes white explicitly.
            color = glyphColor
        )
        Text(
            text = label,
            fontSize = textSize,
            fontWeight = FontWeight.SemiBold,
            color = contentColor
        )
    }
}
