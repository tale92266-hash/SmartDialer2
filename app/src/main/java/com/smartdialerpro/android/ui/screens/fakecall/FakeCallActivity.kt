package com.smartdialerpro.android.ui.screens.fakecall

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartdialerpro.android.MainActivity
import com.smartdialerpro.android.service.AmbientSoundManager
import com.smartdialerpro.android.service.FakeCallActionHandler
import com.smartdialerpro.android.service.FakeCallEngine
import com.smartdialerpro.android.service.FakeCallEngineListener
import com.smartdialerpro.android.service.FakeCallLogWriter
import com.smartdialerpro.android.service.FakeCallService
import com.smartdialerpro.android.service.FakeCallSession
import com.smartdialerpro.android.service.FakeCallSim
import com.smartdialerpro.android.service.FakeCallState
import com.smartdialerpro.android.ui.screens.incall.ActiveCallControls
import com.smartdialerpro.android.ui.screens.incall.EndCallButton
import com.smartdialerpro.android.ui.screens.incall.InCallDtmfPad
import com.smartdialerpro.android.ui.screens.incall.InCallHeader
import com.smartdialerpro.android.ui.theme.SmartDialerTheme

/**
 * The Magic (fake) call screen. Its layout is the real in-call screen,
 * shared component for component (header, control grid, DTMF pad, end
 * button) — the call itself never touches Telecom, the SIM or the
 * network. When it ends, a realistic entry is written to the system
 * call log by [FakeCallLogWriter].
 */
class FakeCallActivity : ComponentActivity() {

    companion object {
        fun start(context: Context, number: String, durationSecs: Int, simSlot: Int) {
            val intent = Intent(context, FakeCallActivity::class.java).apply {
                putExtra("number", number)
                putExtra("fake_duration", durationSecs)
                putExtra("sim_slot", simSlot)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
            context.startActivity(intent)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var engine: FakeCallEngine? = null
    private var ringbackTone: ToneGenerator? = null
    private var isRingbackPlaying = false
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
        simSlot = intent.getIntExtra("sim_slot", -1)
        if (simSlot < 0) simSlot = FakeCallSim.resolveDefaultSimSlot(this)
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
                // Leave the call screen, not the call: the app opens with
                // the return-to-call banner, exactly like a real call.
                openMainApp()
            }
        })

        displayName.value = number
        resolveContactAndSim()
        val initialSimLabel = FakeCallSim.simLabelForSlot(this, simSlot)
        simInfoText.value = initialSimLabel
        FakeCallSession.start(displayName.value, number, simSlot, initialSimLabel, durationSecs)
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
                // Speaker toggled while the ringback tone is playing:
                // replay it on the new route, like the original.
                if (isRingbackPlaying) {
                    playRingbackTone(speaker)
                }
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

    private fun openMainApp() {
        try {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            })
        } catch (_: Throwable) {
            moveTaskToBack(true)
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
        FakeCallSession.markConnected(connectTimeMillis)
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

    /**
     * The outgoing ringback tone — the "turu turu" a real outgoing
     * call plays while the other side rings. Ported from the original
     * Dialar FakeCallActivity: ToneGenerator TONE_SUP_RINGTONE on the
     * voice-call stream (earpiece, like a real call), or the music
     * stream at full volume when the speaker is on. An incoming
     * ringtone tune is never played for an outgoing call.
     */
    private fun startRinging() {
        playRingbackTone(isSpeaker.value)
    }

    private fun playRingbackTone(speaker: Boolean) {
        stopRingbackTone()
        try {
            val stream = if (speaker) {
                android.media.AudioManager.STREAM_MUSIC
            } else {
                android.media.AudioManager.STREAM_VOICE_CALL
            }
            ringbackTone = ToneGenerator(stream, if (speaker) 100 else 85)
            ringbackTone?.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            isRingbackPlaying = true
        } catch (_: Throwable) {
            ringbackTone = null
            isRingbackPlaying = false
        }
    }

    private fun stopRingbackTone() {
        try {
            ringbackTone?.stopTone()
        } catch (_: Throwable) {
        }
        try {
            ringbackTone?.release()
        } catch (_: Throwable) {
        }
        ringbackTone = null
        isRingbackPlaying = false
    }

    private fun stopRinging() {
        stopRingbackTone()
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
            getSharedPreferences("dialer_preferences", MODE_PRIVATE).edit()
                .putString("fake_log_last_number", number)
                .putLong("fake_log_last_date", fakeStartTime)
                .apply()
        } catch (_: Throwable) {
        }
        try {
            val logSaved = FakeCallLogWriter.addFakeLog(this, number, fakeStartTime, finalDuration, simSlot, wasMissed)
            if (!logSaved) {
                Toast.makeText(
                    this,
                    "Call log could not be saved. Set SmartDialer as the default phone app so fake call logs appear in every app.",
                    Toast.LENGTH_LONG
                ).show()
            }
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
                toneGenerator = ToneGenerator(android.media.AudioManager.STREAM_DTMF, 80)
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

        val isConnecting = state == FakeCallState.DIALING || state == FakeCallState.RINGING
        val showActiveControls = state == FakeCallState.ACTIVE

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF08070D), Color(0xFF15102A), Color(0xFF241238), Color(0xFF100B19))
                    )
                )
            )
            Column(
                Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    InCallHeader(
                        stateText = if (state == FakeCallState.ACTIVE) timer else status,
                        displayName = name,
                        number = number,
                        simInfo = simInfo,
                        contactBitmap = bitmap,
                        animateAvatar = isConnecting,
                        simSlot = simSlot + 1
                    )
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when {
                        keypad -> InCallDtmfPad(
                            digits = digits,
                            onDigit = { digit ->
                                dtmfDigits.value += digit
                                playDtmfTone(digit)
                            },
                            onBackspace = { dtmfDigits.value = dtmfDigits.value.dropLast(1) },
                            onClose = { keypadOpen.value = false }
                        )
                        showActiveControls || isConnecting -> ActiveCallControls(
                            context = context,
                            isMuted = muted,
                            speakerOn = speaker,
                            isOnHold = false,
                            isRecording = false,
                            multipleCalls = false,
                            dialingMode = isConnecting,
                            onKeypad = { keypadOpen.value = true },
                            onAddCall = {
                                Toast.makeText(context, "Adding a call is not available for fake calls", Toast.LENGTH_SHORT).show()
                            },
                            onRecord = {
                                Toast.makeText(context, "Recording is not available for fake calls", Toast.LENGTH_SHORT).show()
                            },
                            onToggleMute = { toggleMute() },
                            onToggleSpeaker = { toggleSpeaker() },
                            onToggleHold = {
                                Toast.makeText(context, "Hold is not available for fake calls", Toast.LENGTH_SHORT).show()
                            }
                        )
                        state == FakeCallState.DISCONNECTED -> Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    if (state != FakeCallState.DISCONNECTED) EndCallButton(onEndCall = { userEndCall() })
                }
            }
        }
    }
}
