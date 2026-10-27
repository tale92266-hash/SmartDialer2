package com.smartdialerpro.android.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.smartdialerpro.android.data.RecentCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class CallLogRepository(private val context: Context) {
    companion object {
        private const val TAG = "CallLogRepository"
        private const val CACHE_FILE = "recent_calls_disk_cache.json"

        @Volatile
        private var memoryCache: List<RecentCall> = emptyList()
    }

    /**
     * Instant 0ms Cold Start Access: Returns cached calls from memory or disk
     * immediately before ContentResolver even starts querying.
     */
    fun getInitialCachedCalls(): List<RecentCall> {
        if (memoryCache.isNotEmpty()) return memoryCache
        val diskCalls = loadFromDisk()
        if (diskCalls.isNotEmpty()) {
            memoryCache = diskCalls
            return diskCalls
        }
        return emptyList()
    }

    suspend fun getRecentCalls(limit: Int = 100): List<RecentCall> = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CALL_LOG is not granted; recent calls are unavailable")
            return@withContext memoryCache
        }

        val contacts = mutableListOf<ContactLookup>()
        val exactContacts = HashMap<String, ContactLookup>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_URI
                )
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                    if (numberIdx >= 0) {
                        while (cursor.moveToNext()) {
                            val number = cursor.getString(numberIdx).orEmpty().trim()
                            if (number.isBlank()) continue
                            val item = ContactLookup(
                                number = number,
                                name = if (nameIdx >= 0) cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } else null,
                                photoUri = if (photoIdx >= 0) cursor.getString(photoIdx)?.takeIf { it.isNotBlank() } else null
                            )
                            contacts += item
                            val key = PhoneNumberUtils.normalizeNumber(number)
                            if (key.isNotBlank() && !exactContacts.containsKey(key)) exactContacts[key] = item
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to resolve contacts for call history", t)
            }
        }

        val telecom = context.getSystemService(TelecomManager::class.java)
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val subscriptions = try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                subscriptionManager?.activeSubscriptionInfoList.orEmpty()
            } else emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        val phoneAccountsById = try {
            telecom?.callCapablePhoneAccounts.orEmpty().mapNotNull { handle ->
                try { telecom?.getPhoneAccount(handle)?.let { handle.id to it } }
                catch (t: Throwable) { null }
            }.toMap()
        } catch (t: Throwable) {
            emptyMap()
        }

        val calls = mutableListOf<RecentCall>()
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID
        )

        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(CallLog.Calls._ID)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val accountIdx = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                if (numIdx < 0) return@withContext memoryCache

                while (cursor.moveToNext() && calls.size < limit.coerceAtLeast(1)) {
                    val number = cursor.getString(numIdx)?.trim().orEmpty()
                    if (number.isBlank()) continue
                    val accountId = if (accountIdx >= 0) cursor.getString(accountIdx) else null
                    val account = accountId?.let(phoneAccountsById::get)
                    val contact = findContact(number, exactContacts, contacts)
                    val resolvedName = if (nameIdx >= 0) cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } else null
                        ?: contact?.name

                    calls += RecentCall(
                        id = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else "${number}_${if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L}",
                        name = resolvedName,
                        number = number,
                        type = if (typeIdx >= 0) cursor.getInt(typeIdx) else -1,
                        date = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L,
                        duration = if (durIdx >= 0) cursor.getLong(durIdx).coerceAtLeast(0L) else 0L,
                        simSlot = resolveSlot(accountId, account, subscriptions),
                        photoUri = contact?.photoUri
                    )
                }
            }
        } catch (t: SecurityException) {
            Log.e(TAG, "Call-log access revoked while reading", t)
            return@withContext memoryCache
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to read call history", t)
            return@withContext memoryCache
        }

        val counts = calls.groupingBy { PhoneNumberUtils.normalizeNumber(it.number) }.eachCount()
        val missedCounts = calls.filter { it.type == CallLog.Calls.MISSED_TYPE }
            .groupingBy { PhoneNumberUtils.normalizeNumber(it.number) }.eachCount()

        val processedResult = calls.map { call ->
            val key = PhoneNumberUtils.normalizeNumber(call.number)
            call.copy(callCount = counts[key] ?: 1, missedCount = missedCounts[key] ?: 0)
        }

        // Cache permanently in RAM and asynchronously to disk
        memoryCache = processedResult
        saveToDisk(processedResult)

        processedResult
    }

    private data class ContactLookup(val number: String, val name: String?, val photoUri: String?)

    private fun findContact(number: String, exact: Map<String, ContactLookup>, contacts: List<ContactLookup>): ContactLookup? {
        val normalized = PhoneNumberUtils.normalizeNumber(number)
        exact[normalized]?.let { return it }
        return contacts.firstOrNull { PhoneNumberUtils.compare(it.number, number) }
    }

    private fun resolveSlot(accountId: String?, account: PhoneAccount?, subscriptions: List<SubscriptionInfo>): Int? {
        if (accountId.isNullOrBlank()) return null
        val matchingSub = subscriptions.firstOrNull { sub ->
            accountId == sub.subscriptionId.toString() ||
                accountId.contains(sub.subscriptionId.toString(), ignoreCase = true)
        }
        if (matchingSub != null && matchingSub.simSlotIndex >= 0) return matchingSub.simSlotIndex + 1

        val extras = account?.extras
        val explicitSlot = extras?.getInt("slot", -1) ?: -1
        if (explicitSlot >= 0) return explicitSlot + 1
        val slotId = extras?.getInt("slot_id", -1) ?: -1
        if (slotId >= 0) return slotId + 1

        return null
    }

    private fun loadFromDisk(): List<RecentCall> {
        return try {
            val file = File(context.cacheDir, CACHE_FILE)
            if (!file.exists()) return emptyList()
            val jsonStr = file.readText()
            if (jsonStr.isBlank()) return emptyList()
            val array = JSONArray(jsonStr)
            val list = ArrayList<RecentCall>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    RecentCall(
                        id = obj.optString("id"),
                        name = if (obj.has("name") && !obj.isNull("name")) obj.getString("name") else null,
                        number = obj.optString("number"),
                        type = obj.optInt("type"),
                        date = obj.optLong("date"),
                        duration = obj.optLong("duration"),
                        simSlot = if (obj.has("simSlot") && !obj.isNull("simSlot")) obj.getInt("simSlot") else null,
                        photoUri = if (obj.has("photoUri") && !obj.isNull("photoUri")) obj.getString("photoUri") else null,
                        callCount = obj.optInt("callCount", 1),
                        missedCount = obj.optInt("missedCount", 0)
                    )
                )
            }
            list
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read call log cache from disk", t)
            emptyList()
        }
    }

    private fun saveToDisk(calls: List<RecentCall>) {
        try {
            val array = JSONArray()
            for (call in calls.take(120)) {
                val obj = JSONObject().apply {
                    put("id", call.id)
                    put("name", call.name)
                    put("number", call.number)
                    put("type", call.type)
                    put("date", call.date)
                    put("duration", call.duration)
                    if (call.simSlot != null) put("simSlot", call.simSlot)
                    if (call.photoUri != null) put("photoUri", call.photoUri)
                    put("callCount", call.callCount)
                    put("missedCount", call.missedCount)
                }
                array.put(obj)
            }
            val file = File(context.cacheDir, CACHE_FILE)
            file.writeText(array.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write call log cache to disk", t)
        }
    }
}
