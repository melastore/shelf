package io.github.melastore.shelf.data

import android.content.Context
import androidx.core.content.edit

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

data class AppSettings(
	val decoy: DecoyType,
	val entryMethod: EntryMethod,
	val hidingPreference: HidingPreference,
	val vaultUsesPin: Boolean,
	val biometricEnabled: Boolean,
	val autoHideMode: AutoHideMode,
	val quickLockNotification: Boolean,
)

/** Small, non-sensitive preferences. Secrets are kept in [io.github.melastore.shelf.security.PassphraseGate]. */
class AppPreferences(context: Context) {

	private val values = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

	/** Existing installs used a passphrase, so a missing PIN flag preserves that input mode. */
	fun read(vaultCredentialExists: Boolean): AppSettings = AppSettings(
		decoy = enumValue(KEY_DECOY, DecoyType.NONE),
		entryMethod = enumValue(KEY_ENTRY, EntryMethod.TITLE_HOLD),
		hidingPreference = enumValue(KEY_HIDING, HidingPreference.AUTO),
		vaultUsesPin = if (values.contains(KEY_USES_PIN)) {
			values.getBoolean(KEY_USES_PIN, true)
		} else {
			!vaultCredentialExists
		},
		biometricEnabled = values.getBoolean(KEY_BIOMETRIC, false),
		autoHideMode = autoHideMode(),
		quickLockNotification = values.getBoolean(KEY_QUICK_LOCK_NOTIFICATION, false),
	)

	fun setDecoy(value: DecoyType) = putString(KEY_DECOY, value.name)

	fun decoy(): DecoyType = enumValue(KEY_DECOY, DecoyType.NONE)

	fun setEntryMethod(value: EntryMethod) = putString(KEY_ENTRY, value.name)

	fun setHidingPreference(value: HidingPreference) = putString(KEY_HIDING, value.name)

	fun hidingPreference(): HidingPreference = enumValue(KEY_HIDING, HidingPreference.AUTO)

	fun setVaultUsesPin(value: Boolean) {
		values.edit(commit = true) { putBoolean(KEY_USES_PIN, value) }
	}

	fun setBiometricEnabled(value: Boolean) {
		values.edit(commit = true) { putBoolean(KEY_BIOMETRIC, value) }
	}

	fun biometricEnabled(): Boolean = values.getBoolean(KEY_BIOMETRIC, false)

	fun setAutoHideMode(value: AutoHideMode) = putString(KEY_AUTO_HIDE, value.name)

	fun setQuickLockNotification(value: Boolean) {
		values.edit(commit = true) { putBoolean(KEY_QUICK_LOCK_NOTIFICATION, value) }
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
	 * leave a stale value in the future; anything further away than one full lockout is treated as
	 * expired rather than blocking on an uptime the device no longer has.
	 */
	fun blockedUntil(now: Long, lockoutMillis: Long): Long =
		values.getLong(KEY_BLOCKED_UNTIL, 0L).takeIf { it - now <= lockoutMillis } ?: 0L

	fun recordFailedUnlock(now: Long, maximumAttempts: Int, lockoutMillis: Long): Long {
		val attempts = values.getInt(KEY_FAILED_UNLOCKS, 0) + 1
		val blockedUntil = if (attempts >= maximumAttempts) now + lockoutMillis else 0L
		values.edit(commit = true) {
			putInt(KEY_FAILED_UNLOCKS, if (blockedUntil > 0) 0 else attempts)
			putLong(KEY_BLOCKED_UNTIL, blockedUntil)
		}
		return blockedUntil
	}

	fun clearFailedUnlocks() {
		values.edit(commit = true) {
			remove(KEY_FAILED_UNLOCKS)
			remove(KEY_BLOCKED_UNTIL)
		}
	}

	private inline fun <reified T : Enum<T>> enumValue(key: String, fallback: T): T =
		runCatching { enumValueOf<T>(values.getString(key, null).orEmpty()) }.getOrDefault(fallback)

	private fun putString(key: String, value: String) {
		values.edit(commit = true) { putString(key, value) }
	}

	private companion object {
		const val FILE_NAME = "settings"
		const val KEY_DECOY = "decoy"
		const val KEY_ENTRY = "entry_method"
		const val KEY_HIDING = "hiding_preference"
		const val KEY_USES_PIN = "vault_uses_pin"
		const val KEY_BIOMETRIC = "biometric_enabled"
		const val KEY_AUTO_HIDE = "auto_hide_mode"
		const val KEY_LOCK_TIMEOUT = "lock_timeout"
		const val KEY_LOCK_ON_SCREEN_OFF = "lock_on_screen_off"
		const val KEY_LOCK_TRIGGER = "lock_trigger"
		const val KEY_QUICK_LOCK_NOTIFICATION = "quick_lock_notification"
		const val KEY_FAILED_UNLOCKS = "failed_unlocks"
		const val KEY_BLOCKED_UNTIL = "blocked_until"
	}
}
