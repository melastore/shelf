package io.github.melastore.shelf.data

import io.github.melastore.shelf.security.CredentialFault
import io.github.melastore.shelf.security.CredentialKind
import io.github.melastore.shelf.security.CredentialRules
import io.github.melastore.shelf.security.KnockCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnockCodeTest {

	@Test
	fun `a code repeats quarters where a pattern cannot`() {
		assertTrue(KnockCode.isValid(listOf(0, 0, 0, 0)))
		assertTrue(KnockCode.isValid(listOf(3, 1, 3, 1, 2)))
	}

	@Test
	fun `three taps are too few and a fifth quarter does not exist`() {
		assertFalse(KnockCode.isValid(listOf(0, 1, 2)))
		assertFalse(KnockCode.isValid(listOf(0, 1, 2, 4)))
		assertFalse(KnockCode.isValid(List(KnockCode.MAX_TAPS + 1) { 0 }))
	}

	@Test
	fun `encoding round trips through the shape a gate sees`() {
		val encoded = KnockCode.encode(listOf(2, 0, 3, 1))
		assertEquals("2031", String(encoded))
		assertTrue(KnockCode.isEncoded(encoded))
	}

	@Test
	fun `the rules reject anything the pad could not have produced`() {
		assertEquals(null, CredentialRules.validate(CredentialKind.KNOCK, "0123".toCharArray()))
		assertEquals(
			CredentialFault.KNOCK_TOO_SHORT,
			CredentialRules.validate(CredentialKind.KNOCK, "012".toCharArray()),
		)
		// A digit outside the four quarters is a PIN, not a knock code.
		assertEquals(
			CredentialFault.KNOCK_TOO_SHORT,
			CredentialRules.validate(CredentialKind.KNOCK, "0129".toCharArray()),
		)
	}
}
