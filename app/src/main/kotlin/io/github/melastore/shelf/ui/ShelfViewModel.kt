package io.github.melastore.shelf.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.melastore.shelf.data.FileLocker
import io.github.melastore.shelf.data.FolderHider
import io.github.melastore.shelf.data.FolderTarget
import io.github.melastore.shelf.data.Habit
import io.github.melastore.shelf.data.HabitStore
import io.github.melastore.shelf.data.HeaderRecovery
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HideResult
import io.github.melastore.shelf.data.Journal
import io.github.melastore.shelf.data.LockResult
import io.github.melastore.shelf.data.LockedFile
import io.github.melastore.shelf.data.RecordsCorrupted
import io.github.melastore.shelf.data.SafPaths
import io.github.melastore.shelf.data.UnlockResult
import io.github.melastore.shelf.data.VaultStore
import io.github.melastore.shelf.root.StoragePaths
import io.github.melastore.shelf.security.PassphraseGate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Screen { HABITS, VAULT }

data class AppUiState(
	val screen: Screen = Screen.HABITS,
	val passphraseSet: Boolean = false,
	val habits: List<Habit> = emptyList(),
	val method: HideMethod? = null,
	val canRequestAllFiles: Boolean = false,
	/** Set when an operation stopped to ask the user for access to a folder above the one it wants. */
	val accessNeededFor: Uri? = null,
	val entries: List<HiddenEntry> = emptyList(),
	val lockedFiles: List<LockedFile> = emptyList(),
	val busy: Boolean = false,
	val message: String? = null,
)

/**
 * Drives both faces of the app: the habit tracker the launcher opens, and the hidden vault behind
 * it. Authentication stays separate from habit input so a mistyped secret cannot become a habit.
 */
class ShelfViewModel(app: Application) : AndroidViewModel(app) {

	private val habitStore = HabitStore(File(app.filesDir, "habits.json"))
	private val gate = PassphraseGate(File(app.filesDir, "gate"))

	private val paths = StoragePaths.forCurrentUser()
	private val journal = Journal(File(app.filesDir, "journal.json"))
	private val hider = FolderHider(app, journal, paths)
	private val vault = VaultStore(File(app.filesDir, "vault.json"))
	private val recovery = HeaderRecovery(File(app.filesDir, "headers"))
	private val locker = FileLocker(app, vault, recovery, paths)

	private val _state = MutableStateFlow(AppUiState())
	val state: StateFlow<AppUiState> = _state.asStateFlow()

	/**
	 * Set while the system file picker has the foreground. Handing off to it stops the activity, and
	 * without this the vault would lock itself the moment the user went to choose a folder.
	 */
	private var awaitingPicker = false

	/** What to finish once the user has granted the access an operation asked for. */
	private var pending: (suspend () -> Unit)? = null
	private val operationMutex = Mutex()
	private var recoveryCheckedFor: HideMethod? = null

	init {
		viewModelScope.launch {
			val passphraseSet = gate.isSet()
			val habits = guard { habitStore.read() }.orEmpty()
			_state.update { it.copy(passphraseSet = passphraseSet, habits = habits) }
		}
	}

	/** Reads the habit list back after a change, leaving the shown list alone if it is unreadable. */
	private suspend fun refreshHabits() {
		val habits = guard { habitStore.read() } ?: return
		_state.update { it.copy(habits = habits) }
	}

	// --- Habit tracker (the visible app) ---

	/** Adds a habit. Vault authentication has its own dialog so a mistyped secret is never stored. */
	fun submitHabit(text: String) {
		val trimmed = text.trim()
		if (trimmed.isEmpty()) return

		viewModelScope.launch {
			guard { habitStore.add(trimmed) }
			refreshHabits()
		}
	}

	fun toggleHabit(habit: Habit, date: String) = viewModelScope.launch {
		guard { habitStore.toggle(habit.id, date) }
		refreshHabits()
	}

	fun removeHabit(habit: Habit) = viewModelScope.launch {
		guard { habitStore.remove(habit.id) }
		refreshHabits()
	}

	/** Sets the vault passphrase the first time, through a deliberately obscure entry point. */
	fun setPassphrase(passphrase: CharArray) = viewModelScope.launch {
		try {
			withContext(Dispatchers.Default) { gate.set(passphrase) }
			_state.update { it.copy(passphraseSet = true) }
		} finally {
			passphrase.fill(' ')
		}
	}

	fun unlockVault(passphrase: CharArray) = viewModelScope.launch {
		try {
			val matches = withContext(Dispatchers.Default) { gate.matches(passphrase) }
			if (matches) openVault() else fail("Wrong passphrase.")
		} finally {
			passphrase.fill(' ')
		}
	}

	// --- Vault (revealed only after the trigger) ---

	private suspend fun openVault() {
		awaitingPicker = false
		val entries = guard { journal.read() }.orEmpty()
		val locked = guard { vault.read() }.orEmpty()
		_state.update { it.copy(screen = Screen.VAULT, entries = entries, lockedFiles = locked) }
		refreshCapabilitiesNow()
	}

	/**
	 * Works out how this device can hide a folder today. The answer can change while the app is
	 * running — granting all-files access happens in Settings, not here — so it is re-read whenever
	 * the user comes back to the vault.
	 */
	fun refreshCapabilities() = viewModelScope.launch { refreshCapabilitiesNow() }

	private suspend fun refreshCapabilitiesNow() {
		val method = hider.activeMethod()
		var recoveredEntries: List<HiddenEntry>? = null
		if (_state.value.entries.isEmpty() && method != null && method != recoveryCheckedFor) {
			recoveryCheckedFor = method
			if ((guard { hider.recoverOrphans() } ?: 0) > 0) {
				recoveredEntries = guard { journal.read() }
			}
		}
		_state.update {
			it.copy(
				method = method,
				entries = recoveredEntries ?: it.entries,
				// Root already beats anything all-files access would add, so the upgrade is only worth
				// offering when the fallback rename is all that is left.
				canRequestAllFiles = method == HideMethod.DOT_RENAME,
			)
		}
	}

	/** Leaves the vault and returns to the habit face; the vault contents drop out of memory. */
	fun lockVault() = _state.update {
		it.copy(screen = Screen.HABITS, entries = emptyList(), lockedFiles = emptyList())
	}

	/** Called before handing off to the system picker, which stops the activity underneath it. */
	fun expectExternalPicker() {
		awaitingPicker = true
	}

	/** Called when the picker hands control back, cancelled or not, so the reprieve does not linger. */
	fun onPickerResult() {
		awaitingPicker = false
	}

	/**
	 * The vault must not outlive the app being in the foreground: the next person to pick up an
	 * unlocked phone would find it open behind the launcher. The one exception is the file picker,
	 * which the user reached from inside the vault and returns to it.
	 */
	fun onMovedToBackground() {
		if (awaitingPicker) {
			awaitingPicker = false
			return
		}
		lockVault()
	}

	fun hideFolder(treeUri: Uri) = launchBusy {
		val path = resolveDocumentPath(treeUri, isTree = true)
			?: return@launchBusy fail("Could not resolve that folder to a storage path.")
		val name = DocumentFile.fromTreeUri(getApplication(), treeUri)?.name ?: File(path).name
		// The grant has to outlive this process: without it there is no way to rename the folder back.
		val persisted = persist(treeUri)
		hide(FolderTarget(path, name, treeUri.takeIf { persisted }))
	}

	private suspend fun hide(target: FolderTarget) {
		when (val result = hider.hide(target)) {
			is HideResult.Ok -> reloadVault("Hidden ${result.entry.displayName}", result.warning)
			is HideResult.Failed -> fail(result.reason)
			is HideResult.NeedsAccess -> askForAccess(result) { hide(target) }
		}
	}

	fun restore(entry: HiddenEntry) = launchBusy { restoreEntry(entry) }

	private suspend fun restoreEntry(entry: HiddenEntry) {
		when (val result = hider.restore(entry)) {
			is HideResult.Ok -> reloadVault("Restored ${entry.displayName}", result.warning)
			is HideResult.Failed -> fail(result.reason)
			is HideResult.NeedsAccess -> askForAccess(result) { restoreEntry(entry) }
		}
	}

	/**
	 * Stops and asks for the folder access the operation turned out to need, holding on to what was
	 * being done so the user does not have to start it again.
	 */
	private fun askForAccess(needed: HideResult.NeedsAccess, retry: suspend () -> Unit) {
		pending = retry
		_state.update { it.copy(message = needed.reason, accessNeededFor = initialUri(needed.path)) }
	}

	/** The user answered the request for folder access; take the grant and finish what was asked. */
	fun grantedAccess(treeUri: Uri?) = launchBusy {
		_state.update { it.copy(accessNeededFor = null) }
		val retry = pending ?: return@launchBusy
		pending = null
		if (treeUri == null) return@launchBusy fail("Nothing was changed.")
		if (!persist(treeUri)) {
			return@launchBusy fail("Shelf could not keep access to that folder. Nothing was changed.")
		}
		retry()
	}

	/** Points the folder picker at [path] so the user is not left to find it themselves. */
	private fun initialUri(path: String): Uri? {
		val id = SafPaths.documentId(paths.emulatedRoot, path) ?: return null
		return runCatching { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, id) }.getOrNull()
	}

	fun lockFile(fileUri: Uri, passphrase: CharArray) = launchBusy {
		try {
			val path = resolveDocumentPath(fileUri, isTree = false)
				?: return@launchBusy fail("Could not resolve that file to a storage path.")
			val name = DocumentFile.fromSingleUri(getApplication(), fileUri)?.name ?: File(path).name
			val persistedUri = fileUri.takeIf { persist(fileUri) }

			when (val result = locker.lock(path, name, persistedUri, passphrase)) {
				is LockResult.Ok -> reloadVault("Locked ${result.locked.displayName}", result.warning)
				is LockResult.Failed -> fail(result.reason)
			}
		} finally {
			passphrase.fill(' ')
		}
	}

	fun unlockFile(entry: LockedFile, passphrase: CharArray) = launchBusy {
		try {
			when (val result = locker.unlock(entry, passphrase)) {
				is UnlockResult.Ok -> reloadVault("Unlocked ${entry.displayName}", result.warning)
				is UnlockResult.WrongPassphrase -> fail("Wrong passphrase.")
				is UnlockResult.Failed -> fail(result.reason)
			}
		} finally {
			passphrase.fill(' ')
		}
	}

	fun recoverFile(fileUri: Uri, passphrase: CharArray) = launchBusy {
		try {
			val path = resolveDocumentPath(fileUri, isTree = false)
				?: return@launchBusy fail("Could not resolve that file to a storage path.")
			val name = DocumentFile.fromSingleUri(getApplication(), fileUri)?.name ?: File(path).name
			when (val result = locker.recover(path, name, fileUri, passphrase)) {
				is UnlockResult.Ok -> reloadVault("Recovered $name", result.warning)
				is UnlockResult.WrongPassphrase -> fail("Wrong passphrase.")
				is UnlockResult.Failed -> fail(result.reason)
			}
		} finally {
			passphrase.fill(' ')
		}
	}

	fun consumeMessage() = _state.update { it.copy(message = null) }

	/**
	 * Holds on to a document grant across restarts. Without it a folder hidden today could not be
	 * renamed back tomorrow, and a locked file could not be reopened to decrypt its header.
	 */
	private fun persist(uri: Uri): Boolean {
		val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
		val resolver = getApplication<Application>().contentResolver
		val taken = runCatching {
			resolver.takePersistableUriPermission(uri, flags)
		}.isSuccess
		if (!taken) return false
		return resolver.persistedUriPermissions.any {
			it.uri == uri && it.isReadPermission && it.isWritePermission
		}
	}

	private suspend fun reloadVault(message: String, warning: String? = null) {
		val entries = guard { journal.read() }.orEmpty()
		val locked = guard { vault.read() }.orEmpty()
		_state.update {
			it.copy(
				entries = entries,
				lockedFiles = locked,
				message = listOfNotNull(message, warning).joinToString(" — "),
			)
		}
	}

	private fun launchBusy(block: suspend () -> Unit) {
		viewModelScope.launch {
			operationMutex.withLock {
				_state.update { it.copy(busy = true) }
				try {
					block()
				} catch (e: RecordsCorrupted) {
					fail(recoveryMessage(e))
				} catch (e: Exception) {
					fail(e.message ?: "That did not work.")
				} finally {
					_state.update { it.copy(busy = false) }
				}
			}
		}
	}

	/**
	 * Runs a read or write of the persisted records, turning an unreadable file into a message rather
	 * than a crash. Callers get null and leave the list they already had alone.
	 */
	private suspend fun <T> guard(block: suspend () -> T): T? = try {
		block()
	} catch (e: RecordsCorrupted) {
		fail(recoveryMessage(e))
		null
	}

	private fun recoveryMessage(e: RecordsCorrupted): String =
		"${e.original.name} is damaged and could not be read. Nothing was changed, and the file was " +
			"kept as ${e.preserved.name} so its records can still be recovered."

	private fun fail(reason: String) = _state.update { it.copy(message = reason) }

	private companion object {
		const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
	}

	/**
	 * Maps a Storage Access Framework document Uri back to an absolute path on the primary volume,
	 * whose document ids are "primary:Relative/Path". Other volumes are left for a later revision
	 * rather than guessed at. The root comes from [paths], so on a secondary user or a work profile
	 * this stays on that user's storage.
	 */
	private fun resolveDocumentPath(uri: Uri, isTree: Boolean): String? {
		if (uri.authority != EXTERNAL_STORAGE) return null
		val doc = if (isTree) {
			DocumentFile.fromTreeUri(getApplication(), uri)
		} else {
			DocumentFile.fromSingleUri(getApplication(), uri)
		}
		val docId = doc?.uri?.lastPathSegment ?: uri.lastPathSegment ?: return null
		val (volume, relative) = docId.split(':', limit = 2).let {
			it[0] to it.getOrElse(1) { "" }
		}
		if (volume != "primary") return null
		return paths.emulatedRoot + if (relative.isEmpty()) "" else "/$relative"
	}
}
