package com.smartdialerpro.android.service

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Blinks the camera flash while a call is ringing ("flash on call").
 * Runs on a coroutine loop so it stops cleanly the moment the call
 * is answered, declined or ends. Every camera failure is swallowed:
 * a phone without a flash simply never blinks.
 */
object FlashCallController {
    private var job: Job? = null
    private var activeFlashId: String? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    @Synchronized
    fun start(context: Context) {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val cameraManager =
                    context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                        ?: return@launch
                val flashId = cameraManager.cameraIdList.firstOrNull { id ->
                    try {
                        cameraManager.getCameraCharacteristics(id)
                            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    } catch (_: Throwable) {
                        false
                    }
                } ?: return@launch
                activeFlashId = flashId
                var on = false
                while (isActive) {
                    on = !on
                    try {
                        cameraManager.setTorchMode(flashId, on)
                    } catch (_: Throwable) {
                        return@launch
                    }
                    delay(if (on) 350L else 450L)
                }
            } catch (_: Throwable) {
            } finally {
                val flashId = activeFlashId
                activeFlashId = null
                if (flashId != null) {
                    try {
                        val cameraManager =
                            context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                        cameraManager?.setTorchMode(flashId, false)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}
