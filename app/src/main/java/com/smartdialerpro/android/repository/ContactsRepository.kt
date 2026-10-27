package com.smartdialerpro.android.repository

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import com.smartdialerpro.android.data.Contact
import com.smartdialerpro.android.data.ContactDetails
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

    private fun hasWriteContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun clearCache() {
        memoryCache = emptyList()
    }

    /** Creates a new contact in the system contacts provider. Returns false when the write fails. */
    suspend fun createContact(name: String, phones: List<String>, emails: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (name.isBlank() || phones.none { it.isNotBlank() }) return@withContext false
        if (!hasWriteContactsPermission()) {
            Log.w(TAG, "WRITE_CONTACTS is not granted; contact creation rejected")
            return@withContext false
        }
        try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name.trim())
                    .build()
            )
            for (phone in phones) {
                if (phone.isBlank()) continue
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.trim())
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                )
            }
            for (email in emails) {
                if (email.isBlank()) continue
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.trim())
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_HOME)
                        .build()
                )
            }
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            clearCache()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to create contact", t)
            false
        }
    }

    /** Full name, phone numbers and email addresses for one contact, for the in-app editor. */
    suspend fun getContactDetails(contactId: String): ContactDetails? = withContext(Dispatchers.IO) {
        if (contactId.isBlank()) return@withContext null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return@withContext null
        }
        try {
            var name = ""
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                "${ContactsContract.Contacts._ID}=?",
                arrayOf(contactId),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) name = cursor.getString(0).orEmpty()
            }
            val phones = queryContactDataValues(
                contactId,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val emails = queryContactDataValues(
                contactId,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.ADDRESS
            )
            ContactDetails(contactId = contactId, name = name, phones = phones, emails = emails)
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to read contact details for $contactId", t)
            null
        }
    }

    private fun queryContactDataValues(contactId: String, mimeType: String, column: String): List<String> {
        val values = ArrayList<String>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(column),
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId, mimeType),
            "${ContactsContract.Data._ID} ASC"
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(column)
            if (idx < 0) return values
            while (cursor.moveToNext()) {
                val value = cursor.getString(idx)?.trim().orEmpty()
                if (value.isNotBlank() && !values.contains(value)) values.add(value)
            }
        }
        return values
    }

    /** Updates name, phones and emails in place. Rows beyond the supplied lists are removed. */
    suspend fun updateContact(contactId: String, name: String, phones: List<String>, emails: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (contactId.isBlank() || name.isBlank() || phones.none { it.isNotBlank() }) return@withContext false
        if (!hasWriteContactsPermission()) {
            Log.w(TAG, "WRITE_CONTACTS is not granted; contact update rejected")
            return@withContext false
        }
        try {
            val rawIds = ArrayList<String>()
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID}=?",
                arrayOf(contactId),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0).orEmpty()
                    if (id.isNotBlank()) rawIds.add(id)
                }
            }
            if (rawIds.isEmpty()) return@withContext false

            val nameValues = ContentValues().apply {
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name.trim())
            }
            val updatedNameRows = context.contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                nameValues,
                "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            )
            if (updatedNameRows == 0) {
                val values = ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawIds.first().toLong())
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name.trim())
                }
                context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, values)
            }

            val cleanPhones = phones.map { it.trim() }.filter { it.isNotBlank() }
            replaceContactDataRows(
                contactId = contactId,
                rawContactId = rawIds.first(),
                mimeType = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                valueColumn = ContactsContract.CommonDataKinds.Phone.NUMBER,
                typeColumn = ContactsContract.CommonDataKinds.Phone.TYPE,
                typeValue = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                values = cleanPhones
            )
            val cleanEmails = emails.map { it.trim() }.filter { it.isNotBlank() }
            replaceContactDataRows(
                contactId = contactId,
                rawContactId = rawIds.first(),
                mimeType = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                valueColumn = ContactsContract.CommonDataKinds.Email.ADDRESS,
                typeColumn = ContactsContract.CommonDataKinds.Email.TYPE,
                typeValue = ContactsContract.CommonDataKinds.Email.TYPE_HOME,
                values = cleanEmails
            )
            clearCache()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to update contact $contactId", t)
            false
        }
    }

    private fun replaceContactDataRows(
        contactId: String,
        rawContactId: String,
        mimeType: String,
        valueColumn: String,
        typeColumn: String,
        typeValue: Int,
        values: List<String>
    ) {
        val rowIds = ArrayList<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?",
            arrayOf(contactId, mimeType),
            "${ContactsContract.Data._ID} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) rowIds.add(cursor.getLong(0))
        }
        values.forEachIndexed { index, value ->
            val contentValues = ContentValues().apply {
                put(valueColumn, value)
                put(typeColumn, typeValue)
            }
            if (index < rowIds.size) {
                context.contentResolver.update(
                    ContactsContract.Data.CONTENT_URI,
                    contentValues,
                    "${ContactsContract.Data._ID}=?",
                    arrayOf(rowIds[index].toString())
                )
            } else {
                contentValues.apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId.toLong())
                    put(ContactsContract.Data.MIMETYPE, mimeType)
                }
                context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, contentValues)
            }
        }
        if (rowIds.size > values.size) {
            for (index in values.size until rowIds.size) {
                context.contentResolver.delete(
                    ContactsContract.Data.CONTENT_URI,
                    "${ContactsContract.Data._ID}=?",
                    arrayOf(rowIds[index].toString())
                )
            }
        }
    }

    /** Deletes one contact (all of its raw contacts) from the system contacts provider. */
    suspend fun deleteContact(contactId: String): Boolean = withContext(Dispatchers.IO) {
        if (contactId.isBlank()) return@withContext false
        if (!hasWriteContactsPermission()) {
            Log.w(TAG, "WRITE_CONTACTS is not granted; contact deletion rejected")
            return@withContext false
        }
        try {
            val deleted = context.contentResolver.delete(
                ContactsContract.Contacts.CONTENT_URI,
                "${ContactsContract.Contacts._ID}=?",
                arrayOf(contactId)
            ) > 0
            if (deleted) clearCache()
            deleted
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to delete contact $contactId", t)
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
