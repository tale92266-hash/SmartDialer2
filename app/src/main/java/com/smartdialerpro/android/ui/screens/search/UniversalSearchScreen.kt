package com.smartdialerpro.android.ui.screens.search

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallMade
import androidx.compose.material.icons.rounded.CallMissed
import androidx.compose.material.icons.rounded.CallReceived
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.smartdialerpro.android.data.Contact
import com.smartdialerpro.android.data.RecentCall
import com.smartdialerpro.android.ui.components.NumberActionsSheet
import com.smartdialerpro.android.ui.components.FakeCallSheet
import com.smartdialerpro.android.ui.screens.fakecall.FakeCallActivity
import com.smartdialerpro.android.ui.navigation.Screen
import com.smartdialerpro.android.ui.screens.contacts.ContactAvatar
import com.smartdialerpro.android.util.Utils
import com.smartdialerpro.android.viewmodel.ContactsViewModel
import com.smartdialerpro.android.viewmodel.RecentsViewModel

@Composable
fun UniversalSearchScreen(contactsViewModel: ContactsViewModel, recentsViewModel: RecentsViewModel, navController: NavController, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var query by rememberSaveable { mutableStateOf("") }
    val contacts by contactsViewModel.contacts.collectAsState()
    val recentCalls by recentsViewModel.recentCalls.collectAsState()
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

    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    LaunchedEffect(Unit) { contactsViewModel.loadContacts(force = true); recentsViewModel.loadRecents(force = true) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { contactsViewModel.loadContacts(force = false); recentsViewModel.loadRecents(force = false) } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val trimmed = query.trim()
    val normalized = remember(trimmed) { trimmed.filter(Char::isDigit) }
    val matchingContacts = remember(trimmed, normalized, contacts) {
        if (trimmed.isBlank()) contacts.take(8) else contacts.filter { it.name.contains(trimmed, true) || it.number.contains(trimmed, true) || (normalized.isNotEmpty() && it.number.filter(Char::isDigit).contains(normalized)) }
            .sortedWith(compareByDescending<Contact> { it.name.startsWith(trimmed, true) }.thenBy { it.name.lowercase() }.thenBy { it.number }).take(20)
    }
    val matchingRecents = remember(trimmed, normalized, recentCalls) {
        if (trimmed.isBlank()) recentCalls.take(8) else recentCalls.filter { (it.name?.contains(trimmed, true) == true) || it.number.contains(trimmed, true) || (normalized.isNotEmpty() && it.number.filter(Char::isDigit).contains(normalized)) }.take(20)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.weight(1f), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .75f)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)) {
                    Icon(Icons.Rounded.Contacts, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)); Spacer(Modifier.width(9.dp))
                    BasicSearchField(query = query, onQueryChange = { query = it }, modifier = Modifier.weight(1f))
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Close, "Clear search", modifier = Modifier.size(19.dp)) }
                    if (query.isEmpty()) IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Close, "Exit search", modifier = Modifier.size(19.dp)) }
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.padding(start = 4.dp)) { Text("Cancel") }
        }
        if (query.isBlank()) Text("Quick access", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (matchingContacts.isEmpty() && matchingRecents.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 80.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Contacts, null, modifier = Modifier.size(46.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(12.dp)); Text("Nothing found", fontSize = 20.sp, fontWeight = FontWeight.SemiBold); Text("Try a contact name, phone number, or recent call", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (matchingContacts.isNotEmpty()) {
                    item { SearchSectionTitle("Contacts", Icons.Rounded.Contacts) }
                    items(matchingContacts, key = { "c_${it.id}_${it.number}" }) { contact -> ContactSearchRow(contact, { Utils.makeCall(context, contact.number) }, { navController.navigate(Screen.ContactProfile.createRoute(Uri.encode(contact.number))) }, { sheetNumber = contact.number }) }
                }
                if (matchingRecents.isNotEmpty()) {
                    item { SearchSectionTitle("Recent calls", Icons.Rounded.History) }
                    items(matchingRecents, key = { "r_${it.id}" }) { call -> RecentSearchRow(call, { Utils.makeCall(context, call.number) }, { navController.navigate(Screen.ContactProfile.createRoute(Uri.encode(call.number))) }, { sheetNumber = call.number }) }
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
            onMagicCall = { magicSheetNumber = it }
        )
    }
    magicSheetNumber?.let { magicNumber ->
        FakeCallSheet(
            number = magicNumber,
            simSlot = 0,
            onDismiss = { magicSheetNumber = null },
            onGenuineCall = { genuineNumber -> Utils.makeCall(context, genuineNumber) },
            onDirectCall = { fakeNumber, durationSecs, slot ->
                FakeCallActivity.start(context, fakeNumber, durationSecs, slot)
            }
        )
    }
}

@Composable
private fun BasicSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier) {
    androidx.compose.foundation.text.BasicTextField(value = query, onValueChange = onQueryChange, singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface), modifier = modifier, decorationBox = { inner ->
        Box(Modifier.fillMaxWidth()) {
            if (query.isEmpty()) Text("Search contacts, numbers or calls", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            inner()
        }
    })
}

@Composable
private fun SearchSectionTitle(title: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContactSearchRow(contact: Contact, onCall: () -> Unit, onOpen: () -> Unit, onLongPress: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress).padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        ContactAvatar(contact.name.ifBlank { contact.number }, contact.photoUri, 46.dp)
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) { Text(contact.name.ifBlank { contact.number }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false); Text(contact.number, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) }
        IconButton(onClick = onCall) { Icon(Icons.Rounded.Call, "Call", tint = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentSearchRow(call: RecentCall, onCall: () -> Unit, onOpen: () -> Unit, onLongPress: () -> Unit = {}) {
    val displayName = call.name?.takeIf { it.isNotBlank() } ?: call.number
    val typeIcon = when (call.type) { CallLog.Calls.MISSED_TYPE -> Icons.Rounded.CallMissed; CallLog.Calls.INCOMING_TYPE -> Icons.Rounded.CallReceived; CallLog.Calls.OUTGOING_TYPE -> Icons.Rounded.CallMade; else -> Icons.Rounded.Call }
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onLongPress).padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        ContactAvatar(displayName, call.photoUri, 46.dp)
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) { Text(displayName, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false); Row(verticalAlignment = Alignment.CenterVertically) { Icon(typeIcon, null, modifier = Modifier.size(14.dp), tint = if (call.type == CallLog.Calls.MISSED_TYPE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(5.dp)); Text(call.number, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false) } }
        IconButton(onClick = onCall) { Icon(Icons.Rounded.Call, "Call", tint = MaterialTheme.colorScheme.primary) }
    }
}