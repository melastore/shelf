package io.github.melastore.shelf.data

import io.github.melastore.shelf.crypto.HeaderCipher
import java.io.File
import javax.crypto.SecretKey
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What one pass over a folder managed to do. [failed] is what the user has to be told about. */
data class LockSummary(
	val changed: Int = 0,
	val alreadyDone: Int = 0,
	val skipped: Int = 0,
	val failed: Int = 0,
	val wrongPassphrase: Boolean = false,
) {
	val total: Int get() = changed + alreadyDone + skipped + failed
}

/**
 * Applies [FileLocker] across every file in a folder.
 *
 * One key derivation covers the whole pass: PBKDF2 is deliberately expensive and paying it per file
 * would make a folder of a few thousand photos take longer than copying them would have. Each file
 * still gets its own nonce, and each carries the salt in its own trailer, so a file that later turns
 * up on its own is still recoverable from the passphrase alone.
 *
 * A pass never stops at the first failure. Leaving half a folder locked and reporting nothing about
 * the rest is worse than finishing and saying exactly what could not be done.
 */
class ContentLocker(private val slice: Int = FileLocker.SLICE_LENGTH) {

	suspend fun lock(targets: List<LockTarget>, passphrase: CharArray): LockSummary = withContext(Dispatchers.IO) {
		val salt = HeaderCipher.newSalt()
		val key = HeaderCipher.deriveKey(passphrase, salt)
		var summary = LockSummary()
		for (target in targets) {
			coroutineContext.ensureActive()
			summary = summary.record(FileLocker.lock(target, key, salt))
		}
		summary
	}

	suspend fun unlock(targets: List<LockTarget>, passphrase: CharArray): LockSummary = withContext(Dispatchers.IO) {
		// Files locked in the same pass share a salt, so this derives once in the ordinary case and
		// still copes with a folder assembled from several passes.
		val keys = mutableMapOf<String, SecretKey>()
		val keyFor: (ByteArray) -> SecretKey = { salt ->
			keys.getOrPut(salt.joinToString("") { "%02x".format(it) }) {
				HeaderCipher.deriveKey(passphrase, salt)
			}
		}

		var summary = LockSummary()
		for (target in targets) {
			coroutineContext.ensureActive()
			summary = summary.record(FileLocker.unlock(target, keyFor))
		}
		summary
	}

	private fun LockSummary.record(outcome: LockOutcome): LockSummary = when (outcome) {
		LockOutcome.LOCKED, LockOutcome.UNLOCKED -> copy(changed = changed + 1)

		LockOutcome.ALREADY -> copy(alreadyDone = alreadyDone + 1)

		LockOutcome.EMPTY -> copy(skipped = skipped + 1)

		LockOutcome.FAILED -> copy(failed = failed + 1)

		// One wrong passphrase means every file in the pass will refuse, so it is reported as the one
		// thing that went wrong rather than as N separate failures.
		LockOutcome.WRONG_PASSPHRASE -> copy(failed = failed + 1, wrongPassphrase = true)
	}

	companion object {
		const val MAX_FILES = 20_000

		/** Every file under [root], newest layout first, bounded so a stray path cannot run forever. */
		fun targetsUnder(root: File): List<LockTarget> = root.walkTopDown()
			.filter { it.isFile }
			// One extra is a sentinel: callers must refuse rather than silently leave the remainder clear.
			.take(MAX_FILES + 1)
			.map { FileLockTarget(it) as LockTarget }
			.toList()
	}
}
