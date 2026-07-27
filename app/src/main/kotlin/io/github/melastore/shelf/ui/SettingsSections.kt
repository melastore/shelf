package io.github.melastore.shelf.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.AutoHideMode
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HidingPreference
import io.github.melastore.shelf.data.SafRecoveryCandidate

@Composable
internal fun AuthenticationSettingsSection(
	state: AppUiState,
	onChangePin: () -> Unit,
	onBiometricChange: () -> Unit,
	onSetDecoyPin: () -> Unit,
	onRemoveDecoyPin: () -> Unit,
) {
	SettingsGroup(stringResource(R.string.credentials)) {
		SettingsRow(
			title = stringResource(R.string.change_vault_pin),
			summary = stringResource(
				if (state.vaultUsesPin) R.string.vault_pin_summary else R.string.legacy_passphrase_summary,
			),
			onClick = onChangePin,
		)
		SettingsRow(
			title = stringResource(R.string.biometric_unlock),
			summary = stringResource(
				when {
					state.biometricEnabled && !state.biometricAvailable -> R.string.biometric_unavailable_enabled
					state.biometricEnabled -> R.string.biometric_enabled_summary
					state.biometricAvailable -> R.string.biometric_available_summary
					else -> R.string.biometric_unavailable_summary
				},
			),
			trailing = { SelectionMark(state.biometricEnabled) },
			enabled = state.biometricAvailable || state.biometricEnabled,
			onClick = onBiometricChange,
		)
		SettingsRow(
			title = stringResource(
				if (state.decoyPinSet) R.string.change_decoy_pin else R.string.create_decoy_pin,
			),
			summary = stringResource(R.string.decoy_pin_summary),
			onClick = onSetDecoyPin,
		)
		if (state.decoyPinSet) {
			SettingsRow(
				title = stringResource(R.string.remove_decoy_pin),
				summary = stringResource(R.string.remove_decoy_pin_summary),
				dangerous = true,
				onClick = onRemoveDecoyPin,
			)
		}
	}
}

@Composable
internal fun AutomaticLockSettingsSection(
	state: AppUiState,
	onQuickLockChange: () -> Unit,
	onAutoHideMode: (AutoHideMode) -> Unit,
) {
	SettingsGroup(
		stringResource(R.string.auto_hide),
		stringResource(R.string.auto_hide_summary),
	) {
		ChoiceRow(
			title = stringResource(R.string.auto_hide_choose),
			selected = state.autoHideMode.settingsLabel(),
			options = AutoHideMode.entries.map { it to it.settingsLabel() },
			onSelected = onAutoHideMode,
		)
		SettingsRow(
			title = stringResource(R.string.quick_lock_notification),
			summary = stringResource(R.string.quick_lock_notification_summary),
			trailing = { SelectionMark(state.quickLockNotification) },
			onClick = onQuickLockChange,
		)
	}
}

@Composable
internal fun FolderSettingsSection(
	state: AppUiState,
	onHidingPreference: (HidingPreference) -> Unit,
	onRequestAllFiles: () -> Unit,
) {
	SettingsGroup(
		stringResource(R.string.hiding_engine),
		stringResource(R.string.hiding_engine_description),
	) {
		HidingPreference.entries.forEach { preference ->
			SettingsRow(
				title = preference.settingsTitle(),
				summary = preference.settingsSummary(state.availableMethods),
				trailing = { SelectionMark(state.hidingPreference == preference) },
				onClick = { onHidingPreference(preference) },
			)
		}
		if (state.canRequestAllFiles) {
			Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
				OutlinedButton(onClick = onRequestAllFiles, modifier = Modifier.fillMaxWidth()) {
					Text(stringResource(R.string.grant_all_files))
				}
			}
		}
	}
}

@Composable
internal fun RecoverySettingsSection(
	state: AppUiState,
	onExportRecovery: () -> Unit,
	onImportRecovery: () -> Unit,
	onCheckHealth: () -> Unit,
	onFindRenamed: () -> Unit,
	onCandidate: (SafRecoveryCandidate) -> Unit,
	onForceUnhide: () -> Unit,
) {
	SettingsGroup(
		stringResource(R.string.recovery),
		stringResource(R.string.recovery_description),
	) {
		SettingsRow(
			title = stringResource(R.string.export_recovery),
			summary = stringResource(R.string.export_recovery_summary),
			enabled = !state.busy,
			onClick = onExportRecovery,
		)
		SettingsRow(
			title = stringResource(R.string.import_recovery),
			summary = stringResource(R.string.import_recovery_summary),
			enabled = !state.busy,
			onClick = onImportRecovery,
		)
		SettingsRow(
			title = stringResource(R.string.check_hidden_items),
			summary = stringResource(R.string.check_hidden_items_summary),
			enabled = !state.busy,
			onClick = onCheckHealth,
		)
		SettingsRow(
			title = stringResource(R.string.recover_renamed_folder),
			summary = stringResource(R.string.recover_renamed_folder_summary),
			enabled = !state.busy,
			onClick = onFindRenamed,
		)
		state.safRecoveryCandidates.forEach { candidate ->
			SettingsRow(
				title = candidate.hiddenName,
				summary = stringResource(R.string.recovery_candidate_summary),
				onClick = { onCandidate(candidate) },
			)
		}
		SettingsRow(
			title = stringResource(R.string.force_unhide),
			summary = stringResource(R.string.force_unhide_summary),
			enabled = !state.busy,
			onClick = onForceUnhide,
		)
	}
}

@Composable
private fun HidingPreference.settingsTitle(): String = when (this) {
	HidingPreference.AUTO -> stringResource(R.string.mode_auto)
	HidingPreference.ROOT -> stringResource(R.string.root_mode)
	HidingPreference.ALL_FILES -> stringResource(R.string.all_files_mode)
	HidingPreference.SAF -> stringResource(R.string.saf_mode)
}

@Composable
private fun HidingPreference.settingsSummary(available: Set<HideMethod>): String = when (this) {
	HidingPreference.AUTO -> stringResource(R.string.mode_auto_summary)

	HidingPreference.ROOT -> stringResource(
		if (HideMethod.ROOT_CHMOD in available) R.string.root_mode_available else R.string.root_mode_unavailable,
	)

	HidingPreference.ALL_FILES -> stringResource(
		if (HideMethod.PRIVATE_MOVE in available) R.string.all_files_available else R.string.all_files_unavailable,
	)

	HidingPreference.SAF -> stringResource(R.string.saf_mode_summary)
}

@Composable
private fun AutoHideMode.settingsLabel(): String = when (this) {
	AutoHideMode.SCREEN_OFF -> stringResource(R.string.auto_hide_screen_off)
	AutoHideMode.IMMEDIATE -> stringResource(R.string.auto_hide_immediately)
	AutoHideMode.NEVER -> stringResource(R.string.auto_hide_never)
}
