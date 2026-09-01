package io.github.melastore.shelf.data

import java.io.File
import kotlinx.serialization.builtins.ListSerializer

/**
 * Durable record of which folders Shelf has hidden, and the source of truth for a restore. A folder
 * at mode 000 carries no hint of what its permissions were, so losing this makes the change
 * permanent. An entry is committed before the change is applied and cleared only after a restore.
 */
class Journal(file: File) {

	private val store = AtomicJsonList(file, ListSerializer(HiddenEntry.serializer()))

	suspend fun read(): List<HiddenEntry> = store.read()

	/**
	 * Records [entry], or returns false if the folder is already journalled. Overwriting would
	 * replace the real permissions with the 000 a second hide reads back, leaving nothing to restore.
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

	/** Updates an existing record once a provider reports the name it actually used. */
	suspend fun replace(entry: HiddenEntry): Boolean = store.mutate { current ->
		if (current.none { it.path == entry.path }) {
			current to false
		} else {
			current.map { if (it.path == entry.path) entry else it } to true
		}
	}

	/**
	 * Merges a validated recovery bundle without overwriting anything already held. A record that
	 * disagrees is skipped rather than failing the import: the one held is the newer description of
	 * the same folder, and refusing the file would strand every other record in it.
	 */
	suspend fun merge(entries: List<HiddenEntry>): RecoveryMergeResult = store.mutate { current ->
		val additions = entries.filterNot { incoming -> current.any { it.path == incoming.path } }
		val conflicts = entries.count { incoming ->
			current.any { it.path == incoming.path && it != incoming }
		}
		(current + additions) to RecoveryMergeResult(
			added = additions.size,
			duplicates = entries.size - additions.size - conflicts,
			conflicts = conflicts,
		)
	}
}
