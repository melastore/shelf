package io.github.melastore.shelf.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a folder was hidden, and therefore how it has to be brought back. A restore always uses the
 * recorded method, never whatever the device can do best today, or granting all-files access after
 * a hide would strand the folder.
 */
@Serializable
enum class HideMethod {
	/** Root. The folder stays put and loses its permission bits on the backing store. */
	ROOT_CHMOD,

	/** All-files access. The folder is moved into Shelf's persistent dot-directory. */
	PRIVATE_MOVE,

	/** SAF alone. The folder is renamed to a leading dot, which keeps the media scanner out of it. */
	DOT_RENAME,
}

/**
 * One hidden folder and everything needed to put it back as it was.
 *
 * [path] identifies the entry and is where a restore puts the folder: the /data/media/<user> path
 * whose bits were cleared for [HideMethod.ROOT_CHMOD], the emulated path it was taken from for the
 * two rename-based methods. Serialised as `backingPath` so older journals still parse; those carry
 * no [method] and read as root hides, which is what they were.
 *
 * [originalMode] and [originalOwner] belong to a root hide, [hiddenPath] and [treeUri] to the rest.
 */
@Serializable
data class HiddenEntry(
	@SerialName("backingPath") val path: String,
	val displayName: String,
	val hiddenAt: Long,
	val method: HideMethod = HideMethod.ROOT_CHMOD,
	val originalMode: String = "",
	val originalOwner: String = "",
	val hiddenPath: String = "",
	val treeUri: String = "",
)
