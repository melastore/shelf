package io.github.melastore.shelf.ui

import io.github.melastore.shelf.data.AppPreferences
import io.github.melastore.shelf.security.CredentialKind
import io.github.melastore.shelf.security.PassphraseGate
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class CredentialMatch { PRIMARY, DECOY, NONE }

/** Owns credential storage and comparison; the ViewModel owns only UI policy and navigation. */
internal class AuthCoordinator(filesDir: File, private val preferences: AppPreferences) {
	private val primaryGate = PassphraseGate(File(filesDir, "gate"))
	private val decoyGate = PassphraseGate(File(filesDir, "decoy_gate"))

	suspend fun primaryIsSet(): Boolean = primaryGate.isSet()
	suspend fun decoyIsSet(): Boolean = decoyGate.isSet()

	suspend fun match(input: CharArray): CredentialMatch = withContext(Dispatchers.Default) {
		when {
			primaryGate.matches(input) -> CredentialMatch.PRIMARY
			decoyGate.matches(input) -> CredentialMatch.DECOY
			else -> CredentialMatch.NONE
		}
	}

	suspend fun primaryMatches(input: CharArray): Boolean = withContext(Dispatchers.Default) { primaryGate.matches(input) }

	suspend fun decoyMatches(input: CharArray): Boolean = withContext(Dispatchers.Default) { decoyGate.matches(input) }

	suspend fun setPrimary(input: CharArray, kind: CredentialKind) = withContext(Dispatchers.Default) {
		primaryGate.set(input)
		preferences.setCredentialKind(kind)
	}

	suspend fun setDecoy(input: CharArray) = withContext(Dispatchers.Default) { decoyGate.set(input) }

	suspend fun clearDecoy() = withContext(Dispatchers.IO) { decoyGate.clear() }
}
