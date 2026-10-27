package com.smartdialerpro.android.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartdialerpro.android.data.Contact
import com.smartdialerpro.android.ui.screens.contacts.ContactAvatar
import com.smartdialerpro.android.ui.screens.settings.blockSystemNumber
import com.smartdialerpro.android.ui.screens.settings.isNumberBlocked
import com.smartdialerpro.android.ui.screens.settings.unblockNumberByNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared long-press actions for any phone number shown in the app:
 * recents, contacts, search results and the dialed number. Tap behaviour
 * of the underlying rows is unchanged; only the long-press opens this sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberActionsSheet(
    number: String,
    contact: Contact?,
    contacts: List<Contact>,
    onDismiss: () -> Unit,
    onSaveNew: (String) -> Unit,
    onAddToExisting: (Contact, String) -> Unit,
    onToggleFavorite: () -> Unit,
    onCall: () -> Unit,
    onOpenProfile: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    var pickerQuery by remember { mutableStateOf("") }
    var blocked by remember { mutableStateOf<Boolean?>(null) }
    var blockBusy by remember { mutableStateOf(false) }

    LaunchedEffect(number) {
        blocked = withContext(Dispatchers.IO) { isNumberBlocked(context, number) }
    }

    fun copyNumber() {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Phone Number", number))
            Toast.makeText(context, "Number copied to clipboard", Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            Toast.makeText(context, "Unable to copy", Toast.LENGTH_SHORT).show()
        }
        onDismiss()
    }

    fun toggleBlock() {
        if (blockBusy) return
        blockBusy = true
        val currentlyBlocked = blocked == true
        coroutineScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (currentlyBlocked) unblockNumberByNumber(context, number) else blockSystemNumber(context, number)
            }
            blockBusy = false
            if (ok) {
                blocked = !currentlyBlocked
                Toast.makeText(
                    context,
                    if (currentlyBlocked) "Number unblocked" else "Number blocked",
                    Toast.LENGTH_SHORT
                ).show()
                onDismiss()
            } else {
                Toast.makeText(context, "Could not change blocked state. Set SmartDialer as the default phone app first.", Toast.LENGTH_LONG).show()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ContactAvatar(
                    contact?.name?.takeIf { it.isNotBlank() } ?: number,
                    contact?.photoUri,
                    52.dp
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = contact?.name?.takeIf { it.isNotBlank() } ?: number,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (contact?.name?.isNotBlank() == true) {
                        Text(
                            text = number,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            SheetRow(Icons.Rounded.Call, "Call") {
                onCall()
                onDismiss()
            }
            if (contact == null) {
                SheetRow(Icons.Rounded.PersonAdd, "Save as new contact") {
                    onSaveNew(number)
                }
            }
            SheetRow(Icons.Rounded.GroupAdd, "Add to existing contact") {
                showPicker = true
            }
            SheetRow(Icons.Rounded.ContentCopy, "Copy number") {
                copyNumber()
            }
            if (contact != null) {
                SheetRow(
                    if (contact.isFavorite) Icons.Rounded.StarBorder else Icons.Rounded.Star,
                    if (contact.isFavorite) "Remove from favorites" else "Add to favorites"
                ) {
                    onToggleFavorite()
                    onDismiss()
                }
                SheetRow(Icons.Rounded.Person, "View contact") {
                    onOpenProfile()
                    onDismiss()
                }
            }
            SheetRow(
                Icons.Rounded.Block,
                if (blocked == true) "Unblock number" else "Block number",
                danger = blocked != true
            ) {
                toggleBlock()
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showPicker) {
        val query = pickerQuery.trim()
        val filtered = remember(query, contacts) {
            if (query.isBlank()) {
                contacts.sortedBy { it.name.lowercase() }
            } else {
                contacts.filter {
                    it.name.contains(query, true) ||
                        it.number.contains(query, true) ||
                        it.number.filter(Char::isDigit).contains(query.filter(Char::isDigit))
                }.sortedBy { it.name.lowercase() }
            }
        }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Add to existing contact") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pickerQuery,
                        onValueChange = { pickerQuery = it },
                        label = { Text("Search contacts") },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(filtered, key = { "${it.id}_${it.number}" }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showPicker = false
                                        onAddToExisting(item, number)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ContactAvatar(
                                    item.name.ifBlank { item.number },
                                    item.photoUri,
                                    42.dp
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = item.name.ifBlank { item.number },
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = item.number,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            text = "No contacts match.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            fontSize = 16.sp,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
