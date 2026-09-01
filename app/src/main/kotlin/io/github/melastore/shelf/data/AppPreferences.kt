package io.github.melastore.shelf.data

import android.content.Context
import androidx.core.content.edit
import io.github.melastore.shelf.security.CredentialKind

/**
 * The face the app wears. [NONE] is the default: Shelf is itself and opens on the credential prompt.
 * A disguise has to be picked, never handed out; one the owner never chose is one they forget to
 * keep up.
 */
enum class DecoyType { NONE, HABITS, CALENDAR, CALCULATOR }

enum class EntryMethod { TITLE_HOLD, CORNER_KNOCK, NATURAL_HOLD }

enum class HidingPreference { AUTO, ROOT, ALL_FILES, SAF }

/**
 * Which palette the app paints in. [AMOLED] is dark on true black, free to draw on OLED; [SYSTEM]
 * follows the device. Decoy palettes keep their own hues in every mode, or they stop looking like
 * the app they imitate.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

/** One setting for both closing the private UI and putting exposed folders back out of sight. */
enum class AutoHideMode { SCREEN_OFF, IMMEDIATE, NEVER }

/** What each successive lockout costs: 30s, 1m, 5m, 15m, then an hour for as long as it goes on. */
internal val LOCKOUT_LADDER = listOf(30_000L, 60_000L, 300_000L, 900_000L, 3_600_000L)

data class AppSettings(
	val decoy: DecoyType,
	val entryMethod: EntryMethod,
	val hidingPreference: HidingPreference,
	val credentialKind: CredentialKind,
	val biometricEnabled: Boolean,
	val autoHideMode: AutoHideMode,
	val quickLockNotification: Boolean,
	val fakeCrash: Boolean,
	val hideFromRecents: Boolean,
	val themeMode: ThemeMode,
)

/** Small, non-sensitive settings. Secrets live in [io.github.melastore.shelf.security.PassphraseGate]. */
class AppPreferences(context: Context) {

	private val values = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

	/** Older installs used a passphrase, so a missing kind keeps whatever input mode they had. */
	fun read(vaultCredentialExists: Boolean): AppSettings = AppSettings(
		decoy = enumValue(KEY_DECOY, DecoyType.NONE),
		entryMethod = enumValue(KEY_ENTRY, EntryMethod.TITLE_HOLD),
		hidingPreference = enumValue(KEY_HIDING, HidingPreference.AUTO),
		credentialKind = credentialKind(vaultCredentialExists),
		biometricEnabled = values.getBoolean(KEY_BIOMETRIC, false),
		autoHideMode = autoHideMode(),
		quickLockNotification = values.getBoolean(KEY_QUICK_LOCK_NOTIFICATION, false),
		fakeCrash = values.getBoolean(KEY_FAKE_CRASH, false),
		hideFromRecents = values.getBoolean(KEY_HIDE_FROM_RECENTS, false),
		themeMode = enumValue(KEY_THEME, ThemeMode.SYSTEM),
	)

	fun setDecoy(value: DecoyType) = putString(KEY_DECOY, value.name)

	/**
	 * How far first-run setup got. Swapping the launcher icon at the end kills the task the wizard is
	 * running in, and setup is also easy to walk away from. Either way the next launch resumes.
	 */
	fun setSetupStep(value: Int) {
		values.edit(commit = true) { putInt(KEY_SETUP_STEP, value) }
	}

	fun setupStep(): Int = values.getInt(KEY_SETUP_STEP, 0)

	fun clearSetupStep() {
		values.edit(commit = true) { remove(KEY_SETUP_STEP) }
	}

	fun decoy(): DecoyType = enumValue(KEY_DECOY, DecoyType.NONE)

	fun setEntryMethod(value: EntryMethod) = putString(KEY_ENTRY, value.name)

	fun setHidingPreference(value: HidingPreference) = putString(KEY_HIDING, value.name)

	fun hidingPreference(): HidingPreference = enumValue(KEY_HIDING, HidingPreference.AUTO)

	/**
	 * How the credential is entered. On installs predating this setting two older keys decide it: an
	 * explicit PIN flag, and failing that the mere existence of a credential, which back then could
	 * only have been a passphrase.
	 */
	fun credentialKind(vaultCredentialExists: Boolean): CredentialKind = when {
		values.contains(KEY_CREDENTIAL_KIND) -> enumValue(KEY_CREDENTIAL_KIND, CredentialKind.PIN)

		values.contains(KEY_USES_PIN) -> if (values.getBoolean(KEY_USES_PIN, true)) {
			CredentialKind.PIN
		} else {
			CredentialKind.PASSWORD
		}

		vaultCredentialExists -> CredentialKind.PASSWORD

		else -> CredentialKind.PIN
	}

	fun setCredentialKind(value: CredentialKind) {
		values.edit(commit = true) {
			putString(KEY_CREDENTIAL_KIND, value.name)
			// Kept in step, so a downgrade to a build that only knows the flag still reads it right.
			putBoolean(KEY_USES_PIN, value != CredentialKind.PASSWORD)
		}
	}

	fun setBiometricEnabled(value: Boolean) {
		values.edit(commit = true) { putBoolean(KEY_BIOMETRIC, value) }
	}

	fun biometricEnabled(): Boolean = values.getBoolean(KEY_BIOMETRIC, false)

	fun setAutoHideMode(value: AutoHideMode) = putString(KEY_AUTO_HIDE, value.name)

	fun setQuickLockNotification(value: Boolean) {
		values.edit(commit = true) { putBoolean(KEY_QUICK_LOCK_NOTIFICATION, value) }
	}

	fun quickLockNotification(): Boolean = values.getBoolean(KEY_QUICK_LOCK_NOTIFICATION, false)

	fun setFakeCrash(value: Boolean) {
		values.edit(commit = true) { putBoolean(KEY_FAKE_CRASH, value) }
	}

	fun setHideFromRecents(value: Boolean) {
		values.edit(commit = true) { putBoolean(KEY_HIDE_FROM_RECENTS, value) }
	}

	fun hideFromRecents(): Boolean = values.getBoolean(KEY_HIDE_FROM_RECENTS, false)

	fun setThemeMode(value: ThemeMode) = putString(KEY_THEME, value.name)

	fun themeMode(): ThemeMode = enumValue(KEY_THEME, ThemeMode.SYSTEM)

	fun autoHideMode(): AutoHideMode {
		if (values.contains(KEY_AUTO_HIDE)) return enumValue(KEY_AUTO_HIDE, AutoHideMode.IMMEDIATE)
		// Migrating the two older controls: immediate stays immediate, a screen-off trigger stays
		// screen-off, everything else becomes never.
		val timeout = values.getString(KEY_LOCK_TIMEOUT, null)
		val trigger = values.getString(KEY_LOCK_TRIGGER, null)
		return when {
			timeout == "IMMEDIATE" -> AutoHideMode.IMMEDIATE

			trigger == "SCREEN_OFF" -> AutoHideMode.SCREEN_OFF

			values.contains(KEY_LOCK_ON_SCREEN_OFF) &&
				values.getBoolean(KEY_LOCK_ON_SCREEN_OFF, false) -> AutoHideMode.SCREEN_OFF

			timeout == null && trigger == null && !values.contains(KEY_LOCK_ON_SCREEN_OFF) ->
				AutoHideMode.SCREEN_OFF

			else -> AutoHideMode.NEVER
		}
	}

	/**
	 * Measured against [android.os.SystemClock.elapsedRealtime], so winding the date forward does not
	 * clear a lockout. A reboot restarts that counter and can leave a stale value in the future, so
	 * anything further off than the current streak could have earned is treated as expired.
	 */
	fun blockedUntil(now: Long): Long {
		val until = values.getLong(KEY_BLOCKED_UNTIL, 0L)
		if (until <= now) return 0L
		return until.takeIf { it - now <= lockoutMillis(values.getInt(KEY_LOCKOUTS, 0)) } ?: 0L
	}

	/**
	 * Counts one wrong credential and returns when the next attempt is allowed, or zero.
	 *
	 * The delay grows per lockout rather than staying flat. A fixed 30s per five attempts is just a
	 * rate: about three hours for every four-dot pattern, under a day for a four-digit PIN. Climbing
	 * to an hour makes that months. Only getting in clears the streak, so waiting it out does not.
	 */
	fun recordFailedUnlock(now: Long, maximumAttempts: Int): Long {
		val attempts = values.getInt(KEY_FAILED_UNLOCKS, 0) + 1
		if (attempts < maximumAttempts) {
			values.edit(commit = true) { putInt(KEY_FAILED_UNLOCKS, attempts) }
			return 0L
		}
		val lockouts = values.getInt(KEY_LOCKOUTS, 0) + 1
		val blockedUntil = now + lockoutMillis(lockouts)
		values.edit(commit = true) {
			putInt(KEY_FAILED_UNLOCKS, 0)
			putInt(KEY_LOCKOUTS, lockouts)
			putLong(KEY_BLOCKED_UNTIL, blockedUntil)
		}
		return blockedUntil
	}

	fun clearFailedUnlocks() {
		values.edit(commit = true) {
			remove(KEY_FAILED_UNLOCKS)
			remove(KEY_LOCKOUTS)
			remove(KEY_BLOCKED_UNTIL)
		}
	}

	private fun lockoutMillis(lockouts: Int): Long = LOCKOUT_LADDER[(lockouts - 1).coerceIn(0, LOCKOUT_LADDER.lastIndex)]

	private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
		runCatching { enumValueOf<T>(values.getString(key, null).orEmpty()) }.getOrDefault(fallback)

	private fun putString(key: String, value: String) {
		values.edit(commit = true) { putString(key, value) }
	}

	private companion object {
		const val FILE_NAME = "settings"
		const val KEY_DECOY = "decoy"
		const val KEY_SETUP_STEP = "setup_step"
		const val KEY_ENTRY = "entry_method"
		const val KEY_HIDING = "hiding_preference"
		const val KEY_USES_PIN = "vault_uses_pin"
		const val KEY_CREDENTIAL_KIND = "credential_kind"
		const val KEY_BIOMETRIC = "biometric_enabled"
		const val KEY_AUTO_HIDE = "auto_hide_mode"
		const val KEY_LOCK_TIMEOUT = "lock_timeout"
		const val KEY_LOCK_ON_SCREEN_OFF = "lock_on_screen_off"
		const val KEY_LOCK_TRIGGER = "lock_trigger"
		const val KEY_QUICK_LOCK_NOTIFICATION = "quick_lock_notification"
		const val KEY_FAKE_CRASH = "fake_crash"
		const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"
		const val KEY_THEME = "theme_mode"
		const val KEY_FAILED_UNLOCKS = "failed_unlocks"
		const val KEY_LOCKOUTS = "lockouts"
		const val KEY_BLOCKED_UNTIL = "blocked_until"
	}
}
