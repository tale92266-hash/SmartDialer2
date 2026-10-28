package com.smartdialerpro.android.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Auto redial: when an outgoing call ends busy, unanswered or
 * congested, dial the same number again after a gap, up to the
 * configured number of attempts. The pending redial is exposed as a
 * flow so the Home screen can show a countdown banner with Cancel.
 * A manual call, a connected call, or Cancel stops the chain.
 */
object AutoRedialManager {
    data class PendingRedial(
        val number: String,
        val displayName: String,
        val secondsLeft: Int,
        val attempt: Int,
        val totalAttempts: Int
    )

    private val _pending = MutableStateFlow<PendingRedial?>(null)
    val pending = _pending.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var job: Job? = null

    /** The chain being retried: number + attempts still left. */
    @Volatile
    private var chainNumber: String? = null

    @Volatile
    private var chainRemaining: Int = 0

    @Volatile
    private var chainHandleId: String? = null

    /**
     * Attempts left for [number]'s chain, or -1 when no chain is
     * running for it (a fresh chain should start from the full
     * attempt count).
     */
    @Volatile
    private var lastPlacedNumber: String? = null

    @Volatile
    private var lastPlacedAt: Long = 0L

    /**
     * A fresh manual outgoing call to [number] (not the redial itself,
     * and no countdown running) ends any exhausted chain for it, so a
     * later busy ending starts a new chain instead of reusing an old
     * spent one.
     */
    fun noteOutgoingCall(number: String) {
        val now = System.currentTimeMillis()
        if (number == lastPlacedNumber && now - lastPlacedAt < 30_000L) return
        if (_pending.value == null && chainNumber == number) {
            chainNumber = null
            chainRemaining = 0
        }
    }

    fun chainRemainingFor(number: String): Int {
        return if (chainNumber == number) chainRemaining else -1
    }

    fun schedule(
        context: Context,
        number: String,
        displayName: String,
        accountHandleId: String?,
        attemptsLeft: Int,
        totalAttempts: Int,
        gapSecs: Int
    ) {
        cancelJobOnly()
        chainNumber = number
        chainRemaining = attemptsLeft
        chainHandleId = accountHandleId
        val appContext = context.applicationContext
        job = scope.launch {
            try {
                var left = gapSecs.coerceAtLeast(5)
                val attemptNo = totalAttempts - attemptsLeft + 1
                while (left > 0) {
                    _pending.value = PendingRedial(number, displayName, left, attemptNo, totalAttempts)
                    delay(1000L)
                    left--
                }
                _pending.value = null
                // One attempt spent: the next retryable disconnect for
                // this number chains with one fewer attempt left.
                chainRemaining = attemptsLeft - 1
                placeCall(appContext, number, accountHandleId)
            } catch (t: Throwable) {
                Log.w(TAG, "Auto redial cancelled", t)
                _pending.value = null
            }
        }
    }

    /** A call connected: the chain served its purpose. */
    fun onCallConnected() {
        cancel()
    }

    fun cancel() {
        cancelJobOnly()
        chainNumber = null
        chainRemaining = 0
        chainHandleId = null
    }

    private fun cancelJobOnly() {
        job?.cancel()
        job = null
        _pending.value = null
    }

    private fun placeCall(context: Context, number: String, accountHandleId: String?) {
        try {
            val cleanNumber = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' || it == ',' || it == ';' }
            if (cleanNumber.isBlank()) return
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            val handle = accountHandleId?.let { id ->
                try {
                    telecom?.callCapablePhoneAccounts?.firstOrNull { it.id == id }
                } catch (_: Throwable) {
                    null
                }
            }
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(cleanNumber)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                handle?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            }
            lastPlacedNumber = number
            lastPlacedAt = System.currentTimeMillis()
            context.startActivity(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "Auto redial place-call failed", t)
        }
    }

    private const val TAG = "AutoRedial"
}
