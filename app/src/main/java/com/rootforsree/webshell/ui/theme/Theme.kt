package com.rootforsree.webshell.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = WebShellBlueDark,
    onPrimary = Color.White,
)

private val LightColors = lightColorScheme(
    primary = WebShellBlue,
    onPrimary = Color.White,
)

/**
 * Dark-first theme: the dark scheme is the primary design target, and the
 * light scheme exists for devices that ask for it. No dynamic color, so
 * the accent stays exactly WebShellBlue on every device.
 */
@Composable
fun WebShellTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
