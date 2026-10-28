package com.smartdialerpro.android.service

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallResponse
import android.telecom.CallScreeningService
import android.util.Log

/**
 * "Silence unknown callers": when the setting is on, incoming calls
 * from numbers that are not in the user's contacts are rejected
 * silently — they still land in the call log as missed calls, the
 * phone just never rings. Known contacts and empty/private numbers
 * the user chose to allow always ring through.
 *
 * The system only delivers calls here when the user grants this app
 * the Caller ID & spam role; without the role this service simply
 * never runs and nothing changes.
 */
class SmartDialerScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        try {
            if (!isSilenceUnknownEnabled()) {
                respondToCall(allowCall())
                return
            }
            val number = callDetails.handle?.schemeSpecificPart.orEmpty()
            if (number.isBlank()) {
                // Withheld/private number: treat as unknown and silence.
                respondToCall(silenceCall())
                return
            }
            if (isNumberInContacts(number)) {
                respondToCall(allowCall())
            } else {
                respondToCall(silenceCall())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Screening failed, allowing call", t)
            try {
                respondToCall(allowCall())
            } catch (_: Throwable) {
            }
        }
    }

    private fun isSilenceUnknownEnabled(): Boolean {
        return try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SILENCE_UNKNOWN, false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun isNumberInContacts(number: String): Boolean {
        var cursor: android.database.Cursor? = null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            cursor = contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )
            cursor != null && cursor.moveToFirst()
        } catch (_: Throwable) {
            // On any lookup failure, err on the side of ringing.
            true
        } finally {
            try {
                cursor?.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun allowCall(): CallResponse =
        CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

    private fun silenceCall(): CallResponse =
        CallResponse.Builder()
            .setDisallowCall(true)
            .setRejectCall(true)
            .setSkipCallLog(false)
            .setSkipNotification(false)
            .build()

    companion object {
        private const val TAG = "ScreeningService"
        const val PREFS = "app_prefs"
        const val KEY_SILENCE_UNKNOWN = "silence_unknown_callers"
    }
}
