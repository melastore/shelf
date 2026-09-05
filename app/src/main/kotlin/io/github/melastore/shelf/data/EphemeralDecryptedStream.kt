package io.github.melastore.shelf.data

import java.io.InputStream

/**
 * Streams the original plaintext bytes of a locked file by combining the in-memory decrypted head
 * slice with the unencrypted remainder read directly from the target, without modifying the file on disk.
 */
class EphemeralDecryptedStream(
	private val target: LockTarget,
	private val head: ByteArray,
	private val totalSize: Long,
) : InputStream() {

	private var position: Long = 0L

	override fun read(): Int {
		if (position >= totalSize) return -1
		val b = if (position < head.size) {
			head[position.toInt()].toInt() and 0xFF
		} else {
			target.read(position, 1).firstOrNull()?.let { it.toInt() and 0xFF } ?: -1
		}
		if (b != -1) position++
		return b
	}

	override fun read(b: ByteArray, off: Int, len: Int): Int {
		if (position >= totalSize) return -1
		val toRead = minOf(len.toLong(), totalSize - position).toInt()
		if (toRead <= 0) return 0
		var readCount = 0
		if (position < head.size) {
			val headAvailable = minOf(toRead, head.size - position.toInt())
			System.arraycopy(head, position.toInt(), b, off, headAvailable)
			position += headAvailable
			readCount += headAvailable
		}
		if (readCount < toRead) {
			val remaining = toRead - readCount
			val chunk = target.read(position, remaining)
			if (chunk.isNotEmpty()) {
				System.arraycopy(chunk, 0, b, off + readCount, chunk.size)
				position += chunk.size
				readCount += chunk.size
			}
		}
		return if (readCount > 0) readCount else -1
	}

	override fun available(): Int = (totalSize - position).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
