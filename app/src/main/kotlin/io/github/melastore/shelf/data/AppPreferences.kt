package io.github.melastore.shelf.data

import android.content.Context
import androidx.core.content.edit

enum class DecoyType { HABITS, CALENDAR, CALCULATOR }

enum class EntryMethod { TITLE_HOLD, CORNER_KNOCK, NATURAL_HOLD }

enum class HidingPreference { AUTO, ROOT, ALL_FILES, SAF }

data class AppSettings(
	val decoy: DecoyType,
	val entryMethod: EntryMethod,
	val hidingPreference: HidingPreference,
	val vaultUsesPin: Boolean,
)

/** Small, non-sensitive preferences. Secrets are kept in [io.github.melastore.shelf.security.PassphraseGate]. */
class AppPreferences(context: Context) {

	private val values = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

	/** Existing installs used a passphrase, so a missing PIN flag preserves that input mode. */
	fun read(vaultCredentialExists: Boolean): AppSettings = AppSettings(
		decoy = enumValue(KEY_DECOY, DecoyType.HABITS),
		entryMethod = enumValue(KEY_ENTRY, EntryMethod.TITLE_HOLD),
		hidingPreference = enumValue(KEY_HIDING, HidingPreference.SAF),
		vaultUsesPin = if (values.contains(KEY_USES_PIN)) {
			values.getBoolean(KEY_USES_PIN, true)
		} else {
			!vaultCredentialExists
		},
	)

	fun setDecoy(value: DecoyType) = putString(KEY_DECOY, value.name)

	fun setEntryMethod(value: EntryMethod) = putString(KEY_ENTRY, value.name)

	fun setHidingPreference(value: HidingPreference) = putString(KEY_HIDING, value.name)

	fun setVaultUsesPin(value: Boolean) {
		values.edit(commit = true) { putBoolean(KEY_USES_PIN, value) }
	}

	fun blockedUntil(): Long = values.getLong(KEY_BLOCKED_UNTIL, 0L)

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
		const val KEY_FAILED_UNLOCKS = "failed_unlocks"
		const val KEY_BLOCKED_UNTIL = "blocked_until"
	}
}
