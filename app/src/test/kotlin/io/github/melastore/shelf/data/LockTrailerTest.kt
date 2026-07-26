package io.github.melastore.shelf.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockTrailerTest {

	private val trailer = LockTrailer(
		cipherText = ByteArray(1024) { (it % 251).toByte() },
		salt = ByteArray(16) { it.toByte() },
		nonce = ByteArray(12) { (it + 16).toByte() },
		tag = ByteArray(16) { (it + 32).toByte() },
		originalSize = 4096,
	)

	@Test fun roundTripsARecoveryTrailer() {
		val encoded = LockTrailerCodec.encode(trailer)
		val decoded = LockTrailerCodec.decode(encoded)!!

		assertArrayEquals(trailer.cipherText, decoded.cipherText)
		assertArrayEquals(trailer.salt, decoded.salt)
		assertArrayEquals(trailer.nonce, decoded.nonce)
		assertArrayEquals(trailer.tag, decoded.tag)
		assertEquals(trailer.originalSize, decoded.originalSize)
	}

	@Test fun footerReportsTheWholeTrailerLength() {
		val encoded = LockTrailerCodec.encode(trailer)
		val footer = encoded.copyOfRange(encoded.size - LockTrailerCodec.FOOTER_SIZE, encoded.size)
		assertEquals(encoded.size, LockTrailerCodec.totalLength(footer))
	}

	@Test fun ordinaryFileTailIsNotMistakenForRecoveryData() {
		assertNull(LockTrailerCodec.totalLength(ByteArray(LockTrailerCodec.FOOTER_SIZE)))
		assertNull(LockTrailerCodec.decode(ByteArray(2048)))
	}

	@Test fun truncatedTrailerIsRejected() {
		val encoded = LockTrailerCodec.encode(trailer)
		assertNull(LockTrailerCodec.decode(encoded.copyOf(encoded.size - 1)))
	}
}
