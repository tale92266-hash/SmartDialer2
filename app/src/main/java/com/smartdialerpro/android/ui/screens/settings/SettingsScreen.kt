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
import com.smartdialerpro.android.ui.navigation.Screen

private const val PREFS = "app_prefs"
private const val DIALER_PREFS = "dialer_preferences"
private const val KEY_ACCOUNT_ID = "selected_phone_account_id"

@Composable
fun SettingsScreen(navController: NavController? = null) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var vibrate by remember { mutableStateOf(prefs.getBoolean("dialer_haptic", true)) }
    var dtmfTone by remember { mutableStateOf(prefs.getBoolean("dialer_tones", true)) }
    var confirmCall by remember { mutableStateOf(prefs.getBoolean("confirm_call", false)) }
    var autoRecord by remember { mutableStateOf(prefs.getBoolean("auto_record_calls", false)) }
    var showOnlyContacts by remember { mutableStateOf(prefs.getBoolean("show_only_contacts", false)) }
    val dialerPrefs = remember { context.getSharedPreferences(DIALER_PREFS, Context.MODE_PRIVATE) }
    val selectedAccountId by remember { mutableStateOf(dialerPrefs.getString(KEY_ACCOUNT_ID, null)) }

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

        item { SectionTitle("Calling & SIM") }
        item {
            SettingRow(
                icon = Icons.Rounded.SimCard,
                title = "SIM selection",
                subtitle = if (selectedAccountId.isNullOrBlank()) "System default • choose per call" else "Configured in dialer"
            ) {
                ToastHelper.show(context, "You can select your preferred SIM directly in the Dialer or Contact Profile.")
            }
        }
        item {
            SettingSwitchRow(
                icon = Icons.Rounded.Call,
                title = "Confirm before calling",
                subtitle = "Ask for confirmation before placing an outgoing call",
                checked = confirmCall
            ) {
                confirmCall = it
                prefs.edit().putBoolean("confirm_call", it).apply()
            }
        }
        item {
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
        }

        item { SectionTitle("Dialpad & Feedback") }
        item {
            SettingSwitchRow(
                icon = Icons.Rounded.Vibration,
                title = "Haptic feedback",
                subtitle = "Vibrate lightly on dialpad presses and deletions",
                checked = vibrate
            ) {
                vibrate = it
                prefs.edit().putBoolean("dialer_haptic", it).apply()
            }
        }
        item {
            SettingSwitchRow(
                icon = Icons.Rounded.VolumeUp,
                title = "Dialpad audio tones",
                subtitle = "Play DTMF sound tones when dialing digits",
                checked = dtmfTone
            ) {
                dtmfTone = it
                prefs.edit().putBoolean("dialer_tones", it).apply()
            }
        }
        item {
            SettingSwitchRow(
                icon = Icons.Rounded.Contacts,
                title = "Contacts only in suggestions",
                subtitle = "Hide unknown numbers from dialpad smart matches",
                checked = showOnlyContacts
            ) {
                showOnlyContacts = it
                prefs.edit().putBoolean("show_only_contacts", it).apply()
            }
        }

        item { SectionTitle("Blocked Numbers & Privacy") }
        item {
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
        }

        item { SectionTitle("System & Application") }
        item {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            val isDefault = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
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
        }
        item {
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
        }
        item {
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
        item {
            SettingRow(
                icon = Icons.Rounded.Info,
                title = "About SmartDialer",
                subtitle = "Version 1.0 • Android 16 / Material Expressive"
            ) {}
        }
        item {
            Spacer(Modifier.height(40.dp))
        }
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
