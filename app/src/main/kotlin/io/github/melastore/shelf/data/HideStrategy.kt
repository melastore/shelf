package io.github.melastore.shelf.data

import android.net.Uri

sealed interface HideResult {
	data class Ok(val entry: HiddenEntry, val warning: String? = null) : HideResult
	data class Failed(val reason: String) : HideResult

	/**
	 * Nothing was changed because the folder cannot be reached from any grant Shelf holds; [path] is
	 * the folder the user would have to allow access to. Renaming a folder is a change to its parent,
	 * so a grant on the folder itself is the one grant that cannot perform it — and would be left
	 * pointing at a name that no longer exists.
	 */
	data class NeedsAccess(val path: String, val reason: String) : HideResult
}

/** A folder the user picked, in both the forms the strategies need it. */
data class FolderTarget(
	val emulatedPath: String,
	val displayName: String,
	val treeUri: Uri?,
)

/**
 * One way of making a folder disappear, and the way back.
 *
 * Every implementation is O(1) in the size of the folder. Hiding is a permission change or a rename
 * within one volume, never a copy: a vault that stalls for two minutes on a folder of video is one
 * the user stops reaching for, and a half-finished copy is a far worse failure than a refusal.
 */
interface HideStrategy {

	val method: HideMethod

	/** Whether this device, and the grants this app currently holds, can use it right now. */
	suspend fun isAvailable(): Boolean

	suspend fun hide(target: FolderTarget): HideResult

	suspend fun restore(entry: HiddenEntry): HideResult

	/** Rebuilds records that survived outside app data after a reinstall. */
	suspend fun recoverOrphans(): Int = 0
}
