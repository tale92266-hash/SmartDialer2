package com.smartdialerpro.android.ui.screens.incall

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InCallHeader(
    stateText: String,
    displayName: String,
    number: String,
    simInfo: String?,
    contactBitmap: ImageBitmap?,
    animateAvatar: Boolean,
    /** 1-based SIM slot: when set, the SIM glyph icon is shown instead of the generic SIM icon. */
    simSlot: Int? = null,
    /** Network-delivered CNAP name: shown as a subtitle when it differs from the contact name. */
    cnapName: String? = null,
    /** VoLTE "HD" chip next to the SIM badge (fake calls ride the same HD path as real VoLTE calls). */
    showHdChip: Boolean = false
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
        if (!cnapName.isNullOrBlank() && cnapName != displayName) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = cnapName,
                fontSize = 15.sp,
                color = Color.White.copy(alpha = .60f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (number.isNotBlank() && displayName != number) {
            Spacer(Modifier.height(5.dp))
            val context = LocalContext.current
            Text(
                text = number,
                fontSize = 17.sp,
                color = Color.White.copy(alpha = .68f),
                textAlign = TextAlign.Center,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = {
                        try {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Phone Number", number))
                            Toast.makeText(context, "Number copied to clipboard", Toast.LENGTH_SHORT).show()
                        } catch (_: Throwable) {
                            Toast.makeText(context, "Unable to copy", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            )
        }
        if (!simInfo.isNullOrBlank() || showHdChip) {
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!simInfo.isNullOrBlank()) {
                    if (simSlot != null) {
                        com.smartdialerpro.android.ui.components.SimBadge(
                            slot = simSlot,
                            label = simInfo,
                            glyphColor = Color.White
                        )
                    } else {
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
                if (showHdChip) {
                    Text(
                        text = "HD",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = .20f), RoundedCornerShape(50.dp))
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}
