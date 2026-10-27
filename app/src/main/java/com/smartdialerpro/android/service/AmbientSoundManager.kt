package com.smartdialerpro.android.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import java.io.File
import kotlin.random.Random

/**
 * Background audio for Magic (fake) calls, ported from the original
 * Dialar AmbientSoundManager: procedurally synthesized ambient noise
 * (traffic / airport / office) plus an optional recorded caller voice,
 * all played through the voice-communication audio path.
 */
class AmbientSoundManager private constructor(private val context: Context) {

    companion object {
        const val PRESET_OFF = "OFF"
        const val PRESET_TRAFFIC = "TRAFFIC"
        const val PRESET_AIRPORT = "AIRPORT"
        const val PRESET_OFFICE = "OFFICE"
        const val PREFS_NAME = "app_prefs"
        const val KEY_PRESET = "fake_ambient_preset"
        const val KEY_CUSTOM_VOICE_URI = "custom_voice_uri"

        @Volatile
        private var instance: AmbientSoundManager? = null

        fun getInstance(context: Context): AmbientSoundManager =
            instance ?: synchronized(this) {
                instance ?: AmbientSoundManager(context.applicationContext).also { instance = it }
            }
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile private var ambientAudioTrack: AudioTrack? = null
    @Volatile private var ambientThread: Thread? = null
    @Volatile private var isAmbientPlaying = false
    private var voicePlayer: MediaPlayer? = null
    private var voiceRecorder: MediaRecorder? = null
    private var isRecordingVoice = false
    private var isCallActive = false
    private var currentSpeakerState = false
    private var currentAmbientPreset = PRESET_TRAFFIC

    private val recordedVoicePath: String by lazy {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        File(dir, "custom_caller_voice.m4a").absolutePath
    }

    fun getCurrentPreset(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PRESET, PRESET_TRAFFIC) ?: PRESET_TRAFFIC
    }

    fun setPreset(preset: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PRESET, preset).apply()
    }

    @Synchronized
    fun startCallAudio(ambientPreset: String?, isSpeakerOn: Boolean) {
        stopCallAudio()
        isCallActive = true
        currentAmbientPreset = ambientPreset ?: PRESET_TRAFFIC
        currentSpeakerState = isSpeakerOn
        setSpeakerphone(isSpeakerOn)
        playRealCallerVoice()
        if (!PRESET_OFF.equals(currentAmbientPreset, ignoreCase = true)) {
            startAmbientSynthesis(currentAmbientPreset)
        }
    }

    @Synchronized
    fun setSpeakerphone(isSpeakerOn: Boolean) {
        currentSpeakerState = isSpeakerOn
        try {
            audioManager?.let {
                it.mode = AudioManager.MODE_IN_COMMUNICATION
                it.isSpeakerphoneOn = isSpeakerOn
            }
        } catch (_: Throwable) {
        }
    }

    private fun playRealCallerVoice() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customAudioUriStr = prefs.getString(KEY_CUSTOM_VOICE_URI, null)
        var targetUri: Uri? = null
        if (!customAudioUriStr.isNullOrBlank()) {
            targetUri = Uri.parse(customAudioUriStr)
        } else {
            val recFile = File(recordedVoicePath)
            if (recFile.exists() && recFile.length() > 0) targetUri = Uri.fromFile(recFile)
        }
        if (targetUri == null) return
        try {
            voicePlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(context, targetUri)
                isLooping = false
                setVolume(1.0f, 1.0f)
                setOnPreparedListener { mp ->
                    if (isCallActive) {
                        setSpeakerphone(currentSpeakerState)
                        mp.start()
                    }
                }
                prepareAsync()
            }
        } catch (t: Throwable) {
            Log.e("AmbientSound", "Error playing real caller voice", t)
        }
    }

    @Synchronized
    fun stopCallAudio() {
        isCallActive = false
        isAmbientPlaying = false
        ambientThread?.interrupt()
        ambientThread = null
        try {
            ambientAudioTrack?.let {
                try {
                    it.stop()
                } catch (_: Throwable) {
                }
                it.release()
            }
        } catch (_: Throwable) {
        }
        ambientAudioTrack = null
        try {
            voicePlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                } catch (_: Throwable) {
                }
                it.release()
            }
        } catch (_: Throwable) {
        }
        voicePlayer = null
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
        } catch (_: Throwable) {
        }
    }

    fun startRecordingVoice(): Boolean {
        return try {
            voiceRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(recordedVoicePath)
                prepare()
                start()
            }
            isRecordingVoice = true
            true
        } catch (t: Throwable) {
            Log.e("AmbientSound", "Voice recording failed", t)
            false
        }
    }

    fun stopRecordingVoice(): Boolean {
        if (!isRecordingVoice) return false
        return try {
            voiceRecorder?.let {
                try {
                    it.stop()
                } catch (_: Throwable) {
                }
                it.release()
            }
            voiceRecorder = null
            isRecordingVoice = false
            File(recordedVoicePath).exists() && File(recordedVoicePath).length() > 0
        } catch (t: Throwable) {
            Log.e("AmbientSound", "Voice recording stop failed", t)
            isRecordingVoice = false
            false
        }
    }

    fun isRecordingVoice(): Boolean = isRecordingVoice

    fun hasRecordedVoice(): Boolean {
        val f = File(recordedVoicePath)
        return f.exists() && f.length() > 0
    }

    fun deleteRecordedVoice() {
        try {
            File(recordedVoicePath).delete()
        } catch (_: Throwable) {
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_CUSTOM_VOICE_URI).apply()
    }

    fun setCustomVoiceUri(uri: Uri?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM_VOICE_URI, uri?.toString()).apply()
    }

    fun hasCustomVoice(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_CUSTOM_VOICE_URI, null).isNullOrBlank() || hasRecordedVoice()
    }

    // Procedural ambient noise stream (same synthesis as the original).
    private fun startAmbientSynthesis(preset: String) {
        val sampleRate = 16000
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuf, 4096)
        try {
            ambientAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            ambientAudioTrack?.play()
            isAmbientPlaying = true
            ambientThread = Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val pcmChunk = ShortArray(800)
                val random = Random(System.nanoTime())
                var sampleIndex = 0L
                var lowFilter = 0f
                var midFilter = 0f
                while (isAmbientPlaying && !Thread.currentThread().isInterrupted) {
                    for (i in pcmChunk.indices) {
                        var outputSample = 0f
                        sampleIndex++
                        when {
                            PRESET_TRAFFIC.equals(preset, ignoreCase = true) -> {
                                lowFilter = lowFilter * 0.96f + (random.nextFloat() - 0.5f) * 0.08f
                                midFilter = midFilter * 0.88f + (random.nextFloat() - 0.5f) * 0.04f
                                var horn = 0f
                                val cycle = sampleIndex % (sampleRate * 7)
                                if (cycle < (sampleRate * 0.45)) {
                                    val t = cycle.toDouble() / sampleRate
                                    val envelope = Math.sin(Math.PI * (cycle / (sampleRate * 0.45)))
                                    horn = ((Math.sin(2 * Math.PI * 440 * t) + Math.sin(2 * Math.PI * 554.37 * t)) * 0.12 * envelope).toFloat()
                                }
                                outputSample = (lowFilter * 0.50f) + (midFilter * 0.20f) + horn
                            }
                            PRESET_AIRPORT.equals(preset, ignoreCase = true) -> {
                                lowFilter = lowFilter * 0.98f + (random.nextFloat() - 0.5f) * 0.03f
                                var chime = 0f
                                val cycle = sampleIndex % (sampleRate * 9)
                                if (cycle < (sampleRate * 0.35)) {
                                    val t = cycle.toDouble() / sampleRate
                                    val env = 1.0 - (cycle / (sampleRate * 0.35))
                                    chime = (Math.sin(2 * Math.PI * 587.33 * t) * 0.22 * env).toFloat()
                                } else if (cycle >= (sampleRate * 0.35) && cycle < (sampleRate * 0.80)) {
                                    val part2 = cycle - (sampleRate * 0.35).toLong()
                                    val t = part2.toDouble() / sampleRate
                                    val env = 1.0 - (part2 / (sampleRate * 0.45))
                                    chime = (Math.sin(2 * Math.PI * 440.0 * t) * 0.20 * env).toFloat()
                                }
                                outputSample = lowFilter + chime
                            }
                            PRESET_OFFICE.equals(preset, ignoreCase = true) -> {
                                lowFilter = lowFilter * 0.92f + (random.nextFloat() - 0.5f) * 0.04f
                                var click = 0f
                                if (random.nextInt(sampleRate / 4) < 6) {
                                    click = (random.nextFloat() - 0.5f) * 0.18f
                                }
                                outputSample = lowFilter + click
                            }
                        }
                        var clamped = (outputSample * 10000f).toInt()
                        if (clamped > 32767) clamped = 32767 else if (clamped < -32768) clamped = -32768
                        pcmChunk[i] = clamped.toShort()
                    }
                    if (ambientAudioTrack != null && isAmbientPlaying) {
                        ambientAudioTrack?.write(pcmChunk, 0, pcmChunk.size)
                    }
                }
            }, "AmbientTrackSynthesizer")
            ambientThread?.start()
        } catch (_: Throwable) {
        }
    }
}
