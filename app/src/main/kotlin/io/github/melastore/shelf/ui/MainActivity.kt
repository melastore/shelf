package io.github.melastore.shelf.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.Habit
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.LockedFile
import io.github.melastore.shelf.data.currentStreak
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

	private val viewModel: ShelfViewModel by viewModels()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			MaterialTheme {
				val state by viewModel.state.collectAsStateWithLifecycle()
				val snackbar = remember { SnackbarHostState() }
				val inVault = state.screen == Screen.VAULT

				// Keep the vault out of the Recents thumbnail and out of screenshots. That snapshot is
				// taken as the app leaves the foreground, before any lock could take effect, so the
				// flag has to be set the whole time the vault is on screen.
				LaunchedEffect(inVault) {
					if (inVault) {
						window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
					} else {
						window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
					}
				}

				// Back leaves the vault for the habit face rather than leaving the app: backing out
				// should look exactly like never having been there.
				BackHandler(enabled = inVault) { viewModel.lockVault() }

				LaunchedEffect(state.message) {
					state.message?.let {
						snackbar.showSnackbar(it)
						viewModel.consumeMessage()
					}
				}

				when (state.screen) {
					Screen.HABITS -> HabitScreen(state, viewModel, snackbar)
					Screen.VAULT -> VaultScreen(state, viewModel, snackbar)
				}
			}
		}
	}

	/**
	 * The vault closes whenever the app leaves the foreground. Recents, a home press or a switch to
	 * another app would otherwise leave it open for whoever picks the phone up next.
	 */
	override fun onStop() {
		super.onStop()
		if (!isChangingConfigurations) viewModel.onMovedToBackground()
	}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HabitScreen(state: AppUiState, viewModel: ShelfViewModel, snackbar: SnackbarHostState) {
	var entry by remember { mutableStateOf("") }
	var showVaultPassphrase by remember { mutableStateOf(false) }
	var today by remember { mutableStateOf(LocalDate.now()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(60_000)
			val current = LocalDate.now()
			if (current != today) today = current
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					// The title is the concealed entry point. Authentication stays separate from habit
					// input so a mistyped passphrase can never become visible content.
					Text(
						stringResource(R.string.app_name),
						modifier = Modifier.combinedClickable(
							onClick = {},
							onLongClick = { showVaultPassphrase = true },
						),
					)
				},
			)
		},
		snackbarHost = { SnackbarHost(snackbar) },
	) { padding ->
		Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				OutlinedTextField(
					value = entry,
					onValueChange = { entry = it },
					singleLine = true,
					label = { Text(stringResource(R.string.add_habit)) },
					modifier = Modifier.weight(1f),
				)
				IconButton(
					onClick = {
						viewModel.submitHabit(entry)
						entry = ""
					},
					enabled = entry.isNotBlank(),
				) { Icon(Icons.Filled.Add, stringResource(R.string.add_habit)) }
			}

			if (state.habits.isEmpty()) {
				CenteredMessage(
					body = stringResource(R.string.habits_empty),
					modifier = Modifier.fillMaxSize(),
				)
			} else {
				LazyColumn(
					modifier = Modifier.fillMaxSize().padding(top = 8.dp),
					verticalArrangement = Arrangement.spacedBy(8.dp),
				) {
					items(state.habits, key = { it.id }) { habit ->
						HabitRow(
							habit = habit,
							today = today,
							onToggleDate = { date -> viewModel.toggleHabit(habit, date) },
							onDelete = { viewModel.removeHabit(habit) },
						)
					}
				}
			}
		}
	}

	if (showVaultPassphrase) {
		PassphraseDialog(
			title = stringResource(
				if (state.passphraseSet) R.string.unlock_vault_title else R.string.set_passphrase_title,
			),
			confirmLabel = stringResource(if (state.passphraseSet) R.string.unlock else R.string.save),
			confirmEntry = !state.passphraseSet,
			minimumLength = if (state.passphraseSet) 1 else MIN_PASSPHRASE_LENGTH,
			onConfirm = {
				if (state.passphraseSet) viewModel.unlockVault(it) else viewModel.setPassphrase(it)
				showVaultPassphrase = false
			},
			onDismiss = { showVaultPassphrase = false },
		)
	}
}

@Composable
private fun HabitRow(
	habit: Habit,
	today: LocalDate,
	onToggleDate: (String) -> Unit,
	onDelete: () -> Unit,
) {
	val streak = currentStreak(habit.checkedDates, today)
	val week = remember(today) { (6L downTo 0L).map { today.minusDays(it) } }

	Card(modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(16.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(modifier = Modifier.weight(1f)) {
					Text(habit.name, style = MaterialTheme.typography.titleMedium)
					Text(
						pluralStringResource(R.plurals.streak_format, streak, streak),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
				IconButton(onClick = onDelete) {
					Icon(Icons.Filled.Delete, stringResource(R.string.remove), tint = MaterialTheme.colorScheme.error)
				}
			}
			Spacer(modifier = Modifier.height(12.dp))
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.SpaceBetween,
			) {
				for (date in week) {
					DayCell(
						date = date,
						checked = date.toString() in habit.checkedDates,
						onClick = { onToggleDate(date.toString()) },
					)
				}
			}
		}
	}
}

@Composable
private fun DayCell(date: LocalDate, checked: Boolean, onClick: () -> Unit) {
	val background = if (checked) {
		MaterialTheme.colorScheme.primary
	} else {
		MaterialTheme.colorScheme.surfaceVariant
	}
	val foreground = if (checked) {
		MaterialTheme.colorScheme.onPrimary
	} else {
		MaterialTheme.colorScheme.onSurfaceVariant
	}
	Column(horizontalAlignment = Alignment.CenterHorizontally) {
		Text(
			date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		Spacer(modifier = Modifier.height(4.dp))
		Box(
			modifier = Modifier
				.size(40.dp)
				.clip(CircleShape)
				.background(background)
				.clickable(onClick = onClick),
			contentAlignment = Alignment.Center,
		) {
			Text(
				date.dayOfMonth.toString(),
				style = MaterialTheme.typography.labelSmall,
				color = foreground,
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultScreen(state: AppUiState, viewModel: ShelfViewModel, snackbar: SnackbarHostState) {
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

	// Granting all-files access happens in Settings, so the answer can be different by the time the
	// user comes back.
	val requestAllFiles = rememberLauncherForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		viewModel.onPickerResult()
		viewModel.refreshCapabilities()
	}

	// A rename needs a grant on the folder above the target, which the first pick may not have
	// covered. Asking for it here and carrying on beats making the user start over.
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

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.vault_title)) },
				navigationIcon = {
					IconButton(onClick = viewModel::lockVault) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.close_vault))
					}
				},
				actions = {
					IconButton(
						enabled = !state.busy,
						onClick = {
							viewModel.expectExternalPicker()
							pickFile.launch(arrayOf("*/*"))
						},
					) {
						Icon(Icons.Filled.Lock, stringResource(R.string.lock_file))
					}
				},
			)
		},
		snackbarHost = { SnackbarHost(snackbar) },
		floatingActionButton = {
			if (!state.busy) {
				FloatingActionButton(onClick = {
					viewModel.expectExternalPicker()
					pickFolder.launch(null)
				}) {
					Icon(Icons.Filled.Add, stringResource(R.string.add_folder))
				}
			}
		},
	) { padding ->
		VaultContent(
			state = state,
			onRestore = viewModel::restore,
			onUnlock = { pendingUnlock = it },
			onRequestAllFiles = {
				viewModel.expectExternalPicker()
				requestAllFiles.launch(allFilesAccessSettings(context.packageName))
			},
			onRecoverFile = {
				viewModel.expectExternalPicker()
				pickRecoveryFile.launch(arrayOf("*/*"))
			},
			modifier = Modifier.padding(padding).fillMaxSize(),
		)
	}

	pendingLockUri?.let { uri ->
		PassphraseDialog(
			title = stringResource(R.string.lock_title),
			confirmLabel = stringResource(R.string.lock_action),
			confirmEntry = true,
			minimumLength = MIN_PASSPHRASE_LENGTH,
			onConfirm = {
				viewModel.lockFile(uri, it)
				pendingLockUri = null
			},
			onDismiss = { pendingLockUri = null },
		)
	}

	pendingUnlock?.let { entry ->
		PassphraseDialog(
			title = stringResource(R.string.unlock_title),
			confirmLabel = stringResource(R.string.restore),
			onConfirm = {
				viewModel.unlockFile(entry, it)
				pendingUnlock = null
			},
			onDismiss = { pendingUnlock = null },
		)
	}

	pendingRecoveryUri?.let { uri ->
		PassphraseDialog(
			title = stringResource(R.string.recover_file_title),
			confirmLabel = stringResource(R.string.restore),
			onConfirm = {
				viewModel.recoverFile(uri, it)
				pendingRecoveryUri = null
			},
			onDismiss = { pendingRecoveryUri = null },
		)
	}
}

@Composable
private fun VaultContent(
	state: AppUiState,
	onRestore: (HiddenEntry) -> Unit,
	onUnlock: (LockedFile) -> Unit,
	onRequestAllFiles: () -> Unit,
	onRecoverFile: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(modifier = modifier) {
		MethodBanner(
			state = state,
			onRequestAllFiles = onRequestAllFiles,
			onRecoverFile = onRecoverFile,
		)

		if (state.entries.isEmpty() && state.lockedFiles.isEmpty()) {
			CenteredMessage(
				body = stringResource(R.string.empty_hint),
				modifier = Modifier.fillMaxSize(),
			)
			return@Column
		}

		LazyColumn(
			modifier = Modifier.fillMaxSize().padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			items(state.entries, key = { "folder:${it.path}" }) { entry ->
				ItemCard(
					title = entry.displayName,
					subtitle = entry.path,
					actionLabel = stringResource(R.string.restore),
					enabled = !state.busy,
					onAction = { onRestore(entry) },
				)
			}
			items(state.lockedFiles, key = { "file:${it.path}" }) { locked ->
				ItemCard(
					title = locked.displayName,
					subtitle = stringResource(R.string.locked_label),
					actionLabel = stringResource(R.string.restore),
					enabled = !state.busy,
					onAction = { onUnlock(locked) },
				)
			}
		}
	}
}

/**
 * Says which of the three ways Shelf is hiding folders today, and offers the stronger one when the
 * device could manage it. Being explicit matters: the methods differ in who can still find the
 * folder, and that is not something to leave the user guessing about.
 */
@Composable
private fun MethodBanner(
	state: AppUiState,
	onRequestAllFiles: () -> Unit,
	onRecoverFile: () -> Unit,
) {
	val method = state.method ?: return
	val description = when (method) {
		HideMethod.ROOT_CHMOD -> R.string.method_root
		HideMethod.PRIVATE_MOVE -> R.string.method_private
		HideMethod.DOT_RENAME -> R.string.method_dot
	}
	Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
		Column(modifier = Modifier.padding(16.dp)) {
			Text(
				stringResource(description),
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			if (state.canRequestAllFiles) {
				TextButton(onClick = onRequestAllFiles, enabled = !state.busy) {
					Text(stringResource(R.string.grant_all_files))
				}
			}
			TextButton(onClick = onRecoverFile, enabled = !state.busy) {
				Text(stringResource(R.string.recover_file))
			}
		}
	}
}

@Composable
private fun ItemCard(
	title: String,
	subtitle: String,
	actionLabel: String,
	enabled: Boolean,
	onAction: () -> Unit,
) {
	Card(modifier = Modifier.fillMaxWidth()) {
		Column(modifier = Modifier.padding(16.dp)) {
			Text(title, style = MaterialTheme.typography.titleMedium)
			Text(
				subtitle,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel) }
		}
	}
}

@Composable
private fun PassphraseDialog(
	title: String,
	confirmLabel: String,
	confirmEntry: Boolean = false,
	minimumLength: Int = 1,
	onConfirm: (CharArray) -> Unit,
	onDismiss: () -> Unit,
) {
	var text by remember { mutableStateOf("") }
	var confirmation by remember { mutableStateOf("") }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column {
				OutlinedTextField(
				value = text,
				onValueChange = { text = it },
				singleLine = true,
				label = { Text(stringResource(R.string.passphrase)) },
				visualTransformation = PasswordVisualTransformation(),
				// Password type keeps the passphrase out of the keyboard's learned suggestions.
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
			)
				if (confirmEntry) {
					Spacer(modifier = Modifier.height(12.dp))
					OutlinedTextField(
					value = confirmation,
					onValueChange = { confirmation = it },
					singleLine = true,
					label = { Text(stringResource(R.string.confirm_passphrase)) },
					visualTransformation = PasswordVisualTransformation(),
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
					)
				}
			}
		},
		confirmButton = {
			TextButton(
				onClick = { onConfirm(text.toCharArray()) },
				enabled = text.length >= minimumLength && (!confirmEntry || text == confirmation),
			) { Text(confirmLabel) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
		},
	)
}

@Composable
private fun CenteredMessage(
	body: String,
	modifier: Modifier = Modifier,
	title: String? = null,
) {
	Column(
		modifier = modifier.padding(32.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		if (title != null) {
			Text(title, style = MaterialTheme.typography.headlineSmall)
		}
		Text(body, style = MaterialTheme.typography.bodyLarge)
	}
}

/**
 * The stock picker contracts ask for read access for the length of the task. Shelf has to come back
 * to the same folder days later to rename it out of hiding, and to the same file to decrypt its
 * header, so both grants are asked for as writable and persistable.
 */
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

private const val MIN_PASSPHRASE_LENGTH = 8
