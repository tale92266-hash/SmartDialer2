package com.smartdialerpro.android.ui.screens.contacts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.smartdialerpro.android.repository.ContactsRepository
import com.smartdialerpro.android.repository.SaveTarget
import com.smartdialerpro.android.util.Utils
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var phone1Type by remember { mutableStateOf(ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) }
    var phone2Type by remember { mutableStateOf(ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) }
    var newPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var existingPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var saveTargets by remember { mutableStateOf<List<SaveTarget>>(emptyList()) }
    var selectedTarget by remember { mutableStateOf<SaveTarget?>(null) }
    var showSaveToSheet by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                newPhotoBytes = loadPhotoBytes(context, uri)
                if (newPhotoBytes == null) {
                    errorMessage = "Could not read that photo."
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val targets = repository.loadSaveTargets()
        saveTargets = targets
        val savedKey = prefs.getString("contact_save_target", null)
        selectedTarget = targets.firstOrNull {
            "${it.accountName}|${it.accountType}" == savedKey
        } ?: targets.firstOrNull { it.accountType == "com.google" } ?: targets.firstOrNull()
    }

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
                repository.updateContact(
                    contactId,
                    trimmedName,
                    phones,
                    emails,
                    phoneTypes = listOf(phone1Type, phone2Type),
                    photoBytes = newPhotoBytes
                )
            } else {
                val target = selectedTarget
                if (target != null) {
                    prefs.edit().putString(
                        "contact_save_target",
                        "${target.accountName}|${target.accountType}"
                    ).apply()
                }
                repository.createContact(
                    trimmedName,
                    phones,
                    emails,
                    accountName = target?.accountName,
                    accountType = target?.accountType,
                    phoneTypes = listOf(phone1Type, phone2Type),
                    photoBytes = newPhotoBytes
                )
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
            phone1Type = details.phoneTypes.getOrNull(0) ?: ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            phone2Type = details.phoneTypes.getOrNull(1) ?: ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            existingPhotoBytes = details.photoBytes
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
                title = { Text(if (contactId != null) "Edit contact" else "Create contact") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { saveContact() },
                        enabled = !isSaving && name.isNotBlank() && phone1.isNotBlank(),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text("Save", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
                // Photo circle with initials fallback; tap the badge to pick a photo.
                val previewBytes = newPhotoBytes ?: existingPhotoBytes
                val previewBitmap = remember(previewBytes) {
                    previewBytes?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(contentAlignment = Alignment.BottomEnd) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable { photoPicker.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (previewBitmap != null) {
                                Image(
                                    bitmap = previewBitmap.asImageBitmap(),
                                    contentDescription = "Contact photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                // Default state: a camera icon, never a "?" or an initial.
                                Icon(
                                    Icons.Rounded.PhotoCamera,
                                    contentDescription = "Add photo",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                        }
                        if (previewBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable { photoPicker.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = "Change photo",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
                // Save location (new contacts only): Phone, Google account, SIM.
                if (contactId == null && selectedTarget != null) {
                    val target = selectedTarget!!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .clickable { showSaveToSheet = true }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(23.dp)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp)
                        ) {
                            Text(
                                "Save to",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${target.title} • ${target.subtitle}",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        keyboardType = KeyboardType.Text
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                LabelledPhoneField(
                    value = phone1,
                    onValueChange = { phone1 = it },
                    label = "Phone number",
                    type = phone1Type,
                    onTypeChange = { phone1Type = it }
                )
                LabelledPhoneField(
                    value = phone2,
                    onValueChange = { phone2 = it },
                    label = "Second phone (optional)",
                    type = phone2Type,
                    onTypeChange = { phone2Type = it }
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (optional)") },
                    leadingIcon = { Icon(Icons.Rounded.MailOutline, contentDescription = null) },
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
            }
        }
        if (showSaveToSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSaveToSheet = false },
                sheetState = rememberModalBottomSheetState()
            ) {
                Text(
                    "Save contact to",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                saveTargets.forEach { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedTarget = target
                                showSaveToSheet = false
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.AccountCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(23.dp)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 16.dp)
                        ) {
                            Text(target.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                target.subtitle,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (target == selectedTarget) {
                            Icon(Icons.Rounded.Done, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun LabelledPhoneField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    type: Int,
    onTypeChange: (Int) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val typeLabel = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Home"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Work"
        else -> "Mobile"
    }
    Box {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Rounded.Phone, contentDescription = null) },
            trailingIcon = {
                TextButton(onClick = { menuExpanded = true }) {
                    Text(typeLabel)
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Mobile") },
                onClick = {
                    onTypeChange(ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    menuExpanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Home") },
                onClick = {
                    onTypeChange(ContactsContract.CommonDataKinds.Phone.TYPE_HOME)
                    menuExpanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("Work") },
                onClick = {
                    onTypeChange(ContactsContract.CommonDataKinds.Phone.TYPE_WORK)
                    menuExpanded = false
                }
            )
        }
    }
}

private suspend fun loadPhotoBytes(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
        val original = input.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
        val maxSide = 512
        val scale = minOf(1f, maxSide / original.width.toFloat(), maxSide / original.height.toFloat())
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                original,
                (original.width * scale).toInt(),
                (original.height * scale).toInt(),
                true
            )
        } else {
            original
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        out.toByteArray()
    } catch (_: Throwable) {
        null
    }
}
