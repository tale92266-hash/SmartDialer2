#!/bin/bash
set -e

echo "==> Ensuring directories exist..."
mkdir -p app/src/main/java/com/smartdialerpro/android/ui/navigation
mkdir -p app/src/main/java/com/smartdialerpro/android/ui/screens/settings

echo "==> Writing app/src/main/java/com/smartdialerpro/android/ui/navigation/Screen.kt..."
cat << 'EOF' > app/src/main/java/com/smartdialerpro/android/ui/navigation/Screen.kt
package com.smartdialerpro.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
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
    object ContactProfile : Screen("contact_profile/{number}", "Profile", Icons.Rounded.Person) {
        fun createRoute(number: String) = "contact_profile/$number"
    }
}
EOF

echo "==> Screen.kt updated successfully! Re-run your build."
