package io.github.melastore.shelf.data

import android.content.Context
import androidx.core.content.edit
import io.github.melastore.shelf.security.CredentialKind

/**
 * The face the app wears. [NONE] is the default: Shelf presents itself honestly and opens straight
 * onto the credential prompt. A disguise is a deliberate choice, not something an owner discovers
 * they have — and one they never chose is a disguise they will not remember to keep up.
 */
enum class DecoyType { NONE, HABITS, CALENDAR, CALCULATOR }

enum class EntryMethod { TITLE_HOLD, CORNER_KNOCK, NATURAL_HOLD }

enum class HidingPreference { AUTO, ROOT, ALL_FILES, SAF }

/** One clear policy for both closing the private UI and putting exposed folders back out of sight. */
enum class AutoHideMode { SCREEN_OFF, IMMEDIATE, NEVER }

/**
 * What each successive lockout costs: half a minute, a minute, five, fifteen, then an hour for as
 * long as the guessing goes on.
 */
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
)

/** Small, non-sensitive preferences. Secrets are kept in [io.github.melastore.shelf.security.PassphraseGate]. */
class AppPreferences(context: Context) {

	private val values = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

	/** Existing installs used a passphrase, so a missing kind preserves whichever input mode they had. */
	fun read(vaultCredentialExists: Boolean): AppSettings = AppSettings(
		decoy = enumValue(KEY_DECOY, DecoyType.NONE),
		entryMethod = enumValue(KEY_ENTRY, EntryMethod.TITLE_HOLD),
		hidingPreference = enumValue(KEY_HIDING, HidingPreference.AUTO),
		credentialKind = credentialKind(vaultCredentialExists),
		biometricEnabled = values.getBoolean(KEY_BIOMETRIC, false),
		autoHideMode = autoHideMode(),
		quickLockNotification = values.getBoolean(KEY_QUICK_LOCK_NOTIFICATION, false),
		fakeCrash = values.getBoolean(KEY_FAKE_CRASH, false),
	)

	fun setDecoy(value: DecoyType) = putString(KEY_DECOY, value.name)

	/**
	 * How far first-run setup got. The wizard hands the launcher a new icon at the end, which ends the
	 * task it is running in, and it is also the one screen an owner is most likely to be pulled away
	 * from. Either way the next launch resumes rather than starting over.
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
	 * How the owner's credential is entered. Two older keys still decide it on installs that predate the
	 * setting: an explicit PIN flag, and failing that the fact that a credential exists at all, which on
	 * those builds could only have been a passphrase.
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
			// Kept in step so a downgrade to a build that only knows the flag still reads the right one.
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

	fun autoHideMode(): AutoHideMode {
		if (values.contains(KEY_AUTO_HIDE)) return enumValue(KEY_AUTO_HIDE, AutoHideMode.IMMEDIATE)
		// Migration from the two older controls: an immediate background lock remains immediate;
		// otherwise a screen-off trigger remains screen-off. Every other combination becomes never.
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
	 * Measured against [android.os.SystemClock.elapsedRealtime], not the wall clock, so winding the
	 * device's date forward does not clear a lockout. A reboot restarts that counter, which can only
	 * leave a stale value in the future; anything further away than the longest delay the current
	 * streak could have earned is treated as expired rather than blocking on an uptime the device no
	 * longer has.
	 */
	fun blockedUntil(now: Long): Long {
		val until = values.getLong(KEY_BLOCKED_UNTIL, 0L)
		if (until <= now) return 0L
		return until.takeIf { it - now <= lockoutMillis(values.getInt(KEY_LOCKOUTS, 0)) } ?: 0L
	}

	/**
	 * Counts one wrong credential and returns when the next attempt is allowed, or zero.
	 *
	 * The delay grows with each lockout instead of staying flat. A fixed thirty seconds per five
	 * attempts is a rate — roughly three hours to walk every four-dot pattern, under a day for a
	 * four-digit PIN — and a rate is not a defence. Climbing to an hour turns the same work into
	 * months. The streak is cleared only by getting in, so backing off and returning later resumes
	 * where the attacker left off.
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
		const val KEY_FAILED_UNLOCKS = "failed_unlocks"
		const val KEY_LOCKOUTS = "lockouts"
		const val KEY_BLOCKED_UNTIL = "blocked_until"
	}
}
