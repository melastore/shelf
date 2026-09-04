package io.github.melastore.shelf.data

import android.media.MediaDataSource
import java.io.IOException

/**
 * Random-access [MediaDataSource] that streams original plaintext bytes for in-memory video and
 * audio playback without restoring or writing decrypted data to disk.
 */
class EphemeralMediaDataSource(
	private val target: LockTarget,
	private val head: ByteArray,
	private val totalSize: Long,
) : MediaDataSource() {

	@Synchronized
	override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
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
			try {
				val chunk = target.read(targetPos, targetLen)
				if (chunk.isNotEmpty()) {
					System.arraycopy(chunk, 0, buffer, offset + bytesRead, chunk.size)
					bytesRead += chunk.size
				}
			} catch (e: Exception) {
				if (bytesRead == 0) throw IOException("Failed reading media at $targetPos", e)
			}
		}

		return if (bytesRead > 0) bytesRead else -1
	}

	override fun getSize(): Long = totalSize

	override fun close() = Unit
}
