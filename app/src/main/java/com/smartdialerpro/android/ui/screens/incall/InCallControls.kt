package com.smartdialerpro.android.ui.screens.incall

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdialerpro.android.service.CallManager
import com.smartdialerpro.android.ui.theme.CallAnswerGreen
import com.smartdialerpro.android.ui.theme.CallEndRed

@Composable
fun ActiveCallControls(
    context: Context,
    isMuted: Boolean,
    speakerOn: Boolean,
    isOnHold: Boolean,
    isRecording: Boolean,
    multipleCalls: Boolean = false,
    onKeypad: () -> Unit,
    onAddCall: () -> Unit,
    onRecord: () -> Unit = { CallManager.toggleRecord(context) }
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CallControlButton(
                if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                "Mute",
                isMuted
            ) { CallManager.toggleMute() }
            CallControlButton(Icons.Rounded.Dialpad, "Keypad", false, onClick = onKeypad)
            CallControlButton(
                Icons.Rounded.VolumeUp,
                if (speakerOn) "Speaker on" else "Speaker",
                speakerOn
            ) { CallManager.toggleSpeaker() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CallControlButton(Icons.Rounded.Add, "Add call", false, onClick = onAddCall)
            CallControlButton(
                if (isOnHold) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                if (isOnHold) "Resume" else "Hold",
                isOnHold
            ) { CallManager.toggleHold() }
            CallControlButton(
                Icons.Rounded.FiberManualRecord,
                if (isRecording) "Recording" else "Record",
                isRecording,
                accent = CallEndRed,
                onClick = onRecord
            )
        }
        if (multipleCalls) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                CallControlButton(Icons.Rounded.SwapCalls, "Swap", false) { CallManager.swapCalls() }
                CallControlButton(Icons.Rounded.MergeType, "Merge", false) { CallManager.mergeCalls() }
            }
        }
    }
}

@Composable
fun IncomingCallControls(onDecline: () -> Unit, onAnswer: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        CallActionButton(Icons.Rounded.CallEnd, "Decline", CallEndRed, onDecline)
        CallActionButton(Icons.Rounded.Call, "Answer", CallAnswerGreen, onAnswer)
    }
}

@Composable
fun EndCallButton() {
    FloatingActionButton(
        onClick = { CallManager.endCall() },
        containerColor = CallEndRed,
        contentColor = Color.White,
        shape = CircleShape,
        modifier = Modifier
            .size(76.dp)
            .border(3.dp, Color.White.copy(alpha = .32f), CircleShape)
    ) {
        Icon(Icons.Rounded.CallEnd, "End Call", modifier = Modifier.size(38.dp))
    }
}

@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    accent: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp)
    ) {
        Box(
            Modifier
                .size(72.dp)
                .background(
                    if (active) accent else Color.White.copy(alpha = .11f),
                    CircleShape
                )
                .border(
                    1.5.dp,
                    if (active) accent.copy(alpha = .9f) else Color.White.copy(alpha = .22f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = .9f),
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun CallActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            Modifier
                .size(82.dp)
                .background(color, CircleShape)
                .border(3.dp, Color.White.copy(alpha = .30f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(label, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}