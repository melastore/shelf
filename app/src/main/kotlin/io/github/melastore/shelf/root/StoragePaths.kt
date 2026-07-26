package io.github.melastore.shelf.root

import android.os.Process

/**
 * Translates between the emulated storage view an app sees and the backing store where permission
 * bits are real.
 *
 * On Android 11+ the primary volume under /storage/emulated/<user> (a.k.a. /sdcard) is a FUSE mount.
 * chmod against those paths hits the FUSE daemon, which synthesises its bits from mount options and
 * discards the change. The same files live on the backing filesystem at /data/media/<user>, where
 * the bits persist across reboots. Every privileged operation therefore works on the backing path.
 *
 * The user id is part of that mapping rather than a constant: on a secondary user or a work profile
 * the volume is /data/media/10, and a hardcoded 0 would aim every privileged chmod at another
 * user's files.
 */
class StoragePaths(private val userId: Int) {

	/** Backing root for this user; every target must resolve to somewhere underneath it. */
	val backingRoot: String = BACKING_PREFIX + userId

	/** The path form the Storage Access Framework and MediaStore speak in. */
	val emulatedRoot: String = EMULATED_PREFIX + userId

	private val emulatedRoots = listOf(emulatedRoot, SDCARD)

	fun toBacking(path: String): String {
		val clean = normalise(path)
		if (clean == backingRoot || clean.startsWith("$backingRoot/")) return clean
		for (root in emulatedRoots) {
			if (clean == root) return backingRoot
			if (clean.startsWith("$root/")) return backingRoot + clean.removePrefix(root)
		}
		throw IllegalArgumentException("path is not on this user's primary storage: $path")
	}

	fun toEmulated(backingPath: String): String {
		val clean = normalise(backingPath)
		return if (clean == backingRoot || clean.startsWith("$backingRoot/")) {
			emulatedRoot + clean.removePrefix(backingRoot)
		} else {
			clean
		}
	}

	/** Guards against a recursive operation escaping to the storage root or above. */
	fun isSafeTarget(backingPath: String): Boolean {
		val clean = runCatching { normalise(backingPath) }.getOrElse { return false }
		return clean.startsWith("$backingRoot/") && clean.length > backingRoot.length + 1
	}

	/**
	 * Maps [emulatedPath] onto the backing store, then proves against the real filesystem that the
	 * result is still under [backingRoot] once symlinks are followed. Null if the path belongs to
	 * another volume or user, escapes the root, or does not exist.
	 *
	 * The lexical mapping alone is not enough: a prefix test can be satisfied by a path that walks
	 * back out through a symlink, and whatever is on the other end would then be handed to a root
	 * chmod.
	 */
	suspend fun resolveTarget(emulatedPath: String): String? {
		val backing = runCatching { toBacking(emulatedPath) }.getOrNull() ?: return null
		if (!isSafeTarget(backing)) return null
		val real = RootShell.realPath(backing) ?: return null
		return real.takeIf { isSafeTarget(it) }
	}

	/**
	 * Rejects traversal and empty segments outright rather than resolving them. Collapsing `..`
	 * here would let a caller pass a path that leaves the storage root and comes back, which the
	 * prefix checks above would then wave through.
	 */
	private fun normalise(path: String): String {
		require(path.startsWith('/')) { "path must be absolute: $path" }
		val clean = path.trimEnd('/').ifEmpty { "/" }
		val segments = clean.split('/').drop(1)
		require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
			"path contains traversal or empty segments: $path"
		}
		return clean
	}

	companion object {
		private const val BACKING_PREFIX = "/data/media/"
		private const val EMULATED_PREFIX = "/storage/emulated/"
		@Suppress("SdCardPath")
		private const val SDCARD = "/sdcard"
		private const val PER_USER_RANGE = 100_000

		/** Paths for the Android user this process is running as. */
		fun forCurrentUser(): StoragePaths = StoragePaths(Process.myUid() / PER_USER_RANGE)
	}
}
