package com.smartdialerpro.android.repository

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.smartdialerpro.android.data.Contact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ContactsRepository(private val context: Context) {
    companion object {
        private const val TAG = "ContactsRepository"
        private const val CACHE_FILE = "contacts_disk_cache.json"

        @Volatile
        private var memoryCache: List<Contact> = emptyList()
    }

    /**
     * 0ms Instant Cold Start: Pehle RAM se, fir Disk Cache se turant contacts return karta hai.
     */
    fun getInitialCachedContacts(): List<Contact> {
        if (memoryCache.isNotEmpty()) return memoryCache
        val diskContacts = loadFromDisk()
        if (diskContacts.isNotEmpty()) {
            memoryCache = diskContacts
            return diskContacts
        }
        return emptyList()
    }

    suspend fun getContacts(force: Boolean = false): List<Contact> = withContext(Dispatchers.IO) {
        if (!force && memoryCache.isNotEmpty()) {
            return@withContext memoryCache
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_CONTACTS is not granted; contacts are unavailable")
            return@withContext memoryCache
        }

        val contacts = mutableListOf<Contact>()
        try {
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                ContactsContract.CommonDataKinds.Phone.STARRED
            )
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val normIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
                val starredIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED)
                if (numIdx < 0) return@withContext memoryCache

                while (cursor.moveToNext()) {
                    val number = cursor.getString(numIdx)?.trim().orEmpty()
                    if (number.isBlank()) continue
                    val contactId = if (idIdx >= 0) cursor.getString(idIdx).orEmpty() else ""
                    if (contactId.isBlank()) continue
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx).orEmpty() else ""
                    contacts += Contact(
                        id = contactId,
                        name = name,
                        number = number,
                        normalizedNumber = if (normIdx >= 0) cursor.getString(normIdx)?.takeIf { it.isNotBlank() }
                            ?: android.telephony.PhoneNumberUtils.normalizeNumber(number)
                        else android.telephony.PhoneNumberUtils.normalizeNumber(number),
                        photoUri = if (photoIdx >= 0) cursor.getString(photoIdx)?.takeIf { it.isNotBlank() } else null,
                        isFavorite = starredIdx >= 0 && cursor.getInt(starredIdx) == 1
                    )
                }
            }
        } catch (t: SecurityException) {
            Log.e(TAG, "Contacts permission revoked while reading", t)
            return@withContext memoryCache
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to read contacts", t)
            return@withContext memoryCache
        }

        val uniqueMap = LinkedHashMap<String, Contact>()
        for (c in contacts) {
            val digits = c.number.filter(Char::isDigit)
            val phoneKey = if (digits.length >= 7) digits.takeLast(10) else digits
            val dedupeKey = "${c.name.trim().lowercase()}_$phoneKey"
            if (!uniqueMap.containsKey(dedupeKey)) {
                uniqueMap[dedupeKey] = c
            } else if (c.isFavorite) {
                uniqueMap[dedupeKey] = c
            }
        }

        val result = uniqueMap.values.sortedWith(compareBy<Contact> { it.name.lowercase() }.thenBy { it.number })
        memoryCache = result
        saveToDisk(result)
        result
    }

    suspend fun setFavorite(contactId: String, favorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (contactId.isBlank()) return@withContext false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "WRITE_CONTACTS is not granted; favorite change rejected")
            return@withContext false
        }
        try {
            val values = ContentValues().apply {
                put(ContactsContract.Contacts.STARRED, if (favorite) 1 else 0)
            }
            val updated = context.contentResolver.update(
                ContactsContract.Contacts.CONTENT_URI,
                values,
                "${ContactsContract.Contacts._ID}=?",
                arrayOf(contactId)
            ) > 0
            if (updated) {
                memoryCache = emptyList()
            }
            updated
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to update favorite contact $contactId", t)
            false
        }
    }

    private fun loadFromDisk(): List<Contact> {
        return try {
            val file = File(context.cacheDir, CACHE_FILE)
            if (!file.exists()) return emptyList()
            val jsonStr = file.readText()
            if (jsonStr.isBlank()) return emptyList()
            val array = JSONArray(jsonStr)
            val list = ArrayList<Contact>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Contact(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        number = obj.optString("number"),
                        normalizedNumber = obj.optString("normalizedNumber"),
                        photoUri = if (obj.has("photoUri") && !obj.isNull("photoUri")) obj.getString("photoUri") else null,
                        isFavorite = obj.optBoolean("isFavorite", false)
                    )
                )
            }
            list
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read contacts cache from disk", t)
            emptyList()
        }
    }

    private fun saveToDisk(contacts: List<Contact>) {
        try {
            val array = JSONArray()
            for (c in contacts) {
                val obj = JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("number", c.number)
                    put("normalizedNumber", c.normalizedNumber)
                    if (c.photoUri != null) put("photoUri", c.photoUri)
                    put("isFavorite", c.isFavorite)
                }
                array.put(obj)
            }
            val file = File(context.cacheDir, CACHE_FILE)
            file.writeText(array.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to write contacts cache to disk", t)
        }
    }
}
