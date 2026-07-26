package io.github.melastore.shelf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.DecoyType
import io.github.melastore.shelf.data.EntryMethod
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HidingPreference
import io.github.melastore.shelf.data.LockedFile

@Composable
fun PrivateArea(state: AppUiState, viewModel: ShelfViewModel) {
	var pendingLockUri by remember { mutableStateOf<Uri?>(null) }
	var pendingUnlock by remember { mutableStateOf<LockedFile?>(null) }
	var pendingRecoveryUri by remember { mutableStateOf<Uri?>(null) }

	val pickFolder = rememberLauncherForActivityResult(PickFolderToKeep()) { uri ->
		viewModel.onPickerResult()
		uri?.let(viewModel::hideFolder)
	}
	val pickFile = rememberLauncherForActivityResult(PickFileToKeep()) { uri ->
		viewModel.onPickerResult()
		pendingLockUri = uri
	}
	val pickRecoveryFile = rememberLauncherForActivityResult(PickFileToKeep()) { uri ->
		viewModel.onPickerResult()
		pendingRecoveryUri = uri
	}
	val requestAllFiles = rememberLauncherForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		viewModel.onPickerResult()
		viewModel.refreshCapabilities()
	}
	val grantAccess = rememberLauncherForActivityResult(PickFolderToKeep()) { uri ->
		viewModel.onPickerResult()
		viewModel.grantedAccess(uri)
	}
	LaunchedEffect(state.accessNeededFor) {
		state.accessNeededFor?.let {
			viewModel.expectExternalPicker()
			grantAccess.launch(it)
		}
	}
	val context = LocalContext.current

	when (state.screen) {
		Screen.VAULT -> VaultScreen(
			state = state,
			onClose = viewModel::lockVault,
			onSettings = viewModel::openSettings,
			onHideFolder = {
				viewModel.expectExternalPicker()
				pickFolder.launch(null)
			},
			onLockFile = {
				viewModel.expectExternalPicker()
				pickFile.launch(arrayOf("*/*"))
			},
			onRestore = viewModel::restore,
			onUnlock = { pendingUnlock = it },
		)
		Screen.SETTINGS -> SettingsScreen(
			state = state,
			onBack = viewModel::closeSettings,
			onDecoy = viewModel::setDecoy,
			onEntryMethod = viewModel::setEntryMethod,
			onHidingPreference = viewModel::setHidingPreference,
			onChangePin = viewModel::changeVaultPin,
			onSetDecoyPin = viewModel::setDecoyPin,
			onClearDecoyPin = viewModel::clearDecoyPin,
			onRequestAllFiles = {
				viewModel.expectExternalPicker()
				requestAllFiles.launch(allFilesAccessSettings(context.packageName))
			},
			onRecoverFile = {
				viewModel.expectExternalPicker()
				pickRecoveryFile.launch(arrayOf("*/*"))
			},
		)
		Screen.DECOY -> Unit
	}

	pendingLockUri?.let { uri ->
		PassphraseDialog(
			title = stringResource(R.string.lock_title),
			confirmLabel = stringResource(R.string.lock_action),
			confirmEntry = true,
			minimumLength = FILE_PASSWORD_LENGTH,
			onConfirm = { viewModel.lockFile(uri, it); pendingLockUri = null },
			onDismiss = { pendingLockUri = null },
		)
	}
	pendingUnlock?.let { entry ->
		PassphraseDialog(
			title = stringResource(R.string.unlock_title),
			confirmLabel = stringResource(R.string.restore),
			onConfirm = { viewModel.unlockFile(entry, it); pendingUnlock = null },
			onDismiss = { pendingUnlock = null },
		)
	}
	pendingRecoveryUri?.let { uri ->
		PassphraseDialog(
			title = stringResource(R.string.recover_file_title),
			confirmLabel = stringResource(R.string.restore),
			onConfirm = { viewModel.recoverFile(uri, it); pendingRecoveryUri = null },
			onDismiss = { pendingRecoveryUri = null },
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultScreen(
	state: AppUiState,
	onClose: () -> Unit,
	onSettings: () -> Unit,
	onHideFolder: () -> Unit,
	onLockFile: () -> Unit,
	onRestore: (HiddenEntry) -> Unit,
	onUnlock: (LockedFile) -> Unit,
) {
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.vault_title), fontWeight = FontWeight.SemiBold) },
				navigationIcon = {
					IconButton(onClick = onClose) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close_vault))
					}
				},
				actions = {
					IconButton(onClick = onSettings) {
						Icon(Icons.Filled.Settings, stringResource(R.string.settings))
					}
				},
			)
		},
	) { padding ->
		LazyColumn(
			modifier = Modifier.padding(padding).fillMaxSize(),
			contentPadding = PaddingValues(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			item { VaultSummary(state) }
			item {
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
					Button(
						onClick = onHideFolder,
						modifier = Modifier.weight(1f),
						enabled = !state.busy && state.method != null,
					) {
						Icon(Icons.Filled.Add, null)
						Text(stringResource(R.string.hide_folder), Modifier.padding(start = 8.dp))
					}
					OutlinedButton(
						onClick = onLockFile,
						modifier = Modifier.weight(1f),
						enabled = !state.busy,
					) {
						Icon(Icons.Filled.Lock, null)
						Text(stringResource(R.string.lock_file), Modifier.padding(start = 8.dp))
					}
				}
			}
			if (state.entries.isEmpty() && state.lockedFiles.isEmpty()) {
				item { EmptyState(stringResource(R.string.vault_empty_title), stringResource(R.string.empty_hint)) }
			} else {
				if (state.entries.isNotEmpty()) item { SectionLabel(stringResource(R.string.hidden_folders)) }
				items(state.entries, key = { "folder:${it.path}" }) { entry ->
					VaultItem(entry.displayName, entry.path, stringResource(R.string.restore), !state.busy) {
						onRestore(entry)
					}
				}
				if (state.lockedFiles.isNotEmpty()) item { SectionLabel(stringResource(R.string.locked_files)) }
				items(state.lockedFiles, key = { "file:${it.path}" }) { entry ->
					VaultItem(entry.displayName, stringResource(R.string.locked_label), stringResource(R.string.unlock), !state.busy) {
						onUnlock(entry)
					}
				}
			}
		}
	}
}

@Composable
private fun VaultSummary(state: AppUiState) {
	val method = when (state.method) {
		HideMethod.ROOT_CHMOD -> stringResource(R.string.root_mode)
		HideMethod.PRIVATE_MOVE -> stringResource(R.string.all_files_mode)
		HideMethod.DOT_RENAME -> stringResource(R.string.saf_mode)
		null -> stringResource(R.string.method_unavailable)
	}
	Card(
		Modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
	) {
		Column(Modifier.padding(20.dp)) {
			Text(
				"${state.entries.size + state.lockedFiles.size}",
				style = MaterialTheme.typography.headlineLarge,
				fontWeight = FontWeight.Bold,
			)
			Text(stringResource(R.string.protected_items))
			Spacer(Modifier.height(12.dp))
			Text(
				stringResource(R.string.active_method, method),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onPrimaryContainer,
			)
		}
	}
}

@Composable
private fun VaultItem(title: String, subtitle: String, action: String, enabled: Boolean, onClick: () -> Unit) {
	Card(Modifier.fillMaxWidth()) {
		Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
			Column(Modifier.weight(1f)) {
				Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
				Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			TextButton(onClick = onClick, enabled = enabled) { Text(action) }
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
	state: AppUiState,
	onBack: () -> Unit,
	onDecoy: (DecoyType) -> Unit,
	onEntryMethod: (EntryMethod) -> Unit,
	onHidingPreference: (HidingPreference) -> Unit,
	onChangePin: (CharArray, CharArray) -> Unit,
	onSetDecoyPin: (CharArray) -> Unit,
	onClearDecoyPin: () -> Unit,
	onRequestAllFiles: () -> Unit,
	onRecoverFile: () -> Unit,
) {
	var changePin by remember { mutableStateOf(false) }
	var setDecoyPin by remember { mutableStateOf(false) }
	var removeDecoyPin by remember { mutableStateOf(false) }
	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.settings), fontWeight = FontWeight.SemiBold) },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
					}
				},
			)
		},
	) { padding ->
		LazyColumn(
			Modifier.padding(padding).fillMaxSize(),
			contentPadding = PaddingValues(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			item { SettingsHeader(stringResource(R.string.disguise), stringResource(R.string.disguise_description)) }
			item {
				Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					DecoyType.entries.forEach { decoy ->
						FilterChip(
							selected = state.decoy == decoy,
							onClick = { onDecoy(decoy) },
							label = { Text(decoy.label()) },
						)
					}
				}
			}
			item { SettingsHeader(stringResource(R.string.access), stringResource(R.string.access_description)) }
			items(EntryMethod.entries) { method ->
				ChoiceSetting(
					title = method.title(),
					summary = method.summary(),
					selected = state.entryMethod == method,
					onClick = { onEntryMethod(method) },
				)
			}
			item {
				ActionSetting(
					title = stringResource(R.string.change_vault_pin),
					summary = stringResource(
						if (state.vaultUsesPin) R.string.vault_pin_summary else R.string.legacy_passphrase_summary,
					),
					onClick = { changePin = true },
				)
			}
			item {
				ActionSetting(
					title = stringResource(if (state.decoyPinSet) R.string.change_decoy_pin else R.string.create_decoy_pin),
					summary = stringResource(R.string.decoy_pin_summary),
					onClick = { setDecoyPin = true },
				)
			}
			if (state.decoyPinSet) {
				item {
					ActionSetting(
						title = stringResource(R.string.remove_decoy_pin),
						summary = stringResource(R.string.remove_decoy_pin_summary),
						dangerous = true,
						onClick = { removeDecoyPin = true },
					)
				}
			}
			item { SettingsHeader(stringResource(R.string.hiding_engine), stringResource(R.string.hiding_engine_description)) }
			items(HidingPreference.entries) { preference ->
				ChoiceSetting(
					title = preference.title(),
					summary = preference.summary(state.availableMethods),
					selected = state.hidingPreference == preference,
					onClick = { onHidingPreference(preference) },
				)
			}
			if (state.canRequestAllFiles) {
				item {
					OutlinedButton(onClick = onRequestAllFiles, Modifier.fillMaxWidth()) {
						Text(stringResource(R.string.grant_all_files))
					}
				}
			}
			item { SettingsHeader(stringResource(R.string.recovery), stringResource(R.string.recovery_description)) }
			item {
				ActionSetting(
					title = stringResource(R.string.recover_file),
					summary = stringResource(R.string.recover_file_summary),
					onClick = onRecoverFile,
				)
			}
			item { Spacer(Modifier.height(24.dp)) }
		}
	}

	if (changePin) {
		ChangePinDialog(
			legacyCurrent = !state.vaultUsesPin,
			onConfirm = { current, pin -> onChangePin(current, pin); changePin = false },
			onDismiss = { changePin = false },
		)
	}
	if (setDecoyPin) {
		PinDialog(
			title = stringResource(if (state.decoyPinSet) R.string.change_decoy_pin else R.string.create_decoy_pin),
			confirmLabel = stringResource(R.string.save),
			confirmEntry = true,
			onConfirm = { onSetDecoyPin(it); setDecoyPin = false },
			onDismiss = { setDecoyPin = false },
		)
	}
	if (removeDecoyPin) {
		AlertDialog(
			onDismissRequest = { removeDecoyPin = false },
			title = { Text(stringResource(R.string.remove_decoy_pin)) },
			text = { Text(stringResource(R.string.remove_decoy_pin_confirm)) },
			confirmButton = {
				TextButton(onClick = { onClearDecoyPin(); removeDecoyPin = false }) {
					Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
				}
			},
			dismissButton = {
				TextButton(onClick = { removeDecoyPin = false }) { Text(stringResource(R.string.cancel)) }
			},
		)
	}
}

@Composable
private fun SettingsHeader(title: String, summary: String) {
	Column(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
		Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
		Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
	}
}

@Composable
private fun ChoiceSetting(title: String, summary: String, selected: Boolean, onClick: () -> Unit) {
	Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
		Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
			RadioButton(selected = selected, onClick = onClick)
			Column(Modifier.padding(start = 8.dp).weight(1f)) {
				Text(title, style = MaterialTheme.typography.titleSmall)
				Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
	}
}

@Composable
private fun ActionSetting(
	title: String,
	summary: String,
	onClick: () -> Unit,
	dangerous: Boolean = false,
) {
	Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
		Column(Modifier.padding(16.dp)) {
			Text(
				title,
				style = MaterialTheme.typography.titleSmall,
				color = if (dangerous) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
			)
			Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable
private fun SectionLabel(text: String) {
	Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun DecoyType.label(): String = when (this) {
	DecoyType.HABITS -> stringResource(R.string.decoy_habits)
	DecoyType.CALENDAR -> stringResource(R.string.decoy_calendar)
	DecoyType.CALCULATOR -> stringResource(R.string.decoy_calculator)
}

@Composable
private fun EntryMethod.title(): String = when (this) {
	EntryMethod.TITLE_HOLD -> stringResource(R.string.entry_title_hold)
	EntryMethod.CORNER_KNOCK -> stringResource(R.string.entry_corner_knock)
	EntryMethod.NATURAL_HOLD -> stringResource(R.string.entry_natural_hold)
}

@Composable
private fun EntryMethod.summary(): String = when (this) {
	EntryMethod.TITLE_HOLD -> stringResource(R.string.entry_title_hold_summary)
	EntryMethod.CORNER_KNOCK -> stringResource(R.string.entry_corner_knock_summary)
	EntryMethod.NATURAL_HOLD -> stringResource(R.string.entry_natural_hold_summary)
}

@Composable
private fun HidingPreference.title(): String = when (this) {
	HidingPreference.AUTO -> stringResource(R.string.mode_auto)
	HidingPreference.ROOT -> stringResource(R.string.root_mode)
	HidingPreference.ALL_FILES -> stringResource(R.string.all_files_mode)
	HidingPreference.SAF -> stringResource(R.string.saf_mode)
}

@Composable
private fun HidingPreference.summary(available: Set<HideMethod>): String = when (this) {
	HidingPreference.AUTO -> stringResource(R.string.mode_auto_summary)
	HidingPreference.ROOT -> stringResource(
		if (HideMethod.ROOT_CHMOD in available) R.string.root_mode_available else R.string.root_mode_unavailable,
	)
	HidingPreference.ALL_FILES -> stringResource(
		if (HideMethod.PRIVATE_MOVE in available) R.string.all_files_available else R.string.all_files_unavailable,
	)
	HidingPreference.SAF -> stringResource(R.string.saf_mode_summary)
}

private const val KEEP_ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
	Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
	Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

private class PickFolderToKeep : ActivityResultContracts.OpenDocumentTree() {
	override fun createIntent(context: Context, input: Uri?): Intent =
		super.createIntent(context, input).addFlags(KEEP_ACCESS)
}

private class PickFileToKeep : ActivityResultContracts.OpenDocument() {
	override fun createIntent(context: Context, input: Array<String>): Intent =
		super.createIntent(context, input).addFlags(KEEP_ACCESS)
}

private fun allFilesAccessSettings(packageName: String): Intent = Intent(
	Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
	Uri.fromParts("package", packageName, null),
)

private const val FILE_PASSWORD_LENGTH = 8
