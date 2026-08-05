package com.revshield.spamprobe.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val BrandBlue = Color(0xFF3F5BD6)
private val BrandBlueDark = Color(0xFF9DB0FF)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    secondary = Color(0xFF4A5578),
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    secondary = Color(0xFFBAC3E8),
)

/**
 * App theme. Uses Material You dynamic colour on Android 12+, falling back to a small brand
 * palette on older versions.
 */
@Composable
fun RevShieldTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content,
    )
}
