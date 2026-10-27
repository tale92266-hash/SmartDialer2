package com.smartdialerpro.android.ui.screens.fakecall

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdialerpro.android.service.AmbientSoundManager
import com.smartdialerpro.android.service.FakeCallActionHandler
import com.smartdialerpro.android.service.FakeCallEngine
import com.smartdialerpro.android.service.FakeCallEngineListener
import com.smartdialerpro.android.service.FakeCallLogWriter
import com.smartdialerpro.android.service.FakeCallService
import com.smartdialerpro.android.service.FakeCallSession
import com.smartdialerpro.android.service.FakeCallState
import com.smartdialerpro.android.ui.screens.incall.InCallDtmfPad
import com.smartdialerpro.android.ui.screens.incall.InCallHeader
import com.smartdialerpro.android.ui.theme.CallEndRed
import com.smartdialerpro.android.ui.theme.SmartDialerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Magic (fake) call screen. It looks and behaves like the real
 * in-call screen (dialing -> ringing -> connected with a timer, then
 * auto-end at the chosen duration) but never touches Telecom, the SIM
 * or the network. When it ends, a realistic entry is written to the
 * system call log by [FakeCallLogWriter].
 */
class FakeCallActivity : ComponentActivity() {

    companion object {
        fun start(context: Context, number: String, durationSecs: Int, simSlot: Int) {
            val intent = Intent(context, FakeCallActivity::class.java).apply {
                putExtra("number", number)
                putExtra("fake_duration", durationSecs)
                putExtra("sim_slot", simSlot)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var engine: FakeCallEngine? = null
    private var ringtonePlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null
    private var autoDisconnectRunnable: Runnable? = null
    private var isEnding = false
    private var userRejected = false
    private var connectTimeMillis = 0L

    private val callState = mutableStateOf(FakeCallState.IDLE)
    private val statusText = mutableStateOf("Calling...")
    private val timerText = mutableStateOf("00:00")
    private val isMuted = mutableStateOf(false)
    private val isSpeaker = mutableStateOf(false)
    private val keypadOpen = mutableStateOf(false)
    private val dtmfDigits = mutableStateOf("")
    private val displayName = mutableStateOf("")
    private val simInfoText = mutableStateOf<String?>(null)
    private val contactBitmap = mutableStateOf<ImageBitmap?>(null)

    private var number: String = ""
    private var durationSecs: Int = 0
    private var simSlot: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        number = intent.getStringExtra("number") ?: ""
        durationSecs = intent.getIntExtra("fake_duration", 0)
        simSlot = intent.getIntExtra("sim_slot", 0)
        if (number.isBlank()) {
            finish()
            return
        }

        try {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = lp
            }
        } catch (_: Throwable) {
        }

        FakeCallActionHandler.onEnd = { userEndCall() }
        FakeCallActionHandler.onToggleMute = { toggleMute() }
        FakeCallActionHandler.onToggleSpeaker = { toggleSpeaker() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (keypadOpen.value) {
                    keypadOpen.value = false
                    return
                }
                // Leave the screen, not the call: the notification brings it back.
                moveTaskToBack(true)
            }
        })

        displayName.value = number
        resolveContactAndSim()
        FakeCallSession.start(displayName.value, number, simSlot, null, durationSecs)
        FakeCallService.startFakeCall(this)

        val listener = object : FakeCallEngineListener {
            override fun onStateChanged(state: FakeCallState, status: String) {
                callState.value = state
                statusText.value = status
                when (state) {
                    FakeCallState.RINGING -> startRinging()
                    FakeCallState.ACTIVE -> onConnected()
                    FakeCallState.DISCONNECTED -> {
                        stopRinging()
                        AmbientSoundManager.getInstance(this@FakeCallActivity).stopCallAudio()
                    }
                    else -> Unit
                }
                FakeCallService.updateState(this@FakeCallActivity)
            }

            override fun onTimeUpdated(formatted: String) {
                timerText.value = formatted
            }

            override fun onAudioStateChanged(muted: Boolean, speaker: Boolean) {
                isMuted.value = muted
                isSpeaker.value = speaker
                FakeCallSession.muted = muted
                FakeCallSession.speaker = speaker
                AmbientSoundManager.getInstance(this@FakeCallActivity).setSpeakerphone(speaker)
                FakeCallService.updateState(this@FakeCallActivity)
            }

            override fun onCallEnded() {
                endCall()
            }
        }
        engine = FakeCallEngine(this, listener)
        engine?.initialize()

        setContent {
            SmartDialerTheme {
                FakeCallScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // ACTION_RETURN_TO_CALL just brings this singleTop activity forward.
    }

    override fun onDestroy() {
        super.onDestroy()
        FakeCallActionHandler.onEnd = null
        FakeCallActionHandler.onToggleMute = null
        FakeCallActionHandler.onToggleSpeaker = null
        autoDisconnectRunnable?.let { handler.removeCallbacks(it) }
        stopRinging()
        toneGenerator?.release()
        toneGenerator = null
        if (isFinishing && !isEnding && FakeCallSession.isActive) {
            endCall()
        }
    }

    private fun resolveContactAndSim() {
        val appContext = applicationContext
        Thread {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                appContext.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        val photoUri = cursor.getString(1)
                        if (!name.isNullOrBlank()) {
                            displayName.value = name
                            FakeCallSession.name = name
                        }
                        if (!photoUri.isNullOrBlank()) {
                            try {
                                appContext.contentResolver.openInputStream(Uri.parse(photoUri))?.use { stream ->
                                    BitmapFactory.decodeStream(stream)?.asImageBitmap()?.let { contactBitmap.value = it }
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
            try {
                val subs = SubscriptionManager.from(appContext)?.activeSubscriptionInfoList
                val sub = subs?.firstOrNull { it.simSlotIndex == simSlot } ?: subs?.firstOrNull()
                val carrier = sub?.carrierName?.toString()?.takeIf { it.isNotBlank() }
                val label = "SIM ${simSlot + 1}" + (if (carrier != null) " • $carrier" else "")
                simInfoText.value = label
                FakeCallSession.simLabel = label
            } catch (_: Throwable) {
                simInfoText.value = "SIM ${simSlot + 1}"
            }
        }.start()
    }

    private fun onConnected() {
        connectTimeMillis = System.currentTimeMillis()
        FakeCallSession.connected = true
        FakeCallSession.startTimeMillis = connectTimeMillis
        stopRinging()
        try {
            val preset = AmbientSoundManager.getInstance(this).getCurrentPreset()
            AmbientSoundManager.getInstance(this).startCallAudio(preset, isSpeaker.value)
        } catch (_: Throwable) {
        }
        if (durationSecs > 0) {
            autoDisconnectRunnable = Runnable {
                if (!isEnding) engine?.disconnect()
            }
            handler.postDelayed(autoDisconnectRunnable!!, durationSecs * 1000L)
        }
    }

    private fun startRinging() {
        try {
            stopRinging()
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtonePlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@FakeCallActivity, ringtoneUri)
                isLooping = true
                setVolume(0.8f, 0.8f)
                prepare()
                start()
            }
        } catch (_: Throwable) {
        }
        try {
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 1200), 0))
        } catch (_: Throwable) {
        }
    }

    private fun stopRinging() {
        try {
            ringtonePlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                } catch (_: Throwable) {
                }
                it.release()
            }
        } catch (_: Throwable) {
        }
        ringtonePlayer = null
        try {
            vibrator?.cancel()
        } catch (_: Throwable) {
        }
        vibrator = null
    }

    private fun toggleMute() {
        engine?.toggleMute()
    }

    private fun toggleSpeaker() {
        engine?.toggleSpeaker()
    }

    private fun userEndCall() {
        if (connectTimeMillis == 0L) userRejected = true
        engine?.disconnect()
    }

    private fun endCall() {
        if (isEnding) return
        isEnding = true
        autoDisconnectRunnable?.let { handler.removeCallbacks(it) }
        stopRinging()
        try {
            AmbientSoundManager.getInstance(this).stopCallAudio()
        } catch (_: Throwable) {
        }
        FakeCallService.stopFakeCall(this)
        val now = System.currentTimeMillis()
        val finalDuration = if (connectTimeMillis > 0L) ((now - connectTimeMillis) / 1000).toInt() else 0
        val wasMissed = connectTimeMillis == 0L
        val fakeStartTime = now - (finalDuration * 1000L)
        try {
            FakeCallLogWriter.addFakeLog(this, number, fakeStartTime, finalDuration, simSlot, wasMissed)
        } catch (_: Throwable) {
        }
        if (wasMissed && !userRejected) {
            FakeCallLogWriter.showFakeMissedCallNotification(this, displayName.value, number)
        }
        FakeCallSession.clear()
        finishAndRemoveTask()
    }

    private fun playDtmfTone(digit: Char) {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(AudioManager.STREAM_DTMF, 80)
            }
            val tone = when (digit) {
                '1' -> ToneGenerator.TONE_DTMF_1
                '2' -> ToneGenerator.TONE_DTMF_2
                '3' -> ToneGenerator.TONE_DTMF_3
                '4' -> ToneGenerator.TONE_DTMF_4
                '5' -> ToneGenerator.TONE_DTMF_5
                '6' -> ToneGenerator.TONE_DTMF_6
                '7' -> ToneGenerator.TONE_DTMF_7
                '8' -> ToneGenerator.TONE_DTMF_8
                '9' -> ToneGenerator.TONE_DTMF_9
                '0' -> ToneGenerator.TONE_DTMF_0
                '*' -> ToneGenerator.TONE_DTMF_S
                '#' -> ToneGenerator.TONE_DTMF_P
                else -> return
            }
            toneGenerator?.startTone(tone, 150)
        } catch (_: Throwable) {
        }
    }

    @Composable
    private fun FakeCallScreen() {
        val state by callState
        val status by statusText
        val timer by timerText
        val muted by isMuted
        val speaker by isSpeaker
        val keypad by keypadOpen
        val digits by dtmfDigits
        val name by displayName
        val simInfo by simInfoText
        val bitmap by contactBitmap
        val context = LocalContext.current

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF08070D), Color(0xFF15102A), Color(0xFF241238), Color(0xFF100B19))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                InCallHeader(
                    stateText = if (state == FakeCallState.ACTIVE) timer else status,
                    displayName = name,
                    number = number,
                    simInfo = simInfo,
                    contactBitmap = bitmap,
                    animateAvatar = state == FakeCallState.ACTIVE
                )

                Spacer(Modifier.weight(1f))

                if (keypad) {
                    InCallDtmfPad(
                        digits = digits,
                        onDigit = { digit ->
                            dtmfDigits.value += digit
                            playDtmfTone(digit)
                        },
                        onBackspace = { dtmfDigits.value = dtmfDigits.value.dropLast(1) },
                        onClose = { keypadOpen.value = false }
                    )
                    Spacer(Modifier.height(20.dp))
                } else {
                    FakeControlGrid(
                        muted = muted,
                        speaker = speaker,
                        onMute = { toggleMute() },
                        onKeypad = { keypadOpen.value = true },
                        onSpeaker = { toggleSpeaker() },
                        onRecordInfo = {
                            Toast.makeText(context, "Recording is not available for fake calls", Toast.LENGTH_SHORT).show()
                        }
                    )
                    Spacer(Modifier.height(20.dp))
                }

                // End call button.
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(CallEndRed, CircleShape)
                        .clickable { userEndCall() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.CallEnd, contentDescription = "End call", tint = Color.White, modifier = Modifier.size(34.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    private fun FakeControlGrid(
        muted: Boolean,
        speaker: Boolean,
        onMute: () -> Unit,
        onKeypad: () -> Unit,
        onSpeaker: () -> Unit,
        onRecordInfo: () -> Unit
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                FakeControlButton(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Mute", muted, onMute)
                FakeControlButton(Icons.Rounded.Dialpad, "Keypad", false, onKeypad)
                FakeControlButton(Icons.Rounded.VolumeUp, "Speaker", speaker, onSpeaker)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                FakeControlButton(Icons.Rounded.GroupAdd, "Add call", false, {}, enabled = false)
                FakeControlButton(Icons.Rounded.Pause, "Hold", false, {}, enabled = false)
                FakeControlButton(Icons.Rounded.FiberManualRecord, "Record", false, onRecordInfo, enabled = false)
            }
        }
    }

    @Composable
    private fun FakeControlButton(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        label: String,
        active: Boolean,
        onClick: () -> Unit,
        enabled: Boolean = true
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.alpha(if (enabled) 1f else 0.35f)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        if (active) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.10f),
                        CircleShape
                    )
                    .clickable(enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Medium)
        }
    }
}
