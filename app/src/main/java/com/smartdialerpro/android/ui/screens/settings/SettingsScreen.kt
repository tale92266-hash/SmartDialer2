package com.smartdialerpro.android.ui.screens.settings

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.smartdialerpro.android.ui.components.CompactTopBar
import com.smartdialerpro.android.ui.navigation.Screen

private const val PREFS = "app_prefs"
private const val DIALER_PREFS = "dialer_preferences"
private const val KEY_ACCOUNT_ID = "selected_phone_account_id"

/** Settings home: professional group cards, each opening its own sub-screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController? = null) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Settings", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(
                "Customize your calling & dialer experience",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )
        }
        item {
            SettingsGroupCard(
                icon = Icons.Rounded.Settings,
                title = "General",
                summary = "Dialpad feedback, tones & suggestions"
            ) { navController?.navigate(Screen.SettingsGeneral.route) }
        }
        item {
            SettingsGroupCard(
                icon = Icons.Rounded.SimCard,
                title = "Calls & SIM",
                summary = "SIM selection & call confirmation"
            ) { navController?.navigate(Screen.SettingsCalls.route) }
        }
        item {
            SettingsGroupCard(
                icon = Icons.Rounded.FiberManualRecord,
                title = "Recording",
                summary = "Auto-record & saved recordings"
            ) { navController?.navigate(Screen.SettingsRecording.route) }
        }
        item {
            SettingsGroupCard(
                icon = Icons.Rounded.Security,
                title = "Privacy & Blocked",
                summary = "Blocked numbers & app permissions"
            ) { navController?.navigate(Screen.SettingsPrivacy.route) }
        }
        item {
            SettingsGroupCard(
                icon = Icons.Rounded.Info,
                title = "System & About",
                summary = "Default dialer, notifications & version"
            ) { navController?.navigate(Screen.SettingsSystem.route) }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@Composable
private fun SettingsGroupCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(24.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    summary,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Scaffold(
        topBar = { CompactTopBar(title = title, onBack = onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
fun SettingsGeneralScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var vibrate by remember { mutableStateOf(prefs.getBoolean("dialer_haptic", true)) }
    var dtmfTone by remember { mutableStateOf(prefs.getBoolean("dialer_tones", true)) }
    var showOnlyContacts by remember { mutableStateOf(prefs.getBoolean("show_only_contacts", false)) }
    var openDialpadOnLaunch by remember { mutableStateOf(prefs.getBoolean("open_dialpad_on_launch", false)) }
    SettingsSubScreenScaffold(title = "General", onBack = { navController?.popBackStack() }) {
        SettingSwitchRow(
            icon = Icons.Rounded.Vibration,
            title = "Haptic feedback",
            subtitle = "Vibrate lightly on dialpad presses and deletions",
            checked = vibrate
        ) {
            vibrate = it
            prefs.edit().putBoolean("dialer_haptic", it).apply()
        }
        SettingSwitchRow(
            icon = Icons.Rounded.VolumeUp,
            title = "Dialpad audio tones",
            subtitle = "Play DTMF sound tones when dialing digits",
            checked = dtmfTone
        ) {
            dtmfTone = it
            prefs.edit().putBoolean("dialer_tones", it).apply()
        }
        SettingSwitchRow(
            icon = Icons.Rounded.Contacts,
            title = "Contacts only in suggestions",
            subtitle = "Hide unknown numbers from dialpad smart matches",
            checked = showOnlyContacts
        ) {
            showOnlyContacts = it
            prefs.edit().putBoolean("show_only_contacts", it).apply()
        }
        SettingSwitchRow(
            icon = Icons.Rounded.Dialpad,
            title = "Open dialpad on launch",
            subtitle = "Show the dialpad when the app starts",
            checked = openDialpadOnLaunch
        ) {
            openDialpadOnLaunch = it
            prefs.edit().putBoolean("open_dialpad_on_launch", it).apply()
        }
    }
}

@Composable
fun SettingsCallsScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var confirmCall by remember { mutableStateOf(prefs.getBoolean("confirm_call", false)) }
    var vibrateOnAnswer by remember { mutableStateOf(prefs.getBoolean("vibrate_on_answer", false)) }
    val dialerPrefs = remember { context.getSharedPreferences(DIALER_PREFS, Context.MODE_PRIVATE) }
    val selectedAccountId by remember { mutableStateOf(dialerPrefs.getString(KEY_ACCOUNT_ID, null)) }
    SettingsSubScreenScaffold(title = "Calls & SIM", onBack = { navController?.popBackStack() }) {
        SettingRow(
            icon = Icons.Rounded.SimCard,
            title = "SIM selection",
            subtitle = if (selectedAccountId.isNullOrBlank()) "System default • choose per call" else "Configured in dialer"
        ) {
            ToastHelper.show(context, "You can select your preferred SIM directly in the Dialer or Contact Profile.")
        }
        SettingSwitchRow(
            icon = Icons.Rounded.Call,
            title = "Confirm before calling",
            subtitle = "Ask for confirmation before placing an outgoing call",
            checked = confirmCall
        ) {
            confirmCall = it
            prefs.edit().putBoolean("confirm_call", it).apply()
        }
        SettingSwitchRow(
            icon = Icons.Rounded.Vibration,
            title = "Vibrate when call connects",
            subtitle = "Short vibration when the other side answers",
            checked = vibrateOnAnswer
        ) {
            vibrateOnAnswer = it
            prefs.edit().putBoolean("vibrate_on_answer", it).apply()
        }
    }
}

@Composable
fun SettingsRecordingScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var autoRecord by remember { mutableStateOf(prefs.getBoolean("auto_record_calls", false)) }
    var unknownOnly by remember { mutableStateOf(prefs.getBoolean("auto_record_unknown_only", false)) }
    SettingsSubScreenScaffold(title = "Recording", onBack = { navController?.popBackStack() }) {
        SettingSwitchRow(
            icon = Icons.Rounded.FiberManualRecord,
            title = "Auto-record active calls",
            subtitle = "Automatically trigger recording on answered phone calls",
            checked = autoRecord
        ) {
            autoRecord = it
            prefs.edit().putBoolean("auto_record_calls", it).apply()
            if (it) ToastHelper.show(context, "Recording will start when active calls connect.")
        }
        SettingSwitchRow(
            icon = Icons.Rounded.Person,
            title = "Unknown numbers only",
            subtitle = "Auto-record only calls from numbers not in your contacts",
            checked = unknownOnly
        ) {
            unknownOnly = it
            prefs.edit().putBoolean("auto_record_unknown_only", it).apply()
        }
        SettingRow(
            icon = Icons.Rounded.FiberManualRecord,
            title = "Call recordings",
            subtitle = "Play, share, or delete your recorded calls"
        ) {
            if (navController != null) {
                navController.navigate(Screen.Recordings.route)
            } else {
                ToastHelper.show(context, "Navigation controller unavailable")
            }
        }
    }
}

@Composable
fun SettingsPrivacyScreen(navController: NavController? = null) {
    val context = LocalContext.current
    SettingsSubScreenScaffold(title = "Privacy & Blocked", onBack = { navController?.popBackStack() }) {
        SettingRow(
            icon = Icons.Rounded.Block,
            title = "Blocked numbers",
            subtitle = "Manage system-wide blocked callers and spam rejection"
        ) {
            if (navController != null) {
                navController.navigate(Screen.BlockedNumbers.route)
            } else {
                ToastHelper.show(context, "Navigation controller unavailable")
            }
        }
        SettingRow(
            icon = Icons.Rounded.Security,
            title = "App permissions",
            subtitle = "Manage Phone, Microphone, and Contacts permissions"
        ) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                ToastHelper.show(context, "Unable to open app settings.")
            }
        }
    }
}

@Composable
fun SettingsSystemScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var keepScreenOn by remember { mutableStateOf(prefs.getBoolean("keep_screen_on", false)) }
    SettingsSubScreenScaffold(title = "System & About", onBack = { navController?.popBackStack() }) {
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        val isDefault = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        SettingSwitchRow(
            icon = Icons.Rounded.Visibility,
            title = "Keep screen on during calls",
            subtitle = "Prevent the display from sleeping on the call screen",
            checked = keepScreenOn
        ) {
            keepScreenOn = it
            prefs.edit().putBoolean("keep_screen_on", it).apply()
        }
        SettingRow(
            icon = Icons.Rounded.Phone,
            title = "Default phone app",
            subtitle = if (isDefault) "SmartDialer is active default dialer" else "Set SmartDialer as system default"
        ) {
            try {
                if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                    context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else ToastHelper.show(context, "Default dialer role is unavailable on this device.")
            } catch (_: Throwable) {
                ToastHelper.show(context, "Unable to launch default phone app settings.")
            }
        }
        SettingRow(
            icon = Icons.Rounded.Notifications,
            title = "Call notifications",
            subtitle = "Configure ongoing, incoming, and heads-up banner alerts"
        ) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                ToastHelper.show(context, "Unable to open notification settings.")
            }
        }
        SettingRow(
            icon = Icons.Rounded.Info,
            title = "About SmartDialer",
            subtitle = "Version 1.0"
        ) {}
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp)
    )
}

@Composable
private fun SettingRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(23.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SettingSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(23.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            ) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

private object ToastHelper {
    fun show(context: Context, message: String) =
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}
