package ro.newsfilter.app.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import ro.newsfilter.app.ui.components.ScreenHeader
import ro.newsfilter.app.ui.components.TimeChipSelector
import ro.newsfilter.app.ui.components.TimePickerDialog
import ro.newsfilter.app.ui.components.formatTime

/**
 * Ecranul de Setări, grupat în carduri rotunjite: Cont, Aspect, Preferințe citire,
 * Interacțiuni (senzor), Notificări.
 *
 * Notificări:
 * - Rândul „Ora notificării" deschide un TimePickerDialog Material 3; ora aleasă
 *   este salvată în DataStore și NotificationScheduler este reprogramat imediat.
 * - Starea switch-ului este sincronizată cu permisiunea reală de notificare:
 *   dacă utilizatorul revocă permisiunea POST_NOTIFICATIONS (Android 13+) sau
 *   blochează notificările din setările sistemului, la revenirea în aplicație
 *   switch-ul se dezactivează automat și programarea este anulată.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(context.areNotificationsAllowed()) }

    // Reverificăm permisiunea la fiecare revenire pe ecran (utilizatorul o poate
    // revoca din setările sistemului cât timp aplicația e în fundal).
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = context.areNotificationsAllowed()
        onPauseOrDispose { }
    }

    // Sincronizare: switch activ, dar permisiunea lipsește → îl dezactivăm.
    LaunchedEffect(notificationsAllowed, prefs.dailyNotificationEnabled) {
        if (prefs.dailyNotificationEnabled && !notificationsAllowed) {
            viewModel.setDailyNotification(false)
        }
    }

    fun showPermissionSnackbar() {
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Notificările sunt blocate pentru NewsFilter.",
                actionLabel = "Setări",
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) {
                context.openAppNotificationSettings()
            }
        }
    }

    // Launcher pentru permisiunea de notificări pe Android 13+ (API 33+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationsAllowed = context.areNotificationsAllowed()
        viewModel.setDailyNotification(isGranted)
        if (!isGranted) showPermissionSnackbar()
    }

    fun onDailyNotificationToggle(enable: Boolean) {
        when {
            !enable -> viewModel.setDailyNotification(false)
            context.areNotificationsAllowed() -> viewModel.setDailyNotification(true)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            // Sub Android 13 nu există dialog de permisiune: notificările au fost
            // blocate manual din setările sistemului.
            else -> showPermissionSnackbar()
        }
    }

    val notificationTime = formatTime(prefs.notificationHour, prefs.notificationMinute)

    // Fără Scaffold imbricat: SnackbarHost-ul e așezat direct într-un Box.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 24.dp)
        ) {
            ScreenHeader(title = "Setări", subtitle = "Personalizează experiența de lectură")

            // === Card: Cont ===
            SettingsCard(title = "Cont") {
                ListItem(
                    headlineContent = {
                        Text(
                            text = prefs.userName.ifBlank { "Cititor NewsFilter" },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text(
                            text = buildString {
                                append(prefs.userEmail)
                                append(if (prefs.authProvider == "google") "  •  Google" else "  •  Email")
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingContent = { UserAvatar(name = prefs.userName.ifBlank { prefs.userEmail }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                ListItem(
                    headlineContent = {
                        Text("Deconectare", color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Logout,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(onClick = { showLogoutDialog = true })
                )
            }

            // === Card: Aspect ===
            SettingsCard(title = "Aspect") {
                ListItem(
                    headlineContent = { Text("Temă întunecată") },
                    supportingContent = { Text("Fundal închis pentru citire nocturnă") },
                    leadingContent = { SettingsIcon(Icons.Outlined.DarkMode) },
                    trailingContent = {
                        Switch(checked = prefs.isDarkTheme, onCheckedChange = null)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.toggleable(
                        value = prefs.isDarkTheme,
                        role = Role.Switch,
                        onValueChange = viewModel::setDarkTheme
                    )
                )
            }

            // === Card: Preferințe citire ===
            SettingsCard(title = "Preferințe citire") {
                Column(modifier = Modifier.padding(16.dp)) {
                    CardLabel("Timp de lectură implicit")
                    Spacer(modifier = Modifier.height(10.dp))
                    TimeChipSelector(
                        selectedMinutes = prefs.defaultMinutes,
                        onTimeSelected = viewModel::setDefaultMinutes
                    )

                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(16.dp))

                    CardLabel("Domenii de interes")
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        viewModel.allCategories.forEach { category ->
                            val isSelected = prefs.selectedCategories.contains(category.slug)
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.toggleCategory(category.slug) },
                                label = { Text("${category.emoji} ${category.label}") },
                                shape = CircleShape,
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Filled.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize)
                                        )
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }
            }

            // === Card: Interacțiuni (senzor accelerometru) ===
            SettingsCard(title = "Interacțiuni") {
                ListItem(
                    headlineContent = { Text("Scutură pentru știri noi") },
                    supportingContent = {
                        Text(
                            if (viewModel.isShakeSupported) {
                                "Scutură telefonul pe ecranul principal pentru un pachet proaspăt"
                            } else {
                                "Dispozitivul nu are accelerometru"
                            }
                        )
                    },
                    leadingContent = { SettingsIcon(Icons.Outlined.Vibration) },
                    trailingContent = {
                        Switch(
                            checked = prefs.shakeToRefreshEnabled && viewModel.isShakeSupported,
                            onCheckedChange = null,
                            enabled = viewModel.isShakeSupported
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.toggleable(
                        value = prefs.shakeToRefreshEnabled,
                        enabled = viewModel.isShakeSupported,
                        role = Role.Switch,
                        onValueChange = viewModel::setShakeToRefresh
                    )
                )
            }

            // === Card: Notificări ===
            SettingsCard(title = "Notificări") {
                ListItem(
                    headlineContent = { Text("Pachet zilnic de știri") },
                    supportingContent = {
                        Text(
                            when {
                                prefs.dailyNotificationEnabled ->
                                    "Primești zilnic, la ora $notificationTime, articolele alese pentru tine"
                                !notificationsAllowed ->
                                    "Notificările sunt blocate — atinge pentru a le permite"
                                else -> "Dezactivat"
                            }
                        )
                    },
                    leadingContent = {
                        SettingsIcon(
                            if (prefs.dailyNotificationEnabled) {
                                Icons.Outlined.Notifications
                            } else {
                                Icons.Outlined.NotificationsOff
                            }
                        )
                    },
                    trailingContent = {
                        Switch(checked = prefs.dailyNotificationEnabled, onCheckedChange = null)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.toggleable(
                        value = prefs.dailyNotificationEnabled,
                        role = Role.Switch,
                        onValueChange = ::onDailyNotificationToggle
                    )
                )
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                ListItem(
                    headlineContent = { Text("Ora notificării") },
                    supportingContent = { Text("Atinge pentru a alege ora") },
                    leadingContent = { SettingsIcon(Icons.Outlined.Schedule) },
                    trailingContent = {
                        // Ora curentă, afișată ca o pastilă (HH:mm)
                        Text(
                            text = notificationTime,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (prefs.dailyNotificationEnabled) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (prefs.dailyNotificationEnabled) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    }
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(
                        onClickLabel = "Schimbă ora notificării",
                        onClick = { showTimePicker = true }
                    )
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            icon = { Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null) },
            title = { Text("Te deconectezi?") },
            text = {
                Text("Articolele salvate și preferințele rămân pe acest dispozitiv. Te poți reconecta oricând.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    viewModel.logout()
                }) { Text("Deconectare", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Anulează") }
            }
        )
    }

    if (showTimePicker) {
        TimePickerDialog(
            title = "Ora notificării zilnice",
            initialHour = prefs.notificationHour,
            initialMinute = prefs.notificationMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                showTimePicker = false
                viewModel.setNotificationTime(hour, minute)
                scope.launch {
                    snackbarHostState.showSnackbar(
                        if (prefs.dailyNotificationEnabled) {
                            "Notificarea a fost reprogramată la ${formatTime(hour, minute)}"
                        } else {
                            "Ora salvată: ${formatTime(hour, minute)}. Activează notificarea pentru a o primi."
                        }
                    )
                }
            }
        )
    }
}

/**
 * Card de setări rotunjit, cu titlul secțiunii deasupra (stil „grouped list”).
 */
@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column { content() }
        }
    }
}

/** Avatar circular cu inițiala utilizatorului. */
@Composable
private fun UserAvatar(name: String) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifBlank { "N" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun CardLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun SettingsIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * True dacă aplicația poate afișa notificări. Acoperă atât permisiunea
 * POST_NOTIFICATIONS (Android 13+), cât și blocarea manuală din setările sistemului.
 */
private fun Context.areNotificationsAllowed(): Boolean =
    NotificationManagerCompat.from(this).areNotificationsEnabled()

/** Deschide pagina de notificări a aplicației din setările sistemului. */
private fun Context.openAppNotificationSettings() {
    // minSdk = 26, deci ACTION_APP_NOTIFICATION_SETTINGS (API 26+) e mereu disponibil.
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
