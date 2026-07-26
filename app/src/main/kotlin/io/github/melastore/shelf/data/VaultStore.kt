package io.github.melastore.shelf.data

import java.io.File
import kotlinx.serialization.builtins.ListSerializer

/** App-private record of every file whose header is currently encrypted. */
class VaultStore(file: File) {

	private val store = AtomicJsonList(file, ListSerializer(LockedFile.serializer()))

	suspend fun read(): List<LockedFile> = store.read()

	/**
	 * Records [locked] unless the file is already in the vault, in which case nothing is written and
	 * this returns false. Replacing an existing record would discard the salt, nonce and tag that
	 * reverse the first encryption, and no passphrase could reach the plaintext again.
	 */
	suspend fun addNew(locked: LockedFile): Boolean = store.mutate { current ->
		if (current.any { it.path == locked.path }) {
			current to false
		} else {
			(current + locked) to true
		}
	}

	suspend fun remove(path: String) = store.update { current ->
		current.filterNot { it.path == path }
	}
}
