package com.relaypony.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The two places, as on Android 4.0 (plan section 4.1). Receiving runs whenever the app is open. */
private enum class Tab(val titleKey: String) { Send("nav_send"), Received("nav_received") }

/** Screens that cover the tabs, opened from the top bar or Settings. */
private enum class Overlay { None, Settings, Advanced, Direct }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(c: DesktopController, themeMode: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    LaunchedEffect(Unit) { c.start() }
    if (c.showOnboarding) {
        Onboarding(c)
        return
    }

    var tab by remember { mutableStateOf(Tab.Send) }
    var overlay by remember { mutableStateOf(Overlay.None) }
    var pairOpen by remember { mutableStateOf(false) }
    val showTransfer = c.transferVisible && c.batch != null
    val back = { overlay = if (overlay == Overlay.Advanced || overlay == Overlay.Direct) Overlay.Settings else Overlay.None }
    val openAdvanced = { overlay = Overlay.Advanced }

    val snackbar = remember { SnackbarHostState() }
    val notice = c.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice, withDismissAction = true, duration = SnackbarDuration.Long)
            if (c.notice == notice) c.notice = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (overlay) {
                            Overlay.Settings -> tr("settings_title")
                            Overlay.Advanced -> tr("adv_title")
                            Overlay.Direct -> tr("d_direct_title")
                            Overlay.None -> tr("app_name")
                        },
                    )
                },
                navigationIcon = {
                    if (overlay != Overlay.None) {
                        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("nav_back")) }
                    }
                },
                actions = {
                    IconButton(onClick = { pairOpen = true }) { Icon(Icons.Filled.QrCode, contentDescription = tr("pair_title")) }
                    if (overlay == Overlay.None) {
                        IconButton(onClick = { overlay = Overlay.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = tr("settings_title"))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (overlay == Overlay.None && !showTransfer) {
                NavigationRail {
                    Spacer(Modifier.height(8.dp))
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                Icon(
                                    if (t == Tab.Send) Icons.AutoMirrored.Filled.Send else Icons.Filled.Inbox,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(tr(t.titleKey)) },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                when {
                    overlay == Overlay.Settings -> SettingsScreen(c, themeMode, onThemeChange, onAdvanced = openAdvanced)
                    overlay == Overlay.Advanced -> AdvancedScreen(c, onPair = { pairOpen = true }, onDirect = { overlay = Overlay.Direct })
                    overlay == Overlay.Direct -> DirectScreen(c)
                    showTransfer -> TransferScreen(c, onPair = { pairOpen = true })
                    tab == Tab.Send -> HomeScreen(c)
                    else -> ReceivedScreen(c, onPair = { pairOpen = true })
                }
            }
        }
    }

    if (c.sendToOpen) SendToDialog(c, onPair = { pairOpen = true })
    if (pairOpen) PairDialog(c) { pairOpen = false }
    c.pairing.prompt?.let { p -> PairPromptDialog(c, p) { if (p is DesktopPairing.Prompt.Paired) pairOpen = false } }
    c.pendingVerify?.let { VerifyDialog(c, it) }
    c.problem?.let { ProblemDialog(it) { c.problem = null } }
}
