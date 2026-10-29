package com.smartdialerpro.android.ui.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Rounded.Home)
    object Dialer : Screen("phone", "Phone", Icons.Rounded.Phone)
    object Contacts : Screen("contacts", "Contacts", Icons.Rounded.Contacts)
    object Settings : Screen("settings", "Settings", Icons.Rounded.Settings)
    object Favorites : Screen("favorites", "Favorites", Icons.Rounded.Favorite)
    object UniversalSearch : Screen("universal_search", "Search", Icons.Rounded.Search)
    object BlockedNumbers : Screen("blocked_numbers", "Blocked Numbers", Icons.Rounded.Block)
    object Recordings : Screen("recordings", "Recordings", Icons.Rounded.FiberManualRecord)
    object SettingsGeneral : Screen("settings_general", "General", Icons.Rounded.Settings)
    object SettingsCalls : Screen("settings_calls", "Calls & SIM", Icons.Rounded.SimCard)
    object SettingsRecording : Screen("settings_recording", "Recording", Icons.Rounded.FiberManualRecord)
    object SettingsPrivacy : Screen("settings_privacy", "Privacy & Blocked", Icons.Rounded.Security)
    object SettingsSystem : Screen("settings_system", "System & About", Icons.Rounded.Info)
    object SettingsMagicCall : Screen("settings_magic_call", "Magic Call", Icons.Rounded.AutoAwesome)
    object QuickResponses : Screen("quick_responses", "Quick Responses", Icons.Rounded.Message)
    object CallStats : Screen("call_stats", "Call statistics", Icons.Rounded.History)
    object CallDetails : Screen("call_details", "Call details", Icons.Rounded.Info)
    object SetupWizard : Screen("setup_wizard", "Set up SmartDialer", Icons.Rounded.CheckCircle)
    object PrivacyCentre : Screen("privacy_centre", "Privacy centre", Icons.Rounded.Security)
    object ContactProfile : Screen("contact_profile/{number}", "Profile", Icons.Rounded.Person) {
        fun createRoute(number: String) = "contact_profile/$number"
    }
    object ContactEditor : Screen("contact_editor?contactId={contactId}&number={number}", "Edit Contact", Icons.Rounded.PersonAdd) {
        fun createRoute(contactId: String?, number: String) =
            "contact_editor?contactId=${contactId.orEmpty()}&number=${Uri.encode(number)}"
    }
    object AddToExisting : Screen("add_to_existing/{number}", "Add to existing", Icons.Rounded.GroupAdd) {
        fun createRoute(number: String) = "add_to_existing/${Uri.encode(number)}"
    }
}
