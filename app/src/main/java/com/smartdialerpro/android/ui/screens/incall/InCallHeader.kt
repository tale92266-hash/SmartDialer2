package com.smartdialerpro.android.ui.screens.incall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun InCallHeader(
    stateText: String,
    displayName: String,
    number: String,
    simInfo: String?,
    contactBitmap: ImageBitmap?,
    animateAvatar: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stateText,
            fontSize = 18.sp,
            color = Color.White.copy(alpha = .78f),
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(18.dp))
        InCallAvatar(displayName, contactBitmap, animateAvatar)
        Spacer(Modifier.height(18.dp))
        Text(
            text = displayName,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
        if (number.isNotBlank() && displayName != number) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = number,
                fontSize = 17.sp,
                color = Color.White.copy(alpha = .68f),
                textAlign = TextAlign.Center
            )
        }
        if (!simInfo.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = .20f),
                        RoundedCornerShape(50.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.SimCard,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(end = 7.dp)
                )
                Text(
                    text = simInfo,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }
}
