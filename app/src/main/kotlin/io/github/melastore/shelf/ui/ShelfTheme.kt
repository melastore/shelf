package io.github.melastore.shelf.ui

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

private val LightColors = lightColorScheme(
	primary = Color(0xFF315C49),
	onPrimary = Color.White,
	primaryContainer = Color(0xFFB5F1CF),
	onPrimaryContainer = Color(0xFF002116),
	secondary = Color(0xFF4D6357),
	secondaryContainer = Color(0xFFCFE9D9),
	background = Color(0xFFF7FBF7),
	surface = Color(0xFFF7FBF7),
	surfaceVariant = Color(0xFFDCE5DE),
)

private val DarkColors = darkColorScheme(
	primary = Color(0xFF99D5B4),
	onPrimary = Color(0xFF003826),
	primaryContainer = Color(0xFF164E37),
	onPrimaryContainer = Color(0xFFB5F1CF),
	secondary = Color(0xFFB3CCBD),
	secondaryContainer = Color(0xFF354B3F),
	background = Color(0xFF101512),
	surface = Color(0xFF101512),
	surfaceVariant = Color(0xFF414943),
)

@Composable
fun ShelfTheme(content: @Composable () -> Unit) {
	val dark = isSystemInDarkTheme()
	val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
		val context = LocalContext.current
		if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
	} else if (dark) {
		DarkColors
	} else {
		LightColors
	}
	MaterialTheme(colorScheme = colors, content = content)
}
