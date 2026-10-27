package com.smartdialerpro.android.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.SubscriptionManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Single source of truth for every Telecom Call currently exposed by AppInCallService. */
object CallManager {
    private const val TAG = "SmartDialerCallManager"
    private val lock = Any()
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val calls = LinkedHashMap<Call, MutableCallSnapshot>()
    private val durationJobs = mutableMapOf<Call, Job>()
    private val detailsJobs = mutableMapOf<Call, Job>()

    var inCallService: AppInCallService? = null
        private set
    var currentCall: Call? = null
        private set

    private val _calls = MutableStateFlow<List<CallSnapshot>>(emptyList())
    val trackedCalls = _calls.asStateFlow()
    private val _callState = MutableStateFlow(Call.STATE_NEW)
    val callState = _callState.asStateFlow()
    private val _callNumber = MutableStateFlow("")
    val callNumber = _callNumber.asStateFlow()
    private val _callerName = MutableStateFlow<String?>(null)
    val callerName = _callerName.asStateFlow()
    private val _callerPhotoUri = MutableStateFlow<String?>(null)
    val callerPhotoUri = _callerPhotoUri.asStateFlow()
    private val _simInfo = MutableStateFlow<String?>(null)
    val simInfo = _simInfo.asStateFlow()
    private val _isMuted = MutableStateFlow(false)
    val isMuted = _isMuted.asStateFlow()
    private val _audioRoute = MutableStateFlow(CallAudioState.ROUTE_EARPIECE)
    val audioRoute = _audioRoute.asStateFlow()
    private val _isOnHold = MutableStateFlow(false)
    val isOnHold = _isOnHold.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()
    val isRecording = CallRecordingManager.isRecording
    val recordingStatus = CallRecordingManager.statusMessage

    data class CallSnapshot(val call: Call, val state: Int, val number: String, val name: String?, val photoUri: String?, val simInfo: String?, val durationSeconds: Long, val isSelected: Boolean, val isConference: Boolean)
    private data class MutableCallSnapshot(var state: Int, var number: String = "", var name: String? = null, var photoUri: String? = null, var simInfo: String? = null, var durationSeconds: Long = 0L)

    val callsFlow = trackedCalls
    val selectedCallFlow = MutableStateFlow<Call?>(null).also { flow ->
        scope.launch {
            trackedCalls.collect { snapshots -> flow.value = snapshots.firstOrNull { it.isSelected }?.call }
        }
    }
    val callsSnapshot: List<CallSnapshot>
        get() = _calls.value
    val selectedCall: Call?
        get() = currentCall

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            var shouldStopRecording = false
            synchronized(lock) {
                val snapshot = calls[call] ?: return
                snapshot.state = state
                when (state) {
                    Call.STATE_ACTIVE -> startDurationTickerLocked(call)
                    Call.STATE_HOLDING, Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> stopDurationTickerLocked(call)
                }
                val selected = when {
                    state == Call.STATE_ACTIVE -> call
                    currentCall == call && state == Call.STATE_HOLDING -> selectBestCallLocked()
                    currentCall != null && calls.containsKey(currentCall) && currentCall?.state != Call.STATE_DISCONNECTED -> currentCall
                    else -> selectBestCallLocked()
                }
                publishLocked(selected)
                shouldStopRecording = CallRecordingManager.isRecording.value && CallRecordingManager.recordingCall === call && (state == Call.STATE_DISCONNECTING || state == Call.STATE_DISCONNECTED)
            }
            if (shouldStopRecording) stopRecordingFor(call)
            inCallService?.updateNotification()
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            synchronized(lock) {
                if (!calls.containsKey(call)) return
                updateDetailsLocked(call, details)
                if (call.state == Call.STATE_ACTIVE) startDurationTickerLocked(call)
                publishLocked(currentCall ?: selectBestCallLocked())
            }
            inCallService?.updateNotification()
        }
    }

    fun setCall(call: Call, service: AppInCallService) {
        synchronized(lock) {
            inCallService = service
            if (!calls.containsKey(call)) {
                calls[call] = MutableCallSnapshot(call.state)
                call.registerCallback(callback)
            }
            updateDetailsLocked(call, call.details)
            if (call.state == Call.STATE_ACTIVE) startDurationTickerLocked(call)
            val selected = when {
                currentCall == null -> call
                call.state == Call.STATE_ACTIVE -> call
                currentCall?.state == Call.STATE_DISCONNECTED -> call
                else -> currentCall
            }
            publishLocked(selected)
        }
    }

    fun addCall(call: Call, service: AppInCallService) = setCall(call, service)

    fun removeCall(call: Call) {
        if (CallRecordingManager.recordingCall === call) stopRecordingFor(call)
        synchronized(lock) {
            detailsJobs.remove(call)?.cancel()
            durationJobs.remove(call)?.cancel()
            try { call.unregisterCallback(callback) } catch (t: Throwable) { Log.w(TAG, "Callback unregister failed", t) }
            calls.remove(call)
            publishLocked(selectBestCallLocked())
            if (calls.isEmpty()) inCallService = null
        }
        inCallService?.updateNotification()
    }

    fun selectCall(call: Call): Boolean {
        synchronized(lock) {
            if (!calls.containsKey(call) || call.state == Call.STATE_DISCONNECTED) return false
            publishLocked(call)
        }
        inCallService?.updateNotification()
        return true
    }

    private fun selectBestCallLocked(): Call? = calls.entries.filter { it.key.state != Call.STATE_DISCONNECTED }
        .sortedWith(compareByDescending<Map.Entry<Call, MutableCallSnapshot>> { it.key.state == Call.STATE_ACTIVE }
            .thenByDescending { it.key.state == Call.STATE_RINGING }
            .thenByDescending { it.key.state == Call.STATE_DIALING || it.key.state == Call.STATE_CONNECTING }
            .thenByDescending { it.key.state == Call.STATE_HOLDING })
        .firstOrNull()?.key

    private fun publishLocked(selected: Call?) {
        currentCall = selected
        val snapshot = selected?.let(calls::get)
        _callState.value = selected?.state ?: Call.STATE_DISCONNECTED
        _callNumber.value = snapshot?.number.orEmpty()
        _callerName.value = snapshot?.name
        _callerPhotoUri.value = snapshot?.photoUri
        _simInfo.value = snapshot?.simInfo
        _isOnHold.value = selected?.state == Call.STATE_HOLDING
        _duration.value = snapshot?.durationSeconds ?: 0L
        _calls.value = calls.map { (call, value) -> CallSnapshot(call, value.state, value.number, value.name, value.photoUri, value.simInfo, value.durationSeconds, call == selected, call.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)) }
    }

    private fun updateDetailsLocked(call: Call, details: Call.Details) {
        val snapshot = calls[call] ?: return
        val context = inCallService?.applicationContext ?: return
        snapshot.number = details.handle?.schemeSpecificPart.orEmpty()
        snapshot.simInfo = resolveSimInfo(context, details.accountHandle)
        detailsJobs.remove(call)?.cancel()
        if (snapshot.number.isBlank()) {
            snapshot.name = null
            snapshot.photoUri = null
            return
        }
        val number = snapshot.number
        detailsJobs[call] = scope.launch(Dispatchers.IO) {
            var foundName: String? = null
            var foundPhoto: String? = null
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
                    val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI)
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            foundName = cursor.getString(0)?.takeIf { it.isNotBlank() }
                            foundPhoto = cursor.getString(1)?.takeIf { it.isNotBlank() }
                        }
                    }
                } catch (t: Throwable) { Log.w(TAG, "Contact lookup failed", t) }
            }
            scope.launch {
                synchronized(lock) {
                    val current = calls[call] ?: return@synchronized
                    if (current.number == number) {
                        current.name = foundName
                        current.photoUri = foundPhoto
                        publishLocked(currentCall ?: call)
                    }
                }
                inCallService?.updateNotification()
            }
        }
    }

    /** Resolve the actual Telecom account without using account-list position as a SIM identity. */
    private fun resolveSimInfo(context: Context, handle: android.telecom.PhoneAccountHandle?): String? {
        if (handle == null) return null
        return try {
            val telecom = context.getSystemService(TelecomManager::class.java)
            val account = telecom?.getPhoneAccount(handle)
            val label = account?.label?.toString()?.takeIf { it.isNotBlank() } ?: "Phone account"
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return label
            val subscriptions = context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
            val matched = subscriptions.firstOrNull { it.subscriptionId.toString() == handle.id || handle.id.contains(it.subscriptionId.toString(), ignoreCase = true) }
            when {
                matched != null && matched.simSlotIndex >= 0 -> "SIM ${matched.simSlotIndex + 1} • $label"
                account?.extras?.getInt("slot", -1)?.takeIf { it >= 0 } != null -> "SIM ${(account.extras?.getInt("slot", -1) ?: 0) + 1} • $label"
                account?.extras?.getInt("slot_id", -1)?.takeIf { it >= 0 } != null -> "SIM ${(account.extras?.getInt("slot_id", -1) ?: 0) + 1} • $label"
                else -> label
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SIM lookup failed", t)
            null
        }
    }

    private fun startDurationTickerLocked(call: Call) {
        if (durationJobs[call]?.isActive == true) return
        val connectTime = call.details.connectTimeMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
        durationJobs[call] = scope.launch {
            while (isActive) {
                val active = synchronized(lock) {
                    val snapshot = calls[call]
                    if (snapshot == null || call.state != Call.STATE_ACTIVE) false else {
                        snapshot.durationSeconds = ((System.currentTimeMillis() - connectTime).coerceAtLeast(0L) / 1000L)
                        publishLocked(currentCall ?: call)
                        true
                    }
                }
                if (!active) break
                inCallService?.updateNotification()
                delay(1000L)
            }
            synchronized(lock) { durationJobs.remove(call) }
        }
    }

    private fun stopDurationTickerLocked(call: Call) { durationJobs.remove(call)?.cancel() }
    fun updateAudioState(state: CallAudioState) { _isMuted.value = state.isMuted; _audioRoute.value = state.route; inCallService?.updateNotification() }
    fun setDuration(seconds: Long) { synchronized(lock) { currentCall?.let { calls[it]?.durationSeconds = seconds.coerceAtLeast(0L) }; _duration.value = seconds.coerceAtLeast(0L) } }

    fun endCall() { val call = synchronized(lock) { currentCall } ?: return; if (call.state == Call.STATE_DISCONNECTED) return; try { call.disconnect() } catch (t: Throwable) { Log.e(TAG, "Disconnect failed", t) } }
    fun answer() { val call = synchronized(lock) { currentCall } ?: return; if (call.state != Call.STATE_RINGING) return; try { call.answer(VideoProfile.STATE_AUDIO_ONLY) } catch (t: Throwable) { Log.e(TAG, "Answer failed", t) } }
    fun reject() { val call = synchronized(lock) { currentCall } ?: return; if (call.state != Call.STATE_RINGING) return; try { call.reject(false, null) } catch (t: Throwable) { Log.e(TAG, "Reject failed", t) } }

    fun toggleHold() {
        val call = synchronized(lock) { currentCall } ?: return
        try {
            when (call.state) {
                Call.STATE_ACTIVE -> if (call.details.can(Call.Details.CAPABILITY_HOLD)) call.hold() else Log.w(TAG, "Hold unsupported")
                Call.STATE_HOLDING -> if (call.details.can(Call.Details.CAPABILITY_HOLD)) call.unhold() else Log.w(TAG, "Unhold unsupported")
            }
        } catch (t: Throwable) { Log.e(TAG, "Hold/unhold failed", t) }
    }

    fun swapCalls(): Boolean {
        val active = synchronized(lock) { calls.keys.firstOrNull { it.state == Call.STATE_ACTIVE } } ?: return false
        val held = synchronized(lock) { calls.keys.firstOrNull { it.state == Call.STATE_HOLDING } } ?: return false
        return try {
            if (!active.details.can(Call.Details.CAPABILITY_HOLD) || !held.details.can(Call.Details.CAPABILITY_HOLD)) return false
            active.hold(); held.unhold(); synchronized(lock) { publishLocked(held) }; true
        } catch (t: Throwable) { Log.e(TAG, "Swap failed", t); false }
    }

    fun mergeCalls(): Boolean {
        val active = synchronized(lock) { calls.keys.firstOrNull { it.state == Call.STATE_ACTIVE } } ?: return false
        val other = active.conferenceableCalls.firstOrNull { it.state != Call.STATE_DISCONNECTED } ?: return false
        return try {
            if (!active.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)) return false
            active.conference(other); true
        } catch (t: Throwable) { Log.e(TAG, "Conference merge failed", t); false }
    }

    fun toggleMute() { val service = inCallService ?: return; val call = synchronized(lock) { currentCall } ?: return; if (call.state == Call.STATE_DISCONNECTED) return; try { service.setMuted(!_isMuted.value) } catch (t: Throwable) { Log.e(TAG, "Mute failed", t) } }
    fun toggleSpeaker() { val service = inCallService ?: return; val call = synchronized(lock) { currentCall } ?: return; if (call.state == Call.STATE_DISCONNECTED) return; try { service.setAudioRoute(if (_audioRoute.value == CallAudioState.ROUTE_SPEAKER) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER) } catch (t: Throwable) { Log.e(TAG, "Audio route change failed", t) } }
    fun playDtmf(digit: Char) { val call = synchronized(lock) { currentCall } ?: return; try { call.playDtmfTone(digit) } catch (t: Throwable) { Log.e(TAG, "DTMF start failed", t) } }
    fun stopDtmf() { val call = synchronized(lock) { currentCall } ?: return; try { call.stopDtmfTone() } catch (t: Throwable) { Log.e(TAG, "DTMF stop failed", t) } }

    fun toggleRecord(context: Context) {
        val call = synchronized(lock) { currentCall }
        if (call == null || call.state == Call.STATE_DISCONNECTED) { Toast.makeText(context, "No active call to record.", Toast.LENGTH_SHORT).show(); return }
        if (isRecording.value) {
            val saved = CallRecordingManager.stop(context)
            Toast.makeText(context, if (saved != null) "Recording saved" else (recordingStatus.value ?: "Recording stopped"), Toast.LENGTH_SHORT).show()
        } else if (call.state != Call.STATE_ACTIVE) Toast.makeText(context, "Recording is available during an active call.", Toast.LENGTH_SHORT).show()
        else {
            val started = CallRecordingManager.start(context, call, callNumber.value, callerName.value)
            Toast.makeText(context, if (started) "Recording started" else (recordingStatus.value ?: "Call recording could not start."), Toast.LENGTH_LONG).show()
        }
        inCallService?.updateNotification()
    }

    private fun stopRecordingFor(call: Call) { val context = inCallService?.applicationContext ?: return; if (CallRecordingManager.recordingCall === call && CallRecordingManager.isRecording.value) { CallRecordingManager.stopIfActive(context); inCallService?.updateNotification() } }

    fun clear() {
        val service = inCallService
        service?.applicationContext?.let { if (CallRecordingManager.isRecording.value) CallRecordingManager.stopIfActive(it) }
        synchronized(lock) {
            calls.keys.toList().forEach { call ->
                try { call.unregisterCallback(callback) } catch (t: Throwable) { Log.w(TAG, "Callback cleanup failed", t) }
                durationJobs.remove(call)?.cancel(); detailsJobs.remove(call)?.cancel()
            }
            calls.clear(); currentCall = null; publishLocked(null); inCallService = null
        }
        service?.cancelNotification()
    }
}
