package io.github.melastore.shelf.root

import android.os.Process

/**
 * Translates between the emulated storage an app sees and the backing store where permission bits
 * are real.
 *
 * On Android 11+ the primary volume at /storage/emulated/<user> (aka /sdcard) is a FUSE mount.
 * chmod there hits the FUSE daemon, which synthesises its bits from mount options and throws the
 * change away. The same files live at /data/media/<user>, where the bits persist across reboots,
 * so every privileged operation works on the backing path.
 *
 * The user id is part of the mapping, not a constant: on a secondary user or a work profile the
 * volume is /data/media/10, and a hardcoded 0 would aim every chmod at another user's files.
 */
class StoragePaths private constructor(
	val backingRoot: String,
	val emulatedRoot: String,
	private val root: RootCommandRunner,
) {

	constructor(userId: Int) : this(
		backingRoot = BACKING_PREFIX + userId,
		emulatedRoot = EMULATED_PREFIX + userId,
		root = RootShell,
	)

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

	/** Stops a recursive operation escaping to the storage root or above. */
	fun isSafeTarget(backingPath: String): Boolean {
		val clean = runCatching { normalise(backingPath) }.getOrElse { return false }
		return clean.startsWith("$backingRoot/") && clean.length > backingRoot.length + 1
	}

	/**
	 * Maps [emulatedPath] onto the backing store, then checks against the real filesystem that the
	 * result is still under [backingRoot] with symlinks followed. Null when the path is on another
	 * volume or user, escapes the root, or does not exist.
	 *
	 * The lexical mapping is not enough on its own: a prefix test passes for a path that walks back
	 * out through a symlink, and whatever is on the far end would be handed to a root chmod.
	 */
	suspend fun resolveTarget(emulatedPath: String): String? {
		val backing = runCatching { toBacking(emulatedPath) }.getOrNull() ?: return null
		if (!isSafeTarget(backing)) return null
		val real = root.run("realpath ${RootShell.quote(backing)}")
			.takeIf { it.ok }?.stdout?.firstOrNull()?.trim()?.ifEmpty { null } ?: return null
		return real.takeIf { isSafeTarget(it) }
	}

	/**
	 * Rejects traversal and empty segments rather than resolving them. Collapsing `..` here would let
	 * a caller pass a path that leaves the storage root and comes back, which the prefix checks above
	 * would then wave through.
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

		/** Paths for the Android user this process runs as. */
		fun forCurrentUser(): StoragePaths = StoragePaths(Process.myUid() / PER_USER_RANGE)

		internal fun forTest(backingRoot: String, emulatedRoot: String, root: RootCommandRunner = RootShell,): StoragePaths =
			StoragePaths(backingRoot.trimEnd('/'), emulatedRoot.trimEnd('/'), root)
	}
}
