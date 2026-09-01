package io.github.melastore.shelf.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A folder the user has handed to Shelf, whether or not it is hidden at this moment. */
@Serializable
data class TrackedFolder(val path: String, val displayName: String, val addedAt: Long)

/**
 * Every folder the user has handed to Shelf, kept across a restore.
 *
 * The journal is emptied as each folder comes back; this outlives it. Without it, unhiding would
 * drop a folder off the list and re-hiding would mean finding it in the picker again.
 *
 * Nothing here can reverse anything. [Journal] is still the only record of how a folder was hidden,
 * so losing this file costs a list, not a folder.
 */
class FolderRegistry(file: File) {

	private val store = AtomicJsonList(file, ListSerializer(TrackedFolder.serializer()))

	suspend fun read(): List<TrackedFolder> = store.read()

	/** Adds [path], or refreshes the name of one already tracked. Order is preserved. */
	suspend fun put(path: String, displayName: String, now: Long = System.currentTimeMillis()) {
		store.update { current ->
			val existing = current.firstOrNull { it.path == path }
			when {
				existing == null -> (current + TrackedFolder(path, displayName, now)).takeLast(MAX_FOLDERS)
				existing.displayName == displayName -> current
				else -> current.map { if (it.path == path) it.copy(displayName = displayName) else it }
			}
		}
	}

	/** Adopts folders Shelf hid but never listed, such as records rebuilt after a wipe. */
	suspend fun putAll(folders: List<TrackedFolder>) {
		if (folders.isEmpty()) return
		store.update { current ->
			val known = current.mapTo(mutableSetOf()) { it.path }
			(current + folders.filterNot { it.path in known }).takeLast(MAX_FOLDERS)
		}
	}

	suspend fun remove(path: String) = store.update { current -> current.filterNot { it.path == path } }

	private companion object {
		const val MAX_FOLDERS = 2_000
	}
}
