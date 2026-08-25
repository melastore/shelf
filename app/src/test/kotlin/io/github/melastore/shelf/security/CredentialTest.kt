package io.github.melastore.shelf.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialTest {

	@Test
	fun `a pin must be four to twelve digits`() {
		assertEquals(CredentialFault.TOO_SHORT, validate(CredentialKind.PIN, "123"))
		assertEquals(CredentialFault.TOO_LONG, validate(CredentialKind.PIN, "1234567890123"))
		assertEquals(CredentialFault.PIN_NOT_DIGITS, validate(CredentialKind.PIN, "12a4"))
		assertNull(validate(CredentialKind.PIN, "1234"))
	}

	@Test
	fun `a password must be six characters and free of control codes`() {
		assertEquals(CredentialFault.TOO_SHORT, validate(CredentialKind.PASSWORD, "short"))
		assertEquals(CredentialFault.PASSWORD_UNSUPPORTED, validate(CredentialKind.PASSWORD, "line\nbreak"))
		assertNull(validate(CredentialKind.PASSWORD, "correct horse"))
		assertNull(validate(CredentialKind.PASSWORD, "ሚስጥር ቁልፍ"))
	}

	@Test
	fun `a pattern must join at least four dots`() {
		assertFalse(PatternCode.isValid(listOf(0, 1, 2)))
		assertFalse(PatternCode.isValid(listOf(0, 1, 2, 2)))
		assertFalse(PatternCode.isValid(listOf(0, 1, 2, 9)))
		assertTrue(PatternCode.isValid(listOf(0, 1, 2, 5)))
		assertTrue(PatternCode.isValid((0..8).toList()))
	}

	@Test
	fun `a pattern encodes to the digits of the dots it joins`() {
		assertArrayEquals(charArrayOf('0', '4', '8', '5'), PatternCode.encode(listOf(0, 4, 8, 5)))
		assertNull(validate(CredentialKind.PATTERN, "0485"))
	}

	/** Android's pattern lock picks up the dots a line passes over, so Shelf has to record them too. */
	@Test
	fun `a line across the grid picks up the dots it passes over`() {
		assertEquals(listOf(1), PatternCode.crossed(0, 2))
		assertEquals(listOf(4), PatternCode.crossed(0, 8))
		assertEquals(listOf(3), PatternCode.crossed(0, 6))
		assertEquals(emptyList<Int>(), PatternCode.crossed(0, 4))
		assertEquals(emptyList<Int>(), PatternCode.crossed(0, 5))
		assertEquals(emptyList<Int>(), PatternCode.crossed(3, 1))
	}

	/**
	 * The Keystore wrappers store bytes. A credential that came back mangled would not match the gate,
	 * and would decrypt none of the file headers it was used to lock.
	 */
	@Test
	fun `a credential survives the round trip through bytes`() {
		for (secret in listOf("1234", "correct horse", "ሚስጥር ቁልፍ", "emoji 🔐 key")) {
			val encoded = CredentialBytes.encode(secret.toCharArray())
			assertEquals(secret, CredentialBytes.decode(encoded)?.concatToString())
		}
	}

	@Test
	fun `bytes that are not a storable credential decode to nothing`() {
		assertNull(CredentialBytes.decode(byteArrayOf(-1, -2, -3)))
		assertNull(CredentialBytes.decode("abc".toByteArray()))
		assertNull(CredentialBytes.decode(ByteArray(200) { 'a'.code.toByte() }))
	}

	/**
	 * The prompt cannot draw an invalid pattern, but the rules are what a stored credential is held to
	 * — and a pattern read back from somewhere else has to be rejected rather than quietly accepted.
	 */
	@Test
	fun `a pattern that could not have been drawn is rejected`() {
		assertEquals(CredentialFault.PATTERN_TOO_SHORT, validate(CredentialKind.PATTERN, "0119"))
		assertEquals(CredentialFault.PATTERN_TOO_SHORT, validate(CredentialKind.PATTERN, "0121"))
		assertEquals(CredentialFault.PATTERN_TOO_SHORT, validate(CredentialKind.PATTERN, "01a2"))
		assertEquals(CredentialFault.TOO_LONG, validate(CredentialKind.PATTERN, "0123456780"))
	}

	private fun validate(kind: CredentialKind, value: String) = CredentialRules.validate(kind, value.toCharArray())
}
