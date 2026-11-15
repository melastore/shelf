package io.github.melastore.shelf.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileDescriptor
import java.io.InputStream
import java.security.SecureRandom
import java.util.Locale

/**
 * Decrypted copies of files the owner shared out of a hidden folder. Each one sits in an anonymous
 * memfd, never on disk, and all of them go when the space locks. An app already reading one keeps
 * its own descriptor, so a lock does not cut a send off halfway.
 */
internal object ShareOut {

	private class Entry(val name: String, val mime: String, val size: Long, val memory: FileDescriptor)

	private val entries = mutableMapOf<String, Entry>()
	private val random = SecureRandom()

	// Bumped by every clear. A copy started before a lock is thrown away rather than kept after it.
	private var generation = 0L

	/**
	 * Copies [source] into memory and returns the uri to hand out, or null if it would not fit or a
	 * clear came since [generation] was handed out.
	 */
	fun put(context: Context, name: String, size: Long, source: InputStream, generation: Long): Uri? {
		if (size !in 0..MAX_BYTES) return null
		val memory = runCatching { Os.memfd_create("shelf-share", 0) }.getOrNull() ?: return null
		val buffer = ByteArray(COPY_BUFFER)
		val copied = try {
			var total = 0L
			while (true) {
				val read = source.read(buffer)
				if (read < 0) break
				if (stale(generation)) return close(memory)
				total += read
				if (total > MAX_BYTES) return close(memory)
				var written = 0
				while (written < read) {
					val count = Os.write(memory, buffer, written, read - written)
					if (count <= 0) return close(memory)
					written += count
				}
			}
			total
		} catch (_: Exception) {
			return close(memory)
		} finally {
			buffer.fill(0)
		}
		val token = ByteArray(TOKEN_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
		val kept = synchronized(entries) {
			(this.generation == generation).also { if (it) entries[token] = Entry(name, mimeOf(name), copied, memory) }
		}
		if (!kept) return close(memory)
		return Uri.Builder()
			.scheme("content")
			.authority(authority(context))
			.appendPath(token)
			.appendPath(name)
			.build()
	}

	/** Drops every copy and returns the generation a new share goes under. */
	fun clear(): Long {
		val (gone, next) = synchronized(entries) {
			generation++
			entries.values.toList().also { entries.clear() } to generation
		}
		gone.forEach { runCatching { Os.close(it.memory) } }
		return next
	}

	private fun stale(generation: Long): Boolean = synchronized(entries) { this.generation != generation }

	fun mimeOf(name: String): String = MimeTypeMap.getSingleton()
		.getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
		?: "application/octet-stream"

	private fun close(memory: FileDescriptor): Uri? {
		runCatching { Os.close(memory) }
		return null
	}

	private fun entry(uri: Uri): Entry? {
		val token = uri.pathSegments.firstOrNull() ?: return null
		return synchronized(entries) { entries[token] }
	}

	fun name(uri: Uri): String? = entry(uri)?.name
	fun size(uri: Uri): Long? = entry(uri)?.size
	fun type(uri: Uri): String? = entry(uri)?.mime

	/**
	 * A new open of the memfd rather than a dup: a dup shares the read position, so a second reader,
	 * or the same app opening twice, started at the end.
	 */
	fun open(uri: Uri): ParcelFileDescriptor? {
		val entry = entry(uri) ?: return null
		val fd = runCatching { ParcelFileDescriptor.dup(entry.memory) }.getOrNull() ?: return null
		return try {
			ParcelFileDescriptor.open(File("/proc/self/fd/${fd.fd}"), ParcelFileDescriptor.MODE_READ_ONLY)
		} catch (_: Exception) {
			null
		} finally {
			runCatching { fd.close() }
		}
	}

	private fun authority(context: Context) = context.packageName + ".shareout"

	/** All of a share together, so a folder of videos cannot take the phone's memory with it. */
	const val MAX_BYTES = 256L * 1024 * 1024
	private const val COPY_BUFFER = 64 * 1024
	private const val TOKEN_BYTES = 16
}

/** Serves [ShareOut]'s copies to whichever app was granted the uri. Read-only. */
class ShareOutProvider : ContentProvider() {

	override fun onCreate(): Boolean = true

	override fun getType(uri: Uri): String? = ShareOut.type(uri)

	override fun query(
		uri: Uri,
		projection: Array<out String>?,
		selection: String?,
		selectionArgs: Array<out String>?,
		sortOrder: String?,
	): Cursor? {
		val name = ShareOut.name(uri) ?: return null
		val size = ShareOut.size(uri) ?: return null
		val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }
			?.takeIf { it.isNotEmpty() }
			?: listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
		return MatrixCursor(columns.toTypedArray(), 1).apply {
			addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) name else size })
		}
	}

	override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
		if (mode != "r") throw SecurityException("read only")
		return ShareOut.open(uri) ?: throw java.io.FileNotFoundException()
	}

	override fun insert(uri: Uri, values: ContentValues?): Uri? = null
	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
