package io.github.melastore.shelf.data

import io.github.melastore.shelf.crypto.HeaderCipher
import java.io.File
import java.nio.file.Files
import javax.crypto.SecretKey
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What one pass over a folder managed. [failed] is the part the user has to be told about. */
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
 * One key derivation per pass. PBKDF2 is expensive on purpose, and paying it per file would make a
 * few thousand photos slower than copying them. Each file still gets its own nonce and carries the
 * salt in its own trailer, so a file that turns up on its own is recoverable from the passphrase.
 *
 * A pass never stops at the first failure: half a locked folder with no report is worse than
 * finishing and saying what could not be done.
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
		// Files locked in one pass share a salt, so this derives once in the normal case and still
		// copes with a folder assembled over several passes.
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

		// A wrong passphrase makes every file in the pass refuse, so it is reported once rather than
		// N times.
		LockOutcome.WRONG_PASSPHRASE -> copy(failed = failed + 1, wrongPassphrase = true)
	}

	companion object {
		const val MAX_FILES = 20_000

		private fun File.isSymbolicLink(): Boolean = Files.isSymbolicLink(toPath())

		/**
		 * Every file under [root], bounded so a stray path cannot run forever.
		 *
		 * Directory symlinks are not followed: the walk would leave the folder, and locking files
		 * outside it is both wrong and unrecoverable from this folder's records.
		 */
		fun targetsUnder(root: File): List<LockTarget> = root.walkTopDown()
			.onEnter { it == root || !it.isSymbolicLink() }
			.filter { it.isFile && !it.isSymbolicLink() }
			// The extra one is a sentinel. Callers refuse rather than leave the remainder in the clear.
			.take(MAX_FILES + 1)
			.map { FileLockTarget(it) as LockTarget }
			.toList()
	}
}
