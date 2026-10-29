package com.smartdialerpro.android.ui.screens.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.speech.RecognizerIntent
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import com.smartdialerpro.android.ui.components.SimSlotIcon
import com.smartdialerpro.android.ui.components.WhatsAppChooserDialog
import com.smartdialerpro.android.ui.components.launchWhatsApp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.smartdialerpro.android.R
import com.smartdialerpro.android.data.Contact
import com.smartdialerpro.android.data.RecentCall
import com.smartdialerpro.android.data.SimInfo
import com.smartdialerpro.android.ui.components.EmptyStateView
import com.smartdialerpro.android.ui.components.FakeCallSheet
import com.smartdialerpro.android.service.FakeCallSim
import com.smartdialerpro.android.ui.components.avatarColorsFor
import com.smartdialerpro.android.ui.components.NumberActionsSheet
import com.smartdialerpro.android.ui.components.SimChooserDialog
import com.smartdialerpro.android.ui.navigation.Screen
import com.smartdialerpro.android.ui.screens.fakecall.FakeCallActivity
import com.smartdialerpro.android.ui.theme.CallAnswerGreen
import com.smartdialerpro.android.util.Utils
import com.smartdialerpro.android.viewmodel.ContactsViewModel
import com.smartdialerpro.android.viewmodel.DialerViewModel
import com.smartdialerpro.android.viewmodel.RecentFilter
import com.smartdialerpro.android.viewmodel.RecentsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val PREFS_NAME = "dialer_preferences"
private const val KEY_ACCOUNT_ID = "selected_phone_account_id"

sealed interface CallListItem {
    data class DateHeader(val label: String, val dayKey: String) : CallListItem
    data class CallEntry(val call: RecentCall) : CallListItem
}

@Composable
fun HomeScreen(
    navController: NavController,
    contactsViewModel: ContactsViewModel,
    recentsViewModel: RecentsViewModel,
    dialerViewModel: DialerViewModel,
    startWithDialerOpen: Boolean = false,
    /** Increments when the user leaves this tab: the top search query clears (dialled digits stay). */
    clearSearchSignal: Int = 0,
    /** Increments when the user taps the already-selected tab: scroll back to the top. */
    reselectSignal: Int = 0
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var hasCallLogPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED)
    }

    val callLogLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCallLogPermission = granted
        if (granted) {
            recentsViewModel.startListeningForCallLogChanges(context)
            recentsViewModel.loadRecents(force = true)
        }
    }

    var dialerOpen by rememberSaveable { mutableStateOf(startWithDialerOpen) }
    var selectedFilter by rememberSaveable { mutableStateOf(RecentFilter.ALL) }
    var expandedCallId by rememberSaveable { mutableStateOf<String?>(null) }
    
    var topSearchQuery by rememberSaveable { mutableStateOf("") }
    var dialedNumber by rememberSaveable { mutableStateOf("") }
    var pendingWhatsAppNumber by remember { mutableStateOf<String?>(null) }

    // A tel: link from another app pre-fills the dialpad once.
    LaunchedEffect(Unit) {
        com.smartdialerpro.android.data.PendingDialNumber.take()?.let { number ->
            dialedNumber = number
            dialerOpen = true
        }
    }

    // Leaving the Home tab closes the top search mode; dialled digits survive.
    LaunchedEffect(clearSearchSignal) {
        if (clearSearchSignal > 0) topSearchQuery = ""
    }
    // Tapping the already-selected tab scrolls the recents list to the top.
    var lastReselectSignal by remember { mutableStateOf(reselectSignal) }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal != lastReselectSignal) {
            lastReselectSignal = reselectSignal
            listState.animateScrollToItem(0)
        }
    }

    val contacts by contactsViewModel.contacts.collectAsState()
    val recentCalls by recentsViewModel.recentCalls.collectAsState()
    val sims by dialerViewModel.availableSims.collectAsState()
    var sheetNumber by remember { mutableStateOf<String?>(null) }
    var magicSheetNumber by remember { mutableStateOf<String?>(null) }
    var pendingFavorite by remember { mutableStateOf<Contact?>(null) }
    val favoriteWriteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingFavorite?.let { contactsViewModel.toggleFavorite(it) }
            contactsViewModel.loadContacts(force = true)
        }
        pendingFavorite = null
    }

    val searchUnifiedMatches by dialerViewModel.searchUnifiedMatches.collectAsState()

    // Call notes (latest note per number) + speed-dial assignments.
    var notesMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var noteDialogNumber by remember { mutableStateOf<String?>(null) }
    var noteDialogText by remember { mutableStateOf("") }
    var speedDialAssigned by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(recentCalls) {
        notesMap = withContext(Dispatchers.IO) {
            com.smartdialerpro.android.data.CallNotesStore.ensureInit(context)
            com.smartdialerpro.android.data.CallNotesStore.getNotes(recentCalls.map { it.number })
        }
    }
    LaunchedEffect(dialerOpen) {
        if (dialerOpen) {
            speedDialAssigned = com.smartdialerpro.android.data.SpeedDialStore.assignedDigits(context)
        }
    }

    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var selectedAccountId by remember { mutableStateOf(prefs.getString(KEY_ACCOUNT_ID, null)) }

    LaunchedEffect(sims) {
        if (sims.isNotEmpty()) {
            val valid = selectedAccountId != null && sims.any { it.accountHandle?.id == selectedAccountId }
            if (!valid) {
                val defaultSim = sims.first()
                selectedAccountId = defaultSim.accountHandle?.id
                dialerViewModel.persistSelectedSim(context, defaultSim)
            }
        }
    }

    val selectedSimIndex = sims.indexOfFirst { it.accountHandle?.id == selectedAccountId }.let {
        if (it >= 0) it else 0
    }
    val selectedSim = sims.getOrNull(selectedSimIndex)

    var pendingAskNumber by remember { mutableStateOf<String?>(null) }
    fun requestCall(callNumber: String) {
        val mode = prefs.getInt("default_sim_mode", 1)
        if (mode == 2 && sims.size > 1) {
            pendingAskNumber = callNumber
        } else {
            placeRealCall(context, callNumber, selectedSim)
        }
    }

    val activeQuery = remember(dialedNumber, topSearchQuery) {
        if (dialedNumber.isNotBlank()) dialedNumber else topSearchQuery
    }
    val isSearchMode = activeQuery.isNotBlank()

    val callPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && activeQuery.isNotBlank()) {
            requestCall(activeQuery)
        }
    }

    LaunchedEffect(Unit) {
        if (hasCallLogPermission) {
            recentsViewModel.startListeningForCallLogChanges(context)
        }
        contactsViewModel.loadContacts(force = false)
        dialerViewModel.loadDataForMatching(force = false)
        dialerViewModel.loadSimCards(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
                hasCallLogPermission = granted
                if (granted) {
                    recentsViewModel.startListeningForCallLogChanges(context)
                    recentsViewModel.loadRecents(force = false)
                }
                contactsViewModel.loadContacts(force = false)
                dialerViewModel.loadDataForMatching(force = false)
                dialerViewModel.loadSimCards(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(activeQuery) {
        dialerViewModel.findMatch(activeQuery)
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { it && dialerOpen }
            .collect { dialerOpen = false }
    }

    BackHandler(enabled = dialerOpen || isSearchMode || expandedCallId != null) {
        if (dialerOpen) {
            dialerOpen = false
        } else if (expandedCallId != null) {
            expandedCallId = null
        } else if (isSearchMode) {
            topSearchQuery = ""
            dialedNumber = ""
        }
    }

    val favorites = remember(contacts) { contacts.filter { it.isFavorite }.sortedBy { it.name.lowercase() } }

    val filteredCalls = remember(recentCalls, selectedFilter) {
        recentCalls.filter {
            when (selectedFilter) {
                RecentFilter.ALL -> true
                RecentFilter.MISSED -> it.type == CallLog.Calls.MISSED_TYPE
                RecentFilter.INCOMING -> it.type == CallLog.Calls.INCOMING_TYPE
                RecentFilter.OUTGOING -> it.type == CallLog.Calls.OUTGOING_TYPE
            }
        }
    }

    val groupedCallItems = remember(filteredCalls) {
        val list = ArrayList<CallListItem>(filteredCalls.size + 15)
        var lastDayKey = ""
        for (call in filteredCalls) {
            val dayKey = getDayKey(call.date)
            if (dayKey != lastDayKey) {
                list.add(CallListItem.DateHeader(getDayLabel(call.date), dayKey))
                lastDayKey = dayKey
            }
            list.add(CallListItem.CallEntry(call))
        }
        list
    }

    val groupedSearchItems = remember(searchUnifiedMatches) {
        val list = ArrayList<CallListItem>(searchUnifiedMatches.size + 10)
        var lastDayKey = ""
        var contactHeaderShown = false

        for (call in searchUnifiedMatches) {
            if (call.date > 0L) {
                val dayKey = getDayKey(call.date)
                if (dayKey != lastDayKey) {
                    list.add(CallListItem.DateHeader(getDayLabel(call.date), dayKey))
                    lastDayKey = dayKey
                }
                list.add(CallListItem.CallEntry(call))
            } else {
                if (!contactHeaderShown) {
                    list.add(CallListItem.DateHeader("Contacts", "contacts_no_call_history"))
                    contactHeaderShown = true
                }
                list.add(CallListItem.CallEntry(call))
            }
        }
        list
    }

    pendingWhatsAppNumber?.let { number ->
        WhatsAppChooserDialog(number = number, onDismiss = { pendingWhatsAppNumber = null })
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            HomeLiveSearchBar(
                query = topSearchQuery,
                onQueryChange = {
                    topSearchQuery = it
                    if (dialerOpen && it.isNotBlank()) dialerOpen = false
                },
                onClear = { topSearchQuery = ""; dialedNumber = "" },
                showClear = isSearchMode
            )

            if (!isSearchMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = selectedFilter == RecentFilter.ALL,
                        onClick = { selectedFilter = RecentFilter.ALL },
                        label = { Text("All calls", fontWeight = FontWeight.Medium) },
                        shape = RoundedCornerShape(50),
                        leadingIcon = if (selectedFilter == RecentFilter.ALL) {
                            { Icon(Icons.Rounded.Done, null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = selectedFilter == RecentFilter.MISSED,
                        onClick = { selectedFilter = RecentFilter.MISSED },
                        label = { Text("Missed", fontWeight = FontWeight.Medium) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        leadingIcon = if (selectedFilter == RecentFilter.MISSED) {
                            { Icon(Icons.Rounded.CallMissed, null, modifier = Modifier.size(16.dp)) }
                        } else null
                    )
                }
            }

            if (!hasCallLogPermission && recentCalls.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Rounded.History,
                    title = "Call History Access Required",
                    description = "SmartDialer needs call history access to display your calls.",
                    actionText = "Grant Permission",
                    onActionClick = { callLogLauncher.launch(Manifest.permission.READ_CALL_LOG) },
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = if (dialerOpen) 360.dp else 100.dp)
                ) {
                    if (isSearchMode) {
                        if (groupedSearchItems.isEmpty()) {
                            item(key = "empty_search_state") {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.SearchOff, null, modifier = Modifier.size(46.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(14.dp))
                                        Text("No matches found", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                        Text("No call history or contact matches '$activeQuery'", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        } else {
                            itemsIndexed(
                                items = groupedSearchItems,
                                contentType = { _, item ->
                                    when (item) {
                                        is CallListItem.DateHeader -> "header"
                                        is CallListItem.CallEntry -> "entry"
                                    }
                                },
                                key = { index, item ->
                                    when (item) {
                                        is CallListItem.DateHeader -> "search_hdr_${item.dayKey}_$index"
                                        is CallListItem.CallEntry -> "search_call_${item.call.id}_${item.call.date}_$index"
                                    }
                                }
                            ) { _, item ->
                                when (item) {
                                    is CallListItem.DateHeader -> DateHeader(item.label)
                                    is CallListItem.CallEntry -> {
                                        SwipeableCallRow(
                                            call = item.call,
                                            expanded = expandedCallId == item.call.id,
                                            onToggle = { expandedCallId = if (expandedCallId == item.call.id) null else item.call.id },
                                            onCall = { Utils.makeCall(context, item.call.number) },
                                            onMessage = { openSms(context, item.call.number) },
                                            onWhatsApp = { launchWhatsApp(context, item.call.number) { pendingWhatsAppNumber = it } },
                                            onInfo = { com.smartdialerpro.android.ui.screens.details.CallDetailsHolder.call = item.call; navController.navigate(Screen.CallDetails.route) },
                                            onAddContact = { navController.navigate(Screen.ContactEditor.createRoute(null, item.call.number)) },
                                            onLongPress = { sheetNumber = item.call.number },
                                            note = notesMap[com.smartdialerpro.android.data.CallNotesStore.keyFor(item.call.number)]
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        if (groupedCallItems.isEmpty()) {
                            item(key = "empty_recents_state") {
                                EmptyStateView(
                                    icon = if (selectedFilter == RecentFilter.MISSED) Icons.Rounded.CallMissed else Icons.Rounded.Call,
                                    title = if (selectedFilter == RecentFilter.MISSED) "No missed calls" else "No recent calls",
                                    description = if (selectedFilter == RecentFilter.MISSED) "You're all caught up! No missed calls." else "Calls you make or receive will appear here.",
                                    actionText = if (selectedFilter != RecentFilter.ALL) "Show all calls" else null,
                                    onActionClick = if (selectedFilter != RecentFilter.ALL) ({ selectedFilter = RecentFilter.ALL }) else null,
                                    modifier = Modifier.fillParentMaxHeight(0.7f)
                                )
                            }
                        } else {
                            if (selectedFilter == RecentFilter.ALL && favorites.isNotEmpty()) {
                                item(key = "home_favorites_carousel") {
                                    FavoritesHorizontalRow(
                                        favorites = favorites,
                                        onContactClick = { contact -> navController.navigate(Screen.ContactProfile.createRoute(Uri.encode(contact.number))) },
                                        onCallClick = { contact -> Utils.makeCall(context, contact.number) },
                                        onSeeAllClick = {
                                            navController.navigate(Screen.Favorites.route) {
                                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    )
                                }
                            }

                            itemsIndexed(
                                items = groupedCallItems,
                                contentType = { _, item ->
                                    when (item) {
                                        is CallListItem.DateHeader -> "header"
                                        is CallListItem.CallEntry -> "entry"
                                    }
                                },
                                key = { index, item ->
                                    when (item) {
                                        is CallListItem.DateHeader -> "date_${item.dayKey}_$index"
                                        is CallListItem.CallEntry -> "call_${item.call.id}_${item.call.date}_$index"
                                    }
                                }
                            ) { _, item ->
                                when (item) {
                                    is CallListItem.DateHeader -> DateHeader(item.label)
                                    is CallListItem.CallEntry -> {
                                        SwipeableCallRow(
                                            call = item.call,
                                            expanded = expandedCallId == item.call.id,
                                            onToggle = { expandedCallId = if (expandedCallId == item.call.id) null else item.call.id },
                                            onCall = { Utils.makeCall(context, item.call.number) },
                                            onMessage = { openSms(context, item.call.number) },
                                            onWhatsApp = { launchWhatsApp(context, item.call.number) { pendingWhatsAppNumber = it } },
                                            onInfo = { com.smartdialerpro.android.ui.screens.details.CallDetailsHolder.call = item.call; navController.navigate(Screen.CallDetails.route) },
                                            onAddContact = { navController.navigate(Screen.ContactEditor.createRoute(null, item.call.number)) },
                                            onLongPress = { sheetNumber = item.call.number },
                                            note = notesMap[com.smartdialerpro.android.data.CallNotesStore.keyFor(item.call.number)]
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!dialerOpen) {
            FloatingActionButton(
                onClick = { dialerOpen = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 24.dp)
                    .size(64.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 10.dp)
            ) {
                Icon(Icons.Rounded.Dialpad, contentDescription = "Open dialer", modifier = Modifier.size(28.dp))
            }
        }

        AnimatedVisibility(
            visible = dialerOpen,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 24.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(20.dp)
                            .clickable { dialerOpen = false },
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .size(width = 38.dp, height = 4.5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    HomeNumberDisplayRow(
                        number = dialedNumber,
                        onDelete = {
                            if (dialedNumber.isNotEmpty()) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                dialedNumber = dialedNumber.dropLast(1)
                            }
                        },
                        onLongDelete = {
                            if (dialedNumber.isNotEmpty()) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                dialedNumber = ""
                            }
                        },
                        onNumberLongPress = {
                            if (dialedNumber.isNotBlank()) sheetNumber = dialedNumber
                        }
                    )

                    if (sims.size > 1) {
                        Spacer(Modifier.height(6.dp))
                        HomeSimSelector(sims, selectedSimIndex) { index ->
                            sims.getOrNull(index)?.let { sim ->
                                selectedAccountId = sim.accountHandle?.id
                                dialerViewModel.persistSelectedSim(context, sim)
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    HomeDialpadGrid(
                        assignedDigits = speedDialAssigned,
                        onDigit = { digit ->
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            dialedNumber += digit
                        },
                        onLongDigit = { digit ->
                            if (digit == "0") {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                dialedNumber += "+"
                            } else if (digit in "2".."9") {
                                // Speed dial: with a typed number, long-press
                                // stores it on this digit; empty dialpad +
                                // stored number calls it straight away.
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (dialedNumber.isNotBlank()) {
                                    com.smartdialerpro.android.data.SpeedDialStore.set(context, digit, dialedNumber)
                                    speedDialAssigned = com.smartdialerpro.android.data.SpeedDialStore.assignedDigits(context)
                                    Toast.makeText(context, "Speed dial $digit set for $dialedNumber", Toast.LENGTH_SHORT).show()
                                } else {
                                    val saved = com.smartdialerpro.android.data.SpeedDialStore.get(context, digit)
                                    if (saved != null) {
                                        requestCall(saved)
                                    } else {
                                        Toast.makeText(context, "No speed dial on $digit — type a number, then long-press $digit to set it", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    )

                    Spacer(Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    Row(
                        modifier = Modifier.width(96.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { dialerOpen = false },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Rounded.KeyboardArrowDown,
                                contentDescription = "Minimize dialpad",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.size(48.dp))
                    }

                        FloatingActionButton(
                            onClick = {
                                if (activeQuery.isBlank()) return@FloatingActionButton
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                                    requestCall(activeQuery)
                                } else {
                                    callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
                                }
                            },
                            containerColor = CallAnswerGreen,
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(66.dp),
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp, pressedElevation = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Call, "Call", modifier = Modifier.size(32.dp))
                        }

                        Row(
                            modifier = Modifier.width(96.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(
                                onClick = {
                                    if (dialedNumber.isBlank()) {
                                        Toast.makeText(context, "Please enter a valid number first.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        magicSheetNumber = dialedNumber
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.AutoAwesome,
                                    contentDescription = "Magic Call",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            if (dialedNumber.isNotBlank()) {
                                IconButton(
                                    onClick = { navController.navigate(Screen.ContactEditor.createRoute(null, dialedNumber)) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.PersonAdd,
                                        contentDescription = "Add contact",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            } else {
                                Spacer(Modifier.size(48.dp))
                            }
                        }
                    }
                }
            }
        }

        pendingAskNumber?.let { askNumber ->
            SimChooserDialog(
                number = askNumber,
                sims = sims,
                onDismiss = { pendingAskNumber = null },
                onPick = { sim ->
                    pendingAskNumber = null
                    placeRealCall(context, askNumber, sim)
                }
            )
        }

        sheetNumber?.let { number ->
            val sheetContact = contacts.firstOrNull { Utils.isSameNumber(it.number, number) }
            NumberActionsSheet(
                number = number,
                contact = sheetContact,
                contacts = contacts,

                onDismiss = { sheetNumber = null },
                onSaveNew = { newNumber ->
                    sheetNumber = null
                    navController.navigate(Screen.ContactEditor.createRoute(null, newNumber))
                },
                onOpenAddToExisting = { pickerNumber ->
                    sheetNumber = null
                    navController.navigate(Screen.AddToExisting.createRoute(pickerNumber))
                },
                onToggleFavorite = {
                    val target = sheetContact
                    sheetNumber = null
                    if (target != null) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                            contactsViewModel.toggleFavorite(target)
                        } else {
                            pendingFavorite = target
                            favoriteWriteLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                        }
                    }
                },
                onCall = {
                    sheetNumber = null
                    Utils.makeCall(context, number)
                },
                onOpenProfile = {
                    sheetNumber = null
                    navController.navigate(Screen.ContactProfile.createRoute(Uri.encode(number)))
                },
                onMagicCall = { magicSheetNumber = it },
                onNote = { noteNumber ->
                    sheetNumber = null
                    noteDialogText = notesMap[com.smartdialerpro.android.data.CallNotesStore.keyFor(noteNumber)].orEmpty()
                    noteDialogNumber = noteNumber
                },
                note = notesMap[com.smartdialerpro.android.data.CallNotesStore.keyFor(number)]
            )
        }

        noteDialogNumber?.let { noteNumber ->
            NoteEditorDialog(
                number = noteNumber,
                initialText = noteDialogText,
                onDismiss = { noteDialogNumber = null },
                onSave = { text ->
                    noteDialogNumber = null
                    coroutineScope.launch {
                        withContext(Dispatchers.IO) {
                            com.smartdialerpro.android.data.CallNotesStore.ensureInit(context)
                            com.smartdialerpro.android.data.CallNotesStore.putNote(noteNumber, text)
                        }
                        notesMap = withContext(Dispatchers.IO) {
                            com.smartdialerpro.android.data.CallNotesStore.getNotes(recentCalls.map { it.number })
                        }
                        Toast.makeText(context, if (text.isBlank()) "Note removed" else "Note saved", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        magicSheetNumber?.let { magicNumber ->
            FakeCallSheet(
                number = magicNumber,
                simSlot = selectedSim?.slotIndex?.takeIf { it >= 0 } ?: FakeCallSim.resolveDefaultSimSlot(context),
                onDismiss = { magicSheetNumber = null },
                onGenuineCall = { genuineNumber ->
                    placeRealCall(context, genuineNumber, selectedSim)
                },
                onDirectCall = { fakeNumber, durationSecs, slot ->
                    dialerOpen = false
                    dialedNumber = ""
                    FakeCallActivity.start(context, fakeNumber, durationSecs, slot)
                }
            )
        }
    }
}

@Composable
private fun HomeLiveSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    showClear: Boolean = false
) {
    val context = LocalContext.current
    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                onQueryChange(spokenText)
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(52.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        "Search contacts & numbers",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (query.isNotEmpty() || showClear) {
                IconButton(onClick = onClear, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Exit search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(
                onClick = {
                    try {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak name or number")
                        }
                        voiceLauncher.launch(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "Voice input not supported", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Rounded.Mic,
                    contentDescription = "Voice search",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun FavoritesHorizontalRow(
    favorites: List<Contact>,
    onContactClick: (Contact) -> Unit,
    onCallClick: (Contact) -> Unit,
    onSeeAllClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Favorites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onSeeAllClick, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                Text("See all", fontSize = 13.sp)
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(favorites.take(8), key = { "fav_${it.id}_${it.number}" }) { contact ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(64.dp)
                        .clickable { onCallClick(contact) }
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        HomeCachedAvatar(contact.name.ifBlank { contact.number }, contact.photoUri, 52.dp)
                        Box(
                            Modifier
                                .size(17.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Star, null, tint = Color.White, modifier = Modifier.size(10.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        contact.name.ifBlank { contact.number },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Divider(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    }
}

@Composable
private fun DateHeader(label: String) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableCallRow(
    call: RecentCall,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onWhatsApp: () -> Unit,
    onInfo: () -> Unit,
    onAddContact: () -> Unit,
    onLongPress: () -> Unit = {},
    note: String? = null
) {
    // Swipe right to call, swipe left to message. Implemented with
    // foundation drag APIs only (no version-sensitive Material3
    // dismiss APIs): the row follows the finger, fires past a
    // threshold, and springs back so the list never loses its place.
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val density = LocalDensity.current
    val thresholdPx = with(density) { 96.dp.toPx() }
    val maxPx = with(density) { 160.dp.toPx() }
    val offset = offsetX.value
    // A reused item slot must never keep the previous row's swipe offset.
    LaunchedEffect(call.id) {
        offsetX.snapTo(0f)
    }
    val snapBack = {
        scope.launch {
            offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
    // The swipe wash is a real layout child sized to the row's
    // measured height: background layers sized with fillMaxSize and
    // draw-phase washes did not appear on-device in this list.
    val haptic = LocalHapticFeedback.current
    var crossed by remember { mutableStateOf(false) }
    var rowHeightPx by remember { mutableStateOf(0) }
    val washCall = CallAnswerGreen
    val washMessage = MaterialTheme.colorScheme.secondary
    Box(Modifier.fillMaxWidth()) {
        if (offset != 0f && rowHeightPx > 0) {
            val progress = (kotlin.math.abs(offset) / thresholdPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { rowHeightPx.toDp() })
                    .background(
                        (if (offset > 0f) washCall else washMessage)
                            .copy(alpha = 0.20f + 0.55f * progress),
                        RoundedCornerShape(18.dp)
                    )
            )
        }
        Box(
            modifier = Modifier
                .onSizeChanged { rowHeightPx = it.height }
                .background(MaterialTheme.colorScheme.background, RoundedCornerShape(18.dp))
                .offset { IntOffset(offset.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            crossed = false
                            scope.launch {
                                when {
                                    offsetX.value > thresholdPx -> onCall()
                                    offsetX.value < -thresholdPx -> onMessage()
                                }
                                offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        },
                        onDragCancel = {
                            crossed = false
                            snapBack()
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        val newOffset = (offsetX.value + dragAmount).coerceIn(-maxPx, maxPx)
                        if (!crossed && kotlin.math.abs(newOffset) >= thresholdPx) {
                            crossed = true
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        } else if (crossed && kotlin.math.abs(newOffset) < thresholdPx) {
                            crossed = false
                        }
                        scope.launch {
                            offsetX.snapTo(newOffset)
                        }
                    }
                }
        ) {
            HomeCallItemRow(
                call = call,
                expanded = expanded,
                onToggle = onToggle,
                onCall = onCall,
                onMessage = onMessage,
                onWhatsApp = onWhatsApp,
                onInfo = onInfo,
                onAddContact = onAddContact,
                onLongPress = onLongPress,
                note = note
            )
        }
    }
}

@Composable
private fun NoteEditorDialog(
    number: String,
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Call note") },
        text = {
            Column {
                Text(
                    text = number,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("e.g. Met at the Vi store, call about port-in") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initialText.isNotBlank()) {
                    TextButton(onClick = { onSave("") }) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeCallItemRow(
    call: RecentCall,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onWhatsApp: () -> Unit,
    onInfo: () -> Unit,
    onAddContact: () -> Unit,
    onLongPress: () -> Unit = {},
    note: String? = null
) {
    val isMissed = call.type == CallLog.Calls.MISSED_TYPE
    val isIncoming = call.type == CallLog.Calls.INCOMING_TYPE
    val isOutgoing = call.type == CallLog.Calls.OUTGOING_TYPE
    val isContactOnly = call.type == -1 || call.date == 0L

    val directionIcon = when {
        isMissed -> Icons.Rounded.CallMissed
        isIncoming -> Icons.Rounded.CallReceived
        isOutgoing -> Icons.Rounded.CallMade
        else -> Icons.Rounded.Person
    }

    val iconTint = when {
        isMissed -> MaterialTheme.colorScheme.error
        isOutgoing -> MaterialTheme.colorScheme.primary
        isIncoming -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val displayName = call.name?.takeIf { it.isNotBlank() } ?: call.number
    val isUnknown = call.name.isNullOrBlank()

    val backgroundColor by animateColorAsState(
        if (expanded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f) else Color.Transparent,
        label = "item_bg"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (expanded) 8.dp else 0.dp, vertical = 1.dp)
            .background(backgroundColor, RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onToggle, onLongClick = onLongPress)
            .padding(horizontal = if (expanded) 14.dp else 16.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HomeCachedAvatar(displayName, call.photoUri, 48.dp, highlight = isMissed)

            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        fontSize = 16.sp,
                        fontWeight = if (isMissed) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (call.callCount > 1) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "(${call.callCount})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                Spacer(Modifier.height(2.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(directionIcon, null, tint = iconTint, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    if (isMissed) {
                        Text(
                            text = "Missed",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = call.number,
                        fontSize = 13.sp,
                        color = if (isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (call.duration > 0) {
                        Text(
                            text = " • ${formatSeconds(call.duration)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    call.simSlot?.let { slot ->
                        Spacer(Modifier.width(6.dp))
                        SimSlotIcon(slot = slot, size = 11.dp)
                    }
                }
                if (!note.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = note,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                if (call.date > 0L) {
                    Text(
                        text = Utils.formatCallDate(call.date),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                } else {
                    Text(
                        text = "Contact",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Spacer(Modifier.height(4.dp))
                IconButton(onClick = onCall, modifier = Modifier.size(40.dp)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Call, "Direct call", tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandIn(spring(stiffness = Spring.StiffnessMedium), expandFrom = Alignment.TopCenter) + fadeIn(),
            exit = shrinkOut(spring(stiffness = Spring.StiffnessMedium), shrinkTowards = Alignment.TopCenter) + fadeOut()
        ) {
            Column {
                Divider(Modifier.padding(vertical = 8.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    HomeActionItem(Icons.Rounded.Call, "Call", onCall)
                    HomeActionItem(Icons.AutoMirrored.Rounded.Message, "Message", onMessage)
                    HomeActionItem(ImageVector.vectorResource(R.drawable.ic_whatsapp), "WhatsApp", onWhatsApp, Color(0xFF25D366))
                    HomeActionItem(Icons.Rounded.Info, "Details", onInfo)
                    if (isUnknown && isContactOnly.not()) {
                        HomeActionItem(Icons.Rounded.PersonAdd, "Save", onAddContact)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HomeCachedAvatar(name: String, photoUri: String?, size: Dp, highlight: Boolean = false) {
    val context = LocalContext.current
    var bitmap by remember(photoUri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(photoUri) {
        bitmap = if (!photoUri.isNullOrBlank()) {
            Utils.loadCachedAvatar(context, photoUri)
        } else null
    }

    val (avatarBg, avatarFg) = avatarColorsFor(name)
    // Missed calls: the letter avatar turns red (no row background tint).
    val bg = if (highlight && bitmap == null) MaterialTheme.colorScheme.errorContainer else avatarBg
    val fg = if (highlight && bitmap == null) MaterialTheme.colorScheme.error else avatarFg

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        bitmap?.let {
            Image(it, "Contact photo", modifier = Modifier.fillMaxSize())
        } ?: Text(
            text = Utils.getAvatarInitial(name),
            fontSize = (size.value * 0.40f).sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeNumberDisplayRow(
    number: String,
    onDelete: () -> Unit,
    onLongDelete: () -> Unit,
    onNumberLongPress: () -> Unit = {}
) {
    val fontSize = when {
        number.length <= 6 -> 32.sp
        number.length <= 9 -> 28.sp
        number.length <= 12 -> 24.sp
        number.length <= 15 -> 21.sp
        else -> 18.sp
    }

    val formattedNumber = remember(number) { formatDisplayNumber(number) }
    val backspaceInteraction = remember { MutableInteractionSource() }
    val isBackspacePressed by backspaceInteraction.collectIsPressedAsState()
    val backspaceScale by animateFloatAsState(
        if (isBackspacePressed) 0.85f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "backspaceScale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.size(54.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .height(50.dp)
                .combinedClickable(onClick = {}, onLongClick = onNumberLongPress),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = formattedNumber,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                letterSpacing = 1.sp,
                modifier = Modifier.basicMarquee()
            )
        }

        Box(
            modifier = Modifier.size(54.dp),
            contentAlignment = Alignment.Center
        ) {
            if (number.isNotEmpty()) {
                Surface(
                    shape = CircleShape,
                    color = if (isBackspacePressed) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .scale(backspaceScale)
                        .clip(CircleShape)
                        .combinedClickable(
                            interactionSource = backspaceInteraction,
                            indication = LocalIndication.current,
                            onClick = onDelete,
                            onLongClick = onLongDelete
                        )
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Rounded.Backspace,
                            contentDescription = "Delete",
                            tint = if (isBackspacePressed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSimSelector(sims: List<SimInfo>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.20f)),
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            sims.forEachIndexed { index, sim ->
                val isSelected = selectedIndex == index

                val bgAnimated by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "simBg"
                )

                val contentColorAnimated by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = "simContent"
                )

                val slotTitle = if (sim.slotIndex >= 0) "SIM ${sim.slotIndex + 1}" else "SIM"
                val rawCarrier = (sim.displayName ?: sim.carrierName).trim()
                
                val carrier = if (
                    rawCarrier.isNotBlank() &&
                    !rawCarrier.equals("Phone account", ignoreCase = true) &&
                    !rawCarrier.equals(slotTitle, ignoreCase = true)
                ) {
                    rawCarrier
                } else null

                val simLabel = if (carrier != null) "$slotTitle • $carrier" else slotTitle

                Surface(
                    shape = RoundedCornerShape(50),
                    color = bgAnimated,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onSelect(index) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (sim.slotIndex >= 0) {
                            SimSlotIcon(
                                slot = sim.slotIndex + 1,
                                size = 11.dp,
                                color = contentColorAnimated
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.SimCard,
                                contentDescription = null,
                                tint = contentColorAnimated,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = simLabel,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = contentColorAnimated,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private fun formatDisplayNumber(raw: String): String {
    if (raw.isBlank()) return ""
    if (raw.contains("*") || raw.contains("#")) return raw
    return if (raw.startsWith("+")) {
        val digits = raw.substring(1)
        val out = StringBuilder("+")
        if (digits.isNotEmpty()) out.append(digits.substring(0, minOf(2, digits.length)))
        if (digits.length > 2) out.append(' ').append(digits.substring(2, minOf(7, digits.length)))
        if (digits.length > 7) out.append(' ').append(digits.substring(7))
        out.toString().trim()
    } else {
        val out = StringBuilder()
        if (raw.isNotEmpty()) out.append(raw.substring(0, minOf(5, raw.length)))
        if (raw.length > 5) out.append(' ').append(raw.substring(5, minOf(10, raw.length)))
        if (raw.length > 10) out.append(' ').append(raw.substring(10))
        out.toString().trim()
    }
}

fun placeRealCall(context: Context, number: String, selectedSim: SimInfo?) {
    val cleanNumber = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' || it == ',' || it == ';' }
    if (cleanNumber.isBlank()) return
    com.smartdialerpro.android.service.AutoRedialManager.cancel()
    try {
        val isUssd = cleanNumber.contains("#") || (cleanNumber.startsWith("*") && cleanNumber.endsWith("#"))
        val action = if (isUssd) Intent.ACTION_DIAL else Intent.ACTION_CALL
        val intent = Intent(action, Uri.parse("tel:${Uri.encode(cleanNumber)}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (!isUssd) {
                (selectedSim?.accountHandle ?: Utils.resolveDefaultSimHandle(context))?.let { putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            }
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        try {
            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(cleanNumber)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Throwable) { }
    }
}

private fun getDayKey(time: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = time }
    return "${c.get(Calendar.YEAR)}-${c.get(Calendar.DAY_OF_YEAR)}"
}

private fun getDayLabel(time: Long): String {
    val now = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        getDayKey(time) == getDayKey(now.timeInMillis) -> "Today"
        getDayKey(time) == getDayKey(yesterday.timeInMillis) -> "Yesterday"
        Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
            SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(time))
        else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(time))
    }
}

private fun formatSeconds(seconds: Long): String {
    if (seconds <= 0) return "0s"
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

private fun openSms(context: Context, number: String) {
    val clean = number.filter { it.isDigit() || it == '+' }
    if (clean.isBlank()) return
    try {
        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$clean")))
    } catch (_: Throwable) { }
}

