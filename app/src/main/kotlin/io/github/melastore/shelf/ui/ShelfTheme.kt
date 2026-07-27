package io.github.melastore.shelf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.melastore.shelf.data.DecoyType

/**
 * Each disguise gets its own palette. A calculator that renders in the habit tracker's greens is a
 * worse disguise than one that looks like every other calculator on the store, so the theme follows
 * whichever identity is currently on the launcher.
 */
private val Habits = lightColorScheme(
	primary = Color(0xFF5B4BC4),
	onPrimary = Color.White,
	primaryContainer = Color(0xFFE5DEFF),
	onPrimaryContainer = Color(0xFF1B0B62),
	secondary = Color(0xFF625B71),
	secondaryContainer = Color(0xFFE8DEF8),
	tertiary = Color(0xFF7D5260),
	tertiaryContainer = Color(0xFFFFD8E4),
	background = Color(0xFFFBF8FF),
	onBackground = Color(0xFF1B1B21),
	surface = Color(0xFFFBF8FF),
	onSurface = Color(0xFF1B1B21),
	surfaceVariant = Color(0xFFE7E0EB),
	onSurfaceVariant = Color(0xFF49454E),
	surfaceContainer = Color(0xFFF3EDF7),
	surfaceContainerHigh = Color(0xFFEDE7F3),
	outlineVariant = Color(0xFFCAC4CF),
)

private val HabitsDark = darkColorScheme(
	primary = Color(0xFFC6BFFF),
	onPrimary = Color(0xFF2C1B93),
	primaryContainer = Color(0xFF4333AC),
	onPrimaryContainer = Color(0xFFE5DEFF),
	secondary = Color(0xFFCCC2DC),
	secondaryContainer = Color(0xFF4A4458),
	tertiary = Color(0xFFEFB8C8),
	tertiaryContainer = Color(0xFF633B48),
	background = Color(0xFF131318),
	onBackground = Color(0xFFE5E1E9),
	surface = Color(0xFF131318),
	onSurface = Color(0xFFE5E1E9),
	surfaceVariant = Color(0xFF49454E),
	onSurfaceVariant = Color(0xFFCAC4CF),
	surfaceContainer = Color(0xFF1F1F25),
	surfaceContainerHigh = Color(0xFF2A2930),
	outlineVariant = Color(0xFF49454E),
)

private val Calendar = lightColorScheme(
	primary = Color(0xFF1A66D0),
	onPrimary = Color.White,
	primaryContainer = Color(0xFFD6E3FF),
	onPrimaryContainer = Color(0xFF001B3D),
	secondary = Color(0xFF565E71),
	secondaryContainer = Color(0xFFDAE2F9),
	tertiary = Color(0xFF00696E),
	tertiaryContainer = Color(0xFF9CF1F5),
	background = Color(0xFFFAF9FF),
	onBackground = Color(0xFF1A1B20),
	surface = Color(0xFFFAF9FF),
	onSurface = Color(0xFF1A1B20),
	surfaceVariant = Color(0xFFE0E2EC),
	onSurfaceVariant = Color(0xFF44474F),
	surfaceContainer = Color(0xFFEEEDF4),
	surfaceContainerHigh = Color(0xFFE8E7EF),
	outlineVariant = Color(0xFFC4C6D0),
)

private val CalendarDark = darkColorScheme(
	primary = Color(0xFFAAC7FF),
	onPrimary = Color(0xFF002F65),
	primaryContainer = Color(0xFF00458F),
	onPrimaryContainer = Color(0xFFD6E3FF),
	secondary = Color(0xFFBEC6DC),
	secondaryContainer = Color(0xFF3E4759),
	tertiary = Color(0xFF80D4D9),
	tertiaryContainer = Color(0xFF004F53),
	background = Color(0xFF111318),
	onBackground = Color(0xFFE2E2E9),
	surface = Color(0xFF111318),
	onSurface = Color(0xFFE2E2E9),
	surfaceVariant = Color(0xFF44474F),
	onSurfaceVariant = Color(0xFFC4C6D0),
	surfaceContainer = Color(0xFF1D2024),
	surfaceContainerHigh = Color(0xFF282A2F),
	outlineVariant = Color(0xFF44474F),
)

private val Calculator = lightColorScheme(
	primary = Color(0xFFE06A1E),
	onPrimary = Color.White,
	primaryContainer = Color(0xFFFFDBC8),
	onPrimaryContainer = Color(0xFF351000),
	secondary = Color(0xFF76574A),
	secondaryContainer = Color(0xFFFFDBCE),
	background = Color(0xFFFCF8F6),
	onBackground = Color(0xFF211A16),
	surface = Color(0xFFFCF8F6),
	onSurface = Color(0xFF211A16),
	surfaceVariant = Color(0xFFEFDFD8),
	onSurfaceVariant = Color(0xFF51443F),
	surfaceContainer = Color(0xFFF4EDE9),
	surfaceContainerHigh = Color(0xFFEEE7E3),
	outlineVariant = Color(0xFFD4C3BC),
)

private val CalculatorDark = darkColorScheme(
	primary = Color(0xFFFFB68E),
	onPrimary = Color(0xFF542100),
	primaryContainer = Color(0xFF773100),
	onPrimaryContainer = Color(0xFFFFDBC8),
	secondary = Color(0xFFE6BEAE),
	secondaryContainer = Color(0xFF5C4034),
	background = Color(0xFF121110),
	onBackground = Color(0xFFEDE0DA),
	surface = Color(0xFF121110),
	onSurface = Color(0xFFEDE0DA),
	surfaceVariant = Color(0xFF51443F),
	onSurfaceVariant = Color(0xFFD4C3BC),
	surfaceContainer = Color(0xFF1E1B1A),
	surfaceContainerHigh = Color(0xFF292524),
	outlineVariant = Color(0xFF51443F),
)

/** The private area is deliberately unlike every decoy, so a glance tells you which side you are on. */
private val Vault = lightColorScheme(
	primary = Color(0xFF00696B),
	onPrimary = Color.White,
	primaryContainer = Color(0xFF6FF6F9),
	onPrimaryContainer = Color(0xFF002020),
	secondary = Color(0xFF4A6363),
	secondaryContainer = Color(0xFFCCE8E7),
	background = Color(0xFFF5FAFA),
	onBackground = Color(0xFF171D1D),
	surface = Color(0xFFF5FAFA),
	onSurface = Color(0xFF171D1D),
	surfaceVariant = Color(0xFFDAE5E4),
	onSurfaceVariant = Color(0xFF3F4949),
	surfaceContainer = Color(0xFFE9F0EF),
	surfaceContainerHigh = Color(0xFFE3EAEA),
	outlineVariant = Color(0xFFBEC9C8),
)

private val VaultDark = darkColorScheme(
	primary = Color(0xFF4CD9DD),
	onPrimary = Color(0xFF003737),
	primaryContainer = Color(0xFF004F51),
	onPrimaryContainer = Color(0xFF6FF6F9),
	secondary = Color(0xFFB0CCCB),
	secondaryContainer = Color(0xFF324B4B),
	background = Color(0xFF0E1514),
	onBackground = Color(0xFFDDE4E3),
	surface = Color(0xFF0E1514),
	onSurface = Color(0xFFDDE4E3),
	surfaceVariant = Color(0xFF3F4949),
	onSurfaceVariant = Color(0xFFBEC9C8),
	surfaceContainer = Color(0xFF1A2222),
	surfaceContainerHigh = Color(0xFF242C2C),
	outlineVariant = Color(0xFF3F4949),
)

private val ShelfShapes = Shapes(
	extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
	small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
	medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
	large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
	extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(36.dp),
)

private val ShelfTypography = Typography().run {
	copy(
		displayLarge = displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
		headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
		headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
		headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
		titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
		titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
		labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
		labelMedium = TextStyle(
			fontWeight = FontWeight.SemiBold,
			fontSize = 12.sp,
			lineHeight = 16.sp,
			letterSpacing = 0.6.sp,
		),
	)
}

@Composable
fun ShelfTheme(decoy: DecoyType?, content: @Composable () -> Unit) {
	val dark = isSystemInDarkTheme()
	val colors = when (decoy) {
		DecoyType.HABITS -> if (dark) HabitsDark else Habits

		DecoyType.CALENDAR -> if (dark) CalendarDark else Calendar

		DecoyType.CALCULATOR -> if (dark) CalculatorDark else Calculator

		// No disguise means no pretence to keep up: the lock screen wears the private palette, the
		// same one the space behind it uses.
		DecoyType.NONE, null -> if (dark) VaultDark else Vault
	}
	MaterialTheme(
		colorScheme = colors,
		shapes = ShelfShapes,
		typography = ShelfTypography,
		content = content,
	)
}
