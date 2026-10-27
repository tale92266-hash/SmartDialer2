package com.smartdialerpro.android.ui.screens.incall

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdialerpro.android.service.CallManager

@Composable
fun InCallDtmfPad(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White.copy(alpha = .08f), RoundedCornerShape(28.dp))
            .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(28.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onClose, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Rounded.Close, "Close keypad", tint = Color.White.copy(alpha = .85f))
            }
        }
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
        ).forEach { row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { key ->
                    DtmfKey(key) {
                        CallManager.playDtmf(key.first())
                        CallManager.stopDtmf()
                    }
                }
            }
        }
    }
}

@Composable
private fun DtmfKey(key: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.size(64.dp).background(Color.White.copy(alpha = .10f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = .20f), CircleShape)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(key, fontSize = 25.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}
