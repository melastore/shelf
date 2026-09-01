package io.github.melastore.shelf.data

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.documentfile.provider.DocumentFile
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A [LockTarget] over a granted document, so content locking works on the rename method too. That
 * matters most of the three: renaming is the default and the one a file manager sees straight
 * through.
 *
 * Descriptors are opened per operation, not held. A lock touches a file four times, and holding one
 * open across thousands of files leaves nothing but a crash to close them.
 *
 * Always `rw`, never `rwt`: `t` truncates on open and the first write would discard the file.
 */
class SafLockTarget(private val resolver: ContentResolver, private val uri: Uri,) : LockTarget {

	override fun size(): Long = descriptor("r") { pfd ->
		pfd.statSize.takeIf { it >= 0 } ?: throw IOException("provider did not report a size for $uri")
	}

	override fun read(offset: Long, length: Int): ByteArray = descriptor("r") { pfd ->
		val channel = FileInputStream(pfd.fileDescriptor).channel
		val buffer = ByteBuffer.allocate(length)
		var position = offset
		while (buffer.hasRemaining()) {
			val read = channel.read(buffer, position)
			if (read < 0) throw IOException("unexpected end of $uri at $position")
			position += read
		}
		buffer.array()
	}

	override fun write(offset: Long, bytes: ByteArray) = descriptor("rw") { pfd ->
		val channel = FileOutputStream(pfd.fileDescriptor).channel
		val buffer = ByteBuffer.wrap(bytes)
		var position = offset
		while (buffer.hasRemaining()) {
			position += channel.write(buffer, position)
		}
	}

	override fun truncate(size: Long) = descriptor("rw") { pfd ->
		FileOutputStream(pfd.fileDescriptor).channel.truncate(size)
		Unit
	}

	override fun sync() = descriptor("rw") { pfd -> pfd.fileDescriptor.sync() }

	private inline fun <T> descriptor(mode: String, block: (ParcelFileDescriptor) -> T): T =
		resolver.openFileDescriptor(uri, mode)?.use(block)
			?: throw IOException("could not open $uri for $mode")

	companion object {
		/**
		 * Every file under [folder], or null if any part of the tree could not be listed. A partial
		 * walk fails rather than being trimmed: a caller cannot tell a refused folder from an empty
		 * one, and the difference is whether the files end up protected.
		 */
		fun targetsUnder(
			resolver: ContentResolver,
			folder: DocumentFile,
			limit: Int = ContentLocker.MAX_FILES + 1,
		): List<LockTarget>? {
			val found = mutableListOf<LockTarget>()
			return found.takeIf { collect(resolver, folder, it, limit, depth = 0) }
		}

		private fun collect(
			resolver: ContentResolver,
			folder: DocumentFile,
			found: MutableList<LockTarget>,
			limit: Int,
			depth: Int,
		): Boolean {
			if (found.size >= limit) return true
			if (depth > MAX_DEPTH) return false
			val children = runCatching { folder.listFiles() }.getOrNull() ?: return false
			for (child in children) {
				if (found.size >= limit) return true
				when {
					child.isDirectory -> if (!collect(resolver, child, found, limit, depth + 1)) return false
					child.isFile -> found += SafLockTarget(resolver, child.uri)
				}
			}
			return true
		}

		private const val MAX_DEPTH = 8
	}
}
