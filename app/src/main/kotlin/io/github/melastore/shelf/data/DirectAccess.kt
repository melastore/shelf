package io.github.melastore.shelf.data

import java.io.File

/**
 * Whether [dir] looks openable as a directory by this process.
 *
 * The cheap check, not a guarantee. `canRead` and `canWrite` are access(2) against the FUSE mount
 * and answer for the mount, not the caller, and some builds report `isExternalStorageManager` true
 * for an app that only declares the permission. Passing here means a walk is worth trying.
 */
internal fun canWalkAsFile(dir: File): Boolean =
	dir.isDirectory && dir.canRead() && dir.canWrite() && dir.listFiles() != null

/**
 * Whether [name] is a file Shelf put in a folder rather than something the owner stored: the name
 * manifest, the origin note, the recovery marker. The marker is written as root at mode 600, so
 * treating it as content fails the pass and rolls back the hide that just created it.
 */
internal fun isShelfBookkeeping(name: String): Boolean = name.startsWith(SHELF_FILE_PREFIX)

internal const val SHELF_FILE_PREFIX = ".shelf-"
