package io.github.melastore.shelf.data

import android.content.Context
import android.net.Uri
import io.github.melastore.shelf.root.RootShell
import io.github.melastore.shelf.root.StoragePaths
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file to lock or unlock, in whichever form the backend can address it. */
data class LockTarget(val path: String, val documentUri: Uri?)

/**
 * Reads and writes the leading slice of a file in place, leaving the rest of it alone.
 *
 * Only the slice is ever touched, so locking a two-gigabyte video costs the same as locking a
 * thumbnail. What differs between implementations is how the bytes are reached, not how many.
 */
interface HeaderIo {

	suspend fun isAvailable(): Boolean

	/** Whether this backend can reach [target] at all. */
	fun canAddress(target: LockTarget): Boolean

	/** The path to record for [target], which for root is the backing path behind it. */
	suspend fun resolve(target: LockTarget): String?

	suspend fun size(target: LockTarget): Long?

	suspend fun readHead(target: LockTarget, len: Int): ByteArray?

	suspend fun writeHead(target: LockTarget, bytes: ByteArray): Boolean

	suspend fun readTail(target: LockTarget, len: Int): ByteArray?

	suspend fun append(target: LockTarget, bytes: ByteArray): Boolean

	suspend fun truncate(target: LockTarget, size: Long): Boolean
}

/**
 * The unprivileged path: a document the user picked, opened read-write.
 *
 * The descriptor is seekable, so the first slice can be read and written straight back at offset
 * zero without disturbing anything after it — the same operation `dd conv=notrunc` performs, without
 * the shell, the Base64 round trip or the staging file.
 */
class SafHeaderIo(context: Context) : HeaderIo {

	private val appContext = context.applicationContext
	private val resolver get() = appContext.contentResolver

	override suspend fun isAvailable(): Boolean = true

	override fun canAddress(target: LockTarget): Boolean = target.documentUri != null

	override suspend fun resolve(target: LockTarget): String? = target.path

	override suspend fun size(target: LockTarget): Long? = withContext(Dispatchers.IO) {
		runCatching {
			resolver.openFileDescriptor(target.documentUri!!, "r")?.use { it.statSize }
		}.getOrNull()?.takeIf { it >= 0 }
	}

	override suspend fun readHead(target: LockTarget, len: Int): ByteArray? = withContext(Dispatchers.IO) {
		runCatching {
			resolver.openFileDescriptor(target.documentUri!!, "r")?.use { descriptor ->
				val buffer = ByteBuffer.allocate(len)
				FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
					var position = 0L
					while (buffer.hasRemaining()) {
						val read = channel.read(buffer, position)
						if (read <= 0) break
						position += read
					}
				}
				buffer.array().copyOf(buffer.position())
			}
		}.getOrNull()
	}

	override suspend fun writeHead(target: LockTarget, bytes: ByteArray): Boolean =
		withContext(Dispatchers.IO) {
			runCatching {
				// "rw" rather than "w": the latter truncates, and everything past the header has to
				// survive untouched.
				resolver.openFileDescriptor(target.documentUri!!, "rw")?.use { descriptor ->
					val buffer = ByteBuffer.wrap(bytes)
					FileOutputStream(descriptor.fileDescriptor).channel.use { channel ->
						var position = 0L
						while (buffer.hasRemaining()) {
							position += channel.write(buffer, position)
						}
						channel.force(true)
					}
					true
				} ?: false
			}.getOrDefault(false)
			}

	override suspend fun readTail(target: LockTarget, len: Int): ByteArray? = withContext(Dispatchers.IO) {
		runCatching {
			resolver.openFileDescriptor(target.documentUri!!, "r")?.use { descriptor ->
				FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
					if (channel.size() < len) return@use null
					val buffer = ByteBuffer.allocate(len)
					var position = channel.size() - len
					while (buffer.hasRemaining()) {
						val read = channel.read(buffer, position)
						if (read <= 0) return@use null
						position += read
					}
					buffer.array()
				}
			}
		}.getOrNull()
	}

	override suspend fun append(target: LockTarget, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
		runCatching {
			resolver.openFileDescriptor(target.documentUri!!, "rw")?.use { descriptor ->
				FileOutputStream(descriptor.fileDescriptor).channel.use { channel ->
					val buffer = ByteBuffer.wrap(bytes)
					var position = channel.size()
					while (buffer.hasRemaining()) position += channel.write(buffer, position)
					channel.force(true)
				}
				true
			} ?: false
		}.getOrDefault(false)
	}

	override suspend fun truncate(target: LockTarget, size: Long): Boolean = withContext(Dispatchers.IO) {
		runCatching {
			resolver.openFileDescriptor(target.documentUri!!, "rw")?.use { descriptor ->
				FileOutputStream(descriptor.fileDescriptor).channel.use { channel ->
					channel.truncate(size)
					channel.force(true)
				}
				true
			} ?: false
		}.getOrDefault(false)
	}
}

/**
 * The privileged path, kept for rooted devices: it reaches files no document grant covers, and works
 * from a path alone when a persisted grant has been lost.
 */
class RootHeaderIo(private val paths: StoragePaths) : HeaderIo {

	override suspend fun isAvailable(): Boolean = RootShell.isAvailable()

	override fun canAddress(target: LockTarget): Boolean = true

	override suspend fun resolve(target: LockTarget): String? =
		if (target.path.startsWith(paths.backingRoot)) {
			target.path.takeIf { paths.isSafeTarget(it) }
		} else {
			paths.resolveTarget(target.path)
		}

	override suspend fun size(target: LockTarget): Long? {
		val result = RootShell.run("stat -c %s ${RootShell.quote(target.path)}")
		return result.stdout.firstOrNull()?.trim()?.toLongOrNull()?.takeIf { result.ok }
	}

	/** Reads with `head -c`, which loops until it has the requested count; `dd` can come up short. */
	override suspend fun readHead(target: LockTarget, len: Int): ByteArray? {
		val result = RootShell.run("head -c $len ${RootShell.quote(target.path)} | base64")
		if (!result.ok) return null
		val encoded = result.stdout.joinToString("").filterNot { it.isWhitespace() }
		return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
	}

	/**
	 * Writes [bytes] over the start of the file without truncating the rest. The payload is delivered
	 * to the root shell as Base64 through a heredoc on stdin, which keeps binary data off the command
	 * line and clear of any argument-length limit.
	 *
	 * The staging file is unique, mode 600, and removed by a trap rather than a trailing `rm`: under
	 * `set -e` a failing `dd` skips everything after it, and on the unlock path that file holds the
	 * decrypted header. Its size is checked before the copy so a truncated staging file can never be
	 * the thing that lands on the target.
	 */
	override suspend fun writeHead(target: LockTarget, bytes: ByteArray): Boolean {
		return staged(bytes) { temp ->
			"dd if=$temp of=${RootShell.quote(target.path)} bs=${bytes.size} count=1 conv=notrunc 2>/dev/null"
		}
	}

	override suspend fun readTail(target: LockTarget, len: Int): ByteArray? {
		val result = RootShell.run("tail -c $len ${RootShell.quote(target.path)} | base64")
		if (!result.ok) return null
		val encoded = result.stdout.joinToString("").filterNot { it.isWhitespace() }
		return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
	}

	override suspend fun append(target: LockTarget, bytes: ByteArray): Boolean = staged(bytes) { temp ->
		"cat $temp >> ${RootShell.quote(target.path)}"
	}

	override suspend fun truncate(target: LockTarget, size: Long): Boolean =
		RootShell.run("truncate -s $size ${RootShell.quote(target.path)}", "sync").ok

	private suspend fun staged(bytes: ByteArray, command: (String) -> String): Boolean {
		val encoded = Base64.getEncoder().encodeToString(bytes)
		val script = buildString {
			append("tmp=\$(mktemp $SLICE_TEMPLATE)\n")
			append("trap 'rm -f \"\$tmp\"' EXIT\n")
			append("chmod 600 \"\$tmp\"\n")
			append("base64 -d > \"\$tmp\" <<'$HEREDOC'\n")
			append(encoded).append('\n')
			append("$HEREDOC\n")
			append("[ \"\$(stat -c %s \"\$tmp\")\" = \"${bytes.size}\" ]\n")
			append(command("\"\$tmp\"")).append('\n')
			append("sync\n")
		}
		return RootShell.run(script).ok
	}

	private companion object {
		const val SLICE_TEMPLATE = "/data/local/tmp/.shelf_XXXXXX"
		const val HEREDOC = "SHELF_SLICE_EOF"
	}
}
