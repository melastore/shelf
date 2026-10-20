package io.github.melastore.shelf.data

import io.github.melastore.shelf.crypto.HeaderCipher
import java.io.File
import java.nio.file.Files
import javax.crypto.SecretKey
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What one pass over a folder managed. [failed] is the part the user has to be told about. */
data class LockSummary(
	val changed: Int = 0,
	val alreadyDone: Int = 0,
	val skipped: Int = 0,
	val failed: Int = 0,
	val wrongPassphrase: Boolean = false,
	/** The files this pass changed, which is what a rollback has to undo and nothing more. */
	val touched: List<LockTarget> = emptyList(),
) {
	val total: Int get() = changed + alreadyDone + skipped + failed
}

/**
 * Applies [FileLocker] across every file in a folder.
 *
 * One key derivation per pass at most, and none once [io.github.melastore.shelf.crypto.KeyCache] has
 * it. PBKDF2 is expensive on purpose, and paying it per file would make a few thousand photos slower
 * than copying them. Each file still gets its own nonce and carries the
 * salt in its own trailer, so a file that turns up on its own is recoverable from the passphrase.
 *
 * A pass never stops at the first failure: half a locked folder with no report is worse than
 * finishing and saying what could not be done.
 */
class ContentLocker(private val slice: Int = FileLocker.SLICE_LENGTH) {

	/** [salt] is the install's, so the key is the one already cached for the session. */
	suspend fun lock(
		targets: List<LockTarget>,
		passphrase: CharArray,
		salt: ByteArray = HeaderCipher.newSalt(),
	): LockSummary = withContext(Dispatchers.IO) {
		val key = HeaderCipher.deriveKey(passphrase, salt)
		targets.summarise { FileLocker.lock(it, key, salt) }
	}

	suspend fun unlock(targets: List<LockTarget>, passphrase: CharArray): LockSummary = withContext(Dispatchers.IO) {
		// Files locked in one pass share a salt, so this derives once in the normal case and still
		// copes with a folder assembled over several passes. Guarded because the pass runs several
		// files at once, which also stops two of them paying for the same derivation.
		val keys = mutableMapOf<String, SecretKey>()
		val keyFor: (ByteArray) -> SecretKey = { salt ->
			synchronized(keys) {
				keys.getOrPut(salt.joinToString("") { "%02x".format(it) }) {
					HeaderCipher.deriveKey(passphrase, salt)
				}
			}
		}

		targets.summarise { FileLocker.unlock(it, keyFor) }
	}

	/**
	 * Applies [work] to every target, several at a time, and adds up what happened. The time goes on
	 * Binder round trips to a document provider, not on the CPU, so waiting on several at once is
	 * most of the speed. Order does not matter: the summary is a set of counts.
	 */
	private suspend fun List<LockTarget>.summarise(work: (LockTarget) -> LockOutcome): LockSummary = coroutineScope {
		val gate = Semaphore(PARALLEL_FILES)
		map { target ->
			async {
				gate.withPermit {
					coroutineContext.ensureActive()
					target to work(target)
				}
			}
		}.awaitAll().fold(LockSummary()) { summary, (target, outcome) -> summary.record(target, outcome) }
	}

	private fun LockSummary.record(target: LockTarget, outcome: LockOutcome): LockSummary = when (outcome) {
		LockOutcome.LOCKED, LockOutcome.UNLOCKED -> copy(changed = changed + 1, touched = touched + target)

		LockOutcome.ALREADY -> copy(alreadyDone = alreadyDone + 1)

		LockOutcome.EMPTY -> copy(skipped = skipped + 1)

		LockOutcome.FAILED -> copy(failed = failed + 1)

		// A wrong passphrase makes every file in the pass refuse, so it is reported once rather than
		// N times.
		LockOutcome.WRONG_PASSPHRASE -> copy(failed = failed + 1, wrongPassphrase = true)
	}

	companion object {
		// Latency, not cores: files wait on a provider rather than compute. Capped because each one
		// in flight holds a 64KB slice in memory.
		private const val PARALLEL_FILES = 6

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
