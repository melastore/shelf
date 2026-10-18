package io.github.melastore.shelf.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Root-hidden folders the viewer has opened to read.
 *
 * While the viewer is up the folder sits at 755, which looks the same as a folder restored behind
 * Shelf's back. A re-hide that took it for one restored and re-hid it while the viewer was about to
 * chmod it back to 000, and could record 000 as the mode to restore. Re-hides and the viewer's
 * chmods share one lock so they never interleave.
 */
internal object OpenFolders {
	private val lock = Mutex()
	private val open = mutableMapOf<String, Int>()

	fun isOpen(path: String): Boolean = synchronized(open) { path in open }

	suspend fun <T> transition(block: suspend () -> T): T = lock.withLock { block() }

	// Marked before the chmod and cleared after it, so a stat that sees 755 always sees the mark too.
	suspend fun open(path: String, chmod: suspend () -> Unit) = lock.withLock {
		synchronized(open) { open[path] = (open[path] ?: 0) + 1 }
		chmod()
	}

	suspend fun close(path: String, chmod: suspend () -> Unit) = lock.withLock {
		val last = synchronized(open) { (open[path] ?: 0) <= 1 }
		if (last) chmod()
		synchronized(open) {
			val count = (open[path] ?: 0) - 1
			if (count > 0) open[path] = count else open.remove(path)
		}
	}
}
