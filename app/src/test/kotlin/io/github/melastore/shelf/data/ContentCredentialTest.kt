package io.github.melastore.shelf.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCredentialTest {

	@After
	fun tearDown() {
		ContentCredential.clear()
	}

	@Test
	fun `a lease outlives the session it was taken from`() {
		ContentCredential.set(charArrayOf('1', '2', '3', '4'))

		val lease = ContentCredential.retain()
		ContentCredential.clear()

		assertTrue(ContentCredential.isAvailable())
		assertEquals("1234", ContentCredential.copy()?.concatToString())

		lease.close()
		assertNull(ContentCredential.copy())
	}

	/**
	 * An emergency hide can start while an earlier one is still running. The second has to take a lease
	 * of its own; without it the credential would disappear the moment the first one finished, halfway
	 * through the second folder.
	 */
	@Test
	fun `a lease taken while another is held keeps the credential alive on its own`() {
		ContentCredential.set(charArrayOf('4', '3', '2', '1'))
		val first = ContentCredential.retain()
		ContentCredential.clear()

		val second = ContentCredential.retain()
		first.close()

		assertTrue(ContentCredential.isAvailable())
		assertEquals("4321", ContentCredential.copy()?.concatToString())

		second.close()
		assertNull(ContentCredential.copy())
	}
}
