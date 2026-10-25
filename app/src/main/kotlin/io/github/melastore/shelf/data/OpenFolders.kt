package io.github.melastore.shelf.data

import java.io.File
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

	/**
	 * The same paths on disk. A process killed with the viewer up never runs the close, and nothing
	 * else put the folder back to 000 until the owner next opened that folder in Shelf.
	 */
	@Volatile var ledger: File? = null

	fun isOpen(path: String): Boolean = synchronized(open) { path in open }

	suspend fun <T> transition(block: suspend () -> T): T = lock.withLock { block() }

	// Marked before the chmod and cleared after it, so a stat that sees 755 always sees the mark too.
	suspend fun open(path: String, chmod: suspend () -> Unit) = lock.withLock {
		synchronized(open) { open[path] = (open[path] ?: 0) + 1 }
		write(read() + path)
		chmod()
	}

	/** [chmod] says whether the folder is back at 000. One that is not stays in the ledger for [reseal]. */
	suspend fun close(path: String, chmod: suspend () -> Boolean) = lock.withLock {
		val last = synchronized(open) { (open[path] ?: 0) <= 1 }
		if (last && chmod()) write(read() - path)
		synchronized(open) {
			val count = (open[path] ?: 0) - 1
			if (count > 0) open[path] = count else open.remove(path)
		}
	}

	/** Closes what an earlier process left open. [chmod] says whether a path can be dropped. */
	suspend fun reseal(chmod: suspend (String) -> Boolean) = lock.withLock {
		val stale = read().filterNot(::isOpen)
		if (stale.isEmpty()) return@withLock
		write(read() - stale.filter { chmod(it) }.toSet())
	}

	private fun read(): Set<String> = runCatching {
		ledger?.takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }?.toSet()
	}.getOrNull().orEmpty()

	private fun write(paths: Set<String>) {
		val file = ledger ?: return
		runCatching {
			if (paths.isEmpty()) {
				file.delete()
			} else {
				val temporary = File(file.path + ".tmp")
				temporary.writeText(paths.joinToString("\n"))
				temporary.renameTo(file)
			}
		}
	}
}
