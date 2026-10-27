package com.smartdialerpro.android.ui.screens.contacts

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.smartdialerpro.android.data.Contact
import com.smartdialerpro.android.util.Utils
import com.smartdialerpro.android.viewmodel.ContactsViewModel

private const val SPEED_DIAL_PREFS = "speed_dial_preferences"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesSpeedDialScreen(viewModel: ContactsViewModel, navController: NavController) {
    val context = LocalContext.current
    val contacts by viewModel.contacts.collectAsState()
    val favorites = remember(contacts) { contacts.filter { it.isFavorite }.sortedBy { it.name.lowercase() } }
    val prefs = remember { context.getSharedPreferences(SPEED_DIAL_PREFS, Context.MODE_PRIVATE) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var assigningSlot by remember { mutableStateOf<Int?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) { viewModel.loadContacts(force = false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadContacts(force = false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun assignedContact(slot: Int): Contact? {
        val number = prefs.getString("slot_$slot", null) ?: return null
        return contacts.firstOrNull { Utils.normalizePhoneNumber(it.number) == number }
    }

    fun call(contact: Contact) { Utils.makeCall(context, contact.number) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 100.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Favorites", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Quick access & speed dial",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Text(
                "Speed dial",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Tap to call • long-press to assign a favorite",
                modifier = Modifier.padding(horizontal = 20.dp),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        items((1..9).chunked(3)) { rowSlots ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowSlots.forEach { slot ->
                    val contact = assignedContact(slot)
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(118.dp)
                            .combinedClickable(
                                onClick = { contact?.let(::call) },
                                onLongClick = { assigningSlot = slot }
                            ),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f))
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            Text(
                                "$slot",
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(
                                Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (contact == null) {
                                    Box(
                                        Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Rounded.Add, "Assign", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    Text(
                                        "Empty",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 5.dp)
                                    )
                                } else {
                                    ContactAvatar(contact.name.ifBlank { contact.number }, contact.photoUri, 44.dp)
                                    Text(
                                        contact.name.ifBlank { contact.number },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 5.dp, start = 4.dp, end = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            Text(
                "Favorite contacts",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (favorites.isEmpty()) {
            item {
                Text(
                    "Star contacts to keep them here.",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(favorites, key = { "fav_contact_${it.id}_${it.number}" }) { contact ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { call(contact) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ContactAvatar(contact.name.ifBlank { contact.number }, contact.photoUri, 48.dp)
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 14.dp)
                    ) {
                        Text(
                            contact.name.ifBlank { contact.number },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            contact.number,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Icon(
                        Icons.Rounded.Star,
                        "Favorite",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    IconButton(onClick = { call(contact) }) {
                        Icon(Icons.Rounded.Call, "Call", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    assigningSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { assigningSlot = null },
            title = { Text("Assign speed dial $slot") },
            text = {
                if (favorites.isEmpty()) {
                    Text("No favorite contacts yet. Star a contact first.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                        items(favorites, key = { "assign_${it.id}_${it.number}" }) { contact ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .combinedClickable(
                                        onClick = {
                                            prefs.edit().putString("slot_$slot", Utils.normalizePhoneNumber(contact.number)).apply()
                                            refreshKey++
                                            assigningSlot = null
                                        },
                                        onLongClick = {}
                                    )
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ContactAvatar(contact.name.ifBlank { contact.number }, contact.photoUri, 38.dp)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(contact.name.ifBlank { contact.number }, fontWeight = FontWeight.SemiBold)
                                    Text(contact.number, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { assigningSlot = null }) { Text("Done") } },
            dismissButton = {
                val existing = assignedContact(slot)
                if (existing != null) {
                    TextButton(onClick = {
                        prefs.edit().remove("slot_$slot").apply()
                        refreshKey++
                        assigningSlot = null
                    }) { Text("Clear") }
                }
            }
        )
    }

    @Suppress("UNUSED_VARIABLE")
    val ignored = refreshKey
}
