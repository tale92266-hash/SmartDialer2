package com.smartdialerpro.android

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmartDialerApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // CNAP cache must be ready in EVERY process start, not just
        // when the user opens the app UI: an incoming call cold-starts
        // this process through the telecom-bound InCallService, and
        // MainActivity.onCreate never runs in that path. Without this,
        // a captured CNAP name was shown on the call screen (in-memory
        // only) but never persisted, so call logs could never show it.
        com.smartdialerpro.android.data.CnapStore.init(this)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val fileName = "SmartDialer_Crash_$timestamp.txt"

            var savedUri: Uri? = null
            try {
                // Try MediaStore (Android 10+) – works without permission for Downloads
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        resolver.openOutputStream(it)?.use { outputStream ->
                            outputStream.write(stackTrace.toByteArray())
                            savedUri = it
                        }
                    }
                } else {
                    // Fallback for older Android: write to external files dir (no permission needed)
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val file = File(dir, fileName)
                    FileWriter(file).use { writer ->
                        writer.write(stackTrace)
                    }
                    savedUri = Uri.fromFile(file)
                }
            } catch (e: Exception) {
                // If all fails, log to logcat
                Log.e("SmartDialer", "Failed to write crash log", e)
            } finally {
                if (savedUri != null) {
                    Log.i("SmartDialer", "Crash log saved to $savedUri")
                } else {
                    Log.e("SmartDialer", "Could not save crash log")
                }
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }
        }
    }
}
