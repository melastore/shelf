package io.github.melastore.shelf.data

import android.media.MediaDataSource
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access [MediaDataSource] that streams original plaintext bytes for in-memory video and
 * audio playback without restoring or writing decrypted data to disk.
 *
 * One handle is opened on the first read past the head and kept for the life of the source. Playback
 * seeks constantly, and reopening a document per chunk puts a Binder round trip in front of every
 * one of them. Bytes land in the caller's buffer, so nothing is copied twice.
 */
class EphemeralMediaDataSource(
	private val target: LockTarget,
	private val head: ByteArray,
	private val totalSize: Long,
) : MediaDataSource() {

	private var randomAccessFile: RandomAccessFile? = null
	private var pfd: ParcelFileDescriptor? = null
	private var safChannel: FileChannel? = null
	private var channelInitialized = false
	private var closed = false

	private fun ensureOpen() {
		if (closed || channelInitialized) return
		channelInitialized = true
		try {
			when (target) {
				is FileLockTarget -> {
					randomAccessFile = RandomAccessFile(target.file, "r")
				}

				is SafLockTarget -> {
					val descriptor = target.resolver.openFileDescriptor(target.uri, "r")
					if (descriptor != null) {
						pfd = descriptor
						safChannel = FileInputStream(descriptor.fileDescriptor).channel
					}
				}
			}
		} catch (_: Exception) {
			// Fall back to target.read()
		}
	}

	@Synchronized
	override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
		if (closed) throw IOException("read after close")
		if (position >= totalSize) return -1
		val toRead = minOf(size.toLong(), totalSize - position).toInt()
		if (toRead <= 0) return 0

		var bytesRead = 0
		if (position < head.size) {
			val headAvailable = minOf(toRead, head.size - position.toInt())
			System.arraycopy(head, position.toInt(), buffer, offset, headAvailable)
			bytesRead += headAvailable
		}

		if (bytesRead < toRead) {
			val targetPos = position + bytesRead
			val targetLen = toRead - bytesRead
			val destOffset = offset + bytesRead

			ensureOpen()

			val raf = randomAccessFile
			val channel = safChannel

			if (raf != null) {
				try {
					raf.seek(targetPos)
					val read = raf.read(buffer, destOffset, targetLen)
					if (read > 0) bytesRead += read
				} catch (e: Exception) {
					if (bytesRead == 0) throw IOException("could not read $targetPos", e)
				}
			} else if (channel != null) {
				try {
					val byteBuf = ByteBuffer.wrap(buffer, destOffset, targetLen)
					var pos = targetPos
					while (byteBuf.hasRemaining()) {
						val read = channel.read(byteBuf, pos)
						if (read <= 0) break
						pos += read
					}
					bytesRead += (pos - targetPos).toInt()
				} catch (e: Exception) {
					if (bytesRead == 0) throw IOException("could not read $targetPos from the provider", e)
				}
			} else {
				try {
					val chunk = target.read(targetPos, targetLen)
					if (chunk.isNotEmpty()) {
						System.arraycopy(chunk, 0, buffer, destOffset, chunk.size)
						bytesRead += chunk.size
					}
				} catch (e: Exception) {
					if (bytesRead == 0) throw IOException("could not read media at $targetPos", e)
				}
			}
		}

		return if (bytesRead > 0) bytesRead else -1
	}

	override fun getSize(): Long = totalSize

	@Synchronized
	override fun close() {
		closed = true
		try {
			randomAccessFile?.close()
		} catch (_: Exception) {}
		randomAccessFile = null

		try {
			safChannel?.close()
		} catch (_: Exception) {}
		safChannel = null

		try {
			pfd?.close()
		} catch (_: Exception) {}
		pfd = null
	}
}
