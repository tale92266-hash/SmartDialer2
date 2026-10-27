package com.smartdialerpro.android.service

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.telecom.Call
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns the single real recorder for the currently recorded Telecom Call. */
object CallRecordingManager {
    private const val TAG = "CallRecordingManager"
    private const val MIME_TYPE = "audio/mp4"
    private const val RELATIVE_PATH = "Music/SmartDialer/Calls"

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    @Volatile
    var recordingCall: Call? = null
        private set

    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: ParcelFileDescriptor? = null

    @Synchronized
    fun start(context: Context, call: Call, number: String, name: String?): Boolean {
        if (_isRecording.value) return recordingCall === call
        if (call.state != Call.STATE_ACTIVE) return fail("Recording requires an active call.")

        val appContext = context.applicationContext
        if (!hasRecordAudioPermission(appContext)) {
            return fail("Microphone permission is required to record this call.")
        }
        if (number.isBlank()) return fail("Cannot start recording: the call number is unavailable.")

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, buildFileName(number, name))
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val uri = try {
            appContext.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to create MediaStore recording entry", t)
            null
        }
        if (uri == null) return fail("Unable to create a recording file.")

        var descriptor: ParcelFileDescriptor? = null
        val newRecorder = try {
            descriptor = appContext.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("Unable to open recording destination")

            MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(descriptor!!.fileDescriptor)
                prepare()
                start()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to initialize/start MediaRecorder", t)
            descriptor?.close()
            deleteUri(appContext, uri)
            return fail("Call recording could not start on this device.")
        }

        recorder = newRecorder
        outputUri = uri
        outputDescriptor = descriptor
        recordingCall = call
        _statusMessage.value = "Recording started"
        _isRecording.value = true
        return true
    }

    @Synchronized
    fun stop(context: Context): Uri? {
        if (!_isRecording.value) return null
        val appContext = context.applicationContext
        val currentRecorder = recorder
        val uri = outputUri
        val descriptor = outputDescriptor
        recorder = null
        outputUri = null
        outputDescriptor = null
        recordingCall = null
        _isRecording.value = false

        if (currentRecorder == null || uri == null) {
            closeDescriptor(descriptor)
            _statusMessage.value = "Recording stopped"
            return null
        }

        var valid = true
        try {
            currentRecorder.stop()
        } catch (t: Throwable) {
            valid = false
            Log.e(TAG, "Recorder stop failed; discarding incomplete recording", t)
        } finally {
            releaseRecorder(currentRecorder)
            closeDescriptor(descriptor)
        }

        if (!valid) {
            deleteUri(appContext, uri)
            _statusMessage.value = "Recording could not be finalized."
            return null
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appContext.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                    null,
                    null
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to finalize MediaStore recording", t)
            deleteUri(appContext, uri)
            _statusMessage.value = "Recording could not be finalized."
            return null
        }

        _statusMessage.value = "Recording saved"
        return uri
    }

    @Synchronized
    fun stopIfActive(context: Context) {
        if (_isRecording.value) stop(context)
    }

    fun clearStatus() {
        _statusMessage.value = null
    }

    private fun releaseRecorder(value: MediaRecorder) {
        try {
            value.reset()
        } catch (t: Throwable) {
            Log.w(TAG, "MediaRecorder reset failed", t)
        }
        try {
            value.release()
        } catch (t: Throwable) {
            Log.w(TAG, "MediaRecorder release failed", t)
        }
    }

    private fun closeDescriptor(descriptor: ParcelFileDescriptor?) {
        try {
            descriptor?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to close recording descriptor", t)
        }
    }

    private fun deleteUri(context: Context, uri: Uri) {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to remove failed recording $uri", t)
        }
    }

    private fun hasRecordAudioPermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    private fun fail(message: String): Boolean {
        Log.w(TAG, message)
        _statusMessage.value = message
        _isRecording.value = false
        recordingCall = null
        return false
    }

    private fun buildFileName(number: String, name: String?): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val safeIdentity = sanitizeFilePart(name?.takeIf { it.isNotBlank() } ?: number)
        return "Call_${stamp}_$safeIdentity.m4a"
    }

    private fun sanitizeFilePart(value: String): String =
        value.replace(Regex("[^A-Za-z0-9+_-]"), "_")
            .trim('_')
            .take(60)
            .ifBlank { "Unknown" }
}