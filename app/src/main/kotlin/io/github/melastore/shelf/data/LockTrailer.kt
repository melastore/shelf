package io.github.melastore.shelf.data

import io.github.melastore.shelf.crypto.HeaderCipher
import java.nio.ByteBuffer

/**
 * Recovery data appended to a locked file.
 *
 * Holds an authenticated copy of the bytes about to be overwritten, written and flushed before the
 * head of the file is touched. A process killed mid-overwrite leaves a file whose original head is
 * still recoverable from its own tail, and everything needed to reverse the transform except the
 * passphrase travels with the file, so clearing app data cannot strand it either.
 */
data class LockTrailer(
	val cipherText: ByteArray,
	val salt: ByteArray,
	val nonce: ByteArray,
	val tag: ByteArray,
	val originalSize: Long,
	val padding: ByteArray = byteArrayOf(),
) {
	// ByteArray identity would make two equal trailers compare unequal, which trips up the tests.
	override fun equals(other: Any?): Boolean = other is LockTrailer &&
		cipherText.contentEquals(other.cipherText) && salt.contentEquals(other.salt) &&
		nonce.contentEquals(other.nonce) && tag.contentEquals(other.tag) &&
		originalSize == other.originalSize && padding.contentEquals(other.padding)

	override fun hashCode(): Int = listOf(
		cipherText.contentHashCode(),
		salt.contentHashCode(),
		nonce.contentHashCode(),
		tag.contentHashCode(),
		originalSize.hashCode(),
		padding.contentHashCode(),
	).fold(0) { acc, part -> acc * 31 + part }
}

object LockTrailerCodec {

	const val FOOTER_SIZE = 64
	const val FOOTER_SIZE_V2 = 68
	const val MAX_SLICE = 1 shl 20
	const val MAX_PADDING = 64 * 1024

	private val MAGIC_V1 = "SHLCK001".toByteArray(Charsets.US_ASCII)
	private val MAGIC_V2 = "SHLCK002".toByteArray(Charsets.US_ASCII)

	fun encode(trailer: LockTrailer): ByteArray {
		require(trailer.cipherText.isNotEmpty() && trailer.cipherText.size <= MAX_SLICE)
		require(trailer.salt.size == HeaderCipher.SALT_LENGTH)
		require(trailer.nonce.size == HeaderCipher.NONCE_LENGTH)
		require(trailer.tag.size == HeaderCipher.TAG_LENGTH)
		require(trailer.originalSize >= trailer.cipherText.size)
		require(trailer.padding.size <= MAX_PADDING)

		if (trailer.padding.isEmpty()) {
			return ByteBuffer.allocate(trailer.cipherText.size + FOOTER_SIZE)
				.put(trailer.cipherText)
				.put(trailer.salt)
				.put(trailer.nonce)
				.put(trailer.tag)
				.putInt(trailer.cipherText.size)
				.putLong(trailer.originalSize)
				.put(MAGIC_V1)
				.array()
		}

		return ByteBuffer.allocate(trailer.cipherText.size + trailer.padding.size + FOOTER_SIZE_V2)
			.put(trailer.cipherText)
			.put(trailer.padding)
			.put(trailer.salt)
			.put(trailer.nonce)
			.put(trailer.tag)
			.putInt(trailer.cipherText.size)
			.putInt(trailer.padding.size)
			.putLong(trailer.originalSize)
			.put(MAGIC_V2)
			.array()
	}

	/**
	 * Trailer length described by a footer, or null if these are not the last
	 * bytes of a locked file. Checked before anything is read, let alone written.
	 */
	fun totalLength(footer: ByteArray): Int? {
		if (footer.size < 8) return null
		val magic = footer.copyOfRange(footer.size - 8, footer.size)
		if (magic.contentEquals(MAGIC_V1)) {
			if (footer.size < FOOTER_SIZE) return null
			val footerV1 = footer.copyOfRange(footer.size - FOOTER_SIZE, footer.size)
			val buffer = ByteBuffer.wrap(footerV1)
			buffer.position(HeaderCipher.SALT_LENGTH + HeaderCipher.NONCE_LENGTH + HeaderCipher.TAG_LENGTH)
			val sliceLength = buffer.int
			val originalSize = buffer.long
			val actualMagic = ByteArray(8).also(buffer::get)
			if (!actualMagic.contentEquals(MAGIC_V1)) return null
			if (sliceLength !in 1..MAX_SLICE || originalSize < sliceLength) return null
			return sliceLength + FOOTER_SIZE
		}
		if (magic.contentEquals(MAGIC_V2)) {
			if (footer.size < FOOTER_SIZE_V2) return null
			val footerV2 = footer.copyOfRange(footer.size - FOOTER_SIZE_V2, footer.size)
			val buffer = ByteBuffer.wrap(footerV2)
			buffer.position(HeaderCipher.SALT_LENGTH + HeaderCipher.NONCE_LENGTH + HeaderCipher.TAG_LENGTH)
			val sliceLength = buffer.int
			val paddingLength = buffer.int
			val originalSize = buffer.long
			val actualMagic = ByteArray(8).also(buffer::get)
			if (!actualMagic.contentEquals(MAGIC_V2)) return null
			if (sliceLength !in 1..MAX_SLICE || paddingLength !in 0..MAX_PADDING || originalSize < sliceLength) return null
			return sliceLength + paddingLength + FOOTER_SIZE_V2
		}
		return null
	}

	fun decode(bytes: ByteArray): LockTrailer? {
		if (bytes.size < 8) return null
		val magic = bytes.copyOfRange(bytes.size - 8, bytes.size)
		if (magic.contentEquals(MAGIC_V1)) {
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
		if (magic.contentEquals(MAGIC_V2)) {
			if (bytes.size < FOOTER_SIZE_V2) return null
			val footer = bytes.copyOfRange(bytes.size - FOOTER_SIZE_V2, bytes.size)
			val total = totalLength(footer) ?: return null
			if (bytes.size != total) return null

			val buffer = ByteBuffer.wrap(bytes)
			val footerOffset = HeaderCipher.SALT_LENGTH + HeaderCipher.NONCE_LENGTH + HeaderCipher.TAG_LENGTH
			val sliceLength = ByteBuffer.wrap(footer, footerOffset, 4).int
			val paddingLength = ByteBuffer.wrap(footer, footerOffset + 4, 4).int
			if (sliceLength !in 1..MAX_SLICE || paddingLength !in 0..MAX_PADDING) return null
			if (bytes.size != sliceLength + paddingLength + FOOTER_SIZE_V2) return null

			val cipherText = ByteArray(sliceLength).also(buffer::get)
			val padding = ByteArray(paddingLength).also(buffer::get)
			val salt = ByteArray(HeaderCipher.SALT_LENGTH).also(buffer::get)
			val nonce = ByteArray(HeaderCipher.NONCE_LENGTH).also(buffer::get)
			val tag = ByteArray(HeaderCipher.TAG_LENGTH).also(buffer::get)
			if (buffer.int != sliceLength) return null
			if (buffer.int != paddingLength) return null
			return LockTrailer(cipherText, salt, nonce, tag, buffer.long, padding)
		}
		return null
	}
}
