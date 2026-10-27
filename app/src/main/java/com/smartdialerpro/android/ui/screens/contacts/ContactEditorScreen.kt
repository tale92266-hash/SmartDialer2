package com.smartdialerpro.android.ui.screens.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.smartdialerpro.android.repository.ContactsRepository
import com.smartdialerpro.android.util.Utils
import kotlinx.coroutines.launch

/**
 * In-app contact editor: creates a new contact or edits an existing one
 * directly in the system contacts provider, without leaving SmartDialer.
 * Extra phone numbers and emails beyond the visible fields are preserved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactEditorScreen(
    contactId: String?,
    prefilledNumber: String,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { ContactsRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var phone1 by remember { mutableStateOf(prefilledNumber) }
    var phone2 by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var preservedPhones by remember { mutableStateOf<List<String>>(emptyList()) }
    var preservedEmails by remember { mutableStateOf<List<String>>(emptyList()) }
    var detailsLoaded by remember { mutableStateOf(contactId == null) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var writeGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED
        )
    }
    var pendingSave by remember { mutableStateOf(false) }

    val writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        writeGranted = granted
        if (!granted) {
            pendingSave = false
            errorMessage = "Contacts permission is needed to save."
        }
    }

    fun openSystemEditorFallback() {
        try {
            if (contactId != null) {
                val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, contactId)
                context.startActivity(
                    Intent(Intent.ACTION_EDIT).apply {
                        setDataAndType(uri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                    }
                )
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_INSERT).apply {
                        type = ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(ContactsContract.Intents.Insert.NAME, name.trim())
                        putExtra(ContactsContract.Intents.Insert.PHONE, phone1.trim())
                        if (phone2.isNotBlank()) {
                            putExtra(ContactsContract.Intents.Insert.SECONDARY_PHONE, phone2.trim())
                        }
                        if (email.isNotBlank()) {
                            putExtra(ContactsContract.Intents.Insert.EMAIL, email.trim())
                        }
                    }
                )
            }
        } catch (_: Throwable) {
            Toast.makeText(context, "Unable to open the system editor", Toast.LENGTH_SHORT).show()
        }
    }

    fun saveContact() {
        val trimmedName = name.trim()
        val trimmedPhone = phone1.trim()
        if (trimmedName.isBlank()) {
            errorMessage = "Enter a name"
            return
        }
        if (trimmedPhone.isBlank()) {
            errorMessage = "Enter a phone number"
            return
        }
        if (!writeGranted) {
            pendingSave = true
            writeLauncher.launch(Manifest.permission.WRITE_CONTACTS)
            return
        }
        errorMessage = null
        isSaving = true
        coroutineScope.launch {
            val phones = buildList {
                add(trimmedPhone)
                phone2.trim().takeIf { it.isNotBlank() }?.let { add(it) }
                addAll(preservedPhones)
            }
            val emails = buildList {
                email.trim().takeIf { it.isNotBlank() }?.let { add(it) }
                addAll(preservedEmails)
            }
            val saved = if (contactId != null) {
                repository.updateContact(contactId, trimmedName, phones, emails)
            } else {
                repository.createContact(trimmedName, phones, emails)
            }
            isSaving = false
            if (saved) {
                Toast.makeText(context, "Contact saved", Toast.LENGTH_SHORT).show()
                onSaved()
            } else {
                errorMessage = "Could not save here. Opening the system editor instead."
                openSystemEditorFallback()
            }
        }
    }

    LaunchedEffect(writeGranted) {
        if (writeGranted && pendingSave) {
            pendingSave = false
            saveContact()
        }
    }

    LaunchedEffect(contactId) {
        if (contactId == null) return@LaunchedEffect
        val details = repository.getContactDetails(contactId)
        if (details != null) {
            name = details.name
            phone1 = details.phones.getOrNull(0).orEmpty()
            phone2 = details.phones.getOrNull(1).orEmpty()
            preservedPhones = details.phones.drop(2)
            email = details.emails.getOrNull(0).orEmpty()
            preservedEmails = details.emails.drop(1)
            if (prefilledNumber.isNotBlank() && details.phones.none { Utils.isSameNumber(it, prefilledNumber) }) {
                if (phone2.isBlank()) {
                    phone2 = prefilledNumber
                } else {
                    preservedPhones = preservedPhones + prefilledNumber
                }
            }
        }
        detailsLoaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (contactId != null) "Edit contact" else "New contact") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { saveContact() }, enabled = !isSaving) {
                        Text("Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (!detailsLoaded) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        keyboardType = KeyboardType.Text
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone1,
                    onValueChange = { phone1 = it },
                    label = { Text("Phone number") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone2,
                    onValueChange = { phone2 = it },
                    label = { Text("Second phone (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                errorMessage?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { saveContact() },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text(if (contactId != null) "Save changes" else "Save contact")
                }
            }
        }
    }
}
