package com.smartdialerpro.android.ui.screens.incall

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.smartdialerpro.android.MainActivity
import com.smartdialerpro.android.service.CallManager
import com.smartdialerpro.android.ui.theme.SmartDialerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmartDialerTheme {
                PremiumInCallScreen(
                    onFinish = { finishAndRemoveTask() },
                    onAddCall = { startActivity(Intent(Intent.ACTION_DIAL).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) }
                )
            }
        }
    }

    override fun onBackPressed() {
        if (CallManager.callState.value != Call.STATE_DISCONNECTED) {
            startActivity(Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP })
        } else finishAndRemoveTask()
    }
}

@Composable
fun PremiumInCallScreen(onFinish: () -> Unit, onAddCall: () -> Unit) {
    val context = LocalContext.current
    val callState by CallManager.callState.collectAsState()
    val number by CallManager.callNumber.collectAsState()
    val name by CallManager.callerName.collectAsState()
    val photoUri by CallManager.callerPhotoUri.collectAsState()
    val simInfo by CallManager.simInfo.collectAsState()
    val isMuted by CallManager.isMuted.collectAsState()
    val audioRoute by CallManager.audioRoute.collectAsState()
    val isOnHold by CallManager.isOnHold.collectAsState()
    val isRecording by CallManager.isRecording.collectAsState()
    val duration by CallManager.duration.collectAsState()
    val trackedCalls by CallManager.trackedCalls.collectAsState()
    var showKeypad by remember { mutableStateOf(false) }
    var contactBitmap by remember(photoUri) { mutableStateOf<ImageBitmap?>(null) }
    var pendingRecordAfterPermission by remember { mutableStateOf(false) }

    val recordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingRecordAfterPermission) CallManager.toggleRecord(context)
        pendingRecordAfterPermission = false
    }

    LaunchedEffect(callState, trackedCalls.size) {
        if (callState == Call.STATE_DISCONNECTED && trackedCalls.isEmpty()) {
            delay(300)
            onFinish()
        }
    }

    LaunchedEffect(photoUri) {
        contactBitmap = if (!photoUri.isNullOrBlank()) withContext(Dispatchers.IO) {
            try { context.contentResolver.openInputStream(Uri.parse(photoUri!!))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }
            catch (_: Exception) { null }
        } else null
    }

    val displayName = name?.takeIf { it.isNotBlank() } ?: number.ifBlank { "Unknown" }
    val stateText = when (callState) {
        Call.STATE_DIALING -> "Calling..."
        Call.STATE_CONNECTING -> "Connecting..."
        Call.STATE_RINGING -> "Incoming call"
        Call.STATE_ACTIVE -> formatDuration(duration)
        Call.STATE_HOLDING -> "On hold"
        Call.STATE_DISCONNECTING -> "Ending call..."
        Call.STATE_DISCONNECTED -> "Call ended"
        else -> ""
    }
    val isRinging = callState == Call.STATE_RINGING
    val isConnecting = callState == Call.STATE_DIALING || callState == Call.STATE_CONNECTING
    val showActiveControls = callState == Call.STATE_ACTIVE || callState == Call.STATE_HOLDING
    val speakerOn = audioRoute == CallAudioState.ROUTE_SPEAKER

    fun requestOrToggleRecording() {
        if (CallManager.isRecording.value) {
            CallManager.toggleRecord(context)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            CallManager.toggleRecord(context)
        } else {
            pendingRecordAfterPermission = true
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF08070D), Color(0xFF15102A), Color(0xFF241238), Color(0xFF100B19)))))
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                InCallHeader(stateText, displayName, number, simInfo, contactBitmap, isRinging || isConnecting)
                if (trackedCalls.size > 1) {
                    Spacer(Modifier.height(16.dp))
                    CallSelector(trackedCalls) { CallManager.selectCall(it) }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    isRinging -> IncomingCallControls({ CallManager.reject() }, { CallManager.answer() })
                    showKeypad -> InCallDtmfPad { showKeypad = false }
                    showActiveControls -> ActiveCallControls(
                        context = context,
                        isMuted = isMuted,
                        speakerOn = speakerOn,
                        isOnHold = isOnHold,
                        isRecording = isRecording,
                        multipleCalls = trackedCalls.size > 1,
                        onKeypad = { showKeypad = true },
                        onAddCall = onAddCall,
                        onRecord = ::requestOrToggleRecording
                    )
                    isConnecting || callState == Call.STATE_DISCONNECTING -> Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(20.dp))
                if (!isRinging && callState != Call.STATE_DISCONNECTED) EndCallButton()
            }
        }
    }
}

@Composable
private fun CallSelector(calls: List<CallManager.CallSnapshot>, onSelect: (Call) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        calls.filter { it.state != Call.STATE_DISCONNECTED }.forEachIndexed { index, snapshot ->
            val title = snapshot.name?.takeIf { it.isNotBlank() } ?: snapshot.number.ifBlank { "Call ${index + 1}" }
            val state = when (snapshot.state) {
                Call.STATE_ACTIVE -> "Active"
                Call.STATE_HOLDING -> "Held"
                Call.STATE_RINGING -> "Ringing"
                Call.STATE_DIALING, Call.STATE_CONNECTING -> "Connecting"
                else -> "Call"
            }
            Surface(
                modifier = Modifier.weight(1f).clickable { onSelect(snapshot.call) },
                shape = RoundedCornerShape(14.dp),
                color = if (snapshot.isSelected) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = .10f)
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(title, maxLines = 1, style = MaterialTheme.typography.labelLarge, color = Color.White)
                    Text(state, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .75f))
                }
            }
        }
    }
}

fun formatDuration(seconds: Long): String = String.format("%02d:%02d", seconds / 60, seconds % 60)
