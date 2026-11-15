package io.github.melastore.shelf.ui

/**
 * Files another app shared to Shelf, held until the space is opened. Paths only, in memory, and
 * dropped after a while: a share nobody unlocked for is not one to act on later by surprise.
 */
internal object IncomingShare {

	class Batch(val paths: List<String>, val refused: Int, val at: Long, val tooMany: Boolean = false)

	private var batch: Batch? = null

	@Synchronized
	fun put(value: Batch) {
		batch = value
	}

	@Synchronized
	fun take(now: Long): Batch? {
		val taken = batch ?: return null
		batch = null
		return taken.takeIf { now - it.at in 0..MAX_AGE_MILLIS }
	}

	@Synchronized
	fun clear() {
		batch = null
	}

	private const val MAX_AGE_MILLIS = 10 * 60_000L
}
