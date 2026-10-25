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
class SafLockTarget(val resolver: ContentResolver, val uri: Uri, override val name: String = "",) : LockTarget {

	override fun size(): Long = descriptor("r") { pfd ->
		pfd.statSize.takeIf { it >= 0 } ?: throw IOException("provider did not report a size for $uri")
	}

	override fun read(offset: Long, length: Int): ByteArray = descriptor("r") { pfd ->
		FileInputStream(pfd.fileDescriptor).use { stream ->
			val channel = stream.channel
			val buffer = ByteBuffer.allocate(length)
			var position = offset
			while (buffer.hasRemaining()) {
				val read = channel.read(buffer, position)
				if (read < 0) throw IOException("unexpected end of $uri at $position")
				position += read
			}
			buffer.array()
		}
	}

	override fun write(offset: Long, bytes: ByteArray) = descriptor("rw") { pfd ->
		FileOutputStream(pfd.fileDescriptor).use { stream ->
			val channel = stream.channel
			val buffer = ByteBuffer.wrap(bytes)
			var position = offset
			while (buffer.hasRemaining()) {
				position += channel.write(buffer, position)
			}
		}
	}

	override fun truncate(size: Long) = descriptor("rw") { pfd ->
		FileOutputStream(pfd.fileDescriptor).use { stream ->
			stream.channel.truncate(size)
		}
		Unit
	}

	override fun sync() = descriptor("rw") { pfd -> pfd.fileDescriptor.sync() }

	override fun lastModified(): Long = runCatching {
		resolver.query(
			uri,
			arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED),
			null,
			null,
			null,
		)?.use { cursor ->
			if (cursor.moveToFirst()) {
				val index = cursor.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED)
				if (index >= 0) cursor.getLong(index) else 0L
			} else {
				0L
			}
		} ?: 0L
	}.getOrDefault(0L)

	override fun <T> withSession(mode: String, block: (LockTarget) -> T): T =
		open(mode)?.use { pfd -> block(OpenDocument(pfd)) }
			?: throw IOException("could not open $uri for $mode")

	/**
	 * One descriptor, serving every call until the session ends. The streams are never closed: both
	 * wrap the descriptor the session owns, and closing either would shut it for what follows.
	 */
	private inner class OpenDocument(private val pfd: ParcelFileDescriptor) : LockTarget {
		override val name: String get() = this@SafLockTarget.name

		private val input by lazy { FileInputStream(pfd.fileDescriptor) }
		private val output by lazy { FileOutputStream(pfd.fileDescriptor) }

		override fun size(): Long =
			pfd.statSize.takeIf { it >= 0 } ?: throw IOException("provider did not report a size for $uri")

		override fun read(offset: Long, length: Int): ByteArray {
			val channel = input.channel
			val buffer = ByteBuffer.allocate(length)
			var position = offset
			while (buffer.hasRemaining()) {
				val read = channel.read(buffer, position)
				if (read < 0) throw IOException("unexpected end of $uri at $position")
				position += read
			}
			return buffer.array()
		}

		override fun write(offset: Long, bytes: ByteArray) {
			val channel = output.channel
			val buffer = ByteBuffer.wrap(bytes)
			var position = offset
			while (buffer.hasRemaining()) {
				position += channel.write(buffer, position)
			}
		}

		override fun truncate(size: Long) {
			output.channel.truncate(size)
		}

		override fun sync() = pfd.fileDescriptor.sync()
		override fun lastModified(): Long = this@SafLockTarget.lastModified()
		override fun setLastModified(time: Long): Boolean = this@SafLockTarget.setLastModified(time)
		override fun <T> withSession(mode: String, block: (LockTarget) -> T): T = block(this)
	}

	private inline fun <T> descriptor(mode: String, block: (ParcelFileDescriptor) -> T): T =
		open(mode)?.use(block) ?: throw IOException("could not open $uri for $mode")

	private fun open(mode: String): ParcelFileDescriptor? =
		if (mode == "r") resolver.openUnredacted(uri) else resolver.openFileDescriptor(uri, mode)

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
			skip: (String) -> Boolean = { false },
		): List<LockTarget>? {
			val found = mutableListOf<LockTarget>()
			return found.takeIf { collect(resolver, folder.uri, it, limit, skip, depth = 0) }
		}

		private fun collect(
			resolver: ContentResolver,
			folder: Uri,
			found: MutableList<LockTarget>,
			limit: Int,
			skip: (String) -> Boolean,
			depth: Int,
		): Boolean {
			if (found.size >= limit) return true
			if (depth > MAX_DEPTH) return false
			val children = SafTree.children(resolver, folder) ?: return false
			for (child in children) {
				if (found.size >= limit) return true
				when {
					child.isDirectory -> if (!collect(resolver, child.uri, found, limit, skip, depth + 1)) return false
					child.isFile && !skip(child.name) -> found += SafLockTarget(resolver, child.uri, child.name)
				}
			}
			return true
		}

		private const val MAX_DEPTH = 8
	}
}
