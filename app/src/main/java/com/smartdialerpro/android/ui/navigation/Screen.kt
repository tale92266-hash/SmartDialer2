package com.smartdialerpro.android.ui.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
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
    object ContactProfile : Screen("contact_profile/{number}", "Profile", Icons.Rounded.Person) {
        fun createRoute(number: String) = "contact_profile/$number"
    }
    object ContactEditor : Screen("contact_editor?contactId={contactId}&number={number}", "Edit Contact", Icons.Rounded.PersonAdd) {
        fun createRoute(contactId: String?, number: String) =
            "contact_editor?contactId=${contactId.orEmpty()}&number=${Uri.encode(number)}"
    }
}
