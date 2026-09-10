package io.github.melastore.shelf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalResources
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
import io.github.melastore.shelf.data.HiddenNameStyle
import io.github.melastore.shelf.data.HidingPreference
import io.github.melastore.shelf.data.SafRecoveryCandidate
import io.github.melastore.shelf.data.ThemeMode
import io.github.melastore.shelf.data.worksOnCalculatorKeypad
import io.github.melastore.shelf.security.CredentialKind

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
			onAddDecoyItem = viewModel::addDecoyItem,
			onRemoveDecoyItem = viewModel::removeDecoyItem,
			onDismissAlert = viewModel::dismissDuressAlert,
			onDismissAttempts = viewModel::dismissFailedAttempts,
			onSecretEntry = onSecretEntry,
			onSeedDecoy = viewModel::seedDecoyVault,
		)

		Screen.SETTINGS -> SettingsScreen(
			state = state,
			onBack = viewModel::closeSettings,
			onDecoy = viewModel::setDecoy,
			onEntryMethod = viewModel::setEntryMethod,
			onFakeCrash = viewModel::setFakeCrash,
			onHideFromRecents = viewModel::setHideFromRecents,
			onThemeMode = viewModel::setThemeMode,
			onHidingPreference = viewModel::setHidingPreference,
			onHiddenNameStyle = viewModel::setHiddenNameStyle,
			onChangeCredential = viewModel::changeVaultCredential,
			onSetDecoyCredential = viewModel::setDecoyCredential,
			onClearDecoyPin = viewModel::clearDecoyPin,
			onSetBurnerCredential = viewModel::setBurnerPin,
			onClearBurnerPin = viewModel::clearBurnerPin,
			onBiometricChange = onBiometricChange,
			onQuickLockChange = onQuickLockChange,
			onAllowScreenshotsChange = onAllowScreenshotsChange,
			onAutoHideMode = viewModel::setAutoHideMode,
			onFlipToHideChange = viewModel::setFlipToHide,
			onUsbPanicChange = viewModel::setUsbPanic,
			onDeepProtectionChange = viewModel::setDeepProtection,
			onSeedDecoy = viewModel::seedDecoyVault,
			onForceUnhide = viewModel::forceUnhideAll,
			onCheckHealth = viewModel::checkHiddenItems,
			onRecoverCandidate = viewModel::recoverRenamedFolder,
			onRecordedName = viewModel::recordedFolderName,
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

	state.blocked?.let { blocked ->
		BlockedDialog(
			report = blocked,
			canRequestAllFiles = state.canRequestAllFiles,
			onRequestAllFiles = {
				viewModel.dismissBlocked()
				viewModel.expectExternalPicker()
				requestAllFiles.launch(allFilesAccessSettings(context.packageName))
			},
			onDismiss = viewModel::dismissBlocked,
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
	onAddDecoyItem: (String) -> Unit,
	onRemoveDecoyItem: (DecoyItem) -> Unit,
	onDismissAlert: () -> Unit,
	onDismissAttempts: () -> Unit,
	onSecretEntry: () -> Unit,
	onSeedDecoy: () -> Unit,
) {
	var forgetting by remember { mutableStateOf<VaultFolder?>(null) }
	var discarding by remember { mutableStateOf<DecoyItem?>(null) }
	var naming by remember { mutableStateOf(false) }
	var viewingMedia by remember { mutableStateOf<VaultFolder?>(null) }
	val count = if (state.duress) state.decoyItems.size else state.folders.size
	val resources = LocalResources.current

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
						// Not under duress: a settings screen listing a second credential would give the whole
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
				ExtendedFloatingActionButton(
					onClick = if (state.duress) {
						{ naming = true }
					} else {
						onAddFolder
					},
					expanded = true,
					icon = { Icon(Icons.Filled.Add, null) },
					text = { Text(stringResource(R.string.hide_folder)) },
				)
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
						onForget = { discarding = item },
					)
				}
			} else {
				state.folders.map { folder ->
					ShelfRow(
						key = folder.path,
						title = folder.displayName,
						subtitle = folder.health?.let { stringResource(it.detail.stringResource) }
							?: folder.blocker?.toUiMessage()?.resolve(resources)
							?: stringResource(
								when {
									!folder.hidden -> R.string.folder_status_unlocked

									// Hidden, but nothing inside it was touched: the second layer was
									// off, or the access it needs was not there.
									folder.entry?.deep == false -> R.string.folder_status_hidden_only

									else -> R.string.folder_status_hidden
								},
							),
						hidden = folder.hidden,
						onToggle = { if (folder.hidden) onRestore(folder) else onHideAgain(folder) },
						onForget = { forgetting = folder },
						onViewMedia = { viewingMedia = folder },
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
					item {
						SecurityAlert(
							stringResource(R.string.duress_alert_title),
							state.duressAlert.resolve(resources),
							onDismissAlert,
						)
					}
				}
				if (state.failedAttemptAlert != null) {
					item {
						SecurityAlert(
							stringResource(R.string.failed_attempts_title),
							state.failedAttemptAlert.resolve(resources),
							onDismissAttempts,
						)
					}
				}
				item { VaultStatus(count, state) }

				if (count == 0) {
					item {
						EmptyState(
							stringResource(R.string.vault_empty_title),
							stringResource(R.string.empty_hint),
						)
					}
					if (state.duress) {
						item {
							Box(
								Modifier.fillMaxWidth().padding(top = 8.dp),
								contentAlignment = Alignment.Center,
							) {
								OutlinedButton(onClick = onSeedDecoy) {
									Text(stringResource(R.string.populate_decoy_title))
								}
							}
						}
					}
				} else {
					item {
						FolderListHeader(
							count = count,
							enabled = !state.busy,
							onHideAll = onHideAll.takeIf { exposedCount > 0 },
							onUnhideAll = onUnhideAll.takeIf { exposedCount < rows.size },
						)
					}
					items(rows, key = { it.key }) { FolderTile(it, state.busy) }
				}
			}
		}

		// The way out of the decoy and into the real space: same corner, same five taps. Whoever is
		// standing over the phone has just been shown a private space and everything in it, so there
		// is nothing left to be suspicious of, and the owner need not close the decoy and start over.
		if (state.duress) {
			KnockTarget(onSecretEntry, Modifier.align(Alignment.TopEnd).statusBarsPadding())
		}
	}

	if (naming) {
		TextEntryDialog(
			title = stringResource(R.string.hide_folder),
			label = stringResource(R.string.decoy_item_name),
			subtitle = stringResource(R.string.decoy_item_hint),
			onAdd = {
				onAddDecoyItem(it)
				naming = false
			},
			onDismiss = { naming = false },
		)
	}
	discarding?.let { item ->
		ConfirmDialog(
			title = stringResource(R.string.forget_record),
			body = stringResource(R.string.forget_record_confirm, item.name),
			confirmLabel = stringResource(R.string.forget),
			dangerous = true,
			onConfirm = {
				onRemoveDecoyItem(item)
				discarding = null
			},
			onDismiss = { discarding = null },
		)
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
	viewingMedia?.let { folder ->
		EphemeralMediaViewer(
			folder = folder,
			onDismiss = { viewingMedia = null },
		)
	}
}

@Composable
private fun SecurityAlert(title: String, text: String, onDismiss: () -> Unit) {
	Surface(
		modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
		shape = MaterialTheme.shapes.large,
		color = MaterialTheme.colorScheme.errorContainer,
	) {
		Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
			Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
			Column(Modifier.padding(horizontal = 14.dp).weight(1f)) {
				Text(
					title,
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
	val onViewMedia: (() -> Unit)? = null,
)

/** The header: which method is in use, how many folders, and the busy spinner. */
@Composable
private fun VaultStatus(count: Int, state: AppUiState) {
	val method = state.method.label()
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
 * Whichever bulk actions the list can currently use, which is both of them when some folders are
 * hidden and some are not. Offering only one left the other half of a mixed list reachable a row at
 * a time, which is where a partial unhide leaves you.
 */
@Composable
private fun FolderListHeader(count: Int, enabled: Boolean, onHideAll: (() -> Unit)?, onUnhideAll: (() -> Unit)?,) {
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
		// Both together do not fit a phone at full length, and the header has already said Folders.
		val both = onHideAll != null && onUnhideAll != null
		onHideAll?.let {
			TextButton(onClick = it, enabled = enabled) {
				Text(stringResource(if (both) R.string.hide_all_short else R.string.hide_all))
			}
		}
		onUnhideAll?.let {
			TextButton(onClick = it, enabled = enabled) {
				Text(stringResource(if (both) R.string.unhide_all_short else R.string.unhide_all))
			}
		}
	}
}

/** One folder. Lock state is an icon and a subtitle, not a coloured warning band. */
@Composable
private fun FolderTile(row: ShelfRow, busy: Boolean) {
	var menu by remember { mutableStateOf(false) }
	Surface(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(enabled = !busy && row.onViewMedia != null) { row.onViewMedia?.invoke() },
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
			if (row.onForget != null || row.onViewMedia != null) {
				Box {
					IconButton(onClick = { menu = true }, enabled = !busy) {
						Icon(Icons.Filled.MoreVert, stringResource(R.string.more_options))
					}
					DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
						if (row.onViewMedia != null) {
							DropdownMenuItem(
								text = { Text(stringResource(R.string.peek_photos)) },
								onClick = {
									menu = false
									row.onViewMedia.invoke()
								},
							)
						}
						if (row.onForget != null) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
	state: AppUiState,
	onBack: () -> Unit,
	onDecoy: (DecoyType) -> Unit,
	onEntryMethod: (EntryMethod) -> Unit,
	onFakeCrash: (Boolean) -> Unit,
	onHideFromRecents: (Boolean) -> Unit,
	onThemeMode: (ThemeMode) -> Unit,
	onHidingPreference: (HidingPreference) -> Unit,
	onHiddenNameStyle: (HiddenNameStyle) -> Unit,
	onChangeCredential: (CredentialKind, CharArray, CharArray) -> Unit,
	onSetDecoyCredential: (CharArray, CharArray) -> Unit,
	onClearDecoyPin: (CharArray) -> Unit,
	onSetBurnerCredential: (CharArray, CharArray) -> Unit,
	onClearBurnerPin: (CharArray) -> Unit,
	onBiometricChange: (Boolean) -> Unit,
	onQuickLockChange: (Boolean) -> Unit,
	onAllowScreenshotsChange: (Boolean) -> Unit,
	onAutoHideMode: (AutoHideMode) -> Unit,
	onFlipToHideChange: (Boolean) -> Unit,
	onUsbPanicChange: (Boolean) -> Unit,
	onDeepProtectionChange: (Boolean) -> Unit,
	onSeedDecoy: () -> Unit,
	onForceUnhide: () -> Unit,
	onCheckHealth: () -> Unit,
	onFindRenamed: () -> Unit,
	onRecoverCandidate: (SafRecoveryCandidate, String) -> Unit,
	onRecordedName: suspend (SafRecoveryCandidate) -> String?,
	onExportRecovery: () -> Unit,
	onImportRecovery: () -> Unit,
	onRequestAllFiles: () -> Unit,
) {
	var showStealthGuide by remember { mutableStateOf(false) }
	var changeCurrent by remember { mutableStateOf<CharArray?>(null) }
	var askCurrent by remember { mutableStateOf(false) }
	// The kind being moved to, which is the current one unless a different row was picked.
	var changingTo by remember { mutableStateOf<CredentialKind?>(null) }
	var decoyCurrent by remember { mutableStateOf<CharArray?>(null) }
	var decoyAction by remember { mutableStateOf<DecoyPinAction?>(null) }
	var removeDecoyPin by remember { mutableStateOf(false) }
	var burnerCurrent by remember { mutableStateOf<CharArray?>(null) }
	var burnerAction by remember { mutableStateOf<DecoyPinAction?>(null) }
	var removeBurnerPin by remember { mutableStateOf(false) }
	var confirmForce by remember { mutableStateOf(false) }
	var recoveryCandidate by remember { mutableStateOf<SafRecoveryCandidate?>(null) }
	// Which page is open, or the hub. Registered after the handler that closes settings, so back
	// leaves the page before it leaves the screen.
	var category by remember { mutableStateOf<SettingsCategory?>(null) }
	BackHandler(enabled = category != null) { category = null }

	// A confirmed credential waits here for the second half of a two-step change. Leaving the screen
	// (back press, screen off, automatic lock) abandons the dialog that would have cleared it, so the
	// exit path has to.
	DisposableEffect(Unit) {
		onDispose {
			changeCurrent?.fill(' ')
			decoyCurrent?.fill(' ')
			burnerCurrent?.fill(' ')
		}
	}

	Scaffold(
		containerColor = MaterialTheme.colorScheme.background,
		topBar = {
			TopAppBar(
				title = { Text(category?.title() ?: stringResource(R.string.settings)) },
				navigationIcon = {
					IconButton(onClick = { if (category != null) category = null else onBack() }) {
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
			when (category) {
				null -> {
					item { StealthGuideCard(onClick = { showStealthGuide = true }) }
					item { SettingsHome(state) { category = it } }
				}

				SettingsCategory.DISGUISE -> {
					item { DisguiseSettingsSection(state, onDecoy, onEntryMethod) }
					item { EntrySettingsSection(state, onEntryMethod, onFakeCrash) }
				}

				SettingsCategory.CREDENTIALS -> item {
					AuthenticationSettingsSection(
						state = state,
						onChangePin = {
							changingTo = null
							askCurrent = true
						},
						onCredentialKind = { kind ->
							changingTo = kind
							askCurrent = true
						},
						onBiometricChange = { onBiometricChange(!state.biometricEnabled) },
						onSetDecoyPin = { decoyAction = DecoyPinAction.SET },
						onRemoveDecoyPin = { removeDecoyPin = true },
						onSeedDecoy = onSeedDecoy,
						onSetBurnerPin = { burnerAction = DecoyPinAction.SET },
						onRemoveBurnerPin = { removeBurnerPin = true },
						header = false,
					)
				}

				SettingsCategory.LOCKING -> item {
					AutomaticLockSettingsSection(
						state = state,
						onQuickLockChange = { onQuickLockChange(!state.quickLockNotification) },
						onAutoHideMode = onAutoHideMode,
						onFlipToHideChange = { onFlipToHideChange(!state.flipToHide) },
						onUsbPanicChange = { onUsbPanicChange(!state.usbPanic) },
						onAllowScreenshotsChange = { onAllowScreenshotsChange(!state.allowScreenshots) },
						onHideFromRecentsChange = { onHideFromRecents(!state.hideFromRecents) },
					)
				}

				SettingsCategory.HIDING -> item {
					FolderSettingsSection(
						state,
						onHidingPreference,
						onHiddenNameStyle,
						onRequestAllFiles,
						onDeepProtectionChange = { onDeepProtectionChange(!state.deepProtection) },
					)
				}

				SettingsCategory.APPEARANCE -> item {
					AppearanceSettingsSection(state, onThemeMode, header = false)
				}

				SettingsCategory.RECOVERY -> item {
					RecoverySettingsSection(
						state = state,
						onExportRecovery = onExportRecovery,
						onImportRecovery = onImportRecovery,
						onCheckHealth = onCheckHealth,
						onFindRenamed = onFindRenamed,
						onCandidate = { recoveryCandidate = it },
						onForceUnhide = { confirmForce = true },
						header = false,
					)
				}
			}
		}
	}

	if (askCurrent) {
		// Proving the old credential uses the kind the vault has today. Setting the new one uses the
		// kind being moved to, which is the same unless this is a change of kind.
		CredentialPrompt(
			kind = state.credentialKind,
			title = CredentialWords.currentTitle(state.credentialKind),
			subtitle = stringResource(R.string.current_pin_subtitle),
			confirmLabel = stringResource(R.string.continue_action),
			onConfirm = {
				askCurrent = false
				changeCurrent = it
			},
			onDismiss = {
				askCurrent = false
				changingTo = null
			},
		)
	}
	changeCurrent?.let { current ->
		val wanted = changingTo ?: state.credentialKind
		CredentialPrompt(
			kind = wanted,
			title = CredentialWords.newTitle(wanted),
			subtitle = CredentialWords.hint(wanted),
			confirmLabel = stringResource(R.string.save),
			confirmEntry = true,
			onConfirm = {
				onChangeCredential(wanted, current, it)
				changeCurrent = null
				changingTo = null
			},
			onDismiss = {
				current.fill(' ')
				changeCurrent = null
				changingTo = null
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
			CredentialPrompt(
				kind = state.credentialKind,
				title = stringResource(R.string.confirm_primary_pin),
				subtitle = stringResource(R.string.confirm_primary_pin_summary),
				confirmLabel = stringResource(R.string.continue_action),
				onConfirm = acceptCurrent,
				onDismiss = { decoyAction = null },
			)
		}
	}
	decoyCurrent?.let { current ->
		CredentialPrompt(
			kind = state.credentialKind,
			title = stringResource(
				if (state.decoyPinSet) R.string.change_decoy_pin else R.string.create_decoy_pin,
			),
			subtitle = stringResource(R.string.decoy_pin_hint),
			confirmLabel = stringResource(R.string.save),
			confirmEntry = true,
			onConfirm = { pin ->
				onSetDecoyCredential(current, pin)
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
	burnerAction?.let { action ->
		if (burnerCurrent == null) {
			val acceptCurrent: (CharArray) -> Unit = { current ->
				if (action == DecoyPinAction.SET) {
					burnerCurrent = current
				} else {
					onClearBurnerPin(current)
					burnerAction = null
				}
			}
			CredentialPrompt(
				kind = state.credentialKind,
				title = stringResource(R.string.confirm_primary_pin),
				subtitle = stringResource(R.string.confirm_primary_pin_summary),
				confirmLabel = stringResource(R.string.continue_action),
				onConfirm = acceptCurrent,
				onDismiss = { burnerAction = null },
			)
		}
	}
	burnerCurrent?.let { current ->
		CredentialPrompt(
			kind = state.credentialKind,
			title = stringResource(
				if (state.burnerPinSet) R.string.change_burner_pin else R.string.create_burner_pin,
			),
			subtitle = stringResource(R.string.burner_pin_hint),
			confirmLabel = stringResource(R.string.save),
			confirmEntry = true,
			onConfirm = { pin ->
				onSetBurnerCredential(current, pin)
				burnerCurrent = null
				burnerAction = null
			},
			onDismiss = {
				current.fill(' ')
				burnerCurrent = null
				burnerAction = null
			},
		)
	}
	if (removeBurnerPin) {
		ConfirmDialog(
			title = stringResource(R.string.remove_burner_pin),
			body = stringResource(R.string.remove_burner_pin_confirm),
			confirmLabel = stringResource(R.string.remove),
			dangerous = true,
			onConfirm = {
				removeBurnerPin = false
				burnerAction = DecoyPinAction.REMOVE
			},
			onDismiss = { removeBurnerPin = false },
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
			onRecordedName = onRecordedName,
			onConfirm = { name ->
				onRecoverCandidate(candidate, name)
				recoveryCandidate = null
			},
			onDismiss = { recoveryCandidate = null },
		)
	}
	if (showStealthGuide) {
		StealthGuideDialog(onDismiss = { showStealthGuide = false })
	}
}

/**
 * A row that opens its options in place. A short fixed list of exclusive settings does not need a
 * full-width row each; five of them pushed everything below off the screen.
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

/**
 * Asks what to call a folder found under a name that is not its own. A masked name suggests nothing,
 * so the name recorded inside the folder is read in the background and fills the field in, unless
 * the owner has typed something of their own.
 */
@Composable
private fun RecoverRenamedDialog(
	candidate: SafRecoveryCandidate,
	onRecordedName: suspend (SafRecoveryCandidate) -> String?,
	onConfirm: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	var name by remember(candidate) { mutableStateOf(candidate.suggestedName) }
	var edited by remember(candidate) { mutableStateOf(false) }
	LaunchedEffect(candidate) {
		val recorded = onRecordedName(candidate)
		if (recorded != null && !edited) name = recorded
	}
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.recover_renamed_folder)) },
		text = {
			Column {
				Text(stringResource(R.string.recover_renamed_explanation, candidate.hiddenName))
				Spacer(Modifier.height(12.dp))
				OutlinedTextField(
					value = name,
					onValueChange = {
						edited = true
						name = it
					},
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
	// A text field's value is a String and a String cannot be overwritten, so unlike the keypad this
	// passphrase sits in the heap until it is collected. All we can do is not hold on to it: the
	// state goes with the dialog, and the array handed on from here is cleared by the caller.
	var first by remember { mutableStateOf("") }
	var second by remember { mutableStateOf("") }
	val valid = first.length >= MIN_RECOVERY_PASSWORD && (!confirmEntry || first == second)
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

@Composable
private fun BlockedDialog(
	report: BlockedReport,
	canRequestAllFiles: Boolean,
	onRequestAllFiles: () -> Unit,
	onDismiss: () -> Unit,
) {
	val resources = LocalResources.current
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(report.title.resolve(resources)) },
		text = {
			Column(
				modifier = Modifier.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(8.dp),
			) {
				report.problems.forEach { problem ->
					Text(
						stringResource(
							R.string.named_failure,
							problem.name,
							problem.reason.resolve(resources),
						),
					)
				}
			}
		},
		confirmButton = {
			if (report.needsAllFiles && canRequestAllFiles) {
				TextButton(onClick = onRequestAllFiles) {
					Text(stringResource(R.string.turn_on_all_files))
				}
			} else {
				TextButton(onClick = onDismiss) {
					Text(stringResource(R.string.close))
				}
			}
		},
		dismissButton = if (report.needsAllFiles && canRequestAllFiles) {
			{
				TextButton(onClick = onDismiss) {
					Text(stringResource(R.string.close))
				}
			}
		} else {
			null
		},
	)
}
