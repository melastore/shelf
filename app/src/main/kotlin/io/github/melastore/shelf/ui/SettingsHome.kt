package io.github.melastore.shelf.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.HideMethod

/**
 * The settings, in six pages instead of one scroll. Each row carries what its page is set to, so
 * checking the setup does not mean opening all six.
 */
internal enum class SettingsCategory { DISGUISE, CREDENTIALS, LOCKING, HIDING, APPEARANCE, RECOVERY }

@Composable
internal fun SettingsCategory.title(): String = stringResource(
	when (this) {
		SettingsCategory.DISGUISE -> R.string.category_disguise
		SettingsCategory.CREDENTIALS -> R.string.credentials
		SettingsCategory.LOCKING -> R.string.category_locking
		SettingsCategory.HIDING -> R.string.category_hiding
		SettingsCategory.APPEARANCE -> R.string.appearance
		SettingsCategory.RECOVERY -> R.string.recovery
	},
)

/** What the page is set to, in as few words as it takes. */
@Composable
internal fun SettingsCategory.status(state: AppUiState): String = when (this) {
	SettingsCategory.DISGUISE -> joined(state.decoy.label(), state.entryMethod.title())

	SettingsCategory.CREDENTIALS -> joined(
		CredentialWords.label(state.credentialKind),
		stringResource(R.string.status_biometric).takeIf { state.biometricEnabled },
		stringResource(R.string.status_second_pin).takeIf { state.decoyPinSet },
	)

	SettingsCategory.LOCKING -> joined(
		state.autoHideMode.label(),
		stringResource(R.string.status_flip_to_lock).takeIf { state.flipToHide },
	)

	SettingsCategory.HIDING -> joined(
		state.method.label(),
		// Only the rename leaves a name on show, so only it has a style worth naming here.
		state.hiddenNameStyle.title().takeIf { state.method == HideMethod.DOT_RENAME },
	)

	SettingsCategory.APPEARANCE -> state.themeMode.title()

	SettingsCategory.RECOVERY -> stringResource(R.string.category_recovery_status)
}

@Composable
internal fun SettingsHome(state: AppUiState, onOpen: (SettingsCategory) -> Unit) {
	SettingsGroup(stringResource(R.string.settings)) {
		SettingsCategory.entries.forEach { category ->
			SettingsRow(
				title = category.title(),
				summary = category.status(state),
				trailing = {
					Icon(
						Icons.AutoMirrored.Filled.KeyboardArrowRight,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				},
				onClick = { onOpen(category) },
			)
		}
	}
}

private fun joined(vararg parts: String?): String = parts.filterNotNull().joinToString(" · ")
