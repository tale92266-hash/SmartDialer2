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
import com.smartdialerpro.android.data.CnapStore

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
    /** Raw CallLog column for why a call was missed (no public constant in this SDK). */
    private const val COL_MISSED_REASON = "missed_reason"
    /** missed_reason value: the call rang but was not answered. */
    private const val MISSED_REASON_NOT_ANSWERED = 1

    /**
     * Returns true when the entry is confirmed in the system call log
     * (insert reported success and a matching row reads back). A false
     * result means the entry did not land and the caller should tell
     * the user — fake logs must never fail silently.
     */
    fun addFakeLog(
        context: Context,
        number: String,
        startTimeMillis: Long,
        durationSecs: Int,
        forceSimSlot: Int,
        isMissed: Boolean
    ): Boolean {
        return try {
            performInsert(context, number, startTimeMillis, durationSecs, forceSimSlot, isMissed)
        } catch (t: Throwable) {
            Log.e(TAG, "Fake log insert failed", t)
            false
        }
    }

    private fun performInsert(
        context: Context,
        number: String,
        startTimeMillis: Long,
        durationSecs: Int,
        forceSimSlot: Int,
        isMissed: Boolean
    ): Boolean {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val isDefaultDialer = context.packageName == telecomManager?.defaultDialerPackage
            val hasWritePerm = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALL_LOG) == PackageManager.PERMISSION_GRANTED
            if (!isDefaultDialer && !hasWritePerm) {
                Toast.makeText(context, "Please make the app DEFAULT DIALER first to show logs globally!", Toast.LENGTH_LONG).show()
                saveDiag(context, "blocked: not default dialer and no WRITE_CALL_LOG")
                return false
            }
        }

        val values = ContentValues()
        values.put(CallLog.Calls.NUMBER, number)
        val callEndTime = System.currentTimeMillis()
        val expectedDate = callEndTime - (durationSecs * 1000L)
        values.put(CallLog.Calls.DATE, expectedDate)
        values.put(CallLog.Calls.DURATION, durationSecs)
        values.put(CallLog.Calls.TYPE, if (isMissed) CallLog.Calls.MISSED_TYPE else CallLog.Calls.OUTGOING_TYPE)
        values.put(CallLog.Calls.NEW, if (isMissed) 1 else 0)
        values.put(CallLog.Calls.IS_READ, if (isMissed) 0 else 1)
        if (isMissed) {
            // A real missed row carries why it was missed; the retry
            // ladder strips this exotic column if the provider rejects it.
            values.put(COL_MISSED_REASON, MISSED_REASON_NOT_ANSWERED)
        }

        var countryIso: String? = null
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            countryIso = tm?.networkCountryIso?.takeIf { it.isNotEmpty() }
            if (countryIso != null) values.put(CallLog.Calls.COUNTRY_ISO, countryIso)
        } catch (_: Throwable) {
        }

        values.put("presentation", 1)
        deriveGeocodedLocation(number, countryIso)?.let {
            values.put(CallLog.Calls.GEOCODED_LOCATION, it)
        }
        // Post-dial digits: anything after a pause (,) or wait (;) in
        // the dialled number, exactly as a real dialled row stores it.
        val postDial = number.substringAfter(',', "").substringAfter(';', "")
            .filter { it.isDigit() || it == '*' || it == '#' || it == '+' }
        if (postDial.isNotBlank()) {
            values.put(CallLog.Calls.POST_DIAL_DIGITS, postDial)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isMissed && durationSecs > 0) {
            val jitter = 0.85 + (Math.random() * 0.3)
            values.put(CallLog.Calls.DATA_USAGE, (durationSecs * 1600L * jitter).toLong())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            values.put(CallLog.Calls.FEATURES, CallLog.Calls.FEATURES_HD_CALL)
        }

        populateCachedContactDetails(context, values, number)
        populateNormalizedNumber(values, number)
        populateExtraNumberColumns(values, number, countryIso)

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
                    // Match the handle to the requested SIM by
                    // subscription/extras — the order of call-capable
                    // accounts is not guaranteed to follow slot order.
                    val selectedHandle = selectPhoneAccountHandle(handles, forceSimSlot) ?: handles[0]
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

        if (!insertRow(context, values)) {
            saveDiag(context, "inserted=false (all retries failed)")
            return false
        }
        val verified = verifyRow(context, number, expectedDate, durationSecs)
        saveDiag(
            context,
            "attempt=$lastInsertAttempt errors=[$lastInsertErrors] inserted=true verified=$verified account=${lastVerifyAccountId ?: "none"}"
        )
        return verified
    }

    @Volatile
    private var lastVerifyAccountId: String? = null

    private fun saveDiag(context: Context, text: String) {
        try {
            context.getSharedPreferences("dialer_preferences", Context.MODE_PRIVATE)
                .edit()
                .putString("fake_log_last_write", text)
                .apply()
        } catch (_: Throwable) {
        }
    }

    /**
     * Picks the PhoneAccountHandle for the requested SIM slot by pure
     * list index — exactly like the original Dialar code
     * (handles[forceSimSlot]). Any smarter matching (extras, address,
     * id-contains) can pick a different handle than the read path
     * resolves, so the written slot and the displayed slot disagree.
     */
    private fun selectPhoneAccountHandle(
        handles: List<PhoneAccountHandle>,
        forceSimSlot: Int
    ): PhoneAccountHandle? {
        return if (forceSimSlot in handles.indices) handles[forceSimSlot] else handles.firstOrNull()
    }

    /**
     * Insert, retrying with fewer columns when the provider rejects one.
     * The ladder is ordered so the SIM / phone-account columns survive
     * as long as possible: the raw-string columns (`sub_id`,
     * `phone_account_address`) are the usual poison on OEM providers —
     * this phone's provider has no `sub_id` column at all, so an insert
     * carrying it throws and the old ladder only succeeded once the
     * account columns had been stripped with it, landing rows with a
     * null account id. Each step records its failure so Diagnostics
     * shows exactly which column family the provider rejected.
     */
    private var lastInsertAttempt = -1
    private var lastInsertErrors = ""

    private fun insertRow(context: Context, values: ContentValues): Boolean {
        val working = ContentValues(values)
        val errors = StringBuilder()
        fun tryInsert(attempt: Int): Boolean {
            return try {
                val uri = context.contentResolver.insert(CallLog.Calls.CONTENT_URI, working)
                if (uri != null) {
                    lastInsertAttempt = attempt
                    true
                } else {
                    errors.append("$attempt:null; ")
                    false
                }
            } catch (t: Throwable) {
                errors.append("$attempt:${(t.message ?: t.javaClass.simpleName).take(50)}; ")
                Log.w(TAG, "Insert attempt $attempt failed", t)
                false
            }
        }
        // 1: full set, exactly as a real call row carries it.
        if (tryInsert(1)) {
            lastInsertErrors = errors.toString()
            return true
        }
        // 2: drop only the raw-string account-adjacent columns; KEEP
        // the account id/component and everything else.
        working.remove(COL_SUB_ID)
        working.remove(COL_PHONE_ACCOUNT_ADDRESS)
        if (tryInsert(2)) {
            lastInsertErrors = errors.toString()
            return true
        }
        // 3: drop the exotic columns, still keeping the SIM columns.
        working.remove(CallLog.Calls.GEOCODED_LOCATION)
        working.remove(CallLog.Calls.DATA_USAGE)
        working.remove(CallLog.Calls.FEATURES)
        working.remove("presentation")
        working.remove(COL_NUMBERLABEL)
        working.remove(CallLog.Calls.POST_DIAL_DIGITS)
        working.remove(COL_MISSED_REASON)
        if (tryInsert(3)) {
            lastInsertErrors = errors.toString()
            return true
        }
        // 4: drop the cached-contact extras, still keeping the SIM columns.
        working.remove(CallLog.Calls.CACHED_NAME)
        working.remove(CallLog.Calls.CACHED_NUMBER_TYPE)
        working.remove(CallLog.Calls.CACHED_NUMBER_LABEL)
        working.remove(CallLog.Calls.CACHED_PHOTO_URI)
        working.remove(CallLog.Calls.CACHED_PHOTO_ID)
        working.remove(CallLog.Calls.CACHED_LOOKUP_URI)
        working.remove(CallLog.Calls.CACHED_MATCHED_NUMBER)
        working.remove(CallLog.Calls.CACHED_FORMATTED_NUMBER)
        working.remove(CallLog.Calls.CACHED_NORMALIZED_NUMBER)
        if (tryInsert(4)) {
            lastInsertErrors = errors.toString()
            return true
        }
        // 5: core columns + SIM columns only.
        val coreWithAccount = ContentValues().apply {
            put(CallLog.Calls.NUMBER, values.getAsString(CallLog.Calls.NUMBER))
            put(CallLog.Calls.DATE, values.getAsLong(CallLog.Calls.DATE))
            put(CallLog.Calls.DURATION, values.getAsInteger(CallLog.Calls.DURATION))
            put(CallLog.Calls.TYPE, values.getAsInteger(CallLog.Calls.TYPE))
            put(CallLog.Calls.NEW, values.getAsInteger(CallLog.Calls.NEW))
            put(CallLog.Calls.IS_READ, values.getAsInteger(CallLog.Calls.IS_READ))
            values.getAsString(CallLog.Calls.COUNTRY_ISO)?.let { put(CallLog.Calls.COUNTRY_ISO, it) }
            values.getAsString(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME)?.let { put(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, it) }
            values.getAsString(CallLog.Calls.PHONE_ACCOUNT_ID)?.let { put(CallLog.Calls.PHONE_ACCOUNT_ID, it) }
        }
        working.clear()
        working.putAll(coreWithAccount)
        if (tryInsert(5)) {
            lastInsertErrors = errors.toString()
            return true
        }
        // 6: bare minimal, last resort — SIM info is sacrificed here.
        lastInsertErrors = errors.toString()
        return try {
            val minimal = ContentValues().apply {
                put(CallLog.Calls.NUMBER, values.getAsString(CallLog.Calls.NUMBER))
                put(CallLog.Calls.DATE, values.getAsLong(CallLog.Calls.DATE))
                put(CallLog.Calls.DURATION, values.getAsInteger(CallLog.Calls.DURATION))
                put(CallLog.Calls.TYPE, values.getAsInteger(CallLog.Calls.TYPE))
                put(CallLog.Calls.NEW, values.getAsInteger(CallLog.Calls.NEW))
                put(CallLog.Calls.IS_READ, values.getAsInteger(CallLog.Calls.IS_READ))
            }
            context.contentResolver.insert(CallLog.Calls.CONTENT_URI, minimal) != null
        } catch (t: Throwable) {
            Log.e(TAG, "Minimal insert failed", t)
            false
        }
    }

    /** Read back the newest row for this number and confirm date/duration match. */
    private fun verifyRow(context: Context, number: String, expectedDate: Long, durationSecs: Int): Boolean {
        return try {
            var found = false
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.PHONE_ACCOUNT_ID),
                "${CallLog.Calls.NUMBER} = ?",
                arrayOf(number),
                "${CallLog.Calls.DATE} DESC LIMIT 1"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val date = cursor.getLong(0)
                    val duration = cursor.getInt(1)
                    lastVerifyAccountId = if (!cursor.isNull(2)) cursor.getString(2) else null
                    found = duration == durationSecs && kotlin.math.abs(date - expectedDate) <= 3000L
                    if (found && cursor.isNull(2)) {
                        Log.w(TAG, "Call-log row verified but has no phone-account id — SIM will show blank")
                    }
                }
            }
            if (!found) Log.w(TAG, "Inserted row did not read back for $number")
            found
        } catch (t: Throwable) {
            // Cannot read the log (e.g. READ_CALL_LOG not granted): the
            // insert itself reported success, so do not call it a failure.
            Log.w(TAG, "Call-log verification unavailable", t)
            true
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
                val contactId = cursor.getLong(4)
                val lookupKey = cursor.getString(5)
                if (name != null) values.put(CallLog.Calls.CACHED_NAME, name)
                values.put(CallLog.Calls.CACHED_NUMBER_TYPE, type)
                if (label != null) {
                    values.put(CallLog.Calls.CACHED_NUMBER_LABEL, label)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        values.put(COL_NUMBERLABEL, label)
                    }
                }
                if (photoUri != null) values.put(CallLog.Calls.CACHED_PHOTO_URI, photoUri)
                val photoIdIdx = cursor.getColumnIndex("photo_id")
                if (photoIdIdx >= 0 && !cursor.isNull(photoIdIdx)) {
                    values.put(CallLog.Calls.CACHED_PHOTO_ID, cursor.getLong(photoIdIdx))
                }
                if (lookupKey != null) {
                    try {
                        val lookupUri = ContactsContract.Contacts.getLookupUri(contactId, lookupKey)
                        if (lookupUri != null) {
                            values.put(CallLog.Calls.CACHED_LOOKUP_URI, lookupUri.toString())
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Cached contact lookup failed", t)
        } finally {
            cursor?.close()
        }
        // CNAP fallback: no saved contact — cache the network-delivered
        // name so the system dialer shows it too.
        if (!values.containsKey(CallLog.Calls.CACHED_NAME)) {
            try {
                CnapStore.ensureInit(context)
                CnapStore.getName(phoneNumber)?.let { values.put(CallLog.Calls.CACHED_NAME, it) }
            } catch (_: Throwable) {
            }
        }
    }

    private fun populateNormalizedNumber(values: ContentValues, number: String) {
        try {
            val normalized = PhoneNumberUtils.normalizeNumber(number)
            if (!normalized.isNullOrBlank()) {
                values.put(CallLog.Calls.CACHED_NORMALIZED_NUMBER, normalized)
            }
        } catch (_: Throwable) {
        }
    }

    private fun populateExtraNumberColumns(values: ContentValues, number: String, countryIso: String?) {
        try {
            values.put(CallLog.Calls.CACHED_MATCHED_NUMBER, number)
            val iso = countryIso?.takeIf { it.isNotEmpty() }?.uppercase(java.util.Locale.US)
            var formatted: String? = null
            try {
                formatted = PhoneNumberUtils.formatNumber(number, iso)
            } catch (_: Throwable) {
            }
            if (!formatted.isNullOrBlank()) {
                values.put(CallLog.Calls.CACHED_FORMATTED_NUMBER, formatted)
            }
        } catch (_: Throwable) {
        }
    }

    /** Geocoded location, ported from the original: India mobiles get the country, STD codes get their city. */
    private fun deriveGeocodedLocation(phoneNumber: String, countryIso: String?): String? {
        return try {
            val iso = countryIso?.takeIf { it.isNotEmpty() }?.uppercase(java.util.Locale.US) ?: ""
            var countryName = ""
            try {
                if (iso.isNotEmpty()) {
                    countryName = java.util.Locale("", iso).getDisplayCountry(java.util.Locale.ENGLISH)
                }
            } catch (_: Throwable) {
            }
            if (phoneNumber.isNotEmpty() && iso == "IN") {
                var digits = phoneNumber.filter { it.isDigit() }
                if (digits.startsWith("91") && digits.length > 10) digits = digits.substring(2)
                else if (digits.startsWith("0") && digits.length > 10) digits = digits.substring(1)
                if (digits.length == 10 && "6789".contains(digits[0])) {
                    return countryName.ifEmpty { null }
                }
                val city = matchIndianStdCode(digits)
                if (city != null) {
                    return if (countryName.isEmpty()) city else "$city, $countryName"
                }
            }
            countryName.ifEmpty { null }
        } catch (_: Throwable) {
            null
        }
    }

    private fun matchIndianStdCode(digits: String): String? {
        val codes = arrayOf(
            "120" to "Noida", "124" to "Gurgaon", "129" to "Faridabad",
            "141" to "Jaipur", "172" to "Chandigarh", "161" to "Ludhiana",
            "11" to "Delhi", "22" to "Mumbai", "33" to "Kolkata",
            "44" to "Chennai", "40" to "Hyderabad", "80" to "Bengaluru",
            "79" to "Ahmedabad", "20" to "Pune"
        )
        for ((code, city) in codes) {
            if (digits.startsWith(code) && digits.length > code.length) return city
        }
        return null
    }

    /**
     * Real calls update the saved contact's TIMES_CONTACTED and
     * LAST_TIME_CONTACTED in the Contacts provider; fake calls must
     * do the same so frequency/last-contacted data matches reality.
     * Silent no-op without WRITE_CONTACTS or when the number is not
     * a saved contact.
     */
    fun noteContactCalled(context: Context, number: String) {
        try {
            if (number.isBlank()) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
            var contactId: Long? = null
            var timesContacted = 0
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) contactId = cursor.getLong(0)
            }
            if (contactId == null) return
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.TIMES_CONTACTED),
                "${ContactsContract.Contacts._ID} = ?",
                arrayOf(contactId.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) timesContacted = cursor.getInt(0)
            }
            val values = ContentValues().apply {
                put(ContactsContract.Contacts.TIMES_CONTACTED, timesContacted + 1)
                put(ContactsContract.Contacts.LAST_TIME_CONTACTED, System.currentTimeMillis())
            }
            context.contentResolver.update(
                ContactsContract.Contacts.CONTENT_URI,
                values,
                "${ContactsContract.Contacts._ID} = ?",
                arrayOf(contactId.toString())
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Contact stats update failed", t)
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
            // Same actions a real dialer's missed-call notification has:
            // call the number back, or open the messaging app for it.
            val callBackIntent = PendingIntent.getActivity(
                context,
                1,
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val messageIntent = PendingIntent.getActivity(
                context,
                2,
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
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
                .setNumber(1)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                .addAction(R.drawable.ic_call, "Call back", callBackIntent)
                .addAction(R.drawable.ic_message, "Message", messageIntent)
                .build()
            manager?.notify(1002, notification)
        } catch (t: Throwable) {
            Log.e(TAG, "Missed fake call notification failed", t)
        }
    }
}
