package io.github.melastore.shelf.ui

import io.github.melastore.shelf.crypto.PasswordEnvelope
import io.github.melastore.shelf.data.Journal
import io.github.melastore.shelf.data.RecoveryBundleCodec
import io.github.melastore.shelf.data.RecoveryMergeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Encryption and record merging, with no dependency on the Android document pickers. */
internal class RecoveryCoordinator(private val journal: Journal, private val codec: RecoveryBundleCodec,) {
	suspend fun export(password: CharArray): ByteArray {
		val plaintext = codec.encode(journal.read())
		return try {
			withContext(Dispatchers.Default) { PasswordEnvelope.encrypt(plaintext, password) }
		} finally {
			plaintext.fill(0)
		}
	}

	suspend fun import(encrypted: ByteArray, password: CharArray): RecoveryMergeResult {
		val plaintext = withContext(Dispatchers.Default) { PasswordEnvelope.decrypt(encrypted, password) }
		return try {
			val records = runCatching { codec.decode(plaintext) }
				.getOrElse { throw InvalidRecoveryRecords(it) }
			journal.merge(records)
		} finally {
			plaintext.fill(0)
		}
	}

	companion object {
		const val MAX_ENVELOPE_BYTES = PasswordEnvelope.MAX_ENVELOPE_BYTES
	}
}

internal class InvalidRecoveryRecords(cause: Throwable) : Exception(cause)
