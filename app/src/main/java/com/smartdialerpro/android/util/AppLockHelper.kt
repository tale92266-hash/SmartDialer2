package com.smartdialerpro.android.util

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import java.util.concurrent.Executor

/**
 * App lock via the framework BiometricPrompt (no new dependency):
 * fingerprint/face, falling back to the device credential (PIN /
 * pattern) when biometrics are not enrolled. If neither is available
 * the lock reports unavailable and the app must NOT lock the user
 * out of their own dialer.
 */
object AppLockHelper {
    const val PREF_KEY = "app_lock"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getBoolean(PREF_KEY, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, enabled).apply()
    }

    fun isAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val manager = context.getSystemService(BiometricManager::class.java) ?: return false
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return manager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun authenticate(
        activity: Activity,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        try {
            val executor: Executor = activity.mainExecutor
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle("Unlock SmartDialer")
                .setSubtitle("Confirm it is you to open your dialer")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
            val cancellation = CancellationSignal()
            prompt.authenticate(
                cancellation,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                        onSuccess()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                        onError(errString?.toString().orEmpty())
                    }

                    override fun onAuthenticationFailed() {
                        // Wrong biometric: keep the prompt usable; the
                        // framework shows its own retry feedback.
                    }
                }
            )
        } catch (t: Throwable) {
            onError(t.message.orEmpty())
        }
    }
}
