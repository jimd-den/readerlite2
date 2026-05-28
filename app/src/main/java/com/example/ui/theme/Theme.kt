package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val SophisticatedDarkColorScheme = darkColorScheme(
  primary = SophisticatedPrimary,
  onPrimary = SophisticatedOnPrimary,
  primaryContainer = SophisticatedPrimaryContainer,
  onPrimaryContainer = SophisticatedOnPrimaryContainer,
  secondary = SophisticatedPrimary,
  onSecondary = SophisticatedOnPrimary,
  secondaryContainer = SophisticatedSurfaceVariant,
  onSecondaryContainer = SophisticatedOnPrimaryContainer,
  background = SophisticatedBackground,
  onBackground = SophisticatedTextPrimary,
  surface = SophisticatedSurface,
  onSurface = SophisticatedTextPrimary,
  surfaceVariant = SophisticatedSurfaceVariant,
  onSurfaceVariant = SophisticatedTextVariant,
  outline = SophisticatedOutlineFull
)

private val LightColorScheme = SophisticatedDarkColorScheme // Force dark theme throughout the application

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true, // Force dark theme
  dynamicColor: Boolean = false, // Disable dynamic color to enforce visual branding
  content: @Composable () -> Unit,
) {
  val colorScheme = SophisticatedDarkColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
