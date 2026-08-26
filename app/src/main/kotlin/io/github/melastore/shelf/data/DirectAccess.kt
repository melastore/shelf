package io.github.melastore.shelf.data

import java.io.File

/**
 * Whether [dir] can be opened as a directory by this process.
 *
 * This is the cheap check, not a guarantee. `canRead` and `canWrite` are access(2) against the FUSE
 * mount and answer for the mount rather than for the caller, and on some builds
 * `isExternalStorageManager` reports true for an app that merely declares the permission. Passing
 * here means a walk is worth attempting; only the walk itself says whether anything was seen.
 */
internal fun canWalkAsFile(dir: File): Boolean =
	dir.isDirectory && dir.canRead() && dir.canWrite() && dir.listFiles() != null
