package io.github.melastore.shelf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.AutoHideMode
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HidingPreference
import io.github.melastore.shelf.data.SafRecoveryCandidate
import io.github.melastore.shelf.data.ThemeMode
import io.github.melastore.shelf.security.CredentialKind

@Composable
internal fun StealthGuideCard(onClick: () -> Unit) {
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.padding(top = 8.dp, bottom = 4.dp)
			.clip(MaterialTheme.shapes.large)
			.clickable(onClick = onClick),
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
	) {
		Row(
			modifier = Modifier.padding(16.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(
				modifier = Modifier
					.size(40.dp)
					.clip(CircleShape)
					.background(MaterialTheme.colorScheme.primary),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					Icons.Filled.Lock,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onPrimary,
					modifier = Modifier.size(20.dp),
				)
			}
			Spacer(Modifier.size(14.dp))
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = stringResource(R.string.stealth_guide_title),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onSurface,
				)
				Spacer(Modifier.height(2.dp))
				Text(
					text = stringResource(R.string.stealth_guide_summary),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}
	}
}

@Composable
internal fun StealthGuideDialog(onDismiss: () -> Unit) {
	AlertDialog(
		onDismissRequest = onDismiss,
		shape = MaterialTheme.shapes.extraLarge,
		title = {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Icon(
					Icons.Filled.Lock,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
					modifier = Modifier.size(24.dp),
				)
				Spacer(Modifier.size(10.dp))
				Text(stringResource(R.string.stealth_guide_title))
			}
		},
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(14.dp),
			) {
				GuideTopic(
					title = stringResource(R.string.stealth_guide_disguise_title),
					body = stringResource(R.string.stealth_guide_disguise_body),
				)
				GuideTopic(
					title = stringResource(R.string.stealth_guide_entrance_title),
					body = stringResource(R.string.stealth_guide_entrance_body),
				)
				GuideTopic(
					title = stringResource(R.string.stealth_guide_crash_title),
					body = stringResource(R.string.stealth_guide_crash_body),
				)
				GuideTopic(
					title = stringResource(R.string.stealth_guide_peek_title),
					body = stringResource(R.string.stealth_guide_peek_body),
				)
				GuideTopic(
					title = stringResource(R.string.stealth_guide_duress_title),
					body = stringResource(R.string.stealth_guide_duress_body),
				)
				GuideTopic(
					title = stringResource(R.string.stealth_guide_panic_title),
					body = stringResource(R.string.stealth_guide_panic_body),
				)
				GuideTopic(
					title = stringResource(R.string.stealth_guide_safety_title),
					body = stringResource(R.string.stealth_guide_safety_body),
				)
			}
		},
		confirmButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.stealth_guide_close))
			}
		},
	)
}

@Composable
private fun GuideTopic(title: String, body: String) {
	Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
		Text(
			text = title,
			style = MaterialTheme.typography.titleSmall,
			color = MaterialTheme.colorScheme.primary,
		)
		Text(
			text = body,
			style = MaterialTheme.typography.bodySmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
internal fun AuthenticationSettingsSection(
	state: AppUiState,
	onChangePin: () -> Unit,
	onCredentialKind: (CredentialKind) -> Unit,
	onBiometricChange: () -> Unit,
	onSetDecoyPin: () -> Unit,
	onRemoveDecoyPin: () -> Unit,
	onSeedDecoy: () -> Unit,
) {
	SettingsGroup(stringResource(R.string.credentials), stringResource(R.string.credential_kind_summary)) {
		SettingsRow(
			title = stringResource(R.string.change_vault_pin),
			summary = CredentialWords.label(state.credentialKind),
			onClick = onChangePin,
		)
		CredentialKind.entries.forEach { kind ->
			SettingsRow(
				title = CredentialWords.label(kind),
				summary = CredentialWords.hint(kind),
				trailing = { SelectionMark(state.credentialKind == kind) },
				onClick = { onCredentialKind(kind) },
			)
		}
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
				title = stringResource(R.string.populate_decoy_title),
				summary = stringResource(R.string.populate_decoy_summary),
				onClick = onSeedDecoy,
			)
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
	onFlipToHideChange: () -> Unit,
	onAllowScreenshotsChange: () -> Unit,
	onHideFromRecentsChange: () -> Unit,
) {
	SettingsGroup(
		stringResource(R.string.auto_hide),
		stringResource(R.string.auto_hide_summary),
	) {
		SettingsRow(
			title = stringResource(R.string.flip_to_hide_title),
			summary = stringResource(R.string.flip_to_hide_summary),
			trailing = { SelectionMark(state.flipToHide) },
			onClick = onFlipToHideChange,
		)
		ChoiceRow(
			title = stringResource(R.string.auto_hide_choose),
			selected = state.autoHideMode.label(),
			options = AutoHideMode.entries.map { it to it.label() },
			onSelected = onAutoHideMode,
		)
		SettingsRow(
			title = stringResource(R.string.quick_lock_notification),
			summary = stringResource(R.string.quick_lock_notification_summary),
			trailing = { SelectionMark(state.quickLockNotification) },
			onClick = onQuickLockChange,
		)
		// Same question as auto hide, which is who else gets to see this, so they sit in the one group
		// rather than under appearance.
		SettingsRow(
			title = stringResource(R.string.hide_from_recents),
			summary = stringResource(R.string.hide_from_recents_summary),
			trailing = { SelectionMark(state.hideFromRecents) },
			onClick = onHideFromRecentsChange,
		)
		SettingsRow(
			title = stringResource(R.string.allow_screenshots),
			summary = stringResource(R.string.allow_screenshots_summary),
			dangerous = state.allowScreenshots,
			trailing = { SelectionMark(state.allowScreenshots) },
			onClick = onAllowScreenshotsChange,
		)
	}
}

@Composable
internal fun AppearanceSettingsSection(state: AppUiState, onThemeMode: (ThemeMode) -> Unit) {
	SettingsGroup(
		stringResource(R.string.appearance),
		stringResource(R.string.appearance_summary),
	) {
		ThemeMode.entries.forEach { mode ->
			SettingsRow(
				title = mode.title(),
				summary = mode.summary(),
				trailing = { SelectionMark(state.themeMode == mode) },
				onClick = { onThemeMode(mode) },
			)
		}
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
			// Private move cannot do anything without all-files access, and a device can report that it
			// holds access it does not. Selecting it while it is unavailable would set a method every
			// hide then refuses, so the row asks for the permission instead and is only selectable once
			// the method is really there.
			val needsGrant = preference == HidingPreference.ALL_FILES &&
				HideMethod.PRIVATE_MOVE !in state.availableMethods
			SettingsRow(
				title = preference.title(),
				summary = preference.summary(state.availableMethods),
				trailing = { if (!needsGrant) SelectionMark(state.hidingPreference == preference) },
				onClick = { if (needsGrant) onRequestAllFiles() else onHidingPreference(preference) },
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
