package com.smartdialerpro.android.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.smartdialerpro.android.MainActivity
import com.smartdialerpro.android.R

/**
 * Writes a Magic (fake) call into the system call log so it looks
 * exactly like a real call in Recents, ported from the original
 * Dialar MagicLogHelper: backdated start time, cached contact
 * details, phone-account/SIM columns and a stripped-column retry.
 */
object FakeCallLogWriter {
    private const val TAG = "FakeCallLogWriter"
    private const val COL_PHONE_ACCOUNT_ADDRESS = "phone_account_address"
    private const val COL_SUB_ID = "sub_id"
    private const val COL_NUMBERLABEL = "numberlabel"

    fun addFakeLog(
        context: Context,
        number: String,
        startTimeMillis: Long,
        durationSecs: Int,
        forceSimSlot: Int,
        isMissed: Boolean
    ) {
        try {
            performInsert(context, number, startTimeMillis, durationSecs, forceSimSlot, isMissed)
        } catch (t: Throwable) {
            Log.e(TAG, "Fake log insert failed", t)
        }
    }

    private fun performInsert(
        context: Context,
        number: String,
        startTimeMillis: Long,
        durationSecs: Int,
        forceSimSlot: Int,
        isMissed: Boolean
    ) {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isDefaultDialer = context.packageName == telecomManager?.defaultDialerPackage
            val hasWritePerm = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED
            if (!isDefaultDialer && !hasWritePerm) {
                Toast.makeText(context, "Please make the app DEFAULT DIALER first to show logs globally!", Toast.LENGTH_LONG).show()
                return
            }
        }

        val values = ContentValues()
        values.put(CallLog.Calls.NUMBER, number)
        val callEndTime = System.currentTimeMillis()
        values.put(CallLog.Calls.DATE, callEndTime - (durationSecs * 1000L))
        values.put(CallLog.Calls.DURATION, durationSecs)
        values.put(CallLog.Calls.TYPE, if (isMissed) CallLog.Calls.MISSED_TYPE else CallLog.Calls.OUTGOING_TYPE)
        values.put(CallLog.Calls.NEW, if (isMissed) 1 else 0)
        values.put(CallLog.Calls.IS_READ, if (isMissed) 0 else 1)

        var countryIso: String? = null
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            countryIso = tm?.networkCountryIso?.takeIf { it.isNotEmpty() }
            if (countryIso != null) values.put(CallLog.Calls.COUNTRY_ISO, countryIso)
        } catch (_: Throwable) {
        }

        values.put("presentation", 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isMissed && durationSecs > 0) {
            val jitter = 0.85 + (Math.random() * 0.3)
            values.put(CallLog.Calls.DATA_USAGE, (durationSecs * 1600L * jitter).toLong())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            values.put(CallLog.Calls.FEATURES, CallLog.Calls.FEATURES_HD_CALL)
        }

        populateCachedContactDetails(context, values, number)
        populateNormalizedNumber(values, number, countryIso)

        if (telecomManager != null) {
            val hasPhoneState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            } else true
            if (hasPhoneState) {
                var handles: List<PhoneAccountHandle>? = null
                try {
                    handles = telecomManager.callCapablePhoneAccounts
                } catch (_: SecurityException) {
                }
                if (!handles.isNullOrEmpty()) {
                    var selectedHandle = handles[0]
                    if (forceSimSlot >= 0 && forceSimSlot < handles.size) selectedHandle = handles[forceSimSlot]
                    values.put(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, selectedHandle.componentName.flattenToString())
                    values.put(CallLog.Calls.PHONE_ACCOUNT_ID, selectedHandle.id)
                    try {
                        val account = telecomManager.getPhoneAccount(selectedHandle)
                        account?.address?.let { values.put(COL_PHONE_ACCOUNT_ADDRESS, it.toString()) }
                    } catch (_: Throwable) {
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        val subs = SubscriptionManager.from(context)?.activeSubscriptionInfoList
                        if (!subs.isNullOrEmpty()) {
                            val matched = subs.firstOrNull { it.simSlotIndex == forceSimSlot } ?: subs[0]
                            values.put(COL_SUB_ID, matched.subscriptionId)
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        try {
            context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
        } catch (t: Throwable) {
            Log.w(TAG, "Full-column insert failed, retrying with core set", t)
            try {
                values.remove(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME)
                values.remove(CallLog.Calls.PHONE_ACCOUNT_ID)
                values.remove(COL_PHONE_ACCOUNT_ADDRESS)
                values.remove(COL_SUB_ID)
                values.remove(COL_NUMBERLABEL)
                values.remove("presentation")
                values.remove(CallLog.Calls.DATA_USAGE)
                values.remove(CallLog.Calls.FEATURES)
                context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
            } catch (finalEx: Throwable) {
                Log.e(TAG, "Core-set insert failed", finalEx)
            }
        }
    }

    private fun populateCachedContactDetails(context: Context, values: ContentValues, phoneNumber: String) {
        if (phoneNumber.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        var cursor: Cursor? = null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.TYPE,
                    ContactsContract.PhoneLookup.LABEL,
                    ContactsContract.PhoneLookup.PHOTO_URI,
                    ContactsContract.PhoneLookup._ID,
                    ContactsContract.PhoneLookup.LOOKUP_KEY
                ),
                null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val name = cursor.getString(0)
                val type = cursor.getInt(1)
                val label = cursor.getString(2)
                val photoUri = cursor.getString(3)
                if (name != null) values.put(CallLog.Calls.CACHED_NAME, name)
                values.put(CallLog.Calls.CACHED_NUMBER_TYPE, type)
                if (label != null) {
                    values.put(CallLog.Calls.CACHED_NUMBER_LABEL, label)
                    values.put(COL_NUMBERLABEL, label)
                }
                if (photoUri != null) values.put(CallLog.Calls.CACHED_PHOTO_URI, photoUri)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Cached contact lookup failed", t)
        } finally {
            cursor?.close()
        }
    }

    private fun populateNormalizedNumber(values: ContentValues, number: String, countryIso: String?) {
        try {
            val e164 = PhoneNumberUtils.formatNumberToE164(number, countryIso)
            if (!e164.isNullOrBlank()) {
                values.put(CallLog.Calls.CACHED_NORMALIZED_NUMBER, e164)
            } else {
                values.put(CallLog.Calls.CACHED_NORMALIZED_NUMBER, number.filter { it.isDigit() || it == '+' })
            }
        } catch (_: Throwable) {
        }
    }

    /** Missed-call style notification for a fake call that never connected. */
    fun showFakeMissedCallNotification(context: Context, name: String, number: String) {
        try {
            val channelId = "missed_call_channel"
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(channelId, "Missed Calls", NotificationManager.IMPORTANCE_DEFAULT)
                manager?.createNotificationChannel(channel)
            }
            val openIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val displayName = name.takeIf { it.isNotBlank() } ?: number.ifBlank { "Unknown number" }
            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle("Missed call")
                .setContentText(displayName)
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            manager?.notify(1002, notification)
        } catch (t: Throwable) {
            Log.e(TAG, "Missed fake call notification failed", t)
        }
    }
}
