package io.github.melastore.shelf.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.format.DateFormat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.AppPreferences
import io.github.melastore.shelf.data.AutoHideMode
import io.github.melastore.shelf.data.BundleResult
import io.github.melastore.shelf.data.CalendarEvent
import io.github.melastore.shelf.data.CalendarEventStore
import io.github.melastore.shelf.data.ContentCredential
import io.github.melastore.shelf.data.ContentKeys
import io.github.melastore.shelf.data.DecoyItem
import io.github.melastore.shelf.data.DecoyType
import io.github.melastore.shelf.data.DuressEvent
import io.github.melastore.shelf.data.DuressLog
import io.github.melastore.shelf.data.EntryMethod
import io.github.melastore.shelf.data.EphemeralMediaLoader
import io.github.melastore.shelf.data.FailedUnlock
import io.github.melastore.shelf.data.FailedUnlockLog
import io.github.melastore.shelf.data.FileBundler
import io.github.melastore.shelf.data.FolderTarget
import io.github.melastore.shelf.data.Habit
import io.github.melastore.shelf.data.HabitStore
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HiddenHealth
import io.github.melastore.shelf.data.HiddenHealthStatus
import io.github.melastore.shelf.data.HiddenNameStyle
import io.github.melastore.shelf.data.HideFailure
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HideResult
import io.github.melastore.shelf.data.HideWarning
import io.github.melastore.shelf.data.HidingPreference
import io.github.melastore.shelf.data.OpenFolders
import io.github.melastore.shelf.data.RecordsCorrupted
import io.github.melastore.shelf.data.RecoveryBundleCodec
import io.github.melastore.shelf.data.RecoveryNudge
import io.github.melastore.shelf.data.SafPaths
import io.github.melastore.shelf.data.SafRecoveryCandidate
import io.github.melastore.shelf.data.ShelfCore
import io.github.melastore.shelf.data.ThemeMode
import io.github.melastore.shelf.data.UriPaths
import io.github.melastore.shelf.data.mergeWarnings
import io.github.melastore.shelf.data.worksOnCalculatorKeypad
import io.github.melastore.shelf.security.BiometricAuth
import io.github.melastore.shelf.security.CredentialFault
import io.github.melastore.shelf.security.CredentialKind
import io.github.melastore.shelf.security.CredentialRules
import io.github.melastore.shelf.security.EmergencyCredentialStore
import io.github.melastore.shelf.security.KnockCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class Screen { DECOY, VAULT, SETTINGS }

/** Shared with the dialog that collects it, so the two cannot disagree on what is long enough. */
const val MIN_RECOVERY_PASSWORD = 12

internal const val MAX_PICKED_FILES = 500

/**
 * One row of the private space. [entry] is the journal record describing how to put the folder back,
 * and is null exactly when the folder is sitting in the open under its own name. [bundle] is a folder
 * Shelf made for picked files.
 */
data class VaultFolder(
	val path: String,
	val displayName: String,
	val entry: HiddenEntry?,
	val health: HiddenHealth? = null,
	val blocker: HideFailure? = null,
	val bundle: Boolean = false,
) {
	val hidden: Boolean get() = entry != null
}

/** Files picked and checked, waiting on a name for the folder they go into. */
data class NamingFiles(val count: Int, val suggested: String)

data class FolderProblem(val name: String, val reason: UiMessage, val needsAllFiles: Boolean = false,)

data class BlockedReport(val title: UiMessage, val problems: List<FolderProblem>,) {
	val needsAllFiles: Boolean get() = problems.any { it.needsAllFiles }
}

data class AppUiState(
	val ready: Boolean = false,
	val screen: Screen = Screen.DECOY,
	val credentialSet: Boolean = false,
	val credentialKind: CredentialKind = CredentialKind.PIN,
	val biometricEnabled: Boolean = false,
	val biometricAvailable: Boolean = false,
	val autoHideMode: AutoHideMode = AutoHideMode.SCREEN_OFF,
	val quickLockNotification: Boolean = false,
	/** A count and nothing else. It outlives the private space, so it must not name any folder. */
	val exposedFolders: Int = 0,
	val decoyPinSet: Boolean = false,
	val burnerPinSet: Boolean = false,
	val decoy: DecoyType = DecoyType.HABITS,
	val entryMethod: EntryMethod = EntryMethod.TITLE_HOLD,
	val hidingPreference: HidingPreference = HidingPreference.AUTO,
	val hiddenNameStyle: HiddenNameStyle = HiddenNameStyle.CAMOUFLAGE,
	/** Which first-run step to resume on. Meaningless once a credential exists. */
	val setupStep: Int = 0,
	val habits: List<Habit> = emptyList(),
	val calendarEvents: List<CalendarEvent> = emptyList(),
	val method: HideMethod? = null,
	val availableMethods: Set<HideMethod> = emptySet(),
	val canRequestAllFiles: Boolean = false,
	val fakeCrash: Boolean = false,
	val hideFromRecents: Boolean = false,
	val themeMode: ThemeMode = ThemeMode.SYSTEM,
	val flipToHide: Boolean = false,
	val usbPanic: Boolean = false,
	val deepProtection: Boolean = true,
	/** Minutes a folder may stay exposed before it is put back regardless. Zero is off. */
	val rehideMinutes: Int = 0,
	/** Set when an operation stopped to ask for access to a folder above the target. */
	val accessNeededFor: Uri? = null,
	/** Where the folder picker opens, so the first hide does not start at an empty Recents list. */
	val pickerStart: Uri? = null,
	/** Every folder the user has added, hidden or not, in the order they were added. */
	val folders: List<VaultFolder> = emptyList(),
	val safRecoveryCandidates: List<SafRecoveryCandidate> = emptyList(),
	/**
	 * Whether the private space may be captured, this session only. Not persisted on purpose: the
	 * setting exists so the list can be photographed deliberately, and a hole left open by someone
	 * who forgot about it is worth more to an attacker than not being asked twice.
	 */
	val allowScreenshots: Boolean = false,
	/** True when the second credential opened this space. Nothing here touches real storage. */
	val duress: Boolean = false,
	val decoyItems: List<DecoyItem> = emptyList(),
	/** Repeated on real unlocks until the owner explicitly dismisses it. */
	val duressAlert: UiMessage? = null,
	/** Wrong credentials since the last time the owner got in, on the same terms. */
	val failedAttemptAlert: UiMessage? = null,
	val blocked: BlockedReport? = null,
	val namingFiles: NamingFiles? = null,
	/** Hidden folders the last recovery file does not cover, enough to say so. */
	val recoveryNudge: Boolean = false,
	val shareTarget: Boolean = false,
	val busy: Boolean = false,
	val message: UiMessage? = null,
) {
	/** Anything still hidden means the bulk action worth offering is putting it all back. */
	val anyHidden: Boolean
		get() = if (duress) decoyItems.any { it.hidden } else folders.any { it.hidden }

	val hasFolders: Boolean get() = if (duress) decoyItems.isNotEmpty() else folders.isNotEmpty()
}

class ShelfViewModel(app: Application) : AndroidViewModel(app) {

	// Before anything below reaches for them: the shared instances are built lazily against this.
	init {
		ShelfCore.install(app)
	}

	private val habitStore = HabitStore(File(app.filesDir, "habits.json"))
	private val calendarStore = CalendarEventStore(File(app.filesDir, "calendar.json"))
	private val preferences = AppPreferences(app)
	private val auth = AuthCoordinator(app.filesDir, preferences)
	private val launcherAliases = LauncherAliasController(app)

	// Shared with the emergency-hide receiver rather than rebuilt here. The lock that serialises a
	// read-modify-write lives on the object holding the file, so a second one over the same path
	// would let a hide from the notification interleave with one from this screen.
	private val paths = ShelfCore.paths
	private val journal = ShelfCore.journal
	private val registry = ShelfCore.registry
	private val hider = ShelfCore.hider
	private val bundler = ShelfCore.bundler
	private val uriPaths = UriPaths(app, paths)
	private val folderCoordinator = FolderCoordinator(paths, journal, registry, hider, bundler)
	private val decoyVault = ShelfCore.decoyVault
	private val recoveryCoordinator = RecoveryCoordinator(journal, RecoveryBundleCodec(paths))
	private val duressLog = DuressLog(File(app.filesDir, "duress.json"))
	private val failedUnlockLog = FailedUnlockLog(File(app.filesDir, "attempts.json"))

	private val _state = MutableStateFlow(AppUiState())
	val state: StateFlow<AppUiState> = _state.asStateFlow()

	/**
	 * When the wait for an external picker expires. An unbounded flag would suspend automatic locking
	 * for good the first time a picker was opened and never came back, and leaving for the all-files
	 * permission screen then walking away is enough to do that.
	 */
	private var awaitingPickerUntil = 0L

	/** Until when a trip out is the share sheet. Cleared on the way back in, since it has no result. */
	private var sharingUntil = 0L
	private var pendingFiles: List<FileGroup>? = null

	/** When the app last went to the background, or 0 while a picker had it. */
	private var leftAt = 0L
	private var pending: (suspend () -> Unit)? = null

	// What undoes the steps taken before [pending] was asked for, when the grant is not given.
	private var refused: (suspend () -> Unit)? = null
	private val operationMutex = Mutex()
	private val authenticationMutex = Mutex()
	private var recoveryCheckedFor: HideMethod? = null
	private var backgroundLock: Job? = null
	private var autoHideRequested = false
	private var autoHides = 0

	// The count starts at zero and is filled in off the main thread. Until it lands, zero means
	// "not counted yet", and treating that as "nothing to hide" skipped the hide outright.
	private var exposureCounted = false

	init {
		viewModelScope.launch { runCatching { ShelfCore.resealOpenFolders() } }
		viewModelScope.launch {
			QuickLockSignal.requests.collect {
				QuickLockSignal.consume()
				onEmergencyHide()
			}
		}
		viewModelScope.launch {
			QuickLockSignal.completions.collect {
				autoHideRequested = false
				refreshExposure()
			}
		}
		refreshExposure()
		viewModelScope.launch {
			val credentialSet = auth.primaryIsSet()
			val settings = preferences.read(credentialSet)
			val biometricEnabled = withContext(Dispatchers.IO) {
				if (settings.biometricEnabled && !BiometricAuth.hasCredential(app)) {
					preferences.setBiometricEnabled(false)
					BiometricAuth.reset(app)
					false
				} else {
					settings.biometricEnabled
				}
			}
			// Not while setup is unfinished. Disabling the alias the wizard runs under ends its task
			// and drops the owner on the home screen halfway through, so the disguise goes on once
			// there is a credential behind it.
			if (credentialSet) {
				withContext(Dispatchers.IO) {
					launcherAliases.apply(settings.decoy)
					launcherAliases.applyShare(settings.decoy, settings.shareTarget)
				}
			}
			val habits = guard { habitStore.read() }.orEmpty()
			val events = guard { calendarStore.read() }.orEmpty()
			_state.update {
				it.copy(
					ready = true,
					credentialSet = credentialSet,
					credentialKind = settings.credentialKind,
					biometricEnabled = biometricEnabled,
					biometricAvailable = BiometricAuth.isAvailable(app),
					autoHideMode = settings.autoHideMode,
					quickLockNotification = settings.quickLockNotification,
					decoyPinSet = auth.decoyIsSet(),
					burnerPinSet = auth.burnerIsSet(),
					decoy = settings.decoy,
					entryMethod = settings.entryMethod,
					hidingPreference = settings.hidingPreference,
					hiddenNameStyle = settings.hiddenNameStyle,
					fakeCrash = settings.fakeCrash,
					hideFromRecents = settings.hideFromRecents,
					themeMode = settings.themeMode,
					flipToHide = settings.flipToHide,
					usbPanic = settings.usbPanic,
					deepProtection = settings.deepProtection,
					rehideMinutes = settings.rehideMinutes,
					shareTarget = settings.shareTarget,
					setupStep = if (credentialSet) 0 else preferences.setupStep(),
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

	fun setVaultCredential(kind: CredentialKind, credential: CharArray) = viewModelScope.launch {
		try {
			validate(kind, credential)?.let { return@launch fail(it) }
			withContext(Dispatchers.IO) { EmergencyCredentialStore.clear(getApplication()) }
			auth.setPrimary(credential, kind)
			ContentCredential.set(credential)
			withContext(Dispatchers.IO) { preferences.clearSetupStep() }
			_state.update { it.copy(credentialSet = true, credentialKind = kind, setupStep = 0) }
			keepEntryReachable(kind)
			applyShareEntry()
			openVault()
		} finally {
			credential.fill(' ')
		}
	}

	fun unlockVault(input: CharArray) = viewModelScope.launch {
		if (!authenticationMutex.tryLock()) {
			input.fill(' ')
			return@launch
		}
		try {
			val uptime = SystemClock.elapsedRealtime()
			val blockedUntil = withContext(Dispatchers.IO) { preferences.blockedUntil(uptime) }
			if (uptime < blockedUntil) {
				return@launch fail(uiMessage(R.string.unlock_retry, waitFor(blockedUntil - uptime)))
			}
			when (auth.match(input)) {
				CredentialMatch.PRIMARY -> {
					withContext(Dispatchers.IO) { preferences.clearFailedUnlocks() }
					ContentCredential.set(input)
					openVault()
				}

				CredentialMatch.DECOY -> {
					withContext(Dispatchers.IO) { preferences.clearFailedUnlocks() }
					openDecoyVault(System.currentTimeMillis())
				}

				CredentialMatch.BURNER -> {
					nukeAndWipe(announce = true)
				}

				CredentialMatch.NONE -> recordFailedUnlock()
			}
		} catch (e: Exception) {
			// A storage problem must never reach the user as a rejected credential.
			fail(
				e.message?.let { uiMessage(R.string.private_space_open_failed_detail, it) }
					?: uiMessage(R.string.private_space_open_failed),
			)
		} finally {
			input.fill(' ')
			authenticationMutex.unlock()
		}
	}

	/** [inputs] are every reading of one press, so a miss on all of them is a single miss. */
	fun tryStealthUnlock(inputs: List<CharArray>) = viewModelScope.launch {
		if (!authenticationMutex.tryLock()) {
			inputs.forEach { it.fill(' ') }
			return@launch
		}
		// A knock code can be typed as well as tapped, and a calculator swallows the leading zero of
		// the stored form, so the one-based spelling is tried too.
		val aliases = inputs.mapNotNull(KnockCode::fromOneBased)
		try {
			val uptime = SystemClock.elapsedRealtime()
			val blockedUntil = withContext(Dispatchers.IO) {
				maxOf(preferences.blockedUntil(uptime), preferences.keypadBlockedUntil(uptime))
			}
			if (uptime < blockedUntil) return@launch
			// Keep the spelling that matched, not the keys pressed: that is what folders are sealed
			// under.
			var matched = CredentialMatch.NONE
			var credential = inputs.first()
			for (candidate in inputs + aliases) {
				val result = auth.match(candidate)
				if (result != CredentialMatch.NONE) {
					matched = result
					credential = candidate
					break
				}
			}
			when (matched) {
				CredentialMatch.PRIMARY -> {
					withContext(Dispatchers.IO) { preferences.clearFailedUnlocks() }
					ContentCredential.set(credential)
					openVault()
				}

				CredentialMatch.DECOY -> {
					withContext(Dispatchers.IO) { preferences.clearFailedUnlocks() }
					openDecoyVault(System.currentTimeMillis())
				}

				// The keypad says nothing about a wrong code, so a wipe from there says nothing either.
				CredentialMatch.BURNER -> {
					nukeAndWipe(announce = false)
				}

				// Silent, like everything else here. Most of these are someone doing sums.
				CredentialMatch.NONE -> withContext(Dispatchers.IO) {
					preferences.recordKeypadMiss(uptime, KEYPAD_FREE_MISSES)
				}
			}
		} catch (_: Exception) {
		} finally {
			inputs.forEach { it.fill(' ') }
			aliases.forEach { it.fill(' ') }
			authenticationMutex.unlock()
		}
	}

	private suspend fun nukeAndWipe(announce: Boolean) = withContext(Dispatchers.IO) {
		ContentCredential.clear()
		EphemeralMediaLoader.clearCache()
		EmergencyCredentialStore.clear(getApplication())
		RehideTimer.sync(getApplication(), exposed = false)
		preferences.clearFailedUnlocks()
		preferences.clearRecoveryNudge()
		IncomingShare.clear()
		ShareOut.clear()
		preferences.setBiometricEnabled(false)
		BiometricAuth.reset(getApplication())
		auth.clearPrimary()
		auth.clearBurner()
		auth.clearDecoy()
		val app = getApplication<Application>()
		// A hide or restore still running would write its record back after the delete. Wait for it,
		// but not long: the prompt only answers once this is done.
		val locked = withTimeoutOrNull(WIPE_WAIT_MILLIS) { operationMutex.lock() } != null
		try {
			app.filesDir.listFiles()?.forEach { file ->
				if (file.name.contains("journal") || file.name.contains("gate") || file.name.contains("recovery") ||
					file.name.contains("vault") || file.name.contains("folders") || file.name.contains("duress") ||
					file.name.contains("attempts")
				) {
					file.delete()
				}
			}
		} finally {
			if (locked) operationMutex.unlock()
		}
		// With no credential at all the next frame is the setup wizard, which names the app and shows
		// a wipe just happened. A gate nobody knows keeps the disguise up and refuses every entry.
		val unknowable = randomCredential()
		try {
			auth.setPrimary(unknowable, _state.value.credentialKind)
		} finally {
			unknowable.fill(' ')
		}
		withContext(Dispatchers.Main) {
			// The prompt also opens from inside the second space, so everything a lock drops goes too.
			lockVault()
			_state.update {
				it.copy(
					burnerPinSet = false,
					decoyPinSet = false,
					biometricEnabled = false,
					exposedFolders = 0,
					message = if (announce) uiMessage(R.string.wrong_credential) else it.message,
				)
			}
		}
		// The journal is not the only record. Recovery markers in shared storage name the original
		// paths, and the persisted grants list every parent folder in system state. Its own job: the
		// root sweep walks all of storage, and neither the screen nor the next PIN should wait on it.
		viewModelScope.launch(Dispatchers.IO + NonCancellable) {
			runCatching { hider.dropRecoveryMarkers() }
			runCatching {
				val resolver = app.contentResolver
				for (grant in resolver.persistedUriPermissions) {
					val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
						(if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
					runCatching { resolver.releasePersistableUriPermission(grant.uri, flags) }
				}
			}
		}
	}

	private fun randomCredential(): CharArray {
		val random = SecureRandom()
		return CharArray(UNKNOWABLE_LENGTH) { UNKNOWABLE_ALPHABET[random.nextInt(UNKNOWABLE_ALPHABET.length)] }
	}

	/** Asked before the prompts too, so nobody types three credentials only to be told to unhide first. */
	fun credentialChangeAllowed(): Boolean {
		val blocked = _state.value.folders.any { it.hidden }
		if (blocked) fail(uiMessage(R.string.unhide_before_credential_change))
		return !blocked
	}

	/**
	 * Changing the credential re-keys every file header a hide would encrypt, so it is refused while
	 * anything is hidden. Changing only the kind counts: a pattern and a PIN that happen to be the
	 * same digits are the same secret, and a password never is.
	 */
	fun changeVaultCredential(kind: CredentialKind, current: CharArray, next: CharArray) = launchBusy {
		try {
			validate(kind, next)?.let { return@launchBusy fail(it) }
			if (_state.value.folders.any { it.hidden } || journal.read().isNotEmpty()) {
				return@launchBusy fail(uiMessage(R.string.unhide_before_credential_change))
			}
			val valid = auth.primaryMatches(current)
			if (!valid) return@launchBusy fail(uiMessage(R.string.current_credential_incorrect))
			if (auth.decoyMatches(next) || auth.burnerMatches(next)) {
				return@launchBusy fail(uiMessage(R.string.primary_decoy_pin_different))
			}
			val biometricWasEnabled = _state.value.biometricEnabled
			if (biometricWasEnabled) {
				withContext(Dispatchers.IO) {
					preferences.setBiometricEnabled(false)
					BiometricAuth.reset(getApplication())
				}
			}
			// A second credential in the old shape cannot be typed into the new prompt: a PIN has
			// digits a pattern has no dots for, and a password has characters neither accepts. Leaving
			// it would be a credential the owner believes in and can no longer use.
			val kindChanged = kind != _state.value.credentialKind
			val decoyDropped = kindChanged && _state.value.decoyPinSet
			if (decoyDropped) auth.clearDecoy()
			val burnerDropped = kindChanged && _state.value.burnerPinSet
			if (burnerDropped) auth.clearBurner()

			withContext(Dispatchers.IO) { EmergencyCredentialStore.clear(getApplication()) }
			auth.setPrimary(next, kind)
			ContentCredential.set(next)
			keepEntryReachable(kind)
			_state.update {
				it.copy(
					credentialKind = kind,
					decoyPinSet = it.decoyPinSet && !decoyDropped,
					burnerPinSet = it.burnerPinSet && !burnerDropped,
					biometricEnabled = if (biometricWasEnabled) false else it.biometricEnabled,
					message = uiMessage(
						when {
							decoyDropped || burnerDropped -> R.string.primary_pin_changed_second_removed
							biometricWasEnabled -> R.string.primary_pin_changed_biometric_disabled
							else -> R.string.primary_pin_changed
						},
					),
				)
			}
		} finally {
			current.fill(' ')
			next.fill(' ')
		}
	}

	fun setDecoyCredential(current: CharArray, credential: CharArray) = launchBusy {
		try {
			validate(_state.value.credentialKind, credential)?.let { return@launchBusy fail(it) }
			if (!auth.primaryMatches(current)) {
				return@launchBusy fail(uiMessage(R.string.primary_credential_incorrect))
			}
			if (auth.primaryMatches(credential) || auth.burnerMatches(credential)) {
				return@launchBusy fail(uiMessage(R.string.primary_decoy_pin_different))
			}
			auth.setDecoy(credential)
			_state.update { it.copy(decoyPinSet = true, message = uiMessage(R.string.decoy_pin_saved)) }
		} finally {
			current.fill(' ')
			credential.fill(' ')
		}
	}

	fun clearDecoyPin(current: CharArray) = launchBusy {
		try {
			if (!auth.primaryMatches(current)) {
				return@launchBusy fail(uiMessage(R.string.primary_credential_incorrect))
			}
			auth.clearDecoy()
			_state.update { it.copy(decoyPinSet = false, message = uiMessage(R.string.decoy_pin_removed)) }
		} finally {
			current.fill(' ')
		}
	}

	fun setBurnerPin(current: CharArray, credential: CharArray) = launchBusy {
		try {
			validate(_state.value.credentialKind, credential)?.let { return@launchBusy fail(it) }
			if (!auth.primaryMatches(current)) {
				return@launchBusy fail(uiMessage(R.string.primary_credential_incorrect))
			}
			if (auth.primaryMatches(credential) || auth.decoyMatches(credential)) {
				return@launchBusy fail(uiMessage(R.string.primary_decoy_pin_different))
			}
			auth.setBurner(credential)
			_state.update { it.copy(burnerPinSet = true, message = uiMessage(R.string.burner_pin_saved)) }
		} finally {
			current.fill(' ')
			credential.fill(' ')
		}
	}

	fun clearBurnerPin(current: CharArray) = launchBusy {
		try {
			if (!auth.primaryMatches(current)) {
				return@launchBusy fail(uiMessage(R.string.primary_credential_incorrect))
			}
			auth.clearBurner()
			_state.update { it.copy(burnerPinSet = false, message = uiMessage(R.string.burner_pin_removed)) }
		} finally {
			current.fill(' ')
		}
	}

	fun setDeepProtection(enabled: Boolean) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setDeepProtection(enabled) }
		_state.update {
			it.copy(deepProtection = enabled, message = uiMessage(R.string.deep_protection_updated))
		}
	}

	fun setUsbPanic(enabled: Boolean) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setUsbPanic(enabled) }
		_state.update { it.copy(usbPanic = enabled) }
	}

	/**
	 * [credential] is already authenticated and decrypted by the per-use Keystore operation in
	 * [BiometricAuth]. Installing it gives the session the same folder capabilities a typed entry
	 * would; in-process code is not a second authentication boundary.
	 */
	fun unlockWithBiometric(credential: CharArray, onRejected: () -> Unit) = viewModelScope.launch {
		// The same lock the typed path takes. Two ways in that can run at once are two ways in that
		// can disagree about whether the lockout has expired.
		if (!authenticationMutex.tryLock()) {
			credential.fill(' ')
			return@launch
		}
		try {
			val uptime = SystemClock.elapsedRealtime()
			if (uptime < withContext(Dispatchers.IO) { preferences.blockedUntil(uptime) }) {
				fail(uiMessage(R.string.too_many_attempts_use_pin))
				onRejected()
				return@launch
			}
			withContext(Dispatchers.IO) { preferences.clearFailedUnlocks() }
			ContentCredential.set(credential)
			openVault()
		} finally {
			credential.fill(' ')
			authenticationMutex.unlock()
		}
	}

	/** The enrolment behind the key changed, so the key is gone and the prompt is the way in. */
	fun onBiometricEnrolmentChanged() = viewModelScope.launch {
		if (!withContext(Dispatchers.IO) { preferences.biometricEnabled() }) return@launch
		disableBiometric(R.string.biometric_disabled_enrolment_changed)
	}

	fun onBiometricCredentialMissing() = viewModelScope.launch {
		disableBiometric(R.string.biometric_enable_again)
	}

	/** The caller owns this copy and must hand it straight to [BiometricAuth]. */
	fun biometricEnrollmentCredential(): CharArray? = ContentCredential.copy()

	fun setBiometricEnabled(enabled: Boolean) = viewModelScope.launch {
		val available = !enabled || withContext(Dispatchers.IO) {
			BiometricAuth.hasCredential(getApplication())
		}
		if (!available) return@launch fail(uiMessage(R.string.biometric_enable_failed))
		withContext(Dispatchers.IO) {
			preferences.setBiometricEnabled(enabled)
			if (!enabled) BiometricAuth.reset(getApplication())
		}
		_state.update {
			it.copy(
				biometricEnabled = enabled,
				biometricAvailable = BiometricAuth.isAvailable(getApplication()),
				message = uiMessage(
					if (enabled) R.string.biometric_enabled else R.string.biometric_disabled,
				),
			)
		}
	}

	private suspend fun disableBiometric(message: Int) {
		withContext(Dispatchers.IO) {
			preferences.setBiometricEnabled(false)
			BiometricAuth.reset(getApplication())
		}
		_state.update { it.copy(biometricEnabled = false, message = uiMessage(message)) }
	}

	fun setAutoHideMode(mode: AutoHideMode) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setAutoHideMode(mode) }
		_state.update { it.copy(autoHideMode = mode, message = uiMessage(R.string.auto_hide_updated)) }
	}

	fun setRehideMinutes(minutes: Int) = viewModelScope.launch {
		withContext(Dispatchers.IO) {
			preferences.setRehideMinutes(minutes)
			RehideTimer.sync(getApplication(), _state.value.exposedFolders > 0)
		}
		_state.update { it.copy(rehideMinutes = minutes, message = uiMessage(R.string.auto_hide_updated)) }
	}

	fun setQuickLockNotification(enabled: Boolean) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setQuickLockNotification(enabled) }
		_state.update { it.copy(quickLockNotification = enabled) }
	}

	/** Nothing is written to disk. [lockVault] puts the protection back by dropping this state. */
	fun setAllowScreenshots(allowed: Boolean) = _state.update {
		it.copy(
			allowScreenshots = allowed,
			message = if (allowed) uiMessage(R.string.screenshots_allowed_notice) else null,
		)
	}

	fun refreshBiometricAvailability() = _state.update {
		it.copy(biometricAvailable = BiometricAuth.isAvailable(getApplication()))
	}

	private fun validate(kind: CredentialKind, credential: CharArray): UiMessage? =
		when (CredentialRules.validate(kind, credential)) {
			null -> null

			CredentialFault.PIN_NOT_DIGITS -> uiMessage(R.string.pin_digits_only)

			CredentialFault.PATTERN_TOO_SHORT -> uiMessage(R.string.pattern_too_short)

			CredentialFault.KNOCK_TOO_SHORT -> uiMessage(R.string.knock_too_short)

			CredentialFault.PASSWORD_UNSUPPORTED -> uiMessage(R.string.password_unsupported)

			CredentialFault.TOO_SHORT, CredentialFault.TOO_LONG -> when (kind) {
				CredentialKind.PIN -> uiMessage(R.string.pin_length_error)
				CredentialKind.PATTERN -> uiMessage(R.string.pattern_too_short)
				CredentialKind.KNOCK -> uiMessage(R.string.knock_too_short)
				CredentialKind.PASSWORD -> uiMessage(R.string.password_length_error)
			}
		}

	private suspend fun recordFailedUnlock() {
		val now = SystemClock.elapsedRealtime()
		val blocked = withContext(Dispatchers.IO) {
			preferences.recordFailedUnlock(now, MAX_UNLOCK_ATTEMPTS)
		}
		// Recorded whether or not it triggered a lockout. What the owner needs to see is that someone
		// tried, and a careful guesser stops before the fifth attempt.
		runCatching { failedUnlockLog.record(System.currentTimeMillis()) }
		if (blocked > 0) {
			fail(uiMessage(R.string.unlock_retry, waitFor(blocked - now)))
		} else {
			fail(uiMessage(R.string.wrong_credential))
		}
	}

	/** A wait that can run to an hour, said in whichever unit gives a number a person would use. */
	private fun waitFor(remaining: Long): UiMessage {
		val seconds = ((remaining + 999) / 1_000).coerceAtLeast(1)
		return if (seconds < 60) {
			uiPlural(R.plurals.wait_seconds, seconds.toInt(), seconds.toInt())
		} else {
			val minutes = ((seconds + 59) / 60).toInt()
			uiPlural(R.plurals.wait_minutes, minutes, minutes)
		}
	}

	// Vault and settings navigation

	/**
	 * Opens the real space, whatever the folder list has to say.
	 *
	 * The credential is already proven by the time this runs, so nothing after that gets to decide
	 * otherwise. Reading the list touches storage and can fail, and adopting old records into it
	 * writes as well; letting either escape used to close the prompt without changing screens, which
	 * looks exactly like a wrong credential.
	 */
	private suspend fun openVault() {
		awaitingPickerUntil = 0L
		autoHideRequested = false
		val loaded = runCatching { loadFolders() }
		loaded.getOrNull()?.let { syncEmergencyCredential(it) }
		val alert = runCatching { duressLog.read() }.getOrNull().orEmpty()
			.takeIf { it.isNotEmpty() }?.let(::duressSummary)
		val attempts = runCatching { failedUnlockLog.read() }.getOrNull().orEmpty()
			.takeIf { it.isNotEmpty() }?.let(::attemptSummary)
		val nudge = recoveryNudgeDue()
		_state.update {
			it.copy(
				screen = Screen.VAULT,
				duress = false,
				recoveryNudge = nudge,
				folders = loaded.getOrDefault(emptyList()),
				exposedFolders = loaded.getOrNull()?.count { folder -> !folder.hidden } ?: it.exposedFolders,
				decoyItems = emptyList(),
				duressAlert = alert,
				failedAttemptAlert = attempts,
				blocked = null,
				namingFiles = null,
				message = loaded.exceptionOrNull()?.let(::listFailure) ?: it.message,
			)
		}
		runCatching { refreshCapabilitiesNow() }
		prepareContentKeys(loaded.getOrNull().orEmpty())
		offerIncomingShare()
	}

	/** Seconds of CPU per unlock, so only when a hide or restore is going to need the keys. */
	private fun prepareContentKeys(folders: List<VaultFolder>) = viewModelScope.launch(Dispatchers.IO) {
		if (preferences.deepProtection() || folders.any { it.entry?.deep == true }) {
			ContentKeys.prepare(getApplication())
		}
	}

	private fun listFailure(cause: Throwable): UiMessage = when (cause) {
		is RecordsCorrupted -> recoveryMessage(cause)

		else -> cause.message?.let { uiMessage(R.string.folder_list_read_failed_detail, it) }
			?: uiMessage(R.string.folder_list_read_failed)
	}

	/**
	 * The tracked list joined to the journal. A folder is hidden exactly when a record describes it,
	 * and records Shelf rebuilt on its own (after a reinstall, say) are adopted into the list.
	 */
	private suspend fun loadFolders(): List<VaultFolder> {
		val health = _state.value.folders.associate { it.path to it.health }
		return folderCoordinator.load(health, _state.value.hidingPreference)
	}

	/**
	 * Opens a space that behaves like the real one and holds nothing that matters. The visit is
	 * logged, so the owner learns on their next real unlock that the second credential was used.
	 */
	private suspend fun openDecoyVault(now: Long) {
		awaitingPickerUntil = 0L
		autoHideRequested = false
		// No storage errors in the decoy space: a warning mentioning a duress log would give the
		// arrangement away to the person the second credential is meant for.
		runCatching { duressLog.record(now) }
		val items = runCatching { decoyVault.read() }.getOrElse { decoyVault.fallback() }
		_state.update {
			it.copy(
				screen = Screen.VAULT,
				duress = true,
				folders = emptyList(),
				decoyItems = items,
				duressAlert = null,
				failedAttemptAlert = null,
				// The label the real space shows. A fixed one is a tell on any phone set up otherwise.
				method = folderCoordinator.selectedMethod(it.hidingPreference, it.availableMethods)
					?: HideMethod.DOT_RENAME,
				safRecoveryCandidates = emptyList(),
				namingFiles = null,
			)
		}
		offerIncomingShare()
	}

	/**
	 * The decoy list is the owner's to write.
	 *
	 * As seeded it is the same four names on every install of an app anyone can read the source of,
	 * and a decoy someone recognises is not a decoy. Picking folders from inside the decoy space is
	 * how it becomes this phone's list. Nothing here touches storage: a row is a name and a flag, and
	 * no grant is kept, so the folder picked is left exactly as it was.
	 */
	private suspend fun addDecoyFolder(treeUri: Uri) {
		val path = uriPaths.treePath(treeUri)
			?: return fail(uiMessage(R.string.folder_path_unresolved))
		// Only the check that needs nothing tracked. The overlap check names a real folder.
		reservedFolder(path)?.let { return fail(it) }
		val name = DocumentFile.fromTreeUri(getApplication(), treeUri)?.name ?: File(path).name
		runCatching { decoyVault.add(name) }
		refreshDecoyItems(uiMessage(R.string.folder_hidden_named, name))
	}

	fun removeDecoyItem(item: DecoyItem) = launchBusy {
		runCatching { decoyVault.remove(item.id) }
		refreshDecoyItems(uiMessage(R.string.folder_record_removed, item.name))
	}

	/**
	 * Files picked here become rows and nothing more, as folders do. The checks are the real space's,
	 * so a pick it would refuse is refused here in the same words.
	 */
	private suspend fun addDecoyFiles(groups: List<FileGroup>) {
		groups.firstNotNullOfOrNull { reservedFolder(bundler.plannedPath(it.parent, it.folder)) }?.let { return fail(it) }
		groups.forEach { runCatching { decoyVault.add(it.label, files = true) } }
		val count = groups.sumOf { it.files.size }
		refreshDecoyItems(uiPlural(R.plurals.files_hidden, count, count))
	}

	// Unhiding real files puts them back and drops the row, so a decoy row of files goes the same way.
	fun toggleDecoyItem(item: DecoyItem) = launchBusy {
		if (item.files && item.hidden) {
			runCatching { decoyVault.remove(item.id) }
			return@launchBusy refreshDecoyItems(uiMessage(R.string.files_put_back_named, item.name))
		}
		val hidden = !item.hidden
		runCatching { decoyVault.setHidden(item.id, hidden) }
		refreshDecoyItems(
			uiMessage(if (hidden) R.string.folder_hidden_named else R.string.folder_restored_named, item.name),
		)
	}

	private suspend fun setAllDecoyItemsHidden(hidden: Boolean) {
		runCatching { decoyVault.setAllHidden(hidden) }
		val count = _state.value.decoyItems.count { it.hidden != hidden }
		refreshDecoyItems(
			uiMessage(
				if (hidden) R.string.bulk_hidden else R.string.bulk_restored,
				folderCount(count),
			),
		)
	}

	private suspend fun refreshDecoyItems(message: UiMessage?) {
		val items = runCatching { decoyVault.read() }.getOrDefault(_state.value.decoyItems)
		_state.update { it.copy(decoyItems = items, message = message ?: it.message) }
	}

	fun seedDecoyVault() = viewModelScope.launch {
		runCatching { decoyVault.seedPresets() }
		refreshDecoyItems(uiMessage(R.string.decoy_seeded))
	}

	fun dismissDuressAlert() = viewModelScope.launch {
		val cleared = guard {
			duressLog.clear()
			true
		}
		if (cleared == true) _state.update { it.copy(duressAlert = null) }
	}

	fun dismissFailedAttempts() = viewModelScope.launch {
		val cleared = guard {
			failedUnlockLog.clear()
			true
		}
		if (cleared == true) _state.update { it.copy(failedAttemptAlert = null) }
	}

	private fun duressSummary(events: List<DuressEvent>): UiMessage {
		val formatted = whenItHappened(events.maxOf { it.at })
		return if (events.size == 1) {
			uiMessage(R.string.duress_used_once, formatted)
		} else {
			uiMessage(R.string.duress_used_multiple, events.size, formatted)
		}
	}

	private fun attemptSummary(attempts: List<FailedUnlock>): UiMessage {
		val formatted = whenItHappened(attempts.maxOf { it.at })
		return if (attempts.size == 1) {
			uiMessage(R.string.failed_attempt_once, formatted)
		} else {
			uiMessage(R.string.failed_attempts_multiple, attempts.size, formatted)
		}
	}

	private fun whenItHappened(at: Long): String = DateTimeFormatter.ofPattern(
		DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMHHmm"),
		Locale.getDefault(),
	)
		.withZone(ZoneId.systemDefault())
		.format(Instant.ofEpochMilli(at))

	fun openSettings() {
		val current = _state.value
		if (current.screen == Screen.VAULT && !current.duress) {
			_state.update { it.copy(screen = Screen.SETTINGS) }
		}
	}

	fun closeSettings() {
		if (_state.value.screen == Screen.SETTINGS) _state.update { it.copy(screen = Screen.VAULT) }
		offerIncomingShare()
	}

	/**
	 * Everything derived from the private space goes with it, not just the list. Health details and
	 * recovery candidates are real folder paths, and [pending] is an operation waiting on a grant
	 * that would otherwise run itself the moment whoever opened the app next, second credential
	 * included, answered a folder picker they never asked for.
	 */
	fun lockVault() {
		ContentCredential.clear()
		EphemeralMediaLoader.clearCache()
		ShareOut.clear()
		backgroundLock?.cancel()
		backgroundLock = null
		awaitingPickerUntil = 0L
		sharingUntil = 0L
		pending = null
		refused = null
		pendingFiles = null
		recoveryCheckedFor = null
		autoHideRequested = false
		_state.update {
			it.copy(
				screen = Screen.DECOY,
				allowScreenshots = false,
				duress = false,
				folders = emptyList(),
				decoyItems = emptyList(),
				duressAlert = null,
				failedAttemptAlert = null,
				blocked = null,
				namingFiles = null,
				recoveryNudge = false,
				safRecoveryCandidates = emptyList(),
				accessNeededFor = null,
				method = null,
			)
		}
	}

	/**
	 * The visible half of the panic button. The hiding belongs to the receiver that handled the tap,
	 * since the notification usually outlives this view model; all that is left here is getting the
	 * private space off the display.
	 */
	private fun onEmergencyHide() {
		autoHideRequested = false
		lockVault()
	}

	/**
	 * How many folders are sitting in the open, which decides whether the emergency-hide notification
	 * is worth showing. A count and nothing else, because it survives locking and no path, name or
	 * record may.
	 */
	fun refreshExposure() = viewModelScope.launch {
		val exposed = runCatching { ShelfCore.exposedFolders().size }.getOrNull() ?: return@launch
		exposureCounted = true
		_state.update { it.copy(exposedFolders = exposed) }
	}

	/** Records the disguise. [applyPendingDisguise] swaps the launcher entry later. */
	fun setDecoy(decoy: DecoyType) = viewModelScope.launch {
		try {
			withContext(Dispatchers.IO) { preferences.setDecoy(decoy) }
			_state.update { it.copy(decoy = decoy, message = uiMessage(R.string.disguise_changed)) }
			applyShareEntry()
		} catch (e: Exception) {
			fail(
				e.message?.let { uiMessage(R.string.disguise_change_failed_detail, it) }
					?: uiMessage(R.string.disguise_change_failed),
			)
		}
	}

	/** The setup form of [setDecoy]: same choice, without a snackbar over a wizard step. */
	fun chooseDecoy(decoy: DecoyType) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setDecoy(decoy) }
		_state.update { it.copy(decoy = decoy) }
	}

	fun setSetupStep(step: Int) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setSetupStep(step) }
		_state.update { it.copy(setupStep = step) }
	}

	fun setFakeCrash(enabled: Boolean) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setFakeCrash(enabled) }
		_state.update {
			it.copy(
				fakeCrash = enabled,
				message = uiMessage(if (enabled) R.string.fake_crash_on else R.string.fake_crash_off),
			)
		}
	}

	fun setHideFromRecents(enabled: Boolean) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setHideFromRecents(enabled) }
		_state.update {
			it.copy(
				hideFromRecents = enabled,
				message = uiMessage(if (enabled) R.string.hide_recents_on else R.string.hide_recents_off),
			)
		}
	}

	fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setThemeMode(mode) }
		_state.update { it.copy(themeMode = mode) }
	}

	fun setShareTarget(enabled: Boolean) = viewModelScope.launch {
		preferences.setShareTarget(enabled)
		_state.update { it.copy(shareTarget = enabled) }
		applyShareEntry()
	}

	// Unlike the launcher entry, the share entry can change at once: nothing runs under it.
	private suspend fun applyShareEntry() {
		val current = _state.value
		if (!current.credentialSet) return
		runCatching { withContext(Dispatchers.IO) { launcherAliases.applyShare(current.decoy, current.shareTarget) } }
	}

	fun setFlipToHide(enabled: Boolean) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setFlipToHide(enabled) }
		_state.update { it.copy(flipToHide = enabled) }
	}

	fun setEntryMethod(method: EntryMethod) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setEntryMethod(method) }
		_state.update { it.copy(entryMethod = method, message = uiMessage(R.string.entry_gesture_changed)) }
	}

	/** The setup form of [setEntryMethod]: no snackbar over the wizard's Continue button. */
	fun chooseEntryMethod(method: EntryMethod) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setEntryMethod(method) }
		_state.update { it.copy(entryMethod = method) }
	}

	/**
	 * Puts the entrance back within reach of the credential just set. The keypad takes the title
	 * gesture and the corner taps with it, so a credential that cannot be typed there leaves no way
	 * in. Here rather than in the screens because a lockout has no way back.
	 */
	private suspend fun keepEntryReachable(kind: CredentialKind) {
		if (_state.value.entryMethod != EntryMethod.DIRECT_KEYPAD) return
		if (kind.worksOnCalculatorKeypad()) return
		withContext(Dispatchers.IO) { preferences.setEntryMethod(EntryMethod.DOUBLE_TAP_TITLE) }
		_state.update { it.copy(entryMethod = EntryMethod.DOUBLE_TAP_TITLE) }
	}

	fun setHidingPreference(preference: HidingPreference) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setHidingPreference(preference) }
		_state.update { it.copy(hidingPreference = preference) }
		refreshCapabilitiesNow()
	}

	/**
	 * Changes the name hidden folders wear and renames the ones already hidden. A duress session
	 * remembers the setting and leaves storage alone.
	 */
	fun setHiddenNameStyle(style: HiddenNameStyle) = launchBusy {
		withContext(Dispatchers.IO) { preferences.setHiddenNameStyle(style) }
		_state.update { it.copy(hiddenNameStyle = style) }
		if (_state.value.duress) return@launchBusy
		renameHiddenFolders()
	}

	private suspend fun renameHiddenFolders() {
		val targets = guard { folderCoordinator.hiddenEntries() }.orEmpty()
		var renamed = 0
		val failures = mutableListOf<FolderProblem>()
		for (entry in targets) {
			val result = oneOf(entry.displayName, failures) { folderCoordinator.remask(entry) } ?: continue
			when (result) {
				// Already in the chosen style, so nothing to report.
				is HideResult.Ok -> if (result.entry.hiddenPath != entry.hiddenPath) renamed++

				is HideResult.Failed -> failures += FolderProblem(
					entry.displayName,
					result.failure.toUiMessage(),
					result.failure.needsAllFiles(),
				)

				is HideResult.NeedsAccess -> failures += FolderProblem(
					entry.displayName,
					accessNeeded(result),
					needsAllFiles = false,
				)
			}
		}
		if (failures.isNotEmpty()) {
			reportBlocked(renameBlockedTitle(failures.size), failures)
		}
		if (renamed > 0) reloadVault(uiMessage(R.string.hidden_names_changed, folderCount(renamed)))
	}

	/** The name a folder had before it was hidden, read from inside it. */
	suspend fun recordedFolderName(candidate: SafRecoveryCandidate): String? =
		guard { folderCoordinator.recordedName(candidate.hiddenPath) }

	fun refreshCapabilities() = viewModelScope.launch { refreshCapabilitiesNow() }

	private suspend fun refreshCapabilitiesNow() {
		val preference = _state.value.hidingPreference
		val available = folderCoordinator.availableMethods(
			checkRoot = preference == HidingPreference.AUTO || preference == HidingPreference.ROOT,
		)
		val method = folderCoordinator.selectedMethod(preference, available)
		var recovered: List<VaultFolder>? = null
		// Only in the real space: an all-files marker is sealed under the credential, and a scan from
		// anywhere else would mark the method checked having read none of them.
		val canRecover = ContentCredential.isAvailable() && !_state.value.duress
		if (canRecover && _state.value.folders.isEmpty() && method != null && method != recoveryCheckedFor) {
			recoveryCheckedFor = method
			if ((guard { folderCoordinator.recoverOrphans(method) } ?: 0) > 0) recovered = guard { loadFolders() }
		}
		val currentFolders = _state.value.folders
		val folders = if (recovered != null) {
			recovered
		} else if (currentFolders.isNotEmpty()) {
			guard { loadFolders() } ?: currentFolders
		} else {
			currentFolders
		}
		_state.update {
			it.copy(
				method = method,
				availableMethods = available,
				folders = folders,
				canRequestAllFiles = HideMethod.PRIVATE_MOVE !in available,
				pickerStart = it.pickerStart ?: folderPickerStart(),
			)
		}
	}

	/**
	 * The primary volume root. Without it the picker opens on Recents, which lists no folders, so a
	 * first-time user gets an empty screen at the moment they are trying to hide something.
	 */
	private fun folderPickerStart(): Uri? = runCatching {
		DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:")
	}.getOrNull()

	/** The files picker opened in the folder [path] sits in, so adding to it starts where it can work. */
	fun filesPickerStart(path: String?): Uri? = path
		?.let { SafPaths.documentId(paths.emulatedRoot, SafPaths.parentOf(it)) }
		?.let { runCatching { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, it) }.getOrNull() }
		?: _state.value.pickerStart

	fun expectExternalPicker() {
		awaitingPickerUntil = SystemClock.elapsedRealtime() + PICKER_GRACE_MILLIS
	}

	fun onPickerResult() {
		awaitingPickerUntil = 0L
	}

	fun expectShareSheet() {
		sharingUntil = SystemClock.elapsedRealtime() + PICKER_GRACE_MILLIS
	}

	fun onMovedToBackground(screenOff: Boolean = false) {
		val awaitingPicker = SystemClock.elapsedRealtime() < awaitingPickerUntil
		awaitingPickerUntil = 0L
		// Unlike a picker this is a real trip out, so the away lock still counts it. Only an immediate
		// hide waits, or the app shared to finds the copies gone before it reads them.
		val sharing = SystemClock.elapsedRealtime() < sharingUntil
		sharingUntil = 0L
		backgroundLock?.cancel()
		// Walked away from, a share is abandoned, not waiting for whoever unlocks next. Not on lock:
		// the away lock runs as a share arrives at a space left open, and would drop it.
		if (!awaitingPicker) IncomingShare.clear()
		leftAt = if (awaitingPicker) 0L else SystemClock.elapsedRealtime()
		if (!awaitingPicker) applyPendingDisguise()
		if (screenOff && _state.value.autoHideMode != AutoHideMode.NEVER) {
			autoHideAndClose()
			return
		}
		if (_state.value.autoHideMode != AutoHideMode.IMMEDIATE) return
		// A picker briefly interrupts an immediate hide, or no folder could ever be picked. If it is
		// abandoned, the grace period still ends by closing and hiding everything.
		if (awaitingPicker || sharing) {
			backgroundLock = viewModelScope.launch {
				delay(PICKER_GRACE_MILLIS)
				autoHideAndClose()
			}
		} else {
			autoHideAndClose()
		}
	}

	/**
	 * Puts the chosen launcher entry in place while there is nothing on screen to lose.
	 *
	 * Disabling the alias an activity runs under ends its task, so doing this the moment the disguise
	 * is picked drops the owner on the home screen, during setup and before there is a credential.
	 * Setting a component to the state it already has is free, so this costs nothing on every launch
	 * after the one that changed it.
	 */
	private fun applyPendingDisguise() {
		if (!_state.value.credentialSet) return
		val decoy = _state.value.decoy
		viewModelScope.launch {
			runCatching {
				withContext(Dispatchers.IO) {
					launcherAliases.apply(decoy)
					launcherAliases.applyShare(decoy, _state.value.shareTarget)
				}
			}
		}
	}

	/**
	 * Under screen-off and never, leaving keeps the space open, which is fine for a quick switch to
	 * the gallery. Left longer than that, it comes back locked, or anyone picking up the unlocked
	 * phone walks past the disguise from Recents. Folders stay as they are, only the screen locks.
	 */
	fun onMovedToForeground() {
		backgroundLock?.cancel()
		backgroundLock = null
		autoHideRequested = false
		sharingUntil = 0L
		val away = leftAt
		leftAt = 0L
		val open = _state.value.screen != Screen.DECOY
		if (open && away != 0L && SystemClock.elapsedRealtime() - away >= AWAY_LOCK_MILLIS) lockVault()
	}

	/** Screen-off covers both non-never choices. Immediate has usually already fired from onStop. */
	fun onScreenTurnedOff() {
		if (_state.value.autoHideMode != AutoHideMode.NEVER) autoHideAndClose()
	}

	fun onScreenTurnedOn() = Unit

	/**
	 * Closes the private space and puts exposed folders back out of sight.
	 * The vault UI is locked immediately so returning to the app never shows private contents,
	 * while the emergency credential store ensures background workers can complete re-hiding on storage.
	 */
	fun autoHideAndClose() {
		val nothingExposed = exposureCounted && _state.value.exposedFolders == 0
		if ((_state.value.screen == Screen.DECOY && nothingExposed) || autoHideRequested) return
		if (!nothingExposed) {
			val credential = ContentCredential.copy()
			if (credential != null) {
				EmergencyCredentialStore.arm(getApplication(), credential)
				credential.fill(' ')
			}
		}
		lockVault()
		autoHideRequested = true
		autoHides++
		QuickLockNotification.requestHide(getApplication())
	}

	// Folder operations

	fun hideFolder(treeUri: Uri) = launchBusy {
		if (_state.value.duress) return@launchBusy addDecoyFolder(treeUri)
		val path = uriPaths.treePath(treeUri)
			?: return@launchBusy fail(uiMessage(R.string.folder_path_unresolved))
		unsuitable(path)?.let { return@launchBusy fail(it) }
		val name = DocumentFile.fromTreeUri(getApplication(), treeUri)?.name ?: File(path).name
		val persisted = persist(treeUri)
		// Tracked before the hide, so a folder that still needs a grant stays on the list to retry
		// from instead of something the user has to find in the picker twice.
		guard { registry.put(path, name) }
		hide(FolderTarget(path, name, treeUri.takeIf { persisted }))
	}

	/**
	 * Hides picked files by moving them into a folder made next to them and hiding that folder. Each
	 * directory picked from gets its own folder and its own row. The pick is checked here and the
	 * folder is named first, so nothing moves before the owner has said what to call it.
	 */
	fun hideFiles(uris: List<Uri>) = launchBusy {
		if (!_state.value.duress && !ContentCredential.isAvailable()) {
			return@launchBusy fail(HideFailure.PrimaryPinSessionRequired.toUiMessage())
		}
		val groups = pickedFiles(uris) ?: return@launchBusy
		pendingFiles = groups
		val count = groups.sumOf { it.files.size }
		_state.update { it.copy(namingFiles = NamingFiles(count, bundler.defaultName())) }
	}

	/**
	 * Files shared to Shelf from another app. The paths were read while the share's grant was still
	 * held, so all that is left is the same naming step a pick goes through. Offered only once the
	 * space is open; whoever opened it, the decoy included, gets the same dialog.
	 */
	fun offerIncomingShare() {
		val current = _state.value
		if (current.screen != Screen.VAULT || current.busy || current.namingFiles != null) return
		val batch = IncomingShare.take(SystemClock.elapsedRealtime()) ?: return
		launchBusy { hideSharedFiles(batch) }
	}

	private suspend fun hideSharedFiles(batch: IncomingShare.Batch) {
		if (!_state.value.duress && !ContentCredential.isAvailable()) {
			return fail(HideFailure.PrimaryPinSessionRequired.toUiMessage())
		}
		val files = batch.paths.distinct()
		if (batch.tooMany || files.size > MAX_PICKED_FILES) {
			return fail(uiMessage(R.string.files_too_many, MAX_PICKED_FILES))
		}
		if (files.isEmpty()) return fail(uiMessage(R.string.share_nothing_usable))
		val groups = groupFiles(files)
		pendingFiles = groups
		_state.update {
			it.copy(
				namingFiles = NamingFiles(files.size, bundler.defaultName()),
				message = if (batch.refused >
					0
				) {
					uiPlural(R.plurals.share_some_left_out, batch.refused, batch.refused)
				} else {
					it.message
				},
			)
		}
	}

	fun cancelNamingFiles() {
		pendingFiles = null
		_state.update { it.copy(namingFiles = null) }
		offerIncomingShare()
	}

	fun nameFiles(raw: String) = launchBusy {
		val groups = pendingFiles ?: return@launchBusy
		val suggested = _state.value.namingFiles?.suggested
		cancelNamingFiles()
		val name = FileBundler.folderName(raw) ?: return@launchBusy fail(uiMessage(R.string.folder_name_invalid))
		// Left as suggested, the row goes on naming the files, which says more than a date.
		val named = groups.map { if (name == suggested) it.copy(folder = name) else it.copy(folder = name, label = name) }
		if (_state.value.duress) return@launchBusy addDecoyFiles(named)
		if (!ContentCredential.isAvailable()) {
			return@launchBusy fail(HideFailure.PrimaryPinSessionRequired.toUiMessage())
		}
		// The whole pick or none of it, so a refusal never leaves half the files moved.
		for (group in named) {
			unsuitable(bundler.plannedPath(group.parent, group.folder))?.let { return@launchBusy fail(it) }
		}
		hideFileGroups(named, FilesProgress())
	}

	/**
	 * Moves more picked files into a row's folder. A hidden one is unhidden for the move, without
	 * putting its files back, and hidden again after. Only files from the folder it sits in, which
	 * is where unhiding will put them.
	 */
	fun addFiles(key: String, uris: List<Uri>) = launchBusy {
		if (_state.value.duress) {
			val item = _state.value.decoyItems.firstOrNull { it.id == key && it.files } ?: return@launchBusy
			val count = pickedFiles(uris)?.sumOf { it.files.size } ?: return@launchBusy
			return@launchBusy refreshDecoyItems(uiPlural(R.plurals.files_added_named, count, count, item.name))
		}
		val folder = _state.value.folders.firstOrNull { it.path == key && it.bundle } ?: return@launchBusy
		if (!ContentCredential.isAvailable()) {
			return@launchBusy fail(HideFailure.PrimaryPinSessionRequired.toUiMessage())
		}
		val groups = pickedFiles(uris) ?: return@launchBusy
		val parent = SafPaths.parentOf(folder.path)
		if (groups.any { it.parent != parent }) {
			return@launchBusy fail(uiMessage(R.string.files_add_elsewhere, SafPaths.nameOf(parent)))
		}
		addToBundle(folder, groups.flatMap { it.files })
	}

	private suspend fun addToBundle(folder: VaultFolder, files: List<String>) =
		ShelfCore.bundleSteps.withLock { addToBundleNow(folder, files) }

	// Retries run inside the grant callback, which already holds the operation lock.
	private suspend fun addToBundleNow(folder: VaultFolder, files: List<String>) {
		var path = folder.path
		folder.entry?.let { entry ->
			when (val opened = folderCoordinator.reopen(entry)) {
				is HideResult.Ok -> path = paths.toEmulated(opened.entry.path)

				is HideResult.NeedsAccess -> return askForAccess(opened) { addToBundle(folder, files) }

				is HideResult.Failed -> {
					reportBlocked(uiMessage(R.string.cannot_add_files_title), problem(folder.displayName, opened.failure))
					return reloadVault()
				}
			}
		}
		val filled = bundler.fill(path, files)
		if (filled is BundleResult.NeedsAccess) {
			// Open now. The retry adds to it as it stands, and a refusal hides it again.
			val exposed = folder.copy(path = path, entry = null)
			reloadVault()
			return askForAccess(
				HideResult.NeedsAccess(filled.path, folder.displayName),
				onRefused = rehideAfterRefusal(folder, path),
			) { addToBundle(exposed, files) }
		}
		val moved = (filled as? BundleResult.Ok)?.moved ?: 0
		val problems = when (filled) {
			is BundleResult.Ok -> filled.failed.map { FolderProblem(it, uiMessage(R.string.file_not_moved)) }
			is BundleResult.Failed -> listOf(problem(folder.displayName, filled.failure))
			is BundleResult.NeedsAccess -> emptyList()
		}.toMutableList()
		// Back out of sight whatever moved, as it was before the pick.
		val target = FolderTarget(path, folder.displayName, null)
		var warning: HideWarning? = null
		when (val hidden = folderCoordinator.hide(target, _state.value.hidingPreference)) {
			is HideResult.Ok -> warning = hidden.warning

			is HideResult.Failed -> problems += problem(folder.displayName, hidden.failure)

			is HideResult.NeedsAccess -> {
				if (problems.isNotEmpty()) reportBlocked(uiMessage(R.string.cannot_add_files_title), problems)
				reloadVault()
				return askForAccess(hidden) { hide(target) }
			}
		}
		if (problems.isNotEmpty()) reportBlocked(uiMessage(R.string.cannot_add_files_title), problems)
		reloadVault(
			moved.takeIf { it > 0 }?.let { uiPlural(R.plurals.files_added_named, it, it, folder.displayName) },
			warning,
		)
	}

	private suspend fun pickedFiles(uris: List<Uri>): List<FileGroup>? {
		if (uris.isEmpty()) return null
		if (uris.size > MAX_PICKED_FILES) {
			fail(uiMessage(R.string.files_too_many, MAX_PICKED_FILES))
			return null
		}
		val refusal = arrayOfNulls<UiMessage>(1)
		val files = withContext(Dispatchers.IO) {
			uris.map { uri ->
				val path = uriPaths.filePath(uri)
				if (path == null) {
					refusal[0] = uiMessage(R.string.files_pick_refused)
					return@withContext null
				}
				if (DocumentFile.fromSingleUri(getApplication(), uri)?.isDirectory == true) {
					refusal[0] = uiMessage(R.string.files_pick_folder)
					return@withContext null
				}
				path
			}.distinct()
		}
		if (files == null) {
			refusal[0]?.let(::fail)
			return null
		}
		return groupFiles(files)
	}

	private fun groupFiles(files: List<String>): List<FileGroup> =
		files.groupBy(SafPaths::parentOf).map { (parent, picked) ->
			val first = SafPaths.nameOf(picked.first())
			FileGroup(parent, picked, if (picked.size == 1) first else "$first +${picked.size - 1}")
		}

	// Retries run inside the grant callback, which already holds the operation lock.
	private suspend fun hideFileGroups(groups: List<FileGroup>, progress: FilesProgress) {
		var done = progress
		for ((index, group) in groups.withIndex()) {
			done = when (val outcome = hideFileGroup(group)) {
				is GroupOutcome.Hidden -> done.copy(
					moved = done.moved + outcome.moved,
					warning = mergeWarnings(done.warning, outcome.warning),
					problems = done.problems + outcome.problems,
				)

				is GroupOutcome.Stopped -> done.copy(problems = done.problems + outcome.problems)

				is GroupOutcome.NeedsAccess -> {
					// Reloaded first: the message asking for access must be the last one set.
					reloadVault()
					val rest = groups.drop(index)
					val sofar = done
					askForAccess(outcome.needed) { hideFileGroups(rest, sofar) }
					return
				}
			}
		}
		if (done.problems.isNotEmpty()) reportBlocked(uiMessage(R.string.cannot_hide_files_title), done.problems)
		reloadVault(done.moved.takeIf { it > 0 }?.let { uiPlural(R.plurals.files_hidden, it, it) }, done.warning)
	}

	private suspend fun hideFileGroup(group: FileGroup): GroupOutcome =
		ShelfCore.bundleSteps.withLock { hideFileGroupNow(group) }

	private suspend fun hideFileGroupNow(group: FileGroup): GroupOutcome {
		val bundlePath = when (val created = bundler.create(group.parent, group.label, group.folder)) {
			is BundleResult.Ok -> created.bundlePath

			is BundleResult.NeedsAccess -> return GroupOutcome.NeedsAccess(
				HideResult.NeedsAccess(created.path, created.name),
			)

			is BundleResult.Failed -> return GroupOutcome.Stopped(listOf(problem(group.label, created.failure)))
		}
		// Tracked before anything moves. From the first file on, a crash leaves a row to act on, and
		// the re-hide on lock treats it like any other exposed folder.
		if (guard { registry.put(bundlePath, group.label, bundle = true) } == null) {
			discardBundle(bundlePath)
			return GroupOutcome.Stopped(emptyList())
		}
		val filled = bundler.fill(bundlePath, group.files)
		if (filled !is BundleResult.Ok) {
			discardBundle(bundlePath)
			val failure = (filled as? BundleResult.Failed)?.failure ?: HideFailure.ParentNotGrantable(group.label)
			return GroupOutcome.Stopped(listOf(problem(group.label, failure)))
		}
		val leftBehind = filled.failed.map { FolderProblem(it, uiMessage(R.string.file_not_moved)) }
		if (filled.moved == 0) {
			discardBundle(bundlePath)
			return GroupOutcome.Stopped(leftBehind)
		}

		val target = FolderTarget(bundlePath, group.label, null)
		return when (val result = folderCoordinator.hide(target, _state.value.hidingPreference)) {
			is HideResult.Ok -> GroupOutcome.Hidden(filled.moved, result.warning, leftBehind)

			// A kept journal row means the hide could not be undone cleanly. Hiding again finishes the
			// rollback, as it does for a folder, so the files stay where they are until then.
			is HideResult.Failed -> {
				if (!journalHas(bundlePath)) discardBundle(bundlePath)
				GroupOutcome.Stopped(listOf(problem(group.label, result.failure)) + leftBehind)
			}

			// Nothing was changed by the hide. The files go back before asking, and the retry starts the
			// group over once the grant is there.
			is HideResult.NeedsAccess -> {
				discardBundle(bundlePath)
				GroupOutcome.NeedsAccess(HideResult.NeedsAccess(result.path, group.label))
			}
		}
	}

	/** Puts a bundle's files back and drops its row, keeping the row if anything stayed inside. */
	private suspend fun discardBundle(bundlePath: String) {
		val back = bundler.unwrap(bundlePath)
		if (back is BundleResult.Ok && back.failed.isEmpty()) guard { registry.remove(bundlePath) }
	}

	private suspend fun journalHas(emulatedPath: String): Boolean =
		runCatching { journal.read().any { paths.toEmulated(it.path) == emulatedPath } }.getOrDefault(true)

	private fun problem(name: String, failure: HideFailure) =
		FolderProblem(name, failure.toUiMessage(), failure.needsAllFiles())

	/** For a bundle left exposed: its files go back where they were picked from, and the row goes. */
	fun putBack(folder: VaultFolder) = launchBusy {
		if (_state.value.duress) return@launchBusy
		putBackOrAsk(folder)
	}

	private suspend fun putBackOrAsk(folder: VaultFolder) {
		val result = ShelfCore.bundleSteps.withLock { folderCoordinator.putBack(folder.path, folder.displayName) }
		when (result) {
			is BundleResult.Ok -> if (result.failed.isEmpty()) {
				reloadVault(uiMessage(R.string.files_put_back_named, folder.displayName))
			} else {
				reportBlocked(
					uiMessage(R.string.cannot_put_back_title),
					result.failed.map { FolderProblem(it, uiMessage(R.string.file_not_moved)) },
				)
				reloadVault()
			}

			is BundleResult.NeedsAccess -> askForAccess(HideResult.NeedsAccess(result.path, folder.displayName)) {
				putBackOrAsk(folder)
			}

			is BundleResult.Failed -> {
				reportBlocked(restoreBlockedTitle(1), problem(folder.displayName, result.failure))
				reloadVault()
			}
		}
	}

	/**
	 * Puts some of a bundle's files back where they were picked from. A hidden bundle is opened for
	 * the move, without putting the rest back, and hidden again after. Emptied, the bundle goes.
	 */
	fun takeOutFiles(folder: VaultFolder, names: List<String>) = launchBusy {
		if (_state.value.duress || names.isEmpty() || !folder.bundle) return@launchBusy
		if (!ContentCredential.isAvailable()) {
			return@launchBusy fail(HideFailure.PrimaryPinSessionRequired.toUiMessage())
		}
		// The viewer closes a root folder it opened on its way out. Opening it here first would see it
		// put back to 000 underneath the move.
		folder.entry?.takeIf { it.method == HideMethod.ROOT_CHMOD }?.let { entry ->
			withTimeoutOrNull(VIEWER_CLOSE_MILLIS) { while (OpenFolders.isOpen(entry.path)) delay(50) }
		}
		takeOut(folder, names, rehide = folder.entry != null)
	}

	private suspend fun takeOut(folder: VaultFolder, names: List<String>, rehide: Boolean) =
		ShelfCore.bundleSteps.withLock { takeOutNow(folder, names, rehide) }

	// Retries run inside the grant callback, which already holds the operation lock.
	private suspend fun takeOutNow(folder: VaultFolder, names: List<String>, rehide: Boolean) {
		var path = folder.path
		folder.entry?.let { entry ->
			when (val opened = folderCoordinator.reopen(entry)) {
				is HideResult.Ok -> path = paths.toEmulated(opened.entry.path)

				is HideResult.NeedsAccess -> return askForAccess(opened) { takeOut(folder, names, rehide) }

				is HideResult.Failed -> {
					reportBlocked(uiMessage(R.string.cannot_put_back_title), problem(folder.displayName, opened.failure))
					return reloadVault()
				}
			}
		}
		val taken = bundler.takeOut(path, names)
		if (taken is BundleResult.NeedsAccess) {
			val exposed = folder.copy(path = path, entry = null)
			reloadVault()
			return askForAccess(
				HideResult.NeedsAccess(taken.path, folder.displayName),
				onRefused = if (rehide) rehideAfterRefusal(folder, path) else null,
			) { takeOut(exposed, names, rehide) }
		}
		val moved = (taken as? BundleResult.Ok)?.moved ?: 0
		val problems = when (taken) {
			is BundleResult.Ok -> taken.failed.map { FolderProblem(it, uiMessage(R.string.file_not_moved)) }
			is BundleResult.Failed -> listOf(problem(folder.displayName, taken.failure))
			is BundleResult.NeedsAccess -> emptyList()
		}.toMutableList()
		val parentName = SafPaths.nameOf(SafPaths.parentOf(path))
		val done = moved.takeIf { it > 0 }?.let { uiPlural(R.plurals.files_taken_out, it, it, parentName) }

		var warning: HideWarning? = null
		if (bundler.hasFiles(path) == false) {
			// Nothing left to keep hidden. The empty folder and its row go, as on Put back.
			when (val gone = folderCoordinator.putBack(path, folder.displayName)) {
				is BundleResult.Ok -> problems += gone.failed.map { FolderProblem(it, uiMessage(R.string.file_not_moved)) }

				is BundleResult.Failed -> problems += problem(folder.displayName, gone.failure)

				is BundleResult.NeedsAccess -> {
					if (problems.isNotEmpty()) reportBlocked(uiMessage(R.string.cannot_put_back_title), problems)
					reloadVault(done)
					val exposed = folder.copy(path = path, entry = null)
					return askForAccess(HideResult.NeedsAccess(gone.path, folder.displayName)) { putBackOrAsk(exposed) }
				}
			}
		} else if (rehide) {
			val target = FolderTarget(path, folder.displayName, null)
			when (val hidden = folderCoordinator.hide(target, _state.value.hidingPreference)) {
				is HideResult.Ok -> warning = hidden.warning

				is HideResult.Failed -> problems += problem(folder.displayName, hidden.failure)

				is HideResult.NeedsAccess -> {
					if (problems.isNotEmpty()) reportBlocked(uiMessage(R.string.cannot_put_back_title), problems)
					reloadVault(done)
					return askForAccess(hidden) { hide(target) }
				}
			}
		}
		if (problems.isNotEmpty()) reportBlocked(uiMessage(R.string.cannot_put_back_title), problems)
		reloadVault(done, warning)
	}

	/**
	 * Why this folder cannot be taken on, or null when it can.
	 *
	 * Two things the picker will hand over that must not be hidden. One of Android's own top-level
	 * folders, which it recreates and which the camera and gallery are pointed at. And a folder
	 * sitting above or below one already tracked: hiding the outer one moves the inner one's
	 * recorded path out from under it, and nothing is left that can find it again.
	 */
	private suspend fun unsuitable(path: String): UiMessage? {
		reservedFolder(path)?.let { return it }
		// Ignoring case, as emulated storage does. The hidden locations count too: the picker shows
		// a renamed folder to anyone with hidden files on, and hiding it again would write over the
		// manifest that holds its real names.
		fun overlaps(other: String) = other.isNotEmpty() && (
			path.equals(other, ignoreCase = true) ||
				path.startsWith("$other/", ignoreCase = true) ||
				other.startsWith("$path/", ignoreCase = true)
			)
		val tracked = guard { registry.read() }.orEmpty()
		tracked.firstOrNull { !it.path.equals(path, ignoreCase = true) && overlaps(it.path) }
			?.let { return uiMessage(R.string.folder_overlaps_tracked, it.displayName) }
		// A root hide leaves hiddenPath empty, and toEmulated throws on that.
		val hidden = guard { journal.read() }.orEmpty()
		return hidden
			.filter { it.hiddenPath.isNotEmpty() && it.hiddenPath != it.path }
			.firstOrNull { overlaps(paths.toEmulated(it.hiddenPath)) }
			?.let { uiMessage(R.string.folder_overlaps_tracked, it.displayName) }
	}

	private fun reservedFolder(path: String): UiMessage? {
		val relative = path.removePrefix(paths.emulatedRoot).trim('/')
		// The checks below compare strings, so a ".." would walk straight past them.
		if (relative.isNotEmpty() && !relative.split('/').all(SafPaths::isSafeName)) {
			return uiMessage(R.string.folder_path_unresolved)
		}
		// Where the all-files method keeps what it moved, at any depth.
		val top = relative.substringBefore('/').lowercase()
		if (relative.isNotEmpty() && relative.lowercase() !in RESERVED_FOLDERS && top != PRIVATE_VAULT) return null
		return uiMessage(R.string.folder_is_reserved, relative.ifEmpty { "/" })
	}

	private suspend fun hide(target: FolderTarget) {
		if (!ContentCredential.isAvailable()) {
			return fail(HideFailure.PrimaryPinSessionRequired.toUiMessage())
		}
		when (val result = folderCoordinator.hide(target, _state.value.hidingPreference)) {
			is HideResult.Ok -> reloadVault(
				uiMessage(R.string.folder_hidden_named, result.entry.displayName),
				result.warning,
			)

			is HideResult.Failed -> {
				reportBlocked(
					hideBlockedTitle(1),
					FolderProblem(target.displayName, result.failure.toUiMessage(), result.failure.needsAllFiles()),
				)
				reloadVault()
			}

			is HideResult.NeedsAccess -> askForAccess(result) { hide(target) }
		}
	}

	/** Puts a folder already on the list back out of sight, with whichever method applies today. */
	fun hideAgain(folder: VaultFolder) = launchBusy { hideTrackedOrAsk(folder) }

	// Retries run inside the grant callback, which already holds the operation lock, so this stays a
	// plain suspend function rather than a launchBusy that would wait on itself.
	private suspend fun hideTrackedOrAsk(folder: VaultFolder) {
		when (val result = hideTracked(folder)) {
			is HideResult.Ok -> reloadVault(
				uiMessage(R.string.folder_hidden_named, result.entry.displayName),
				result.warning,
			)

			is HideResult.Failed -> {
				reportBlocked(
					hideBlockedTitle(1),
					FolderProblem(folder.displayName, result.failure.toUiMessage(), result.failure.needsAllFiles()),
				)
				reloadVault()
			}

			is HideResult.NeedsAccess -> askForAccess(result) { hideTrackedOrAsk(folder) }
		}
	}

	private suspend fun hideTracked(folder: VaultFolder): HideResult = if (ContentCredential.isAvailable()) {
		folderCoordinator.hide(
			FolderTarget(folder.path, folder.displayName, null),
			_state.value.hidingPreference,
		)
	} else {
		HideResult.Failed(HideFailure.PrimaryPinSessionRequired)
	}

	fun restore(folder: VaultFolder) = launchBusy {
		val entry = folder.entry ?: return@launchBusy
		restoreEntry(entry)
	}

	/** The header's bulk actions. A mixed list offers both, so neither decides for the other. */
	fun hideAll() = launchBusy {
		if (_state.value.duress) setAllDecoyItemsHidden(true) else hideEverythingTracked()
	}

	fun unhideAll() = launchBusy {
		if (_state.value.duress) setAllDecoyItemsHidden(false) else unhideEverythingTracked()
	}

	private suspend fun hideEverythingTracked() {
		val targets = _state.value.folders.filterNot { it.hidden }
		if (targets.isEmpty()) return fail(uiMessage(R.string.every_folder_hidden))
		var hidden = 0
		val failures = mutableListOf<FolderProblem>()
		for (folder in targets) {
			val result = oneOf(folder.displayName, failures) { hideTracked(folder) } ?: continue
			when (result) {
				is HideResult.Ok -> hidden++

				is HideResult.Failed -> failures += FolderProblem(
					folder.displayName,
					result.failure.toUiMessage(),
					result.failure.needsAllFiles(),
				)

				// A picker per folder would turn one tap into a queue of dialogs. Report instead.
				is HideResult.NeedsAccess -> failures += FolderProblem(
					folder.displayName,
					accessNeeded(result),
					needsAllFiles = false,
				)
			}
		}
		if (failures.isNotEmpty()) {
			reportBlocked(hideBlockedTitle(failures.size), failures)
		}
		if (hidden > 0 || failures.isEmpty()) {
			reloadVault(uiMessage(R.string.bulk_hidden, folderCount(hidden)))
		} else {
			reloadVault()
		}
	}

	private suspend fun unhideEverythingTracked() {
		val targets = _state.value.folders.mapNotNull { it.entry }
		if (targets.isEmpty()) return fail(uiMessage(R.string.nothing_hidden))
		var restored = 0
		val failures = mutableListOf<FolderProblem>()
		for (entry in targets) {
			val result = oneOf(entry.displayName, failures) { folderCoordinator.restore(entry) } ?: continue
			when (result) {
				is HideResult.Ok -> restored++

				is HideResult.Failed -> failures += FolderProblem(
					entry.displayName,
					result.failure.toUiMessage(),
					result.failure.needsAllFiles(),
				)

				is HideResult.NeedsAccess -> failures += FolderProblem(
					entry.displayName,
					accessNeeded(result),
					needsAllFiles = false,
				)
			}
		}
		if (failures.isNotEmpty()) {
			reportBlocked(restoreBlockedTitle(failures.size), failures)
		}
		if (restored > 0 || failures.isEmpty()) {
			reloadVault(uiMessage(R.string.bulk_restored, folderCount(restored)))
		} else {
			reloadVault()
		}
	}

	fun checkHiddenItems() = launchBusy {
		val checks = _state.value.folders.associate { folder ->
			folder.path to folder.entry?.let { folderCoordinator.health(it) }
		}
		val checked = checks.values.filterNotNull()
		val healthy = checked.count { it.status == HiddenHealthStatus.HEALTHY }
		_state.update { state ->
			state.copy(
				folders = state.folders.map { it.copy(health = checks[it.path]) },
				message = uiMessage(
					R.string.health_check_summary,
					folderCount(checked.size),
					healthy,
					checked.size - healthy,
				),
			)
		}
	}

	fun findRenamedFolders(parentTree: Uri) = launchBusy {
		if (!persist(parentTree)) {
			return@launchBusy fail(uiMessage(R.string.parent_access_not_persisted))
		}
		val candidates = folderCoordinator.recoveryCandidates(parentTree)
		_state.update {
			it.copy(
				safRecoveryCandidates = candidates,
				message = if (candidates.isEmpty()) {
					uiMessage(R.string.no_renamed_folders_found)
				} else {
					uiMessage(R.string.renamed_folders_found, folderCount(candidates.size))
				},
			)
		}
	}

	fun recoverRenamedFolder(candidate: SafRecoveryCandidate, restoredName: String) = launchBusy {
		val recovered = folderCoordinator.recover(candidate, restoredName)
		when (val result = recovered.result) {
			is HideResult.Ok -> {
				_state.update {
					it.copy(safRecoveryCandidates = it.safRecoveryCandidates - candidate)
				}
				val done = if (recovered.bundle) R.string.files_put_back_named else R.string.folder_restored_named
				reloadVault(uiMessage(done, result.entry.displayName), result.warning)
			}

			is HideResult.Failed -> fail(result.failure.toUiMessage())

			is HideResult.NeedsAccess -> fail(accessNeeded(result))
		}
	}

	fun exportRecoveryBundle(destination: Uri, password: CharArray) = launchBusy {
		var encrypted = byteArrayOf()
		var exported = false
		try {
			if (password.size < MIN_RECOVERY_PASSWORD) {
				return@launchBusy fail(uiMessage(R.string.recovery_password_too_short))
			}
			// Read under the same lock as the export, so it is the set the file holds.
			val covered = journal.read().map { paths.toEmulated(it.path) }
			encrypted = recoveryCoordinator.export(password)
			val resolver = getApplication<Application>().contentResolver
			// The destination can be a cloud provider, which blocks for as long as the upload takes.
			val written = withContext(Dispatchers.IO) {
				resolver.openFileDescriptor(destination, "rwt")?.use { pfd ->
					FileOutputStream(pfd.fileDescriptor).use { output ->
						output.write(encrypted)
						output.fd.sync()
					}
				} != null
			}
			if (!written) return@launchBusy fail(uiMessage(R.string.recovery_destination_open_failed))
			exported = true
			runCatching { preferences.setRecoveryExported(hiddenDigests(covered)) }
			_state.update { it.copy(message = uiMessage(R.string.recovery_exported), recoveryNudge = false) }
		} finally {
			password.fill(' ')
			encrypted.fill(0)
			if (!exported) discardRecoveryDestination(destination)
		}
	}

	/** The picker creates the file before the passphrase is asked for, so an export that stops leaves it empty. */
	fun snoozeRecoveryNudge() = viewModelScope.launch {
		val current = runCatching { journal.read().map { paths.toEmulated(it.path) } }.getOrNull() ?: return@launch
		runCatching { preferences.setRecoverySnoozed(hiddenDigests(current)) }
		_state.update { it.copy(recoveryNudge = false) }
	}

	private suspend fun recoveryNudgeDue(): Boolean = withContext(Dispatchers.IO) {
		val current = runCatching { journal.read().map { paths.toEmulated(it.path) } }.getOrNull()
			?: return@withContext false
		runCatching {
			RecoveryNudge.due(hiddenDigests(current), preferences.recoveryExported(), preferences.recoverySnoozed())
		}.getOrDefault(false)
	}

	private fun hiddenDigests(paths: List<String>): Set<String> {
		val salt = preferences.installSalt(NUDGE_SALT, NUDGE_SALT_BYTES)
		return paths.mapTo(hashSetOf()) { RecoveryNudge.digest(salt, it.lowercase(Locale.ROOT)) }
	}

	fun discardRecoveryDestination(destination: Uri) = viewModelScope.launch(Dispatchers.IO) {
		runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, destination) }
	}

	fun importRecoveryBundle(source: Uri, password: CharArray) = launchBusy {
		var encrypted = byteArrayOf()
		try {
			if (password.size < MIN_RECOVERY_PASSWORD) {
				return@launchBusy fail(uiMessage(R.string.recovery_password_too_short))
			}
			val resolver = getApplication<Application>().contentResolver
			val read = withContext(Dispatchers.IO) {
				resolver.openInputStream(source)?.use { input ->
					val bytes = ByteArrayOutputStream()
					val buffer = ByteArray(16 * 1024)
					try {
						while (true) {
							val count = input.read(buffer)
							if (count < 0) break
							if (bytes.size() + count > RecoveryCoordinator.MAX_ENVELOPE_BYTES) {
								return@withContext RecoveryRead.TooLarge
							}
							bytes.write(buffer, 0, count)
						}
					} finally {
						buffer.fill(0)
					}
					RecoveryRead.Bytes(bytes.toByteArray())
				} ?: RecoveryRead.Unopened
			}
			encrypted = when (read) {
				is RecoveryRead.Bytes -> read.bytes
				RecoveryRead.TooLarge -> return@launchBusy fail(uiMessage(R.string.recovery_file_too_large))
				RecoveryRead.Unopened -> return@launchBusy fail(uiMessage(R.string.recovery_file_open_failed))
			}
			val before = journal.read().mapTo(hashSetOf()) { paths.toEmulated(it.path) }
			val merged = runCatching { recoveryCoordinator.import(encrypted, password) }.getOrElse {
				return@launchBusy if (it is InvalidRecoveryRecords) {
					fail(uiMessage(R.string.recovery_records_invalid, it.cause?.message.orEmpty()))
				} else {
					fail(uiMessage(R.string.recovery_decrypt_failed))
				}
			}
			val summary = when {
				merged.duplicates == 0 && merged.conflicts == 0 -> uiMessage(
					R.string.recovery_imported,
					folderCount(merged.added),
				)

				else -> uiMessage(
					R.string.recovery_imported_with_skips,
					folderCount(merged.added),
					merged.duplicates,
					merged.conflicts,
				)
			}
			// What just came from a file is already in one, so it is no reason to ask for another.
			val imported = journal.read().map { paths.toEmulated(it.path) }.filterNot { it in before }
			runCatching {
				preferences.setRecoveryExported(preferences.recoveryExported().orEmpty() + hiddenDigests(imported))
			}
			reloadVault(summary)
		} finally {
			password.fill(' ')
			encrypted.fill(0)
		}
	}

	private suspend fun restoreEntry(entry: HiddenEntry) {
		val restored = folderCoordinator.restoreFolder(entry)
		when (val result = restored.result) {
			is HideResult.Ok -> reloadVault(
				if (restored.bundle) {
					uiMessage(R.string.files_put_back_named, entry.displayName)
				} else {
					uiMessage(R.string.folder_restored_named, result.entry.displayName)
				},
				result.warning,
			)

			is HideResult.Failed -> {
				reportBlocked(
					restoreBlockedTitle(1),
					FolderProblem(entry.displayName, result.failure.toUiMessage(), result.failure.needsAllFiles()),
				)
				reloadVault()
			}

			is HideResult.NeedsAccess -> askForAccess(result) { restoreEntry(entry) }
		}
	}

	/**
	 * Puts everything back in one pass, scanning first for folders whose record was lost. The way out
	 * after a reinstall, a dropped grant, or a single restore that keeps failing: it never stops at
	 * the first problem and reports what it could not do at the end.
	 */
	fun forceUnhideAll() = launchBusy {
		val recovered = guard { folderCoordinator.recoverEverything() } ?: 0
		// Otherwise a reinstall with a new PIN reads as nothing to unhide while the folders sit there.
		val locked = (guard { folderCoordinator.lockedOrphans() } ?: 0)
			.takeIf { it > 0 }
			?.let { uiMessage(R.string.orphans_other_credential, folderCount(it)) }
		val entries = guard { loadFolders() }.orEmpty().mapNotNull { it.entry }
		if (entries.isEmpty()) {
			val summary = when {
				recovered > 0 -> uiMessage(R.string.recovered_records_unreadable, folderCount(recovered))
				locked != null -> null
				else -> uiMessage(R.string.nothing_to_unhide)
			}
			reloadVault(withNote(summary, locked))
			return@launchBusy
		}

		var restored = 0
		val failures = mutableListOf<FolderProblem>()
		for (entry in entries) {
			val result = oneOf(entry.displayName, failures) { folderCoordinator.restore(entry) } ?: continue
			when (result) {
				is HideResult.Ok -> restored++

				is HideResult.Failed -> failures += FolderProblem(
					entry.displayName,
					result.failure.toUiMessage(),
					result.failure.needsAllFiles(),
				)

				// A grant per folder would turn one action into a queue of pickers. Report it and let
				// the user restore that one on its own.
				is HideResult.NeedsAccess -> failures += FolderProblem(
					entry.displayName,
					accessNeeded(result),
					needsAllFiles = false,
				)
			}
		}

		if (failures.isNotEmpty()) {
			reportBlocked(restoreBlockedTitle(failures.size), failures)
		}
		val summary = if (recovered > 0) {
			uiMessage(R.string.force_unhide_success_recovered, folderCount(restored), recovered)
		} else if (restored > 0 || failures.isEmpty()) {
			uiMessage(R.string.bulk_restored, folderCount(restored))
		} else {
			null
		}
		reloadVault(withNote(summary, locked))
	}

	private fun withNote(message: UiMessage?, note: UiMessage?): UiMessage? = when {
		message == null -> note
		note == null -> message
		else -> uiMessage(R.string.message_joined, message, note)
	}

	/**
	 * Drops a record without touching storage, for a folder the user has already dealt with by hand.
	 * Separate from a restore on purpose: it throws away the only description of how to put the
	 * folder back, so no automatic pass ever decides to do it.
	 */
	fun forgetEntry(folder: VaultFolder) = launchBusy {
		val blocked = guard {
			// By path, not folder.entry: that is null while a journalled folder is exposed, and the
			// row left behind is adopted straight back on the next reload.
			folderCoordinator.forget(folder.path)
		}
		when (blocked) {
			is HideResult.Failed -> return@launchBusy fail(namedFailure(folder.displayName, blocked.failure.toUiMessage()))
			is HideResult.NeedsAccess -> return@launchBusy fail(accessNeeded(blocked))
			else -> Unit
		}
		reloadVault(uiMessage(R.string.folder_record_removed, folder.displayName))
	}

	private fun folderCount(count: Int): UiMessage = uiPlural(R.plurals.folder_count, count, count)

	private fun namedFailure(name: String, failure: UiMessage): UiMessage =
		uiMessage(R.string.named_failure, name, failure)

	private fun accessNeeded(needed: HideResult.NeedsAccess): UiMessage =
		uiMessage(R.string.folder_access_needed, needed.name)

	// Only a bundle opened for this goes back: one the owner had out already stays out.
	private fun rehideAfterRefusal(folder: VaultFolder, path: String): (suspend () -> Unit)? =
		if (folder.entry == null) null else ({ hide(FolderTarget(path, folder.displayName, null)) })

	private fun askForAccess(
		needed: HideResult.NeedsAccess,
		onRefused: (suspend () -> Unit)? = null,
		retry: suspend () -> Unit,
	) {
		pending = retry
		refused = onRefused
		_state.update { it.copy(message = accessNeeded(needed), accessNeededFor = initialUri(needed.path)) }
	}

	fun grantedAccess(treeUri: Uri?) = launchBusy {
		_state.update { it.copy(accessNeededFor = null) }
		val retry = pending ?: return@launchBusy
		val undo = refused
		pending = null
		refused = null
		if (treeUri == null) return@launchBusy undo?.invoke() ?: fail(uiMessage(R.string.nothing_changed))
		if (!persist(treeUri)) {
			undo?.invoke()
			return@launchBusy fail(uiMessage(R.string.folder_access_not_persisted))
		}
		retry()
	}

	private fun initialUri(path: String): Uri? {
		val id = SafPaths.documentId(paths.emulatedRoot, path) ?: return null
		return runCatching { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, id) }.getOrNull()
	}

	fun consumeMessage() = _state.update { it.copy(message = null) }

	fun dismissBlocked() = _state.update { it.copy(blocked = null) }

	private fun reportBlocked(title: UiMessage, problem: FolderProblem) = reportBlocked(title, listOf(problem))

	// Like reloadVault: a report naming folders never lands on a screen the lock already put up.
	private fun reportBlocked(title: UiMessage, problems: List<FolderProblem>) {
		_state.update {
			if (it.screen == Screen.DECOY || it.duress) it else it.copy(blocked = BlockedReport(title, problems))
		}
	}

	private fun HideFailure.needsAllFiles(): Boolean = when (this) {
		is HideFailure.ParentNotGrantable,
		is HideFailure.AllFilesRequired -> true

		else -> false
	}

	private fun hideBlockedTitle(count: Int): UiMessage =
		if (count == 1) uiMessage(R.string.cannot_hide_title) else uiMessage(R.string.cannot_hide_multiple_title)

	private fun restoreBlockedTitle(count: Int): UiMessage =
		if (count == 1) uiMessage(R.string.cannot_restore_title) else uiMessage(R.string.cannot_restore_multiple_title)

	private fun renameBlockedTitle(count: Int): UiMessage = if (count == 1) {
		uiMessage(R.string.cannot_rename_hidden_title)
	} else {
		uiMessage(R.string.cannot_rename_hidden_multiple_title)
	}

	private fun persist(uri: Uri): Boolean {
		val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
		val resolver = getApplication<Application>().contentResolver
		if (runCatching { resolver.takePersistableUriPermission(uri, flags) }.isFailure) return false
		return resolver.persistedUriPermissions.any {
			it.uri == uri && it.isReadPermission && it.isWritePermission
		}
	}

	private suspend fun reloadVault(message: UiMessage? = null, warning: HideWarning? = null) {
		val folders = guard { loadFolders() }.orEmpty()
		syncEmergencyCredential(folders)
		val nudge = recoveryNudgeDue()
		_state.update {
			// An operation can outlive the lock that closed the space, and neither the list nor a message
			// naming a folder is for whatever screen is up by the time it finishes. The count still is:
			// the panic exits go by it.
			val stillOpen = it.screen != Screen.DECOY && !it.duress
			val joined = warning?.let { w ->
				message?.let { m -> uiMessage(R.string.message_joined, m, w.toUiMessage()) } ?: w.toUiMessage()
			} ?: message
			it.copy(
				folders = if (stillOpen) folders else it.folders,
				exposedFolders = folders.count { folder -> !folder.hidden },
				recoveryNudge = stillOpen && nudge,
				message = if (stillOpen) joined else it.message,
			)
		}
	}

	/**
	 * Keeps a device-bound re-hide capability only while at least one real folder is exposed. It goes
	 * as soon as the last one is hidden.
	 */
	private suspend fun syncEmergencyCredential(folders: List<VaultFolder>) {
		val exposed = folders.any { !it.hidden }
		val credential = ContentCredential.copy()
		try {
			withContext(Dispatchers.IO) {
				when {
					exposed && credential != null -> EmergencyCredentialStore.arm(getApplication(), credential)
					!exposed -> EmergencyCredentialStore.clear(getApplication())
				}
				RehideTimer.sync(getApplication(), exposed)
			}
		} finally {
			credential?.fill(' ')
		}
	}

	private fun launchBusy(block: suspend () -> Unit) {
		viewModelScope.launch {
			operationMutex.withLock {
				_state.update { it.copy(busy = true) }
				// A lock while this runs clears the session credential. The operation keeps its own
				// copy until it finishes, or a hide or restore loses it halfway through a folder.
				val lease = ContentCredential.retain()
				val hidesBefore = autoHides
				try {
					block()
				} catch (e: RecordsCorrupted) {
					fail(recoveryMessage(e))
				} catch (e: CancellationException) {
					// The screen going away, not a failure to report on it.
					throw e
				} catch (e: Exception) {
					fail(unexpected(e))
				} finally {
					// An unhide still running when the lock's hide went through finishes in the open,
					// after that hide found nothing to do. The reload armed the credential for it.
					if (autoHides != hidesBefore && _state.value.screen == Screen.DECOY && _state.value.exposedFolders > 0) {
						QuickLockNotification.requestHide(getApplication())
					}
					lease.close()
					_state.update { it.copy(busy = false) }
				}
			}
			// A share that came in while this ran, or while Settings or the naming dialog was up.
			offerIncomingShare()
		}
	}

	/**
	 * One folder's turn in a bulk pass, with a thrown error recorded against that folder.
	 *
	 * The loops used to call straight through, so the first folder to throw took the pass with it:
	 * every folder after it went untried and the per-folder summary was replaced by one generic
	 * message. Cancellation still leaves, because that is the screen going away rather than a folder
	 * that could not be done.
	 */
	private suspend fun oneOf(
		displayName: String,
		failures: MutableList<FolderProblem>,
		block: suspend () -> HideResult,
	): HideResult? = try {
		block()
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		failures += FolderProblem(displayName, unexpected(e), needsAllFiles = false)
		null
	}

	private fun unexpected(e: Throwable): UiMessage =
		e.message?.let { uiMessage(R.string.operation_failed_detail, it) } ?: uiMessage(R.string.operation_failed)

	private suspend fun <T> guard(block: suspend () -> T): T? = try {
		block()
	} catch (e: RecordsCorrupted) {
		fail(recoveryMessage(e))
		null
	}

	override fun onCleared() {
		ContentCredential.clear()
		super.onCleared()
	}

	private fun recoveryMessage(e: RecordsCorrupted): UiMessage =
		uiMessage(R.string.records_corrupted, e.original.name, e.preserved.name)

	private fun fail(reason: UiMessage) = _state.update { it.copy(message = reason) }

	private data class FileGroup(
		val parent: String,
		val files: List<String>,
		val label: String,
		val folder: String? = null,
	)

	private data class FilesProgress(
		val moved: Int = 0,
		val problems: List<FolderProblem> = emptyList(),
		val warning: HideWarning? = null,
	)

	private sealed interface GroupOutcome {
		data class Hidden(val moved: Int, val warning: HideWarning?, val problems: List<FolderProblem>) : GroupOutcome
		data class Stopped(val problems: List<FolderProblem>) : GroupOutcome
		data class NeedsAccess(val needed: HideResult.NeedsAccess) : GroupOutcome
	}

	private sealed interface RecoveryRead {
		class Bytes(val bytes: ByteArray) : RecoveryRead
		data object TooLarge : RecoveryRead
		data object Unopened : RecoveryRead
	}

	private companion object {
		/**
		 * Android's own folders under the storage root. It recreates them, indexes them, and points
		 * the camera and gallery at them, so hiding one breaks the phone rather than a folder.
		 */
		private val RESERVED_FOLDERS = setOf(
			"alarms",
			"android",
			"audiobooks",
			"dcim",
			"documents",
			"download",
			"movies",
			"music",
			"notifications",
			"pictures",
			"podcasts",
			"recordings",
			"ringtones",
		)

		const val PRIVATE_VAULT = ".shelf"
		const val EXTERNAL_STORAGE = UriPaths.EXTERNAL_STORAGE
		const val MAX_UNLOCK_ATTEMPTS = 5
		const val KEYPAD_FREE_MISSES = 10
		const val PICKER_GRACE_MILLIS = 120_000L
		const val WIPE_WAIT_MILLIS = 3_000L
		const val AWAY_LOCK_MILLIS = 60_000L
		const val VIEWER_CLOSE_MILLIS = 5_000L
		const val NUDGE_SALT = "recovery_nudge"
		const val NUDGE_SALT_BYTES = 16
		const val UNKNOWABLE_LENGTH = 48
		const val UNKNOWABLE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
	}
}
