package io.github.melastore.shelf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.AutoHideMode
import io.github.melastore.shelf.data.DecoyItem
import io.github.melastore.shelf.data.DecoyType
import io.github.melastore.shelf.data.EntryMethod
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HidingPreference
import io.github.melastore.shelf.data.SafRecoveryCandidate

@Composable
fun PrivateArea(
	state: AppUiState,
	viewModel: ShelfViewModel,
	onBiometricChange: (Boolean) -> Unit,
	onQuickLockChange: (Boolean) -> Unit,
	onAllowScreenshotsChange: (Boolean) -> Unit,
	onSecretEntry: () -> Unit,
) {
	val pickFolder = rememberLauncherForActivityResult(PickFolderToKeep()) { uri ->
		viewModel.onPickerResult()
		uri?.let(viewModel::hideFolder)
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
	val recoverRenamed = rememberLauncherForActivityResult(PickFolderToKeep()) { uri ->
		viewModel.onPickerResult()
		uri?.let(viewModel::findRenamedFolders)
	}
	var exportUri by remember { mutableStateOf<Uri?>(null) }
	var importUri by remember { mutableStateOf<Uri?>(null) }
	val createRecovery = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument("application/octet-stream"),
	) { uri ->
		viewModel.onPickerResult()
		exportUri = uri
	}
	val openRecovery = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		viewModel.onPickerResult()
		importUri = uri
	}
	LaunchedEffect(state.accessNeededFor) {
		state.accessNeededFor?.let {
			viewModel.expectExternalPicker()
			grantAccess.launch(it)
		}
	}
	val context = LocalContext.current
	val resources = context.resources

	when (state.screen) {
		Screen.VAULT -> VaultScreen(
			state = state,
			onClose = viewModel::lockVault,
			onSettings = viewModel::openSettings,
			onAddFolder = {
				viewModel.expectExternalPicker()
				pickFolder.launch(state.pickerStart)
			},
			onHideAll = viewModel::hideAll,
			onUnhideAll = viewModel::unhideAll,
			onRestore = viewModel::restore,
			onHideAgain = viewModel::hideAgain,
			onForget = viewModel::forgetEntry,
			onToggleDecoyItem = viewModel::toggleDecoyItem,
			onDismissAlert = viewModel::dismissDuressAlert,
			onSecretEntry = onSecretEntry,
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
			onBiometricChange = onBiometricChange,
			onQuickLockChange = onQuickLockChange,
			onAllowScreenshotsChange = onAllowScreenshotsChange,
			onAutoHideMode = viewModel::setAutoHideMode,
			onForceUnhide = viewModel::forceUnhideAll,
			onCheckHealth = viewModel::checkHiddenItems,
			onRecoverCandidate = viewModel::recoverRenamedFolder,
			onExportRecovery = {
				viewModel.expectExternalPicker()
				createRecovery.launch("shelf-recovery.shelfrecovery")
			},
			onImportRecovery = {
				viewModel.expectExternalPicker()
				openRecovery.launch(arrayOf("application/octet-stream", "application/json", "*/*"))
			},
			onFindRenamed = {
				viewModel.expectExternalPicker()
				recoverRenamed.launch(state.pickerStart)
			},
			onRequestAllFiles = {
				viewModel.expectExternalPicker()
				requestAllFiles.launch(allFilesAccessSettings(context.packageName))
			},
		)

		Screen.DECOY -> Unit
	}

	exportUri?.let { uri ->
		RecoveryPassphraseDialog(
			title = stringResource(R.string.export_recovery),
			confirmEntry = true,
			onConfirm = {
				viewModel.exportRecoveryBundle(uri, it)
				exportUri = null
			},
			onDismiss = { exportUri = null },
		)
	}
	importUri?.let { uri ->
		RecoveryPassphraseDialog(
			title = stringResource(R.string.import_recovery),
			confirmEntry = false,
			onConfirm = {
				viewModel.importRecoveryBundle(uri, it)
				importUri = null
			},
			onDismiss = { importUri = null },
		)
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultScreen(
	state: AppUiState,
	onClose: () -> Unit,
	onSettings: () -> Unit,
	onAddFolder: () -> Unit,
	onHideAll: () -> Unit,
	onUnhideAll: () -> Unit,
	onRestore: (VaultFolder) -> Unit,
	onHideAgain: (VaultFolder) -> Unit,
	onForget: (VaultFolder) -> Unit,
	onToggleDecoyItem: (DecoyItem) -> Unit,
	onDismissAlert: () -> Unit,
	onSecretEntry: () -> Unit,
) {
	var forgetting by remember { mutableStateOf<VaultFolder?>(null) }
	val count = if (state.duress) state.decoyItems.size else state.folders.size
	val resources = LocalContext.current.resources

	Box(Modifier.fillMaxSize()) {
		Scaffold(
			containerColor = MaterialTheme.colorScheme.background,
			topBar = {
				TopAppBar(
					title = { Text(stringResource(R.string.vault_title)) },
					navigationIcon = {
						IconButton(onClick = onClose) {
							Icon(Icons.Filled.Close, stringResource(R.string.close_vault))
						}
					},
					actions = {
						// Hidden under duress: a settings screen listing a decoy PIN would give the whole
						// arrangement away to the person standing over the phone.
						if (!state.duress) {
							IconButton(onClick = onSettings) {
								Icon(Icons.Filled.Settings, stringResource(R.string.settings))
							}
						}
					},
					colors = TopAppBarDefaults.topAppBarColors(
						containerColor = MaterialTheme.colorScheme.background,
					),
				)
			},
			floatingActionButton = {
				if (!state.duress) {
					ExtendedFloatingActionButton(
						onClick = onAddFolder,
						expanded = true,
						icon = { Icon(Icons.Filled.Add, null) },
						text = { Text(stringResource(R.string.hide_folder)) },
					)
				}
			},
		) { padding ->
			val rows = if (state.duress) {
				state.decoyItems.map { item ->
					ShelfRow(
						key = item.id,
						title = item.name,
						subtitle = stringResource(R.string.hidden_folder_label),
						hidden = item.hidden,
						onToggle = { onToggleDecoyItem(item) },
						onForget = null,
					)
				}
			} else {
				state.folders.map { folder ->
					ShelfRow(
						key = folder.path,
						title = folder.displayName,
						subtitle = folder.health?.let { stringResource(it.detail.stringResource) }
							?: stringResource(
								if (folder.hidden) {
									R.string.folder_status_hidden
								} else {
									R.string.folder_status_unlocked
								},
							),
						hidden = folder.hidden,
						onToggle = { if (folder.hidden) onRestore(folder) else onHideAgain(folder) },
						onForget = { forgetting = folder },
					)
				}
			}
			val exposedCount = rows.count { !it.hidden }

			LazyColumn(
				modifier = Modifier.padding(padding).fillMaxSize(),
				contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp),
			) {
				if (state.duressAlert != null) {
					item { DuressAlert(state.duressAlert.resolve(resources), onDismissAlert) }
				}
				item { VaultStatus(count, state) }
				if (!state.duress && state.method == HideMethod.DOT_RENAME) {
					item { RenameVisibilityNotice() }
				}

				if (count == 0) {
					item {
						EmptyState(
							stringResource(R.string.vault_empty_title),
							stringResource(R.string.empty_hint),
						)
					}
				} else {
					item {
						FolderListHeader(
							count = count,
							action = if (exposedCount > 0) {
								stringResource(R.string.hide_all)
							} else {
								stringResource(R.string.unhide_all)
							},
							enabled = !state.busy,
							onAction = if (exposedCount > 0) onHideAll else onUnhideAll,
						)
					}
					items(rows, key = { it.key }) { FolderTile(it, state.busy) }
				}
			}
		}

		// The way out of the decoy space and into the real one, in the same corner and with the same
		// five taps that opened this. Someone standing over the phone has just watched a private space
		// open and been shown everything in it; there is nothing left for them to become suspicious of,
		// and the owner does not have to close the decoy and start again to reach their own space.
		if (state.duress) {
			KnockTarget(onSecretEntry, Modifier.align(Alignment.TopEnd).statusBarsPadding())
		}
	}

	forgetting?.let { entry ->
		ConfirmDialog(
			title = stringResource(R.string.forget_record),
			body = stringResource(R.string.forget_record_confirm, entry.displayName),
			confirmLabel = stringResource(R.string.forget),
			dangerous = true,
			onConfirm = {
				onForget(entry)
				forgetting = null
			},
			onDismiss = { forgetting = null },
		)
	}
}

@Composable
private fun RenameVisibilityNotice() {
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.secondaryContainer,
	) {
		Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
			Icon(
				Icons.Filled.Lock,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSecondaryContainer,
			)
			Text(
				stringResource(R.string.rename_visibility_warning),
				modifier = Modifier.padding(start = 12.dp),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSecondaryContainer,
			)
		}
	}
}

@Composable
private fun DuressAlert(text: String, onDismiss: () -> Unit) {
	Surface(
		modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.errorContainer,
	) {
		Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
			Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
			Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
				Text(
					stringResource(R.string.duress_alert_title),
					style = MaterialTheme.typography.titleSmall,
					color = MaterialTheme.colorScheme.onErrorContainer,
				)
				Text(
					text,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onErrorContainer,
				)
			}
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
		}
	}
}

/** One row of the shelf, built once so the decoy space and the real one cannot drift apart. */
private data class ShelfRow(
	val key: String,
	val title: String,
	val subtitle: String,
	val hidden: Boolean,
	val onToggle: () -> Unit,
	val onForget: (() -> Unit)?,
)

/** A compact visual summary that makes the space feel distinct without hiding the useful state. */
@Composable
private fun VaultStatus(count: Int, state: AppUiState) {
	val method = when (state.method) {
		HideMethod.ROOT_CHMOD -> stringResource(R.string.root_mode)
		HideMethod.PRIVATE_MOVE -> stringResource(R.string.all_files_mode)
		HideMethod.DOT_RENAME -> stringResource(R.string.saf_mode)
		null -> stringResource(R.string.method_unavailable)
	}
	Surface(
		modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
		shape = MaterialTheme.shapes.extraLarge,
		color = MaterialTheme.colorScheme.primaryContainer,
	) {
		Row(
			Modifier.padding(20.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(
				Modifier.size(48.dp).clip(CircleShape).background(
					MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
				),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					painterResource(R.drawable.ic_lock_open),
					contentDescription = null,
					tint = MaterialTheme.colorScheme.primary,
				)
			}
			Column(Modifier.padding(start = 16.dp).weight(1f)) {
				Text(
					stringResource(R.string.vault_status_unlocked),
					style = MaterialTheme.typography.titleMedium,
					color = MaterialTheme.colorScheme.onPrimaryContainer,
				)
				Text(
					"${pluralStringResource(R.plurals.folder_total, count, count)}  ·  $method",
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.76f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			if (state.busy) {
				CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
			}
		}
	}
}

/**
 * One quiet list header. The bulk action follows the useful direction: hide anything currently
 * visible, otherwise restore everything.
 */
@Composable
private fun FolderListHeader(count: Int, action: String, enabled: Boolean, onAction: () -> Unit,) {
	Row(
		Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			stringResource(R.string.folders),
			style = MaterialTheme.typography.titleMedium,
			color = MaterialTheme.colorScheme.onSurface,
		)
		Text(
			count.toString(),
			modifier = Modifier.padding(start = 8.dp).weight(1f),
			style = MaterialTheme.typography.labelLarge,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		TextButton(onClick = onAction, enabled = enabled) { Text(action) }
	}
}

/**
 * A calm folder card. Lock state is carried by one familiar icon and a short subtitle instead of
 * splitting the screen into warning zones.
 */
@Composable
private fun FolderTile(row: ShelfRow, busy: Boolean) {
	var menu by remember { mutableStateOf(false) }
	Surface(
		modifier = Modifier.fillMaxWidth(),
		shape = MaterialTheme.shapes.large,
		color = if (row.hidden) {
			MaterialTheme.colorScheme.surfaceContainerLow
		} else {
			MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.52f)
		},
		tonalElevation = 0.dp,
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Box(
				Modifier.padding(start = 16.dp).size(40.dp).clip(CircleShape).background(
					if (row.hidden) {
						MaterialTheme.colorScheme.primaryContainer
					} else {
						MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
					},
				),
				contentAlignment = Alignment.Center,
			) {
				val iconTint = if (row.hidden) {
					MaterialTheme.colorScheme.onPrimaryContainer
				} else {
					MaterialTheme.colorScheme.primary
				}
				if (row.hidden) {
					Icon(
						Icons.Filled.Lock,
						contentDescription = null,
						modifier = Modifier.size(18.dp),
						tint = iconTint,
					)
				} else {
					Icon(
						painterResource(R.drawable.ic_lock_open),
						contentDescription = null,
						modifier = Modifier.size(18.dp),
						tint = iconTint,
					)
				}
			}
			Column(
				Modifier.padding(start = 12.dp, top = 14.dp, bottom = 14.dp)
					.weight(1f),
			) {
				Text(
					row.title,
					style = MaterialTheme.typography.titleSmall,
					color = if (row.hidden) {
						MaterialTheme.colorScheme.onSurfaceVariant
					} else {
						MaterialTheme.colorScheme.onSurface
					},
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				Text(
					row.subtitle,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
			}
			TextButton(onClick = row.onToggle, enabled = !busy) {
				Text(stringResource(if (row.hidden) R.string.restore else R.string.hide))
			}
			if (row.onForget != null) {
				Box {
					IconButton(onClick = { menu = true }, enabled = !busy) {
						Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options))
					}
					DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
						DropdownMenuItem(
							text = { Text(stringResource(R.string.forget_record)) },
							onClick = {
								menu = false
								row.onForget.invoke()
							},
						)
					}
				}
			}
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
	onSetDecoyPin: (CharArray, CharArray) -> Unit,
	onClearDecoyPin: (CharArray) -> Unit,
	onBiometricChange: (Boolean) -> Unit,
	onQuickLockChange: (Boolean) -> Unit,
	onAllowScreenshotsChange: (Boolean) -> Unit,
	onAutoHideMode: (AutoHideMode) -> Unit,
	onForceUnhide: () -> Unit,
	onCheckHealth: () -> Unit,
	onFindRenamed: () -> Unit,
	onRecoverCandidate: (SafRecoveryCandidate, String) -> Unit,
	onExportRecovery: () -> Unit,
	onImportRecovery: () -> Unit,
	onRequestAllFiles: () -> Unit,
) {
	var changeCurrent by remember { mutableStateOf<CharArray?>(null) }
	var askCurrent by remember { mutableStateOf(false) }
	var decoyCurrent by remember { mutableStateOf<CharArray?>(null) }
	var decoyAction by remember { mutableStateOf<DecoyPinAction?>(null) }
	var removeDecoyPin by remember { mutableStateOf(false) }
	var confirmForce by remember { mutableStateOf(false) }
	var recoveryCandidate by remember { mutableStateOf<SafRecoveryCandidate?>(null) }

	// A confirmed PIN waits here for the second half of a two-step change. Leaving this screen — a
	// back press, the screen going off, an automatic lock — abandons the dialog that would have
	// cleared it, so the exit path has to.
	DisposableEffect(Unit) {
		onDispose {
			changeCurrent?.fill(' ')
			decoyCurrent?.fill(' ')
		}
	}

	Scaffold(
		containerColor = MaterialTheme.colorScheme.background,
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.settings)) },
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
					}
				},
				colors = TopAppBarDefaults.topAppBarColors(
					containerColor = MaterialTheme.colorScheme.background,
				),
			)
		},
	) { padding ->
		LazyColumn(
			Modifier.padding(padding).fillMaxSize(),
			contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
		) {
			item {
				SettingsGroup(
					stringResource(R.string.disguise),
					stringResource(R.string.disguise_description),
				) {
					Row(
						Modifier.fillMaxWidth().padding(12.dp),
						horizontalArrangement = Arrangement.spacedBy(12.dp),
					) {
						DecoyType.entries.forEach { decoy ->
							ChoiceTile(
								label = decoy.label(),
								selected = state.decoy == decoy,
								modifier = Modifier.weight(1f),
								icon = { DecoyBadge(decoy) },
								onClick = { onDecoy(decoy) },
							)
						}
					}
				}
			}

			item {
				SettingsGroup(
					stringResource(R.string.access),
					stringResource(R.string.access_description),
				) {
					EntryMethod.entries.forEach { method ->
						SettingsRow(
							title = method.title(),
							summary = method.summary(),
							trailing = { SelectionMark(state.entryMethod == method) },
							onClick = { onEntryMethod(method) },
						)
					}
				}
			}

			item {
				AuthenticationSettingsSection(
					state = state,
					onChangePin = { askCurrent = true },
					onBiometricChange = { onBiometricChange(!state.biometricEnabled) },
					onSetDecoyPin = { decoyAction = DecoyPinAction.SET },
					onRemoveDecoyPin = { removeDecoyPin = true },
				)
			}

			item {
				AutomaticLockSettingsSection(
					state = state,
					onQuickLockChange = { onQuickLockChange(!state.quickLockNotification) },
					onAutoHideMode = onAutoHideMode,
				)
			}

			item {
				ScreenCaptureSettingsSection(
					state = state,
					onAllowScreenshotsChange = { onAllowScreenshotsChange(!state.allowScreenshots) },
				)
			}

			item {
				FolderSettingsSection(state, onHidingPreference, onRequestAllFiles)
			}

			item {
				RecoverySettingsSection(
					state = state,
					onExportRecovery = onExportRecovery,
					onImportRecovery = onImportRecovery,
					onCheckHealth = onCheckHealth,
					onFindRenamed = onFindRenamed,
					onCandidate = { recoveryCandidate = it },
					onForceUnhide = { confirmForce = true },
				)
			}
		}
	}

	if (askCurrent) {
		if (state.vaultUsesPin) {
			PinPrompt(
				title = stringResource(R.string.current_pin),
				subtitle = stringResource(R.string.current_pin_subtitle),
				confirmLabel = stringResource(R.string.continue_action),
				onConfirm = {
					askCurrent = false
					changeCurrent = it
				},
				onDismiss = { askCurrent = false },
			)
		} else {
			PassphraseDialog(
				title = stringResource(R.string.current_passphrase),
				confirmLabel = stringResource(R.string.continue_action),
				onConfirm = {
					askCurrent = false
					changeCurrent = it
				},
				onDismiss = { askCurrent = false },
			)
		}
	}
	changeCurrent?.let { current ->
		PinPrompt(
			title = stringResource(R.string.new_pin),
			subtitle = stringResource(R.string.pin_hint),
			confirmLabel = stringResource(R.string.save),
			confirmEntry = true,
			onConfirm = {
				onChangePin(current, it)
				changeCurrent = null
			},
			onDismiss = {
				current.fill(' ')
				changeCurrent = null
			},
		)
	}
	decoyAction?.let { action ->
		if (decoyCurrent == null) {
			val acceptCurrent: (CharArray) -> Unit = { current ->
				if (action == DecoyPinAction.SET) {
					decoyCurrent = current
				} else {
					onClearDecoyPin(current)
					decoyAction = null
				}
			}
			if (state.vaultUsesPin) {
				PinPrompt(
					title = stringResource(R.string.confirm_primary_pin),
					subtitle = stringResource(R.string.confirm_primary_pin_summary),
					confirmLabel = stringResource(R.string.continue_action),
					onConfirm = acceptCurrent,
					onDismiss = { decoyAction = null },
				)
			} else {
				PassphraseDialog(
					title = stringResource(R.string.confirm_primary_passphrase),
					confirmLabel = stringResource(R.string.continue_action),
					onConfirm = acceptCurrent,
					onDismiss = { decoyAction = null },
				)
			}
		}
	}
	decoyCurrent?.let { current ->
		PinPrompt(
			title = stringResource(
				if (state.decoyPinSet) R.string.change_decoy_pin else R.string.create_decoy_pin,
			),
			subtitle = stringResource(R.string.decoy_pin_hint),
			confirmLabel = stringResource(R.string.save),
			confirmEntry = true,
			onConfirm = { pin ->
				onSetDecoyPin(current, pin)
				decoyCurrent = null
				decoyAction = null
			},
			onDismiss = {
				current.fill(' ')
				decoyCurrent = null
				decoyAction = null
			},
		)
	}
	if (removeDecoyPin) {
		ConfirmDialog(
			title = stringResource(R.string.remove_decoy_pin),
			body = stringResource(R.string.remove_decoy_pin_confirm),
			confirmLabel = stringResource(R.string.remove),
			dangerous = true,
			onConfirm = {
				removeDecoyPin = false
				decoyAction = DecoyPinAction.REMOVE
			},
			onDismiss = { removeDecoyPin = false },
		)
	}
	if (confirmForce) {
		ConfirmDialog(
			title = stringResource(R.string.force_unhide),
			body = stringResource(R.string.force_unhide_confirm),
			confirmLabel = stringResource(R.string.force_unhide_action),
			onConfirm = {
				onForceUnhide()
				confirmForce = false
			},
			onDismiss = { confirmForce = false },
		)
	}
	recoveryCandidate?.let { candidate ->
		RecoverRenamedDialog(
			candidate = candidate,
			onConfirm = { name ->
				onRecoverCandidate(candidate, name)
				recoveryCandidate = null
			},
			onDismiss = { recoveryCandidate = null },
		)
	}
}

/**
 * A single row that opens its options in place. A short, fixed list of mutually exclusive settings
 * does not need one full-width row each — five of them pushed everything below off the screen.
 */
@Composable
internal fun <T> ChoiceRow(title: String, selected: String, options: List<Pair<T, String>>, onSelected: (T) -> Unit,) {
	var open by remember { mutableStateOf(false) }
	Box {
		SettingsRow(
			title = title,
			summary = selected,
			trailing = {
				Icon(
					Icons.Filled.ArrowDropDown,
					contentDescription = null,
					tint = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			},
			onClick = { open = true },
		)
		DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
			options.forEach { (value, label) ->
				DropdownMenuItem(
					text = { Text(label) },
					trailingIcon = { SelectionMark(label == selected) },
					onClick = {
						open = false
						onSelected(value)
					},
				)
			}
		}
	}
}

@Composable
private fun RecoverRenamedDialog(candidate: SafRecoveryCandidate, onConfirm: (String) -> Unit, onDismiss: () -> Unit,) {
	var name by remember(candidate) { mutableStateOf(candidate.suggestedName) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.recover_renamed_folder)) },
		text = {
			Column {
				Text(stringResource(R.string.recover_renamed_explanation, candidate.hiddenName))
				Spacer(Modifier.height(12.dp))
				OutlinedTextField(
					value = name,
					onValueChange = { name = it },
					label = { Text(stringResource(R.string.restored_folder_name)) },
					singleLine = true,
				)
			}
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
				Text(stringResource(R.string.restore))
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
	)
}

@Composable
private fun RecoveryPassphraseDialog(
	title: String,
	confirmEntry: Boolean,
	onConfirm: (CharArray) -> Unit,
	onDismiss: () -> Unit,
) {
	// A text field's value is a String and there is no version of that which can be overwritten, so
	// unlike the PIN keypad this passphrase does live in the heap until it is collected. What is worth
	// doing is not holding onto it: the fields are dropped as soon as the dialog closes, and the array
	// handed on from here is cleared by the caller.
	var first by remember { mutableStateOf("") }
	var second by remember { mutableStateOf("") }
	val valid = first.length >= MIN_RECOVERY_PASSWORD && (!confirmEntry || first == second)
	DisposableEffect(Unit) {
		onDispose {
			first = ""
			second = ""
		}
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text(stringResource(R.string.recovery_passphrase_explanation))
				OutlinedTextField(
					value = first,
					onValueChange = { first = it },
					label = { Text(stringResource(R.string.recovery_passphrase)) },
					visualTransformation = PasswordVisualTransformation(),
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
					singleLine = true,
				)
				if (confirmEntry) {
					OutlinedTextField(
						value = second,
						onValueChange = { second = it },
						label = { Text(stringResource(R.string.confirm_recovery_passphrase)) },
						visualTransformation = PasswordVisualTransformation(),
						keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
						singleLine = true,
					)
				}
			}
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(first.toCharArray()) }, enabled = valid) {
				Text(stringResource(R.string.continue_action))
			}
		},
		dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
	)
}

private enum class DecoyPinAction { SET, REMOVE }

/**
 * The real launcher icon, drawn from the same layers the adaptive icon uses, so the chooser is a
 * preview of what will actually appear on the home screen rather than an approximation of it.
 */
@Composable
private fun DecoyBadge(decoy: DecoyType) {
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

@Composable
private fun DecoyType.label(): String = when (this) {
	DecoyType.NONE -> stringResource(R.string.decoy_none)
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

private fun allFilesAccessSettings(packageName: String): Intent = Intent(
	Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
	Uri.fromParts("package", packageName, null),
)
