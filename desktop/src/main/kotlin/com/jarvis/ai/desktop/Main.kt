package com.jarvis.ai.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "JarvisAI",
        state = rememberWindowState(size = DpSize(1120.dp, 780.dp)),
    ) {
        MaterialTheme(colorScheme = jarvisColorScheme()) {
            JarvisDesktopScreen()
        }
    }
}

/** A calm dark palette that matches the Android dashboard's look. */
@androidx.compose.runtime.Composable
private fun jarvisColorScheme() = darkColorScheme(
    primary = Color(0xFF42D9C8),
    onPrimary = Color(0xFF08111B),
    secondary = Color(0xFF8CEFE4),
    background = Color(0xFF08111B),
    onBackground = Color(0xFFF1F7FA),
    surface = Color(0xFF111E2B),
    onSurface = Color(0xFFF1F7FA),
    surfaceVariant = Color(0xFF16283A),
    onSurfaceVariant = Color(0xFFA9BAC7),
)
