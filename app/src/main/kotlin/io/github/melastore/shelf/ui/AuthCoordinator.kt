package io.github.melastore.shelf.ui

import io.github.melastore.shelf.data.AppPreferences
import io.github.melastore.shelf.security.CredentialKind
import io.github.melastore.shelf.security.PassphraseGate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

internal enum class CredentialMatch { PRIMARY, DECOY, BURNER, NONE }

/** Owns credential storage and comparison. The view model keeps UI policy and navigation. */
internal class AuthCoordinator(filesDir: File, private val preferences: AppPreferences) {
	private val primaryGate = PassphraseGate(File(filesDir, "gate"))
	private val decoyGate = PassphraseGate(File(filesDir, "decoy_gate"))
	private val burnerGate = PassphraseGate(File(filesDir, "burner_gate"))

	suspend fun primaryIsSet(): Boolean = primaryGate.isSet()
	suspend fun decoyIsSet(): Boolean = decoyGate.isSet()
	suspend fun burnerIsSet(): Boolean = burnerGate.isSet()

	// Each gate is a full PBKDF2 run, so they go side by side. Taken one after another, the owner's
	// own PIN waited for the burner hash first, and a wrong one for all three.
	suspend fun match(input: CharArray): CredentialMatch = withContext(Dispatchers.Default) {
		// One unreadable gate must not stop the others: a damaged decoy file would lock out the owner.
		// It only surfaces when nothing matched, as an error rather than a wrong credential.
		val burner = async { runCatching { burnerGate.matches(input) } }
		val primary = async { runCatching { primaryGate.matches(input) } }
		val decoy = async { runCatching { decoyGate.matches(input) } }
		val results = listOf(burner.await(), primary.await(), decoy.await())
		when {
			results[0].getOrDefault(false) -> CredentialMatch.BURNER
			results[1].getOrDefault(false) -> CredentialMatch.PRIMARY
			results[2].getOrDefault(false) -> CredentialMatch.DECOY
			else -> results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it } ?: CredentialMatch.NONE
		}
	}

	suspend fun primaryMatches(input: CharArray): Boolean = withContext(Dispatchers.Default) { primaryGate.matches(input) }

	suspend fun decoyMatches(input: CharArray): Boolean = withContext(Dispatchers.Default) { decoyGate.matches(input) }

	suspend fun burnerMatches(input: CharArray): Boolean = withContext(Dispatchers.Default) { burnerGate.matches(input) }

	suspend fun setPrimary(input: CharArray, kind: CredentialKind) = withContext(Dispatchers.Default) {
		primaryGate.set(input)
		preferences.setCredentialKind(kind)
	}

	suspend fun clearPrimary() = withContext(Dispatchers.IO) { primaryGate.clear() }

	suspend fun setDecoy(input: CharArray) = withContext(Dispatchers.Default) { decoyGate.set(input) }

	suspend fun clearDecoy() = withContext(Dispatchers.IO) { decoyGate.clear() }

	suspend fun setBurner(input: CharArray) = withContext(Dispatchers.Default) { burnerGate.set(input) }

	suspend fun clearBurner() = withContext(Dispatchers.IO) { burnerGate.clear() }
}
