package io.github.melastore.shelf.data

import io.github.melastore.shelf.crypto.HeaderCipher
import java.nio.ByteBuffer

/**
 * Recovery data appended to a locked file.
 *
 * It holds a complete, authenticated copy of the bytes that are about to be overwritten, which is
 * what makes locking survivable. It is written and flushed *before* the head of the file is touched,
 * so a process killed halfway through the overwrite leaves a file whose original head can still be
 * recovered from its own tail. Clearing Shelf's app data cannot strand a file either: everything
 * needed to reverse the transform except the passphrase travels with the file itself.
 */
data class LockTrailer(
	val cipherText: ByteArray,
	val salt: ByteArray,
	val nonce: ByteArray,
	val tag: ByteArray,
	val originalSize: Long,
) {
	// ByteArray identity would make two equal trailers compare unequal, which is a trap in tests.
	override fun equals(other: Any?): Boolean = other is LockTrailer &&
		cipherText.contentEquals(other.cipherText) && salt.contentEquals(other.salt) &&
		nonce.contentEquals(other.nonce) && tag.contentEquals(other.tag) &&
		originalSize == other.originalSize

	override fun hashCode(): Int = listOf(
		cipherText.contentHashCode(),
		salt.contentHashCode(),
		nonce.contentHashCode(),
		tag.contentHashCode(),
		originalSize.hashCode(),
	).fold(0) { acc, part -> acc * 31 + part }
}

object LockTrailerCodec {

	const val FOOTER_SIZE = 64
	const val MAX_SLICE = 1 shl 20

	private val magic = "SHLCK001".toByteArray(Charsets.US_ASCII)

	fun encode(trailer: LockTrailer): ByteArray {
		require(trailer.cipherText.isNotEmpty() && trailer.cipherText.size <= MAX_SLICE)
		require(trailer.salt.size == HeaderCipher.SALT_LENGTH)
		require(trailer.nonce.size == HeaderCipher.NONCE_LENGTH)
		require(trailer.tag.size == HeaderCipher.TAG_LENGTH)
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

	/**
	 * The complete trailer length described by a [FOOTER_SIZE]-byte footer, or null if these are not
	 * the last bytes of a locked file. This is the only thing that identifies a locked file, so it is
	 * checked before anything is read, let alone written.
	 */
	fun totalLength(footer: ByteArray): Int? {
		if (footer.size != FOOTER_SIZE) return null
		val buffer = ByteBuffer.wrap(footer)
		buffer.position(HeaderCipher.SALT_LENGTH + HeaderCipher.NONCE_LENGTH + HeaderCipher.TAG_LENGTH)
		val sliceLength = buffer.int
		val originalSize = buffer.long
		val actualMagic = ByteArray(magic.size).also(buffer::get)
		if (!actualMagic.contentEquals(magic)) return null
		if (sliceLength !in 1..MAX_SLICE || originalSize < sliceLength) return null
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
		val salt = ByteArray(HeaderCipher.SALT_LENGTH).also(buffer::get)
		val nonce = ByteArray(HeaderCipher.NONCE_LENGTH).also(buffer::get)
		val tag = ByteArray(HeaderCipher.TAG_LENGTH).also(buffer::get)
		if (buffer.int != sliceLength) return null
		return LockTrailer(cipherText, salt, nonce, tag, buffer.long)
	}
}
