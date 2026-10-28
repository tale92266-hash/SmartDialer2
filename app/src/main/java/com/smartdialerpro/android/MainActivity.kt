package com.smartdialerpro.android

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.smartdialerpro.android.ui.MainScreen
import com.smartdialerpro.android.ui.theme.SmartDialerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // CNAP name cache (network-delivered caller names).
        com.smartdialerpro.android.data.CnapStore.init(this)
        // Appearance choices (accent colour + pure-black theme).
        com.smartdialerpro.android.data.ThemeSettings.init(this)
        setContent {
            val accent by com.smartdialerpro.android.data.ThemeSettings.accent.collectAsState()
            val pureBlack by com.smartdialerpro.android.data.ThemeSettings.pureBlack.collectAsState()
            SmartDialerTheme(accent = accent, pureBlack = pureBlack) {
                val context = LocalContext.current
                val roleManager = remember { context.getSystemService(Context.ROLE_SERVICE) as? RoleManager }
                var isDefaultDialer by remember { mutableStateOf(roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true) }
                var permissionRefresh by remember { mutableIntStateOf(0) }

                val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                    permissionRefresh++
                }
                val defaultDialerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                    isDefaultDialer = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            isDefaultDialer = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
                            permissionRefresh++
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val callGranted = hasPermission(context, Manifest.permission.CALL_PHONE)
                val contactsGranted = hasPermission(context, Manifest.permission.READ_CONTACTS)
                val callLogGranted = hasPermission(context, Manifest.permission.READ_CALL_LOG)
                val phoneStateGranted = hasPermission(context, Manifest.permission.READ_PHONE_STATE)
                val notificationsGranted = android.os.Build.VERSION.SDK_INT < 33 || hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                @Suppress("UNUSED_VARIABLE") val refresh = permissionRefresh

                when {
                    !isDefaultDialer -> DefaultDialerPromptScreen {
                        try {
                            if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                                defaultDialerLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                            } else Toast.makeText(context, "Default dialer role is unavailable on this device.", Toast.LENGTH_LONG).show()
                        } catch (t: Throwable) {
                            Toast.makeText(context, "Unable to open default phone app settings.", Toast.LENGTH_LONG).show()
                        }
                    }
                    !callGranted || !contactsGranted || !callLogGranted || !phoneStateGranted || !notificationsGranted -> PermissionSetupScreen(
                        callGranted = callGranted,
                        contactsGranted = contactsGranted,
                        callLogGranted = callLogGranted,
                        phoneStateGranted = phoneStateGranted,
                        notificationsGranted = notificationsGranted,
                        onGrant = {
                            val missing = buildList {
                                if (!callGranted) add(Manifest.permission.CALL_PHONE)
                                if (!contactsGranted) add(Manifest.permission.READ_CONTACTS)
                                if (!callLogGranted) add(Manifest.permission.READ_CALL_LOG)
                                if (!phoneStateGranted) add(Manifest.permission.READ_PHONE_STATE)
                                if (!notificationsGranted && android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
                        },
                        onOpenSettings = {
                            try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }
                            catch (t: Throwable) { Toast.makeText(context, "Unable to open app settings.", Toast.LENGTH_LONG).show() }
                        }
                    )
                    else -> MainScreen()
                }
            }
        }
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun DefaultDialerPromptScreen(onRequestDefault: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(100.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Phone, "Dialer", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(40.dp))
            Text("Set as Default Dialer", fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text("SmartDialer needs to be your default phone app to make, receive, and manage calls seamlessly.", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, lineHeight = 24.sp)
            Spacer(Modifier.height(48.dp))
            Button(onClick = onRequestDefault, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("Set as Default", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun PermissionSetupScreen(
    callGranted: Boolean,
    contactsGranted: Boolean,
    callLogGranted: Boolean,
    phoneStateGranted: Boolean,
    notificationsGranted: Boolean,
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val allGranted = callGranted && contactsGranted && callLogGranted && phoneStateGranted && notificationsGranted
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Phone, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(24.dp))
            Text("Permissions needed", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text("SmartDialer checks these permissions every time the app resumes. Recording permission is requested only when you start recording.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
            Spacer(Modifier.height(24.dp))
            PermissionRow("Phone calls", "Place outgoing calls", callGranted)
            PermissionRow("Contacts", "Read and manage contact favorites", contactsGranted)
            PermissionRow("Call history", "Show recent calls", callLogGranted)
            PermissionRow("Phone state", "Detect available SIM/phone accounts", phoneStateGranted)
            if (android.os.Build.VERSION.SDK_INT >= 33) PermissionRow("Notifications", "Show ongoing/incoming call notifications", notificationsGranted)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onGrant, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
                Text(if (allGranted) "Continue" else "Allow permissions", fontWeight = FontWeight.Bold)
            }
            if (!allGranted) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenSettings) { Text("Open app settings") }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, reason: String, granted: Boolean) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(reason, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (granted) "Allowed" else "Needed", color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}