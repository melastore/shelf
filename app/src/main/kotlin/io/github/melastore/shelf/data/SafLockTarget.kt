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
 * A [LockTarget] over a document the user has granted access to, so content locking is available on
 * the rename method as well — which matters more than the other two, because renaming is the default
 * and the one a file manager can see straight through.
 *
 * The descriptor is opened per operation rather than held. A lock touches a file four times, so the
 * cost is small, and the alternative is a descriptor left open across a long pass over thousands of
 * files with nothing but a crash to close them.
 *
 * The mode is always `rw`, never `rwt`: `t` truncates on open, which on the very first write would
 * discard the file this whole exercise exists to preserve.
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
		 * Every file under [folder], as targets. Directories are walked; anything the provider will not
		 * describe is left out rather than guessed at.
		 */
		fun targetsUnder(
			resolver: ContentResolver,
			folder: DocumentFile,
			limit: Int = ContentLocker.MAX_FILES + 1,
		): List<LockTarget> {
			val found = mutableListOf<LockTarget>()
			collect(resolver, folder, found, limit, depth = 0)
			return found
		}

		private fun collect(
			resolver: ContentResolver,
			folder: DocumentFile,
			found: MutableList<LockTarget>,
			limit: Int,
			depth: Int,
		) {
			if (depth > MAX_DEPTH || found.size >= limit) return
			for (child in runCatching { folder.listFiles() }.getOrNull().orEmpty()) {
				if (found.size >= limit) return
				when {
					child.isDirectory -> collect(resolver, child, found, limit, depth + 1)
					child.isFile -> found += SafLockTarget(resolver, child.uri)
				}
			}
		}

		private const val MAX_DEPTH = 8
	}
}
