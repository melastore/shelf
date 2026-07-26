package io.github.melastore.shelf.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.melastore.shelf.data.AppPreferences
import io.github.melastore.shelf.data.CalendarEvent
import io.github.melastore.shelf.data.CalendarEventStore
import io.github.melastore.shelf.data.DecoyType
import io.github.melastore.shelf.data.EntryMethod
import io.github.melastore.shelf.data.FileLocker
import io.github.melastore.shelf.data.FolderHider
import io.github.melastore.shelf.data.FolderTarget
import io.github.melastore.shelf.data.Habit
import io.github.melastore.shelf.data.HabitStore
import io.github.melastore.shelf.data.HeaderRecovery
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HideResult
import io.github.melastore.shelf.data.HidingPreference
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

enum class Screen { DECOY, VAULT, SETTINGS }

data class AppUiState(
	val ready: Boolean = false,
	val screen: Screen = Screen.DECOY,
	val credentialSet: Boolean = false,
	val vaultUsesPin: Boolean = true,
	val decoyPinSet: Boolean = false,
	val decoy: DecoyType = DecoyType.HABITS,
	val entryMethod: EntryMethod = EntryMethod.TITLE_HOLD,
	val hidingPreference: HidingPreference = HidingPreference.AUTO,
	val habits: List<Habit> = emptyList(),
	val calendarEvents: List<CalendarEvent> = emptyList(),
	val method: HideMethod? = null,
	val availableMethods: Set<HideMethod> = emptySet(),
	val canRequestAllFiles: Boolean = false,
	/** Set when an operation stopped to ask for access to a folder above the target. */
	val accessNeededFor: Uri? = null,
	val entries: List<HiddenEntry> = emptyList(),
	val lockedFiles: List<LockedFile> = emptyList(),
	val busy: Boolean = false,
	val message: String? = null,
)

class ShelfViewModel(app: Application) : AndroidViewModel(app) {

	private val habitStore = HabitStore(File(app.filesDir, "habits.json"))
	private val calendarStore = CalendarEventStore(File(app.filesDir, "calendar.json"))
	private val gate = PassphraseGate(File(app.filesDir, "gate"))
	private val decoyGate = PassphraseGate(File(app.filesDir, "decoy_gate"))
	private val preferences = AppPreferences(app)
	private val launcherAliases = LauncherAliasController(app)

	private val paths = StoragePaths.forCurrentUser()
	private val journal = Journal(File(app.filesDir, "journal.json"))
	private val hider = FolderHider(app, journal, paths)
	private val vault = VaultStore(File(app.filesDir, "vault.json"))
	private val recovery = HeaderRecovery(File(app.filesDir, "headers"))
	private val locker = FileLocker(app, vault, recovery, paths)

	private val _state = MutableStateFlow(AppUiState())
	val state: StateFlow<AppUiState> = _state.asStateFlow()

	private var awaitingPicker = false
	private var pending: (suspend () -> Unit)? = null
	private val operationMutex = Mutex()
	private var recoveryCheckedFor: HideMethod? = null

	init {
		viewModelScope.launch {
			val credentialSet = gate.isSet()
			val settings = preferences.read(credentialSet)
			withContext(Dispatchers.IO) { launcherAliases.apply(settings.decoy) }
			val habits = guard { habitStore.read() }.orEmpty()
			val events = guard { calendarStore.read() }.orEmpty()
			_state.update {
				it.copy(
					ready = true,
					credentialSet = credentialSet,
					vaultUsesPin = settings.vaultUsesPin,
					decoyPinSet = decoyGate.isSet(),
					decoy = settings.decoy,
					entryMethod = settings.entryMethod,
					hidingPreference = settings.hidingPreference,
					habits = habits,
					calendarEvents = events,
				)
			}
		}
	}

	// Decoy content

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

	fun addCalendarEvent(date: String, title: String) {
		val clean = title.trim()
		if (clean.isEmpty()) return
		viewModelScope.launch {
			guard { calendarStore.add(date, clean) }
			refreshCalendar()
		}
	}

	fun removeCalendarEvent(event: CalendarEvent) = viewModelScope.launch {
		guard { calendarStore.remove(event.id) }
		refreshCalendar()
	}

	private suspend fun refreshHabits() {
		val habits = guard { habitStore.read() } ?: return
		_state.update { it.copy(habits = habits) }
	}

	private suspend fun refreshCalendar() {
		val events = guard { calendarStore.read() } ?: return
		_state.update { it.copy(calendarEvents = events) }
	}

	// Authentication

	fun setVaultPin(pin: CharArray) = viewModelScope.launch {
		try {
			validatePin(pin)?.let { return@launch fail(it) }
			withContext(Dispatchers.Default) { gate.set(pin) }
			preferences.setVaultUsesPin(true)
			_state.update { it.copy(credentialSet = true, vaultUsesPin = true) }
			openVault()
		} finally {
			pin.fill(' ')
		}
	}

	fun unlockVault(input: CharArray) = viewModelScope.launch {
		try {
			val now = System.currentTimeMillis()
			val blockedUntil = preferences.blockedUntil()
			if (now < blockedUntil) {
				val seconds = ((blockedUntil - now + 999) / 1_000).coerceAtLeast(1)
				return@launch fail("Try again in $seconds seconds.")
			}
			val (vaultMatches, decoyMatches) = withContext(Dispatchers.Default) {
				gate.matches(input) to decoyGate.matches(input)
			}
			when {
				vaultMatches -> {
					preferences.clearFailedUnlocks()
					openVault()
				}
				decoyMatches -> {
					preferences.clearFailedUnlocks()
					lockVault()
				}
				else -> recordFailedUnlock()
			}
		} finally {
			input.fill(' ')
		}
	}

	fun changeVaultPin(current: CharArray, newPin: CharArray) = launchBusy {
		try {
			validatePin(newPin)?.let { return@launchBusy fail(it) }
			val valid = withContext(Dispatchers.Default) { gate.matches(current) }
			if (!valid) return@launchBusy fail("Current credential is incorrect.")
			if (withContext(Dispatchers.Default) { decoyGate.matches(newPin) }) {
				return@launchBusy fail("Vault and decoy PINs must be different.")
			}
			withContext(Dispatchers.Default) { gate.set(newPin) }
			preferences.setVaultUsesPin(true)
			_state.update { it.copy(vaultUsesPin = true, message = "Vault PIN changed.") }
		} finally {
			current.fill(' ')
			newPin.fill(' ')
		}
	}

	fun setDecoyPin(pin: CharArray) = launchBusy {
		try {
			validatePin(pin)?.let { return@launchBusy fail(it) }
			if (withContext(Dispatchers.Default) { gate.matches(pin) }) {
				return@launchBusy fail("Vault and decoy PINs must be different.")
			}
			withContext(Dispatchers.Default) { decoyGate.set(pin) }
			_state.update { it.copy(decoyPinSet = true, message = "Decoy PIN saved.") }
		} finally {
			pin.fill(' ')
		}
	}

	fun clearDecoyPin() = viewModelScope.launch {
		withContext(Dispatchers.IO) { decoyGate.clear() }
		_state.update { it.copy(decoyPinSet = false, message = "Decoy PIN removed.") }
	}

	private fun validatePin(pin: CharArray): String? = when {
		pin.size !in MIN_PIN_LENGTH..MAX_PIN_LENGTH -> "PIN must be 4 to 12 digits."
		pin.any { !it.isDigit() } -> "PIN can contain digits only."
		else -> null
	}

	private fun recordFailedUnlock() {
		val blocked = preferences.recordFailedUnlock(
			now = System.currentTimeMillis(),
			maximumAttempts = MAX_UNLOCK_ATTEMPTS,
			lockoutMillis = LOCKOUT_MILLIS,
		)
		if (blocked > 0) {
			fail("Too many attempts. Try again in 30 seconds.")
		} else {
			fail("Wrong credential.")
		}
	}

	// Vault and settings navigation

	private suspend fun openVault() {
		awaitingPicker = false
		val entries = guard { journal.read() }.orEmpty()
		val locked = guard { vault.read() }.orEmpty()
		_state.update { it.copy(screen = Screen.VAULT, entries = entries, lockedFiles = locked) }
		refreshCapabilitiesNow()
	}

	fun openSettings() {
		if (_state.value.screen == Screen.VAULT) _state.update { it.copy(screen = Screen.SETTINGS) }
	}

	fun closeSettings() {
		if (_state.value.screen == Screen.SETTINGS) _state.update { it.copy(screen = Screen.VAULT) }
	}

	fun lockVault() = _state.update {
		it.copy(screen = Screen.DECOY, entries = emptyList(), lockedFiles = emptyList())
	}

	fun setDecoy(decoy: DecoyType) = viewModelScope.launch {
		try {
			withContext(Dispatchers.IO) {
				preferences.setDecoy(decoy)
				launcherAliases.apply(decoy)
			}
			_state.update { it.copy(decoy = decoy, message = "Decoy changed.") }
		} catch (e: Exception) {
			fail(e.message ?: "Could not change the launcher decoy.")
		}
	}

	fun setEntryMethod(method: EntryMethod) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setEntryMethod(method) }
		_state.update { it.copy(entryMethod = method, message = "Entry method changed.") }
	}

	fun setHidingPreference(preference: HidingPreference) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setHidingPreference(preference) }
		_state.update { it.copy(hidingPreference = preference) }
		refreshCapabilitiesNow()
	}

	fun refreshCapabilities() = viewModelScope.launch { refreshCapabilitiesNow() }

	private suspend fun refreshCapabilitiesNow() {
		val preference = _state.value.hidingPreference
		val available = hider.availableMethods(
			checkRoot = preference == HidingPreference.AUTO || preference == HidingPreference.ROOT,
		)
		val method = hider.selectedMethod(preference, available)
		var recoveredEntries: List<HiddenEntry>? = null
		if (_state.value.entries.isEmpty() && method != null && method != recoveryCheckedFor) {
			recoveryCheckedFor = method
			if ((guard { hider.recoverOrphans(method) } ?: 0) > 0) recoveredEntries = guard { journal.read() }
		}
		_state.update {
			it.copy(
				method = method,
				availableMethods = available,
				entries = recoveredEntries ?: it.entries,
				canRequestAllFiles = HideMethod.PRIVATE_MOVE !in available,
			)
		}
	}

	fun expectExternalPicker() {
		awaitingPicker = true
	}

	fun onPickerResult() {
		awaitingPicker = false
	}

	fun onMovedToBackground() {
		if (awaitingPicker) {
			awaitingPicker = false
			return
		}
		lockVault()
	}

	// Folder and file operations

	fun hideFolder(treeUri: Uri) = launchBusy {
		val path = resolveDocumentPath(treeUri, isTree = true)
			?: return@launchBusy fail("Could not resolve that folder to a storage path.")
		val name = DocumentFile.fromTreeUri(getApplication(), treeUri)?.name ?: File(path).name
		val persisted = persist(treeUri)
		hide(FolderTarget(path, name, treeUri.takeIf { persisted }))
	}

	private suspend fun hide(target: FolderTarget) {
		when (val result = hider.hide(target, _state.value.hidingPreference)) {
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

	private fun askForAccess(needed: HideResult.NeedsAccess, retry: suspend () -> Unit) {
		pending = retry
		_state.update { it.copy(message = needed.reason, accessNeededFor = initialUri(needed.path)) }
	}

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

	private fun persist(uri: Uri): Boolean {
		val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
		val resolver = getApplication<Application>().contentResolver
		if (runCatching { resolver.takePersistableUriPermission(uri, flags) }.isFailure) return false
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

	private fun resolveDocumentPath(uri: Uri, isTree: Boolean): String? {
		if (uri.authority != EXTERNAL_STORAGE) return null
		val document = if (isTree) {
			DocumentFile.fromTreeUri(getApplication(), uri)
		} else {
			DocumentFile.fromSingleUri(getApplication(), uri)
		}
		val documentId = document?.uri?.lastPathSegment ?: uri.lastPathSegment ?: return null
		val parts = documentId.split(':', limit = 2)
		if (parts.firstOrNull() != "primary") return null
		val relative = parts.getOrElse(1) { "" }
		return paths.emulatedRoot + if (relative.isEmpty()) "" else "/$relative"
	}

	private companion object {
		const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
		const val MIN_PIN_LENGTH = 4
		const val MAX_PIN_LENGTH = 12
		const val MAX_UNLOCK_ATTEMPTS = 5
		const val LOCKOUT_MILLIS = 30_000L
	}
}
