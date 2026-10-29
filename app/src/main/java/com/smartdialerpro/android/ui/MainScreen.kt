package com.smartdialerpro.android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartdialerpro.android.repository.CallLogRepository
import com.smartdialerpro.android.repository.ContactsRepository
import com.smartdialerpro.android.service.AutoRedialManager
import com.smartdialerpro.android.service.CallManager
import com.smartdialerpro.android.ui.navigation.Screen
import com.smartdialerpro.android.ui.screens.contacts.AddToExistingScreen
import com.smartdialerpro.android.ui.screens.contacts.ContactEditorScreen
import com.smartdialerpro.android.ui.screens.contacts.ContactsScreen
import com.smartdialerpro.android.ui.screens.contacts.FavoritesSpeedDialScreen
import com.smartdialerpro.android.ui.screens.details.CallDetailsHolder
import com.smartdialerpro.android.ui.screens.details.CallDetailsScreen
import com.smartdialerpro.android.ui.screens.home.HomeScreen
import com.smartdialerpro.android.service.FakeCallSession
import com.smartdialerpro.android.ui.screens.fakecall.FakeCallActivity
import com.smartdialerpro.android.ui.screens.incall.InCallActivity
import kotlinx.coroutines.delay
import com.smartdialerpro.android.ui.screens.profile.ContactProfileScreen
import com.smartdialerpro.android.ui.screens.search.UniversalSearchScreen
import com.smartdialerpro.android.ui.screens.settings.BlockedNumbersScreen
import com.smartdialerpro.android.ui.screens.settings.CallStatsScreen
import com.smartdialerpro.android.ui.screens.settings.PrivacyCentreScreen
import com.smartdialerpro.android.ui.screens.settings.QuickResponsesScreen
import com.smartdialerpro.android.ui.screens.settings.RecordingsScreen
import com.smartdialerpro.android.ui.screens.settings.SettingsCallsScreen
import com.smartdialerpro.android.ui.screens.settings.SettingsGeneralScreen
import com.smartdialerpro.android.ui.screens.settings.SettingsMagicCallScreen
import com.smartdialerpro.android.ui.screens.settings.SettingsPrivacyScreen
import com.smartdialerpro.android.ui.screens.settings.SettingsRecordingScreen
import com.smartdialerpro.android.ui.screens.settings.SettingsScreen
import com.smartdialerpro.android.ui.screens.settings.SettingsSystemScreen
import com.smartdialerpro.android.ui.screens.settings.SetupWizardScreen
import com.smartdialerpro.android.viewmodel.AppViewModelFactory
import com.smartdialerpro.android.viewmodel.ContactsViewModel
import com.smartdialerpro.android.viewmodel.DialerViewModel
import com.smartdialerpro.android.viewmodel.RecentsViewModel

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val factory = remember { AppViewModelFactory(ContactsRepository(context), CallLogRepository(context)) }
    val contactsViewModel: ContactsViewModel = viewModel(factory = factory)
    val recentsViewModel: RecentsViewModel = viewModel(factory = factory)
    val dialerViewModel: DialerViewModel = viewModel(factory = factory)
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val items = listOf(Screen.Home, Screen.Favorites, Screen.Contacts, Screen.Settings)

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    // Tab signals: reselecting the current tab scrolls it to the top, and
    // leaving the Home tab closes the top search mode (dialled digits stay).
    var reselectSignal by remember { mutableStateOf(0) }
    var leaveHomeSignal by remember { mutableStateOf(0) }

    // Automatically sync active tab route into preferences
    LaunchedEffect(currentRoute) {
        if (currentRoute != null && items.any { it.route == currentRoute }) {
            prefs.edit().putString("last_route", currentRoute).apply()
        }
    }

    // Cold-start tab restoration
    var hasRestoredColdStart by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!hasRestoredColdStart) {
            hasRestoredColdStart = true
            val saved = prefs.getString("last_route", Screen.Home.route) ?: Screen.Home.route
            if (saved in listOf(Screen.Favorites.route, Screen.Contacts.route, Screen.Settings.route)) {
                navController.navigate(saved) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    val isHomeTab = currentRoute == Screen.Home.route
    val isTabRoute = items.any { it.route == currentRoute }
    var lastBackPressMillis by remember { mutableStateOf(0L) }

    // Home tab: double-back to exit. Registered first so the handlers
    // below (deeper in the back stack) win when they are enabled.
    BackHandler(enabled = isHomeTab) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressMillis < 2000L) {
            (context as? Activity)?.finish()
        } else {
            lastBackPressMillis = now
            Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
        }
    }
    // Other top-level tabs: back returns to the Home tab.
    BackHandler(enabled = isTabRoute && !isHomeTab) {
        navController.navigate(Screen.Home.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // Detail destinations (search, profile, editor, blocked numbers,
    // recordings): back pops to the previous destination.
    BackHandler(enabled = currentRoute != null && !isTabRoute) {
        navController.popBackStack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            PremiumBottomBar(items = items, navController = navController) { screen ->
                if (screen.route == currentRoute) {
                    reselectSignal++
                } else {
                    if (currentRoute == Screen.Home.route || currentRoute == Screen.Dialer.route) {
                        leaveHomeSignal++
                    }
                    // From any detail screen (profile, editor, search,
                    // settings sub-screens...), pop back to the Home tab
                    // first, so a tab tap always lands on the requested
                    // tab instead of leaving the detail screen in place.
                    if (!isTabRoute) {
                        navController.popBackStack(Screen.Home.route, inclusive = false)
                    }
                    // Every tab navigates, including Home: skipping the
                    // navigate call for Home left the user stuck on
                    // Favorites/Contacts/Settings, since the Home
                    // destination below them never came to the front.
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            val callState by CallManager.callState.collectAsState()
            val duration by CallManager.duration.collectAsState()
            val number by CallManager.callNumber.collectAsState()
            val callerName by CallManager.callerName.collectAsState()
            val fakeCallActive by FakeCallSession.activeFlow.collectAsState()
            val fakeCallConnected by FakeCallSession.connectedFlow.collectAsState()
            val pendingRedial by AutoRedialManager.pending.collectAsState()
            if (callState != Call.STATE_DISCONNECTED && callState != Call.STATE_NEW) {
                val label = callerName?.takeIf { it.isNotBlank() } ?: number.ifBlank { "Ongoing call" }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))
                        .clickable {
                            context.startActivity(Intent(context, InCallActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            })
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Phone, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (callState == Call.STATE_ACTIVE) "$label • ${String.format("%02d:%02d", duration / 60, duration % 60)}" else label,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Text("Return", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else if (fakeCallActive) {
                // Same return-to-call banner as a real call, for a Magic call.
                // fakeElapsed is READ below, so the 1s ticker actually
                // recomposes the banner and the timer ticks.
                var fakeElapsed by remember { mutableStateOf(0L) }
                LaunchedEffect(fakeCallActive, fakeCallConnected) {
                    while (fakeCallActive) {
                        fakeElapsed = if (fakeCallConnected && FakeCallSession.startTimeMillis > 0L) {
                            ((System.currentTimeMillis() - FakeCallSession.startTimeMillis) / 1000).coerceAtLeast(0)
                        } else 0L
                        delay(1000)
                    }
                }
                val fakeLabel = FakeCallSession.name.takeIf { it.isNotBlank() }
                    ?: FakeCallSession.number.ifBlank { "Ongoing call" }
                val fakeTimerText = if (fakeCallConnected && FakeCallSession.startTimeMillis > 0L) {
                    " • ${String.format("%02d:%02d", fakeElapsed / 60, fakeElapsed % 60)}"
                } else ""
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))
                        .clickable {
                            context.startActivity(Intent(context, FakeCallActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            })
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Phone, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "$fakeLabel$fakeTimerText",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Text("Return", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            } else if (pendingRedial != null) {
                // Auto-redial countdown: tapping Cancel stops the chain.
                val redial = pendingRedial!!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary)))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Refresh, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Redialing ${redial.displayName} in ${redial.secondsLeft}s (attempt ${redial.attempt} of ${redial.totalAttempts})",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    Text(
                        "Cancel",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { AutoRedialManager.cancel() }
                    )
                }
            }
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.weight(1f),
                // Directional swipe: moving to a tab on the right slides the
                // new screen in from the right (and vice versa); detail
                // screens keep the forward/back convention.
                enterTransition = {
                    val dir = navSwipeDir(initialState.destination.route, targetState.destination.route, 1)
                    fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 5 * dir }
                },
                exitTransition = {
                    val dir = navSwipeDir(initialState.destination.route, targetState.destination.route, 1)
                    fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -it / 5 * dir }
                },
                popEnterTransition = {
                    val dir = navSwipeDir(initialState.destination.route, targetState.destination.route, -1)
                    fadeIn(tween(160)) + slideInHorizontally(tween(220)) { it / 5 * dir }
                },
                popExitTransition = {
                    val dir = navSwipeDir(initialState.destination.route, targetState.destination.route, -1)
                    fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { -it / 5 * dir }
                }
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(navController, contactsViewModel, recentsViewModel, dialerViewModel, startWithDialerOpen = prefs.getBoolean("open_dialpad_on_launch", false), clearSearchSignal = leaveHomeSignal, reselectSignal = reselectSignal)
                }
                composable(Screen.Dialer.route) {
                    HomeScreen(navController, contactsViewModel, recentsViewModel, dialerViewModel, startWithDialerOpen = true, clearSearchSignal = leaveHomeSignal, reselectSignal = reselectSignal)
                }
                composable(Screen.Favorites.route) {
                    FavoritesSpeedDialScreen(contactsViewModel, navController, reselectSignal = reselectSignal)
                }
                composable(Screen.Contacts.route) {
                    ContactsScreen(contactsViewModel, navController, reselectSignal = reselectSignal)
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(navController)
                }
                composable(Screen.BlockedNumbers.route) {
                    BlockedNumbersScreen(onBack = { navController.popBackStack() })
                }
                composable(Screen.Recordings.route) {
                    RecordingsScreen(onBack = { navController.popBackStack() })
                }
                composable(Screen.SettingsGeneral.route) {
                    SettingsGeneralScreen(navController)
                }
                composable(Screen.SettingsCalls.route) {
                    SettingsCallsScreen(navController)
                }
                composable(Screen.SettingsRecording.route) {
                    SettingsRecordingScreen(navController)
                }
                composable(Screen.SettingsPrivacy.route) {
                    SettingsPrivacyScreen(navController)
                }
                composable(Screen.SettingsSystem.route) {
                    SettingsSystemScreen(navController)
                }
                composable(Screen.SettingsMagicCall.route) {
                    SettingsMagicCallScreen(navController)
                }
                composable(Screen.QuickResponses.route) {
                    QuickResponsesScreen(navController)
                }
                composable(Screen.CallStats.route) {
                    CallStatsScreen(onBack = { navController.popBackStack() })
                }
                composable(Screen.CallDetails.route) {
                    CallDetailsScreen(navController)
                }
                composable(Screen.SetupWizard.route) {
                    SetupWizardScreen(navController)
                }
                composable(Screen.PrivacyCentre.route) {
                    PrivacyCentreScreen(navController)
                }
                composable(
                    route = Screen.ContactEditor.route,
                    arguments = listOf(
                        navArgument("contactId") { type = NavType.StringType; defaultValue = "" },
                        navArgument("number") { type = NavType.StringType; defaultValue = "" }
                    )
                ) { backStackEntry ->
                    val contactIdArg = backStackEntry.arguments?.getString("contactId")?.takeIf { it.isNotBlank() }
                    val numberArg = backStackEntry.arguments?.getString("number") ?: ""
                    ContactEditorScreen(
                        contactId = contactIdArg,
                        prefilledNumber = numberArg,
                        onBack = { navController.popBackStack() },
                        onSaved = {
                            contactsViewModel.loadContacts(force = true)
                            recentsViewModel.loadRecents(force = true)
                            navController.popBackStack()
                        }
                    )
                }
                composable(
                    route = Screen.AddToExisting.route,
                    arguments = listOf(navArgument("number") { type = NavType.StringType }),
                    enterTransition = { fadeIn(tween(150)) + slideInHorizontally(tween(220)) { it / 4 } },
                    exitTransition = { fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { it / 4 } },
                    popEnterTransition = { fadeIn(tween(150)) },
                    popExitTransition = { fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { it / 4 } }
                ) { backStackEntry ->
                    val numberArg = backStackEntry.arguments?.getString("number") ?: ""
                    AddToExistingScreen(
                        number = numberArg,
                        contactsViewModel = contactsViewModel,
                        onBack = { navController.popBackStack() },
                        onPick = { contact ->
                            navController.navigate(Screen.ContactEditor.createRoute(contact.id, numberArg)) {
                                popUpTo(Screen.AddToExisting.route) { inclusive = true }
                            }
                        }
                    )
                }
                composable(
                    route = Screen.UniversalSearch.route,
                    enterTransition = { fadeIn(tween(140)) + slideInHorizontally(tween(200)) { it / 5 } },
                    exitTransition = { fadeOut(tween(110)) + slideOutHorizontally(tween(180)) { it / 5 } },
                    popEnterTransition = { fadeIn(tween(140)) },
                    popExitTransition = { fadeOut(tween(110)) + slideOutHorizontally(tween(180)) { it / 5 } }
                ) {
                    UniversalSearchScreen(contactsViewModel, recentsViewModel, navController, onClose = { navController.popBackStack() })
                }
                composable(
                    route = Screen.ContactProfile.route,
                    arguments = listOf(navArgument("number") { type = NavType.StringType }),
                    enterTransition = { fadeIn(tween(150)) + slideInHorizontally(tween(220)) { it / 4 } },
                    exitTransition = { fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { it / 4 } },
                    popEnterTransition = { fadeIn(tween(150)) },
                    popExitTransition = { fadeOut(tween(120)) + slideOutHorizontally(tween(200)) { it / 4 } }
                ) { backStackEntry ->
                    ContactProfileScreen(
                        number = backStackEntry.arguments?.getString("number") ?: "",
                        onBack = { navController.popBackStack() },
                        onOpenEditor = { contactId, editorNumber ->
                            navController.navigate(Screen.ContactEditor.createRoute(contactId, editorNumber))
                        },
                        onOpenAddToExisting = { pickerNumber ->
                            navController.navigate(Screen.AddToExisting.createRoute(pickerNumber))
                        },
                        onOpenBlocked = { navController.navigate(Screen.BlockedNumbers.route) },
                        onContactsChanged = {
                            contactsViewModel.loadContacts(force = true)
                            recentsViewModel.loadRecents(force = true)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumBottomBar(items: List<Screen>, navController: androidx.navigation.NavHostController, onItemClick: (Screen) -> Unit) {
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(62.dp).clip(RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 5.dp, shadowElevation = 8.dp
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            items.forEach { screen ->
                val selected = destination?.hierarchy?.any { it.route == screen.route } == true
                PremiumNavItem(screen, selected, { onItemClick(screen) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PremiumNavItem(screen: Screen, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(stiffness = 700f), label = "navScale")
    val iconTint by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, label = "navIconTint")
    val textTint by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, label = "navTextTint")
    Box(
        modifier = modifier.fillMaxHeight().scale(scale).clip(RoundedCornerShape(20.dp)).clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier.fillMaxWidth(0.82f).height(46.dp).clip(RoundedCornerShape(18.dp)).background(
                    Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer))
                )
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 4.dp)) {
            Icon(screen.icon, screen.title, tint = iconTint, modifier = Modifier.size(if (selected) 22.dp else 21.dp))
            Spacer(Modifier.height(2.dp))
            Text(screen.title, color = textTint, fontSize = 10.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Bottom-bar tab order for directional swipe animations (Home/Dialer share the first slot). */
private fun tabIndex(route: String?): Int? = when (route) {
    Screen.Home.route, Screen.Dialer.route -> 0
    Screen.Favorites.route -> 1
    Screen.Contacts.route -> 2
    Screen.Settings.route -> 3
    else -> null
}

/** +1 when travelling to a later tab, -1 when travelling to an earlier one, [defaultDir] otherwise. */
private fun navSwipeDir(fromRoute: String?, toRoute: String?, defaultDir: Int): Int {
    val from = tabIndex(fromRoute)
    val to = tabIndex(toRoute)
    if (from == null || to == null || from == to) return defaultDir
    return if (to > from) 1 else -1
}
