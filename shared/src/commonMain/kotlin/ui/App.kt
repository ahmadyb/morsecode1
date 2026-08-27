package net.morsecode.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import net.morsecode.ui.screens.ChatScreen
import net.morsecode.ui.screens.HomeScreen
import net.morsecode.ui.screens.LibraryScreen
import net.morsecode.ui.screens.SettingsScreen
import net.morsecode.ui.screens.WebConnectScreen
import net.morsecode.ui.theme.MorseCodeTheme

/** The five top-level destinations (Section A), in order. */
enum class TopScreen(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Library("Library", Icons.Default.FolderOpen),
    Chat("Chat", Icons.Default.Chat),
    WebConnect("Web", Icons.Default.Lan),
    Settings("Settings", Icons.Default.Settings),
}

/**
 * Root composable, shared by Android and Desktop (Sections A/D).
 *
 * Mobile gets a bottom [NavigationBar]; Desktop a [NavigationRail]. The content
 * is identical — one Compose tree, two chrome styles.
 */
@Composable
fun MorseCodeApp(app: AppState) {
    MorseCodeTheme(darkTheme = app.darkTheme) {
        var current by rememberSaveable { mutableStateOf(TopScreen.Home) }

        Scaffold(
            bottomBar = {
                if (!app.isDesktop) {
                    NavigationBar {
                        TopScreen.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = current == dest,
                                onClick = { current = dest },
                                icon = { Icon(dest.icon, dest.label) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (app.isDesktop) {
                    NavigationRail {
                        TopScreen.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = current == dest,
                                onClick = { current = dest },
                                icon = { Icon(dest.icon, dest.label) },
                                label = { Text(dest.label) },
                            )
                        }
                    }
                }
                when (current) {
                    TopScreen.Home -> HomeScreen(app)
                    TopScreen.Library -> LibraryScreen(app)
                    TopScreen.Chat -> ChatScreen(app)
                    TopScreen.WebConnect -> WebConnectScreen(app)
                    TopScreen.Settings -> SettingsScreen(app)
                }
            }
        }
    }
}
