package io.github.melastore.shelf.data

import java.io.File
import kotlinx.serialization.builtins.ListSerializer

/**
 * Durable record of which folders Shelf has hidden.
 *
 * The record is the source of truth for a restore: a folder at mode 000 carries no hint of what its
 * permissions used to be, so if this were lost the change would be effectively permanent. An entry
 * is committed before the permission change is applied and cleared only after a restore succeeds.
 */
class Journal(file: File) {

	private val store = AtomicJsonList(file, ListSerializer(HiddenEntry.serializer()))

	suspend fun read(): List<HiddenEntry> = store.read()

	/**
	 * Records [entry] unless the folder is already journalled, in which case nothing is written and
	 * this returns false. Overwriting an existing entry would replace the real permissions with the
	 * 000 a second hide would read back, leaving nothing to restore to.
	 */
	suspend fun addNew(entry: HiddenEntry): Boolean = store.mutate { current ->
		if (current.any { it.path == entry.path }) {
			current to false
		} else {
			(current + entry) to true
		}
	}

	suspend fun remove(path: String) = store.update { current ->
		current.filterNot { it.path == path }
	}

	/** Updates an existing operation after a provider returns the final name it actually used. */
	suspend fun replace(entry: HiddenEntry): Boolean = store.mutate { current ->
		if (current.none { it.path == entry.path }) {
			current to false
		} else {
			current.map { if (it.path == entry.path) entry else it } to true
		}
	}
}
