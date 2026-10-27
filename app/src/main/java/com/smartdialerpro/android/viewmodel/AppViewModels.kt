package com.smartdialerpro.android.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.smartdialerpro.android.data.Contact
import com.smartdialerpro.android.data.RecentCall
import com.smartdialerpro.android.data.SimInfo
import com.smartdialerpro.android.repository.CallLogRepository
import com.smartdialerpro.android.repository.ContactsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactsViewModel(private val repository: ContactsRepository) : ViewModel() {
    private val _contacts = MutableStateFlow<List<Contact>>(repository.getInitialCachedContacts())
    val contacts = _contacts.asStateFlow()
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private var isFetching = false

    fun loadContacts(force: Boolean = false) {
        if (isFetching && !force) return
        if (_contacts.value.isNotEmpty() && !force) return
        isFetching = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = repository.getContacts(force)
                withContext(Dispatchers.Main) {
                    _contacts.value = res
                }
            } finally {
                isFetching = false
            }
        }
    }

    fun updateSearchQuery(query: String) { _searchQuery.value = query }

    fun toggleFavorite(contact: Contact) {
        viewModelScope.launch {
            if (repository.setFavorite(contact.id, !contact.isFavorite)) {
                loadContacts(force = true)
            }
        }
    }
}

class RecentsViewModel(private val repository: CallLogRepository) : ViewModel() {
    private val _recentCalls = MutableStateFlow<List<RecentCall>>(repository.getInitialCachedCalls())
    val recentCalls = _recentCalls.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _filter = MutableStateFlow(RecentFilter.ALL)
    val filter = _filter.asStateFlow()

    private var isFetching = false
    private var lastLoadAtMs = 0L
    private var callLogObserver: ContentObserver? = null

    init {
        loadRecents(force = false)
    }

    fun startListeningForCallLogChanges(context: Context) {
        if (callLogObserver != null) return
        try {
            callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    loadRecents(force = true)
                }
            }
            context.applicationContext.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                callLogObserver!!
            )
        } catch (t: Throwable) {
            Log.w("RecentsViewModel", "Failed to register CallLog observer", t)
        }
    }

    fun loadRecents(force: Boolean = false) {
        if (isFetching && !force) return
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastLoadAtMs < 3000L) return
            lastLoadAtMs = now
        }
        isFetching = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fresh = repository.getRecentCalls(150)
                if (fresh.isNotEmpty() || _recentCalls.value.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _recentCalls.value = fresh
                    }
                }
            } finally {
                isFetching = false
            }
        }
    }

    fun updateSearchQuery(query: String) { _searchQuery.value = query }
    fun setFilter(value: RecentFilter) { _filter.value = value }

    fun filteredCalls(): List<RecentCall> {
        val query = _searchQuery.value.trim()
        val digits = query.filter(Char::isDigit)
        return _recentCalls.value.filter { call ->
            val typeMatches = when (_filter.value) {
                RecentFilter.ALL -> true
                RecentFilter.MISSED -> call.type == CallLog.Calls.MISSED_TYPE
                RecentFilter.INCOMING -> call.type == CallLog.Calls.INCOMING_TYPE
                RecentFilter.OUTGOING -> call.type == CallLog.Calls.OUTGOING_TYPE
            }
            if (!typeMatches) return@filter false
            if (query.isBlank()) true else {
                call.name?.contains(query, true) == true || call.number.contains(query, true) ||
                    (digits.isNotBlank() && call.number.filter(Char::isDigit).contains(digits))
            }
        }
    }
}

enum class RecentFilter { ALL, MISSED, INCOMING, OUTGOING }

data class IndexedSearchEntry(
    val call: RecentCall,
    val cleanDigits: String,
    val t9Name: String,
    val nameLower: String,
    val dedupeKey: String,
    val hasHistory: Boolean
)

class DialerViewModel(
    private val contactsRepo: ContactsRepository,
    private val recentsRepo: CallLogRepository
) : ViewModel() {
    companion object {
        private const val TAG = "DialerViewModel"
        private const val PREFS = "dialer_preferences"
        private const val KEY_ACCOUNT_ID = "selected_phone_account_id"
        private const val KEY_SELECTED_SIM_SLOT = "selected_sim_slot"
    }

    private var allIndexedEntries: List<IndexedSearchEntry> = emptyList()

    private val _searchUnifiedMatches = MutableStateFlow<List<RecentCall>>(emptyList())
    val searchUnifiedMatches = _searchUnifiedMatches.asStateFlow()

    val searchRecentMatches = _searchUnifiedMatches.asStateFlow()
    private val _searchContactMatches = MutableStateFlow<List<Contact>>(emptyList())
    val searchContactMatches = _searchContactMatches.asStateFlow()
    val matchedContacts = _searchContactMatches

    private val _availableSims = MutableStateFlow<List<SimInfo>>(emptyList())
    val availableSims = _availableSims.asStateFlow()

    private var isIndexing = false

    private val searchFlow = MutableSharedFlow<String>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        viewModelScope.launch(Dispatchers.Default) {
            searchFlow
                .distinctUntilChanged()
                .debounce(150L)
                .collect { query ->
                    executeSearch(query)
                }
        }
    }

    private fun normalizeToDedupeKey(number: String): String {
        val digits = number.filter(Char::isDigit)
        return when {
            digits.length >= 10 -> digits.takeLast(10)
            digits.length >= 7 -> digits.takeLast(7)
            else -> digits
        }
    }

    fun loadDataForMatching(force: Boolean = false) {
        if (isIndexing && !force) return
        isIndexing = true
        viewModelScope.launch(Dispatchers.IO) {
            val contacts = contactsRepo.getContacts(force)
            val recents = recentsRepo.getRecentCalls(250)

            // Index contacts by clean normalized key
            val contactMap = HashMap<String, Contact>()
            for (c in contacts) {
                val key = normalizeToDedupeKey(c.number)
                if (key.isNotBlank() && !contactMap.containsKey(key)) {
                    contactMap[key] = c
                }
            }

            // 1. Process all call logs (Keep most recent entry per person)
            val latestCallsMap = LinkedHashMap<String, RecentCall>()
            for (r in recents) {
                val key = normalizeToDedupeKey(r.number)
                if (key.isNotBlank() && !latestCallsMap.containsKey(key)) {
                    val contactMatch = contactMap[key]
                    val effectiveName = r.name?.takeIf { it.isNotBlank() } ?: contactMatch?.name
                    val effectivePhoto = r.photoUri?.takeIf { it.isNotBlank() } ?: contactMatch?.photoUri
                    latestCallsMap[key] = r.copy(name = effectiveName, photoUri = effectivePhoto)
                }
            }

            val entries = ArrayList<IndexedSearchEntry>(latestCallsMap.size + contacts.size)
            val seenNumbersInLogs = HashSet<String>()

            // Add all real Call Logs (Strictly Priority 1)
            for ((key, call) in latestCallsMap) {
                seenNumbersInLogs.add(key)
                val digits = call.number.filter(Char::isDigit)
                val name = call.name.orEmpty().trim()
                entries.add(
                    IndexedSearchEntry(
                        call = call,
                        cleanDigits = digits,
                        t9Name = computeT9(name),
                        nameLower = name.lowercase(),
                        dedupeKey = key,
                        hasHistory = true
                    )
                )
            }

            // 2. Add Saved Contacts ONLY IF they never had a call in the logs
            for (c in contacts) {
                val key = normalizeToDedupeKey(c.number)
                if (key.isNotBlank() && !seenNumbersInLogs.contains(key)) {
                    seenNumbersInLogs.add(key) // Ensure no duplicate contact cards
                    val name = c.name.trim()
                    val digits = c.number.filter(Char::isDigit)
                    entries.add(
                        IndexedSearchEntry(
                            call = RecentCall(
                                id = "contact_${c.id}_${c.number}",
                                name = name,
                                number = c.number,
                                type = -1,
                                date = 0L,
                                duration = 0L,
                                photoUri = c.photoUri,
                                callCount = 0,
                                missedCount = 0
                            ),
                            cleanDigits = digits,
                            t9Name = computeT9(name),
                            nameLower = name.lowercase(),
                            dedupeKey = key,
                            hasHistory = false
                        )
                    )
                }
            }

            allIndexedEntries = entries
            isIndexing = false
        }
    }

    fun loadContactsForMatching(force: Boolean = false) = loadDataForMatching(force)
    fun loadRecentsForMatching(force: Boolean = false) = loadDataForMatching(force)

    fun findMatch(query: String) {
        searchFlow.tryEmit(query)
    }

    private fun executeSearch(query: String) {
        val raw = query.trim()
        if (raw.isBlank()) {
            _searchUnifiedMatches.value = emptyList()
            return
        }

        val digits = raw.filter(Char::isDigit)
        val queryLower = raw.lowercase()

        val matchedLogs = ArrayList<RecentCall>()
        val matchedContactsWithoutHistory = ArrayList<RecentCall>()
        val seenKeys = HashSet<String>()

        for (item in allIndexedEntries) {
            val isDigitMatch = digits.isNotEmpty() && item.cleanDigits.contains(digits)
            val isT9Match = digits.isNotEmpty() && item.t9Name.contains(digits)
            val isNameMatch = queryLower.isNotEmpty() && item.nameLower.contains(queryLower)

            if (isDigitMatch || isT9Match || isNameMatch) {
                if (item.dedupeKey.isNotBlank() && seenKeys.add(item.dedupeKey)) {
                    if (item.hasHistory) {
                        matchedLogs.add(item.call)
                    } else {
                        matchedContactsWithoutHistory.add(item.call)
                    }
                }
            }
        }

        // 1. Logs sorted by latest call date DESC (all saved & unsaved logs come here)
        val sortedLogs = matchedLogs.sortedByDescending { it.date }

        // 2. Pure contacts with 0 history placed neatly at the bottom
        val sortedContacts = matchedContactsWithoutHistory.sortedBy { it.name?.lowercase() ?: it.number }

        // Combined result: Call history strictly at the top!
        _searchUnifiedMatches.value = (sortedLogs + sortedContacts).take(35)
    }

    private fun computeT9(name: String): String {
        if (name.isBlank()) return ""
        val sb = java.lang.StringBuilder(name.length)
        for (i in 0 until name.length) {
            when (name[i].uppercaseChar()) {
                in 'A'..'C' -> sb.append('2')
                in 'D'..'F' -> sb.append('3')
                in 'G'..'I' -> sb.append('4')
                in 'J'..'L' -> sb.append('5')
                in 'M'..'O' -> sb.append('6')
                in 'P'..'S' -> sb.append('7')
                in 'T'..'V' -> sb.append('8')
                in 'W'..'Z' -> sb.append('9')
                in '0'..'9' -> sb.append(name[i])
            }
        }
        return sb.toString()
    }

    fun loadSimCards(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            _availableSims.value = emptyList()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = queryPhoneAccounts(context.applicationContext)
            withContext(Dispatchers.Main) {
                _availableSims.value = result
                reconcileSavedAccount(context.applicationContext, result)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun queryPhoneAccounts(context: Context): List<SimInfo> {
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val subscriptions = try { subscriptionManager?.activeSubscriptionInfoList ?: emptyList() }
        catch (_: Throwable) { emptyList() }
        val accounts = try { telecom.callCapablePhoneAccounts } catch (_: Throwable) { emptyList() }

        return accounts.mapNotNull { handle ->
            val account = try { telecom.getPhoneAccount(handle) } catch (_: Throwable) { null } ?: return@mapNotNull null
            val subscription = subscriptions.firstOrNull { sub ->
                handle.id == sub.subscriptionId.toString() ||
                    account.address?.schemeSpecificPart == sub.number
            }
            val slot = subscription?.simSlotIndex ?: inferSlotFromAccount(account)
            val carrier = account.label?.toString()?.takeIf { it.isNotBlank() }
                ?: subscription?.carrierName?.toString()?.takeIf { it.isNotBlank() }
                ?: "Phone account"
            val displayName = account.label?.toString()?.takeIf { it.isNotBlank() } ?: carrier
            val number = account.address?.schemeSpecificPart ?: subscription?.number
            SimInfo(slot, carrier, handle, subscription?.subscriptionId, displayName, number, subscription != null)
        }.distinctBy { it.accountHandle?.id ?: "" }
            .sortedWith(compareBy<SimInfo> { if (it.slotIndex >= 0) it.slotIndex else Int.MAX_VALUE }.thenBy { it.displayName ?: it.carrierName })
    }

    private fun inferSlotFromAccount(account: PhoneAccount): Int {
        val explicit = account.extras?.getInt("slot", -1) ?: -1
        if (explicit >= 0) return explicit
        val extrasSlot = account.extras?.getInt("slot_id", -1) ?: -1
        if (extrasSlot >= 0) return extrasSlot
        return -1
    }

    private fun reconcileSavedAccount(context: Context, sims: List<SimInfo>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedId = prefs.getString(KEY_ACCOUNT_ID, null)
        val matched = savedId?.let { id -> sims.firstOrNull { it.accountHandle?.id == id } }
        when {
            matched != null -> prefs.edit().putInt(KEY_SELECTED_SIM_SLOT, matched.slotIndex).apply()
            sims.size == 1 -> {
                val only = sims.first()
                prefs.edit().putString(KEY_ACCOUNT_ID, only.accountHandle?.id).putInt(KEY_SELECTED_SIM_SLOT, only.slotIndex).apply()
            }
            else -> prefs.edit().remove(KEY_ACCOUNT_ID).putInt(KEY_SELECTED_SIM_SLOT, -1).apply()
        }
    }

    fun persistSelectedSim(context: Context, sim: SimInfo?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sim == null || sim.accountHandle == null) {
            prefs.edit().remove(KEY_ACCOUNT_ID).putInt(KEY_SELECTED_SIM_SLOT, -1).apply()
        } else {
            prefs.edit().putString(KEY_ACCOUNT_ID, sim.accountHandle.id).putInt(KEY_SELECTED_SIM_SLOT, sim.slotIndex).apply()
        }
    }
}

class AppViewModelFactory(
    private val contactsRepo: ContactsRepository,
    private val recentsRepo: CallLogRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ContactsViewModel::class.java) -> ContactsViewModel(contactsRepo) as T
        modelClass.isAssignableFrom(RecentsViewModel::class.java) -> RecentsViewModel(recentsRepo) as T
        modelClass.isAssignableFrom(DialerViewModel::class.java) -> DialerViewModel(contactsRepo, recentsRepo) as T
        else -> throw IllegalArgumentException("Unknown ViewModel")
    }
}
