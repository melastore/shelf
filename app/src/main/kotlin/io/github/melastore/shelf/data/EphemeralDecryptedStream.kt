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

	// Past the head every target read opens the file, so a caller reading byte by byte without its
	// own buffer used to cost an open per byte. Read ahead for it instead.
	private var ahead = byteArrayOf()
	private var aheadStart = 0L

	override fun read(): Int {
		if (position >= totalSize) return -1
		if (position < head.size) return head[position++.toInt()].toInt() and 0xFF
		if (position < aheadStart || position >= aheadStart + ahead.size) {
			ahead = target.read(position, minOf(READ_AHEAD.toLong(), totalSize - position).toInt())
			aheadStart = position
			if (ahead.isEmpty()) return -1
		}
		return ahead[(position++ - aheadStart).toInt()].toInt() and 0xFF
	}

	private companion object {
		const val READ_AHEAD = 64 * 1024
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
