package io.github.melastore.shelf.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.AutoHideMode
import io.github.melastore.shelf.data.DecoyType
import io.github.melastore.shelf.data.EntryMethod
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HidingPreference
import io.github.melastore.shelf.data.ThemeMode

// Setup and Settings offer the same choices and have to name them the same way. These used to be
// copied into both screens, which is how the wording drifted apart.

/**
 * The name and mono icon the disguise wears outside the app: on the launcher, and on the quick-hide
 * notification. Both have to agree, or the notification names an app that is not on the home screen.
 */
internal fun DecoyType.identity(): Pair<Int, Int> = when (this) {
	DecoyType.NONE -> R.string.launcher_shelf to R.drawable.ic_launcher_shelf_foreground
	DecoyType.HABITS -> R.string.launcher_habits to R.drawable.ic_launcher_foreground
	DecoyType.CALENDAR -> R.string.launcher_calendar to R.drawable.ic_mono_calendar
	DecoyType.CALCULATOR -> R.string.launcher_calculator to R.drawable.ic_mono_calculator
}

@Composable
internal fun DecoyType.label(): String = stringResource(
	when (this) {
		DecoyType.NONE -> R.string.decoy_none
		DecoyType.HABITS -> R.string.decoy_habits
		DecoyType.CALENDAR -> R.string.decoy_calendar
		DecoyType.CALCULATOR -> R.string.decoy_calculator
	},
)

@Composable
internal fun EntryMethod.title(): String = stringResource(
	when (this) {
		EntryMethod.TITLE_HOLD -> R.string.entry_title_hold
		EntryMethod.CORNER_KNOCK -> R.string.entry_corner_knock
		EntryMethod.NATURAL_HOLD -> R.string.entry_natural_hold
	},
)

@Composable
internal fun EntryMethod.summary(): String = stringResource(
	when (this) {
		EntryMethod.TITLE_HOLD -> R.string.entry_title_hold_summary
		EntryMethod.CORNER_KNOCK -> R.string.entry_corner_knock_summary
		EntryMethod.NATURAL_HOLD -> R.string.entry_natural_hold_summary
	},
)

/** Null is the method that could not be worked out, which the vault header shows as unavailable. */
@Composable
internal fun HideMethod?.label(): String = stringResource(
	when (this) {
		HideMethod.ROOT_CHMOD -> R.string.root_mode
		HideMethod.PRIVATE_MOVE -> R.string.all_files_mode
		HideMethod.DOT_RENAME -> R.string.saf_mode
		null -> R.string.method_unavailable
	},
)

@Composable
internal fun HidingPreference.title(): String = stringResource(
	when (this) {
		HidingPreference.AUTO -> R.string.mode_auto
		HidingPreference.ROOT -> R.string.root_mode
		HidingPreference.ALL_FILES -> R.string.all_files_mode
		HidingPreference.SAF -> R.string.saf_mode
	},
)

/** Whether a method is really there decides the wording, so the summary needs [available]. */
@Composable
internal fun HidingPreference.summary(available: Set<HideMethod>): String = stringResource(
	when (this) {
		HidingPreference.AUTO -> R.string.mode_auto_summary

		HidingPreference.ROOT ->
			if (HideMethod.ROOT_CHMOD in available) R.string.root_mode_available else R.string.root_mode_unavailable

		HidingPreference.ALL_FILES ->
			if (HideMethod.PRIVATE_MOVE in available) R.string.all_files_available else R.string.all_files_unavailable

		HidingPreference.SAF -> R.string.saf_mode_summary
	},
)

@Composable
internal fun AutoHideMode.label(): String = stringResource(
	when (this) {
		AutoHideMode.SCREEN_OFF -> R.string.auto_hide_screen_off
		AutoHideMode.IMMEDIATE -> R.string.auto_hide_immediately
		AutoHideMode.NEVER -> R.string.auto_hide_never
	},
)

@Composable
internal fun ThemeMode.title(): String = stringResource(
	when (this) {
		ThemeMode.SYSTEM -> R.string.theme_system
		ThemeMode.LIGHT -> R.string.theme_light
		ThemeMode.DARK -> R.string.theme_dark
		ThemeMode.AMOLED -> R.string.theme_amoled
	},
)

@Composable
internal fun ThemeMode.summary(): String = stringResource(
	when (this) {
		ThemeMode.SYSTEM -> R.string.theme_system_summary
		ThemeMode.LIGHT -> R.string.theme_light_summary
		ThemeMode.DARK -> R.string.theme_dark_summary
		ThemeMode.AMOLED -> R.string.theme_amoled_summary
	},
)

/**
 * The real launcher icon, from the same layers the adaptive icon uses, so the chooser previews what
 * will actually land on the home screen rather than something like it.
 */
@Composable
internal fun DecoyBadge(decoy: DecoyType) {
	val (background, foreground) = when (decoy) {
		DecoyType.NONE -> R.drawable.ic_bg_shelf to R.drawable.ic_launcher_shelf_foreground
		DecoyType.HABITS -> R.drawable.ic_bg_habits to R.drawable.ic_launcher_foreground
		DecoyType.CALENDAR -> R.drawable.ic_bg_calendar to R.drawable.ic_launcher_calendar_foreground
		DecoyType.CALCULATOR -> R.drawable.ic_bg_calculator to R.drawable.ic_launcher_calculator_foreground
	}
	Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.medium)) {
		Image(painterResource(background), null, Modifier.fillMaxSize())
		Image(painterResource(foreground), null, Modifier.fillMaxSize())
	}
}
