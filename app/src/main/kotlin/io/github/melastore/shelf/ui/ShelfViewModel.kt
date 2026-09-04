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
import io.github.melastore.shelf.data.CalendarEvent
import io.github.melastore.shelf.data.CalendarEventStore
import io.github.melastore.shelf.data.ContentCredential
import io.github.melastore.shelf.data.DecoyItem
import io.github.melastore.shelf.data.DecoyType
import io.github.melastore.shelf.data.DuressEvent
import io.github.melastore.shelf.data.DuressLog
import io.github.melastore.shelf.data.EntryMethod
import io.github.melastore.shelf.data.FailedUnlock
import io.github.melastore.shelf.data.FailedUnlockLog
import io.github.melastore.shelf.data.FolderTarget
import io.github.melastore.shelf.data.Habit
import io.github.melastore.shelf.data.HabitStore
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HiddenHealth
import io.github.melastore.shelf.data.HiddenHealthStatus
import io.github.melastore.shelf.data.HideFailure
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HideResult
import io.github.melastore.shelf.data.HideWarning
import io.github.melastore.shelf.data.HidingPreference
import io.github.melastore.shelf.data.RecordsCorrupted
import io.github.melastore.shelf.data.RecoveryBundleCodec
import io.github.melastore.shelf.data.SafPaths
import io.github.melastore.shelf.data.SafRecoveryCandidate
import io.github.melastore.shelf.data.ShelfCore
import io.github.melastore.shelf.data.ThemeMode
import io.github.melastore.shelf.security.BiometricAuth
import io.github.melastore.shelf.security.CredentialFault
import io.github.melastore.shelf.security.CredentialKind
import io.github.melastore.shelf.security.CredentialRules
import io.github.melastore.shelf.security.EmergencyCredentialStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Screen { DECOY, VAULT, SETTINGS }

/** Shared with the dialog that collects it, so the two cannot disagree on what is long enough. */
const val MIN_RECOVERY_PASSWORD = 12

/**
 * One row of the private space. [entry] is the journal record describing how to put the folder back,
 * and is null exactly when the folder is sitting in the open under its own name.
 */
data class VaultFolder(
	val path: String,
	val displayName: String,
	val entry: HiddenEntry?,
	val health: HiddenHealth? = null,
) {
	val hidden: Boolean get() = entry != null
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
	val decoy: DecoyType = DecoyType.HABITS,
	val entryMethod: EntryMethod = EntryMethod.TITLE_HOLD,
	val hidingPreference: HidingPreference = HidingPreference.AUTO,
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
	private val folderCoordinator = FolderCoordinator(paths, journal, registry, hider)
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
	private var pending: (suspend () -> Unit)? = null
	private val operationMutex = Mutex()
	private val authenticationMutex = Mutex()
	private var recoveryCheckedFor: HideMethod? = null
	private var backgroundLock: Job? = null
	private var autoHideRequested = false

	init {
		viewModelScope.launch {
			QuickLockSignal.requests.collect {
				QuickLockSignal.consume()
				onEmergencyHide()
			}
		}
		viewModelScope.launch {
			QuickLockSignal.completions.collect { refreshExposure() }
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
			if (credentialSet) withContext(Dispatchers.IO) { launcherAliases.apply(settings.decoy) }
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
					decoy = settings.decoy,
					entryMethod = settings.entryMethod,
					hidingPreference = settings.hidingPreference,
					fakeCrash = settings.fakeCrash,
					hideFromRecents = settings.hideFromRecents,
					themeMode = settings.themeMode,
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
			if (auth.decoyMatches(next)) {
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

			withContext(Dispatchers.IO) { EmergencyCredentialStore.clear(getApplication()) }
			auth.setPrimary(next, kind)
			ContentCredential.set(next)
			_state.update {
				it.copy(
					credentialKind = kind,
					decoyPinSet = it.decoyPinSet && !decoyDropped,
					biometricEnabled = if (biometricWasEnabled) false else it.biometricEnabled,
					message = uiMessage(
						when {
							decoyDropped -> R.string.primary_pin_changed_second_removed
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
			if (auth.primaryMatches(credential)) {
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
		_state.update {
			it.copy(
				screen = Screen.VAULT,
				duress = false,
				folders = loaded.getOrDefault(emptyList()),
				exposedFolders = loaded.getOrNull()?.count { folder -> !folder.hidden } ?: it.exposedFolders,
				decoyItems = emptyList(),
				duressAlert = alert,
				failedAttemptAlert = attempts,
				message = loaded.exceptionOrNull()?.let(::listFailure) ?: it.message,
			)
		}
		runCatching { refreshCapabilitiesNow() }
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
		return folderCoordinator.load(health)
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
				method = HideMethod.DOT_RENAME,
				safRecoveryCandidates = emptyList(),
			)
		}
	}

	/**
	 * The decoy list is the owner's to write.
	 *
	 * As seeded it is the same four names on every install of an app anyone can read the source of,
	 * and a decoy someone recognises is not a decoy. Adding and removing rows from inside the decoy
	 * space is how it becomes this phone's list. Nothing here touches storage: a row is a name and a
	 * flag, and restoring one strikes it off exactly as the real space would.
	 */
	fun addDecoyItem(name: String) = launchBusy {
		val clean = name.trim()
		if (clean.isEmpty()) return@launchBusy
		runCatching { decoyVault.add(clean) }
		refreshDecoyItems(uiMessage(R.string.folder_hidden_named, clean))
	}

	fun removeDecoyItem(item: DecoyItem) = launchBusy {
		runCatching { decoyVault.remove(item.id) }
		refreshDecoyItems(uiMessage(R.string.folder_record_removed, item.name))
	}

	fun toggleDecoyItem(item: DecoyItem) = launchBusy {
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
	}

	/**
	 * Everything derived from the private space goes with it, not just the list. Health details and
	 * recovery candidates are real folder paths, and [pending] is an operation waiting on a grant
	 * that would otherwise run itself the moment whoever opened the app next, second credential
	 * included, answered a folder picker they never asked for.
	 */
	fun lockVault() {
		ContentCredential.clear()
		backgroundLock?.cancel()
		backgroundLock = null
		awaitingPickerUntil = 0L
		pending = null
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
		_state.update { it.copy(exposedFolders = exposed) }
	}

	/** Records the disguise. [applyPendingDisguise] swaps the launcher entry later. */
	fun setDecoy(decoy: DecoyType) = viewModelScope.launch {
		try {
			withContext(Dispatchers.IO) { preferences.setDecoy(decoy) }
			_state.update { it.copy(decoy = decoy, message = uiMessage(R.string.disguise_changed)) }
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

	fun setEntryMethod(method: EntryMethod) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setEntryMethod(method) }
		_state.update { it.copy(entryMethod = method, message = uiMessage(R.string.entry_gesture_changed)) }
	}

	fun setHidingPreference(preference: HidingPreference) = viewModelScope.launch {
		withContext(Dispatchers.IO) { preferences.setHidingPreference(preference) }
		_state.update { it.copy(hidingPreference = preference) }
		refreshCapabilitiesNow()
	}

	fun refreshCapabilities() = viewModelScope.launch { refreshCapabilitiesNow() }

	private suspend fun refreshCapabilitiesNow() {
		val preference = _state.value.hidingPreference
		val available = folderCoordinator.availableMethods(
			checkRoot = preference == HidingPreference.AUTO || preference == HidingPreference.ROOT,
		)
		val method = folderCoordinator.selectedMethod(preference, available)
		var recovered: List<VaultFolder>? = null
		if (_state.value.folders.isEmpty() && method != null && method != recoveryCheckedFor) {
			recoveryCheckedFor = method
			if ((guard { folderCoordinator.recoverOrphans(method) } ?: 0) > 0) recovered = guard { loadFolders() }
		}
		_state.update {
			it.copy(
				method = method,
				availableMethods = available,
				folders = recovered ?: it.folders,
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

	fun expectExternalPicker() {
		awaitingPickerUntil = SystemClock.elapsedRealtime() + PICKER_GRACE_MILLIS
	}

	fun onPickerResult() {
		awaitingPickerUntil = 0L
	}

	fun onMovedToBackground() {
		val awaitingPicker = SystemClock.elapsedRealtime() < awaitingPickerUntil
		awaitingPickerUntil = 0L
		backgroundLock?.cancel()
		if (!awaitingPicker) applyPendingDisguise()
		if (_state.value.autoHideMode != AutoHideMode.IMMEDIATE) return
		// A picker briefly interrupts an immediate hide, or no folder could ever be picked. If it is
		// abandoned, the grace period still ends by closing and hiding everything.
		if (awaitingPicker) {
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
			runCatching { withContext(Dispatchers.IO) { launcherAliases.apply(decoy) } }
		}
	}

	fun onMovedToForeground() {
		backgroundLock?.cancel()
		backgroundLock = null
		autoHideRequested = false
	}

	/** Screen-off covers both non-never choices. Immediate has usually already fired from onStop. */
	fun onScreenTurnedOff() = viewModelScope.launch {
		val mode = withContext(Dispatchers.IO) { preferences.autoHideMode() }
		if (mode != AutoHideMode.NEVER) autoHideAndClose()
	}

	fun onScreenTurnedOn() = Unit

	/**
	 * Hands closing and hiding to a receiver that can finish after Android destroys this view model.
	 * The receiver takes a lease on the session credential before telling this screen to close.
	 */
	private fun autoHideAndClose() {
		if ((_state.value.screen == Screen.DECOY && _state.value.exposedFolders == 0) || autoHideRequested) return
		autoHideRequested = true
		QuickLockNotification.requestHide(getApplication())
	}

	// Folder operations

	fun hideFolder(treeUri: Uri) = launchBusy {
		val path = resolveDocumentPath(treeUri, isTree = true)
			?: return@launchBusy fail(uiMessage(R.string.folder_path_unresolved))
		val name = DocumentFile.fromTreeUri(getApplication(), treeUri)?.name ?: File(path).name
		val persisted = persist(treeUri)
		// Tracked before the hide, so a folder that still needs a grant stays on the list to retry
		// from instead of something the user has to find in the picker twice.
		guard { registry.put(path, name) }
		hide(FolderTarget(path, name, treeUri.takeIf { persisted }))
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

			is HideResult.Failed -> fail(result.failure.toUiMessage())

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

			is HideResult.Failed -> fail(result.failure.toUiMessage())

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

	/**
	 * The one control at the top of the private space: everything out of sight, or everything back.
	 * A half-hidden list unhides, since anything still visible means the useful direction is out.
	 */
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
		val failures = mutableListOf<UiMessage>()
		for (folder in targets) {
			when (val result = hideTracked(folder)) {
				is HideResult.Ok -> hidden++

				is HideResult.Failed -> failures += namedFailure(folder.displayName, result.failure.toUiMessage())

				// A picker per folder would turn one tap into a queue of dialogs. Report instead.
				is HideResult.NeedsAccess -> failures += accessNeeded(result)
			}
		}
		reloadVault(summarise(hidden = true, count = hidden, failures = failures))
	}

	private suspend fun unhideEverythingTracked() {
		val targets = _state.value.folders.mapNotNull { it.entry }
		if (targets.isEmpty()) return fail(uiMessage(R.string.nothing_hidden))
		var restored = 0
		val failures = mutableListOf<UiMessage>()
		for (entry in targets) {
			when (val result = folderCoordinator.restore(entry)) {
				is HideResult.Ok -> restored++
				is HideResult.Failed -> failures += namedFailure(entry.displayName, result.failure.toUiMessage())
				is HideResult.NeedsAccess -> failures += accessNeeded(result)
			}
		}
		reloadVault(summarise(hidden = false, count = restored, failures = failures))
	}

	private fun summarise(hidden: Boolean, count: Int, failures: List<UiMessage>): UiMessage = when {
		failures.isEmpty() -> uiMessage(
			if (hidden) R.string.bulk_hidden else R.string.bulk_restored,
			folderCount(count),
		)

		count == 0 -> uiMessage(R.string.bulk_none_changed, failures.first())

		else -> uiMessage(
			if (hidden) R.string.bulk_hidden_partial else R.string.bulk_restored_partial,
			folderCount(count),
			failures.size,
			failures.first(),
		)
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
		when (val result = folderCoordinator.recover(candidate, restoredName)) {
			is HideResult.Ok -> {
				_state.update {
					it.copy(safRecoveryCandidates = it.safRecoveryCandidates - candidate)
				}
				reloadVault(uiMessage(R.string.folder_restored_named, result.entry.displayName), result.warning)
			}

			is HideResult.Failed -> fail(result.failure.toUiMessage())

			is HideResult.NeedsAccess -> fail(accessNeeded(result))
		}
	}

	fun exportRecoveryBundle(destination: Uri, password: CharArray) = launchBusy {
		var encrypted = byteArrayOf()
		try {
			if (password.size < MIN_RECOVERY_PASSWORD) {
				return@launchBusy fail(uiMessage(R.string.recovery_password_too_short))
			}
			encrypted = recoveryCoordinator.export(password)
			val resolver = getApplication<Application>().contentResolver
			val descriptor = resolver.openFileDescriptor(destination, "rwt")
				?: return@launchBusy fail(uiMessage(R.string.recovery_destination_open_failed))
			descriptor.use { pfd ->
				FileOutputStream(pfd.fileDescriptor).use { output ->
					output.write(encrypted)
					output.fd.sync()
				}
			}
			_state.update { it.copy(message = uiMessage(R.string.recovery_exported)) }
		} finally {
			password.fill(' ')
			encrypted.fill(0)
		}
	}

	fun importRecoveryBundle(source: Uri, password: CharArray) = launchBusy {
		var encrypted = byteArrayOf()
		try {
			if (password.size < MIN_RECOVERY_PASSWORD) {
				return@launchBusy fail(uiMessage(R.string.recovery_password_too_short))
			}
			val resolver = getApplication<Application>().contentResolver
			encrypted = resolver.openInputStream(source)?.use { input ->
				val bytes = ByteArrayOutputStream()
				val buffer = ByteArray(16 * 1024)
				while (true) {
					val read = input.read(buffer)
					if (read < 0) break
					if (bytes.size() + read > RecoveryCoordinator.MAX_ENVELOPE_BYTES) {
						return@launchBusy fail(uiMessage(R.string.recovery_file_too_large))
					}
					bytes.write(buffer, 0, read)
				}
				buffer.fill(0)
				bytes.toByteArray()
			} ?: return@launchBusy fail(uiMessage(R.string.recovery_file_open_failed))
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
			reloadVault(summary)
		} finally {
			password.fill(' ')
			encrypted.fill(0)
		}
	}

	private suspend fun restoreEntry(entry: HiddenEntry) {
		when (val result = folderCoordinator.restore(entry)) {
			is HideResult.Ok -> reloadVault(
				uiMessage(R.string.folder_restored_named, entry.displayName),
				result.warning,
			)

			is HideResult.Failed -> fail(result.failure.toUiMessage())

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
		val entries = guard { loadFolders() }.orEmpty().mapNotNull { it.entry }
		if (entries.isEmpty()) {
			reloadVault(
				if (recovered > 0) {
					uiMessage(R.string.recovered_records_unreadable, folderCount(recovered))
				} else {
					uiMessage(R.string.nothing_to_unhide)
				},
			)
			return@launchBusy
		}

		var restored = 0
		val failures = mutableListOf<UiMessage>()
		for (entry in entries) {
			when (val result = folderCoordinator.restore(entry)) {
				is HideResult.Ok -> restored++

				is HideResult.Failed -> failures += namedFailure(entry.displayName, result.failure.toUiMessage())

				// A grant per folder would turn one action into a queue of pickers. Report it and let
				// the user restore that one on its own.
				is HideResult.NeedsAccess -> failures += accessNeeded(result)
			}
		}

		val summary = when {
			failures.isEmpty() -> if (recovered > 0) {
				uiMessage(R.string.force_unhide_success_recovered, folderCount(restored), recovered)
			} else {
				uiMessage(R.string.bulk_restored, folderCount(restored))
			}

			restored == 0 -> uiMessage(R.string.force_unhide_none, failures.first())

			else -> uiMessage(
				R.string.force_unhide_partial,
				folderCount(restored),
				recovered,
				failures.size,
				failures.first(),
			)
		}
		reloadVault(summary)
	}

	/**
	 * Drops a record without touching storage, for a folder the user has already dealt with by hand.
	 * Separate from a restore on purpose: it throws away the only description of how to put the
	 * folder back, so no automatic pass ever decides to do it.
	 */
	fun forgetEntry(folder: VaultFolder) = launchBusy {
		guard {
			folder.entry?.let { journal.remove(it.path) }
			registry.remove(folder.path)
		}
		reloadVault(uiMessage(R.string.folder_record_removed, folder.displayName))
	}

	private fun folderCount(count: Int): UiMessage = uiPlural(R.plurals.folder_count, count, count)

	private fun namedFailure(name: String, failure: UiMessage): UiMessage =
		uiMessage(R.string.named_failure, name, failure)

	private fun accessNeeded(needed: HideResult.NeedsAccess): UiMessage =
		uiMessage(R.string.folder_access_needed, needed.name)

	private fun askForAccess(needed: HideResult.NeedsAccess, retry: suspend () -> Unit) {
		pending = retry
		_state.update { it.copy(message = accessNeeded(needed), accessNeededFor = initialUri(needed.path)) }
	}

	fun grantedAccess(treeUri: Uri?) = launchBusy {
		_state.update { it.copy(accessNeededFor = null) }
		val retry = pending ?: return@launchBusy
		pending = null
		if (treeUri == null) return@launchBusy fail(uiMessage(R.string.nothing_changed))
		if (!persist(treeUri)) {
			return@launchBusy fail(uiMessage(R.string.folder_access_not_persisted))
		}
		retry()
	}

	private fun initialUri(path: String): Uri? {
		val id = SafPaths.documentId(paths.emulatedRoot, path) ?: return null
		return runCatching { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, id) }.getOrNull()
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

	private suspend fun reloadVault(message: UiMessage, warning: HideWarning? = null) {
		val folders = guard { loadFolders() }.orEmpty()
		syncEmergencyCredential(folders)
		_state.update {
			it.copy(
				folders = folders,
				exposedFolders = folders.count { folder -> !folder.hidden },
				message = warning?.let { uiMessage(R.string.message_joined, message, it.toUiMessage()) }
					?: message,
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
			}
		} finally {
			credential?.fill(' ')
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
					fail(
						e.message?.let { uiMessage(R.string.operation_failed_detail, it) }
							?: uiMessage(R.string.operation_failed),
					)
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

	override fun onCleared() {
		ContentCredential.clear()
		super.onCleared()
	}

	private fun recoveryMessage(e: RecordsCorrupted): UiMessage =
		uiMessage(R.string.records_corrupted, e.original.name, e.preserved.name)

	private fun fail(reason: UiMessage) = _state.update { it.copy(message = reason) }

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
		const val MAX_UNLOCK_ATTEMPTS = 5
		const val PICKER_GRACE_MILLIS = 120_000L
	}
}
