package io.github.melastore.shelf.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a folder was made to disappear, and therefore how it has to be brought back.
 *
 * A restore always uses the method that hid the folder, never whatever the device can do best today:
 * granting all-files access after hiding something must not strand it.
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
 * One hidden folder and everything needed to put it back exactly as it was.
 *
 * [path] identifies the entry and is where a restore puts the folder back: for [HideMethod.ROOT_CHMOD]
 * that is the /data/media/<user> path whose bits were cleared, and for the two rename-based methods
 * it is the emulated path the folder was taken from. It keeps the serialised name `backingPath` so
 * journals written by earlier versions still parse; those have no [method] and are read as root
 * hides, which is what they were.
 *
 * [originalMode] and [originalOwner] apply to a root hide, [hiddenPath] and [treeUri] to the others.
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
