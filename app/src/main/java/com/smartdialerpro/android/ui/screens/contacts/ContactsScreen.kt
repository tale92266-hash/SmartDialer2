package com.smartdialerpro.android.ui.screens.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.smartdialerpro.android.data.Contact
import com.smartdialerpro.android.ui.components.EmptyStateView
import com.smartdialerpro.android.ui.components.WhatsAppChooserDialog
import com.smartdialerpro.android.ui.components.avatarColorsFor
import com.smartdialerpro.android.ui.components.launchWhatsApp
import com.smartdialerpro.android.ui.components.NumberActionsSheet
import com.smartdialerpro.android.ui.components.FakeCallSheet
import com.smartdialerpro.android.service.FakeCallSim
import com.smartdialerpro.android.ui.screens.fakecall.FakeCallActivity
import com.smartdialerpro.android.ui.navigation.Screen
import com.smartdialerpro.android.util.Utils
import com.smartdialerpro.android.viewmodel.ContactsViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

sealed interface ContactListItem {
    data class Header(val title: String) : ContactListItem
    data class Item(val contact: Contact, val sectionTag: String = "") : ContactListItem
}

@Composable
fun ContactsScreen(
    viewModel: ContactsViewModel,
    navController: NavController,
    /** Increments when the user taps the already-selected tab: scroll back to the top. */
    reselectSignal: Int = 0
) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(hasReadContacts(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val listState = rememberLazyListState()
    var lastReselectSignal by remember { mutableStateOf(reselectSignal) }
    LaunchedEffect(reselectSignal) {
        if (reselectSignal != lastReselectSignal) {
            lastReselectSignal = reselectSignal
            listState.animateScrollToItem(0)
        }
    }
    val coroutineScope = rememberCoroutineScope()
    var pendingWhatsAppNumber by remember { mutableStateOf<String?>(null) }
    pendingWhatsAppNumber?.let { number ->
        WhatsAppChooserDialog(number = number, onDismiss = { pendingWhatsAppNumber = null })
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) viewModel.loadContacts(force = false)
    }
    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.loadContacts(force = true)
    }
    var pendingFavorite by remember { mutableStateOf<Contact?>(null) }
    val favoriteWriteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            pendingFavorite?.let { viewModel.toggleFavorite(it) }
            viewModel.loadContacts(force = true)
        }
        pendingFavorite = null
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = hasReadContacts(context)
                hasPermission = granted
                if (granted) viewModel.loadContacts(force = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val contacts by viewModel.contacts.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var sheetNumber by remember { mutableStateOf<String?>(null) }
    var magicSheetNumber by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = searchQuery.isNotBlank()) { viewModel.updateSearchQuery("") }

    val structuredItems = remember(contacts, searchQuery) {
        val query = searchQuery.trim()
        val digits = query.filter(Char::isDigit)

        val filtered = if (query.isBlank()) {
            contacts
        } else {
            contacts.filter { contact ->
                contact.name.contains(query, true) ||
                    contact.number.contains(query, true) ||
                    (digits.isNotBlank() && contact.number.filter(Char::isDigit).contains(digits))
            }
        }

        val list = ArrayList<ContactListItem>(filtered.size + 30)

        if (query.isBlank()) {
            val favorites = filtered.filter { it.isFavorite }.sortedBy { it.name.lowercase() }
            if (favorites.isNotEmpty()) {
                list.add(ContactListItem.Header("Favorites"))
                favorites.forEach { list.add(ContactListItem.Item(it, sectionTag = "fav")) }
            }

            val grouped = filtered.groupBy { contact ->
                val firstChar = contact.name.trim().firstOrNull()?.uppercaseChar() ?: '#'
                if (firstChar in 'A'..'Z') firstChar.toString() else "#"
            }.toSortedMap()

            for (letter in 'A'..'Z') {
                val key = letter.toString()
                val group = grouped[key]
                if (!group.isNullOrEmpty()) {
                    list.add(ContactListItem.Header(key))
                    group.sortedBy { it.name.lowercase() }.forEach { list.add(ContactListItem.Item(it, sectionTag = key)) }
                }
            }

            val hashGroup = grouped["#"]
            if (!hashGroup.isNullOrEmpty()) {
                list.add(ContactListItem.Header("#"))
                hashGroup.sortedBy { it.name.lowercase() }.forEach { list.add(ContactListItem.Item(it, sectionTag = "hash")) }
            }
        } else {
            filtered.sortedBy { it.name.lowercase() }.forEach { list.add(ContactListItem.Item(it, sectionTag = "search")) }
        }

        list
    }

    val sectionIndexMap = remember(structuredItems) {
        val map = HashMap<String, Int>()
        structuredItems.forEachIndexed { index, item ->
            if (item is ContactListItem.Header) {
                map[item.title] = index
            }
        }
        map
    }

    val availableLetters = remember(structuredItems) {
        structuredItems.filterIsInstance<ContactListItem.Header>().map { it.title }.toSet()
    }

    val sidebarLetters = remember {
        listOf("★") + ('A'..'Z').map { it.toString() } + listOf("#")
    }

    var activePopupLetter by remember { mutableStateOf<String?>(null) }

    // Alphabet sidebar auto-hide: visible while the list scrolls (or the
    // sidebar itself is dragged), fading out 1.5s after scrolling stops.
    var sidebarVisible by remember { mutableStateOf(false) }
    var sidebarDragging by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        var hideJob: Job? = null
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) {
                    hideJob?.cancel()
                    sidebarVisible = true
                } else {
                    hideJob?.cancel()
                    hideJob = launch {
                        delay(1500)
                        if (!sidebarDragging) sidebarVisible = false
                    }
                }
            }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!hasPermission) {
            EmptyStateView(
                icon = Icons.Rounded.Contacts,
                title = "Access Required",
                description = "Allow access to your contacts to quickly find and call people.",
                actionText = "Grant Permission",
                onActionClick = { launcher.launch(Manifest.permission.READ_CONTACTS) }
            )
        } else {
            ContactsHeader(
                searchQuery,
                viewModel::updateSearchQuery,
                onAddContact = { navController.navigate(Screen.ContactEditor.createRoute(null, "")) }
            )

            val isSearching = searchQuery.isNotBlank()
            val shouldShowEmpty = if (isSearching) structuredItems.isEmpty() else contacts.isEmpty()

            if (shouldShowEmpty) {
                EmptyStateView(
                    icon = Icons.Rounded.SearchOff,
                    title = if (isSearching) "No contacts found" else "No contacts yet",
                    description = if (isSearching) "Try a different name or number." else "Your saved contacts will appear here.",
                    actionText = if (isSearching) "Clear search" else null,
                    onActionClick = if (isSearching) ({ viewModel.updateSearchQuery("") }) else null
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp, end = 26.dp)
                    ) {
                        itemsIndexed(
                            items = structuredItems,
                            contentType = { _, item ->
                                when (item) {
                                    is ContactListItem.Header -> "header"
                                    is ContactListItem.Item -> "item"
                                }
                            },
                            key = { index, item ->
                                when (item) {
                                    is ContactListItem.Header -> "hdr_${item.title}_$index"
                                    is ContactListItem.Item -> "contact_${item.sectionTag}_${item.contact.id}_${item.contact.number}_$index"
                                }
                            }
                        ) { _, item ->
                            when (item) {
                                is ContactListItem.Header -> {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.background
                                    ) {
                                        Text(
                                            text = item.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                                        )
                                    }
                                }
                                is ContactListItem.Item -> {
                                    ContactRow(
                                        contact = item.contact,
                                        onRowClick = { navController.navigate(Screen.ContactProfile.createRoute(Uri.encode(item.contact.number))) },
                                        onCall = { Utils.makeCall(context, item.contact.number) },
                                        onWhatsApp = { launchWhatsApp(context, item.contact.number) { pendingWhatsAppNumber = it } },
                                        onFavorite = {
                                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                                                viewModel.toggleFavorite(item.contact)
                                            } else {
                                                writeLauncher.launch(Manifest.permission.WRITE_CONTACTS)
                                            }
                                        },
                                        onLongPress = { sheetNumber = item.contact.number }
                                    )
                                }
                            }
                        }
                    }

                    if (searchQuery.isBlank()) {
                        ContactsAlphabetSidebar(
                            visible = sidebarVisible,
                            sidebarLetters = sidebarLetters,
                            sectionIndexMap = sectionIndexMap,
                            availableLetters = availableLetters,
                            listState = listState,
                            onLetterActive = { activePopupLetter = it },
                            onDragStateChange = { dragging ->
                                sidebarDragging = dragging
                                if (dragging) sidebarVisible = true
                            },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }

                    activePopupLetter?.let { letter ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                shadowElevation = 8.dp,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (letter == "Favorites") "★" else letter,
                                        fontSize = if (letter == "Favorites") 28.sp else 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
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
                        viewModel.toggleFavorite(target)
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
            onMagicCall = { magicSheetNumber = it }
        )
    }
    magicSheetNumber?.let { magicNumber ->
        FakeCallSheet(
            number = magicNumber,
            simSlot = FakeCallSim.resolveDefaultSimSlot(context),
            onDismiss = { magicSheetNumber = null },
            onGenuineCall = { genuineNumber -> Utils.makeCall(context, genuineNumber) },
            onDirectCall = { fakeNumber, durationSecs, slot ->
                FakeCallActivity.start(context, fakeNumber, durationSecs, slot)
            }
        )
    }
}

private fun hasReadContacts(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun ContactsHeader(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onAddContact: () -> Unit
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

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Contacts", fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onAddContact, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Rounded.PersonAdd,
                    contentDescription = "Add contact",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Text(
            if (searchQuery.isBlank()) "Your people, grouped alphabetically" else "Searching contacts",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search name or number") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Rounded.Close, "Clear search", modifier = Modifier.size(18.dp))
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
            },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(
    contact: Contact,
    onRowClick: () -> Unit,
    onCall: () -> Unit,
    onWhatsApp: () -> Unit,
    onFavorite: () -> Unit,
    onLongPress: () -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onRowClick, onLongClick = onLongPress)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContactAvatar(contact.name.ifBlank { contact.number }, contact.photoUri, 50.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(contact.name.ifBlank { contact.number }, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(contact.number, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onFavorite, modifier = Modifier.size(40.dp)) {
            Icon(
                if (contact.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                "Favorite",
                tint = if (contact.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onCall, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Call, "Call", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun ContactAvatar(name: String, photoUri: String?, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    var bitmap by remember(photoUri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(photoUri) {
        bitmap = if (!photoUri.isNullOrBlank()) {
            Utils.loadCachedAvatar(context, photoUri)
        } else null
    }

    val (avatarBg, avatarFg) = avatarColorsFor(name)

    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(avatarBg),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(bitmap!!, "Contact photo", Modifier.fillMaxSize())
        } else {
            Text(
                text = Utils.getAvatarInitial(name),
                fontSize = (size.value * .38f).sp,
                fontWeight = FontWeight.Bold,
                color = avatarFg
            )
        }
    }
}


/**
 * Alphabet fast-scroll sidebar for the Contacts tab, kept as its own
 * composable so AnimatedVisibility resolves to the top-level overload
 * (inside a ColumnScope it would pick the ColumnScope overload and fail
 * to compile). Dragging or tapping a letter jumps the list to that
 * section and reports the active letter for the centre popup bubble.
 */
@Composable
private fun ContactsAlphabetSidebar(
    visible: Boolean,
    sidebarLetters: List<String>,
    sectionIndexMap: Map<String, Int>,
    availableLetters: Set<String>,
    listState: LazyListState,
    onLetterActive: (String?) -> Unit,
    onDragStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(26.dp)
                .padding(vertical = 4.dp)
                .pointerInput(sidebarLetters, sectionIndexMap) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onDragStateChange(true)
                            if (size.height > 0) {
                                val index = ((offset.y / size.height) * sidebarLetters.size)
                                    .toInt()
                                    .coerceIn(0, sidebarLetters.lastIndex)
                                val char = sidebarLetters[index]
                                val sectionKey = if (char == "★") "Favorites" else char
                                onLetterActive(sectionKey)
                                sectionIndexMap[sectionKey]?.let { targetIdx ->
                                    coroutineScope.launch { listState.scrollToItem(targetIdx) }
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            if (size.height > 0) {
                                val index = ((change.position.y / size.height) * sidebarLetters.size)
                                    .toInt()
                                    .coerceIn(0, sidebarLetters.lastIndex)
                                val char = sidebarLetters[index]
                                val sectionKey = if (char == "★") "Favorites" else char
                                onLetterActive(sectionKey)
                                sectionIndexMap[sectionKey]?.let { targetIdx ->
                                    coroutineScope.launch { listState.scrollToItem(targetIdx) }
                                }
                            }
                        },
                        onDragEnd = { onDragStateChange(false); onLetterActive(null) },
                        onDragCancel = { onDragStateChange(false); onLetterActive(null) }
                    )
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(
                modifier = Modifier.fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                sidebarLetters.forEach { char ->
                    val sectionKey = if (char == "★") "Favorites" else char
                    val exists = availableLetters.contains(sectionKey)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clickable(enabled = exists) {
                                onLetterActive(sectionKey)
                                sectionIndexMap[sectionKey]?.let { idx ->
                                    coroutineScope.launch { listState.scrollToItem(idx) }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = char,
                            fontSize = if (char == "★") 10.sp else 8.5.sp,
                            lineHeight = 9.sp,
                            fontWeight = if (exists) FontWeight.Bold else FontWeight.Normal,
                            color = if (exists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
