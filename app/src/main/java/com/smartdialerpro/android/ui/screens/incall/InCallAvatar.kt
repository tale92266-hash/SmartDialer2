package com.smartdialerpro.android.ui.screens.incall

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdialerpro.android.util.Utils

@Composable
fun InCallAvatar(
    displayName: String,
    contactBitmap: ImageBitmap?,
    animate: Boolean
) {
    val transition = rememberInfiniteTransition(label = "call_avatar")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (animate) 1.045f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "call_avatar_scale"
    )

    Box(
        modifier = Modifier
            .size(148.dp)
            .scale(scale)
            .shadow(28.dp, CircleShape)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.tertiary
                    )
                )
            )
            .border(3.dp, Color.White.copy(alpha = .55f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (contactBitmap != null) {
            Image(
                bitmap = contactBitmap,
                contentDescription = "Contact photo",
                modifier = Modifier.size(142.dp).clip(CircleShape)
            )
        } else {
            val initial = Utils.getAvatarInitial(displayName)
            Text(
                text = initial,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
