package com.smartdialerpro.android.ui.screens.phone

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.smartdialerpro.android.ui.screens.home.HomeScreen
import com.smartdialerpro.android.viewmodel.ContactsViewModel
import com.smartdialerpro.android.viewmodel.DialerViewModel
import com.smartdialerpro.android.viewmodel.RecentsViewModel

/**
 * Backward-compatible delegator to the unified HomeScreen with dialer active.
 */
@Composable
fun PhoneScreen(
    contactsViewModel: ContactsViewModel,
    dialerViewModel: DialerViewModel,
    recentsViewModel: RecentsViewModel,
    snackbarHostState: SnackbarHostState,
    navController: NavController
) {
    HomeScreen(
        navController = navController,
        contactsViewModel = contactsViewModel,
        recentsViewModel = recentsViewModel,
        dialerViewModel = dialerViewModel,
        startWithDialerOpen = true
    )
}
