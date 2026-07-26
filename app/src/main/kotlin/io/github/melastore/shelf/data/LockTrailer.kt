package io.github.melastore.shelf.data

import java.nio.ByteBuffer

/** Recovery data appended to a locked file so clearing Shelf's app data cannot strand it. */
data class LockTrailer(
	val cipherText: ByteArray,
	val salt: ByteArray,
	val nonce: ByteArray,
	val tag: ByteArray,
	val originalSize: Long,
)

object LockTrailerCodec {

	const val FOOTER_SIZE = 64
	private val magic = "SHLCK001".toByteArray(Charsets.US_ASCII)

	fun encode(trailer: LockTrailer): ByteArray {
		require(trailer.cipherText.isNotEmpty())
		require(trailer.salt.size == 16)
		require(trailer.nonce.size == 12)
		require(trailer.tag.size == 16)
		require(trailer.originalSize >= trailer.cipherText.size)

		return ByteBuffer.allocate(trailer.cipherText.size + FOOTER_SIZE)
			.put(trailer.cipherText)
			.put(trailer.salt)
			.put(trailer.nonce)
			.put(trailer.tag)
			.putInt(trailer.cipherText.size)
			.putLong(trailer.originalSize)
			.put(magic)
			.array()
	}

	/** Returns the complete trailer length described by a 64-byte footer. */
	fun totalLength(footer: ByteArray): Int? {
		if (footer.size != FOOTER_SIZE) return null
		val buffer = ByteBuffer.wrap(footer)
		buffer.position(44)
		val sliceLength = buffer.int
		val originalSize = buffer.long
		val actualMagic = ByteArray(magic.size).also(buffer::get)
		if (!actualMagic.contentEquals(magic)) return null
		if (sliceLength <= 0 || sliceLength > MAX_SLICE || originalSize < sliceLength) return null
		return sliceLength + FOOTER_SIZE
	}

	fun decode(bytes: ByteArray): LockTrailer? {
		if (bytes.size < FOOTER_SIZE) return null
		val footer = bytes.copyOfRange(bytes.size - FOOTER_SIZE, bytes.size)
		val total = totalLength(footer) ?: return null
		if (bytes.size != total) return null

		val sliceLength = total - FOOTER_SIZE
		val buffer = ByteBuffer.wrap(bytes)
		val cipherText = ByteArray(sliceLength).also(buffer::get)
		val salt = ByteArray(16).also(buffer::get)
		val nonce = ByteArray(12).also(buffer::get)
		val tag = ByteArray(16).also(buffer::get)
		if (buffer.int != sliceLength) return null
		val originalSize = buffer.long
		return LockTrailer(cipherText, salt, nonce, tag, originalSize)
	}

	private const val MAX_SLICE = 1 shl 20
}
