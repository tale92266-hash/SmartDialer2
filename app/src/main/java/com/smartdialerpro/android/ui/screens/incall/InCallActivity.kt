package com.smartdialerpro.android.ui.screens.incall

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.smartdialerpro.android.MainActivity
import com.smartdialerpro.android.service.CallManager
import com.smartdialerpro.android.service.CallProximityController
import com.smartdialerpro.android.ui.theme.CallAnswerGreen
import com.smartdialerpro.android.ui.theme.CallEndRed
import com.smartdialerpro.android.ui.theme.SmartDialerTheme
import com.smartdialerpro.android.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InCallActivity : ComponentActivity() {
    private val keypadOpenState = mutableStateOf(false)
    private var proximityController: CallProximityController? = null

    private fun applyProximity(state: Int) {
        try {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                proximityController?.stop()
                return
            }
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("proximity_screen_off", true)) {
                proximityController?.stop()
                return
            }
            if (state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || state == Call.STATE_ACTIVE) {
                if (proximityController == null) proximityController = CallProximityController(this)
                proximityController?.start()
            } else {
                proximityController?.stop()
            }
        } catch (_: Throwable) {
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the display awake on the call screen when the user asked for it.
        try {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("keep_screen_on", false)) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (_: Throwable) {
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Innermost layer first: keypad, then leave the call screen.
                if (keypadOpenState.value) {
                    keypadOpenState.value = false
                    return
                }
                if (CallManager.callState.value != Call.STATE_DISCONNECTED) {
                    startActivity(Intent(this@InCallActivity, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP })
                } else finishAndRemoveTask()
            }
        })
        setContent {
            SmartDialerTheme {
                PremiumInCallScreen(
                    onFinish = { finishAndRemoveTask() },
                    onAddCall = { startActivity(Intent(Intent.ACTION_DIAL).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }) },
                    keypadOpen = keypadOpenState.value,
                    onKeypadOpenChange = { keypadOpenState.value = it }
                )
            }
        }
        lifecycleScope.launch {
            CallManager.callState.collect { state -> applyProximity(state) }
        }
    }

    override fun onResume() {
        super.onResume()
        CallManager.inCallUiForeground = true
        applyProximity(CallManager.callState.value)
        CallManager.inCallService?.updateNotification()
        // Stale-call watchdog: returning to this activity with no tracked
        // call and no live state means there is nothing to show.
        if (CallManager.trackedCalls.value.isEmpty() && CallManager.callState.value == Call.STATE_DISCONNECTED) {
            finishAndRemoveTask()
        }
    }

    override fun onPause() {
        CallManager.inCallUiForeground = false
        try {
            proximityController?.stop()
        } catch (_: Throwable) {
        }
        CallManager.inCallService?.updateNotification()
        super.onPause()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumInCallScreen(
    onFinish: () -> Unit,
    onAddCall: () -> Unit,
    keypadOpen: Boolean = false,
    onKeypadOpenChange: (Boolean) -> Unit = {}
) {
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
    var contactBitmap by remember(photoUri) { mutableStateOf<ImageBitmap?>(null) }
    var pendingRecordAfterPermission by remember { mutableStateOf(false) }
    var dtmfDigits by remember { mutableStateOf("") }
    var showQuickResponses by remember { mutableStateOf(false) }
    var pendingQuickResponse by remember { mutableStateOf<String?>(null) }
    val quickResponses = remember {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.getStringSet("quick_responses", null)?.toList()?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: listOf("Can't talk right now", "I'll call you back", "I'm in a meeting", "I'm driving")
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val text = pendingQuickResponse
        pendingQuickResponse = null
        if (granted && text != null) {
            CallManager.reject()
            val sent = CallManager.sendQuickResponseSms(number, text)
            android.widget.Toast.makeText(
                context,
                if (sent) "Reply sent" else "Could not send reply",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } else if (!granted) {
            android.widget.Toast.makeText(context, "SMS permission needed to send a reply", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // A fresh call starts with an empty keypad input.
    LaunchedEffect(callState) {
        if (callState == Call.STATE_DISCONNECTED) dtmfDigits = ""
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingRecordAfterPermission) CallManager.toggleRecord(context)
        pendingRecordAfterPermission = false
    }

    // When the call ends, leave the call screen promptly — no extra
    // summary screen in between.
    LaunchedEffect(callState, trackedCalls.size) {
        if (callState == Call.STATE_DISCONNECTED && trackedCalls.isEmpty()) {
            delay(300)
            onFinish()
        }
    }

    // Stale-call watchdog: a fresh state with no tracked call is not a
    // real call screen; give Telecom a short grace, then leave.
    LaunchedEffect(callState, trackedCalls.size) {
        if (callState == Call.STATE_NEW && trackedCalls.isEmpty()) {
            delay(1200)
            if (CallManager.callState.value == Call.STATE_NEW && CallManager.trackedCalls.value.isEmpty()) {
                onFinish()
            }
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
                if (isRecording && (showActiveControls || isConnecting)) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(9.dp).background(CallEndRed, CircleShape)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "Recording",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (trackedCalls.size > 1) {
                    Spacer(Modifier.height(16.dp))
                    CallSelector(trackedCalls) { CallManager.selectCall(it) }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    isRinging -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IncomingCallControls({ CallManager.reject() }, { CallManager.answer() })
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = { showQuickResponses = true }) {
                            Text(
                                "Reply with message",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    keypadOpen -> InCallDtmfPad(
                        digits = dtmfDigits,
                        onDigit = { digit ->
                            dtmfDigits += digit
                            CallManager.playDtmf(digit)
                        },
                        onBackspace = { dtmfDigits = dtmfDigits.dropLast(1) },
                        onClose = { onKeypadOpenChange(false) }
                    )
                    showActiveControls -> ActiveCallControls(
                        context = context,
                        isMuted = isMuted,
                        speakerOn = speakerOn,
                        isOnHold = isOnHold,
                        isRecording = isRecording,
                        multipleCalls = trackedCalls.size > 1,
                        onKeypad = { onKeypadOpenChange(true) },
                        onAddCall = onAddCall,
                        onRecord = ::requestOrToggleRecording
                    )
                    isConnecting -> ActiveCallControls(
                        context = context,
                        isMuted = isMuted,
                        speakerOn = speakerOn,
                        isOnHold = isOnHold,
                        isRecording = isRecording,
                        multipleCalls = false,
                        dialingMode = true,
                        onKeypad = { onKeypadOpenChange(true) },
                        onAddCall = onAddCall,
                        onRecord = ::requestOrToggleRecording
                    )
                    callState == Call.STATE_DISCONNECTING -> Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(20.dp))
                if (!isRinging && callState != Call.STATE_DISCONNECTED) EndCallButton()
            }
        }

        if (showQuickResponses) {
            ModalBottomSheet(onDismissRequest = { showQuickResponses = false }) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        "Reply with message",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    quickResponses.forEach { template ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showQuickResponses = false
                                    pendingQuickResponse = template
                                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                                        CallManager.reject()
                                        val sent = CallManager.sendQuickResponseSms(number, template)
                                        android.widget.Toast.makeText(
                                            context,
                                            if (sent) "Reply sent" else "Could not send reply",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                        pendingQuickResponse = null
                                    } else {
                                        smsPermissionLauncher.launch(Manifest.permission.SEND_SMS)
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Call,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(14.dp))
                            Text(template, fontSize = 15.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

    }}

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
