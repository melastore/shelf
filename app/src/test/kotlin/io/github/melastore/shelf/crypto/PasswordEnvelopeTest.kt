package io.github.melastore.shelf.crypto

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PasswordEnvelopeTest {

	@Test
	fun `round trip authenticates header and content`() {
		val plaintext = "recovery records".toByteArray()
		val password = "correct horse battery staple".toCharArray()
		val encrypted = PasswordEnvelope.encrypt(plaintext, password, PasswordEnvelope.MIN_ITERATIONS)

		assertArrayEquals(plaintext, PasswordEnvelope.decrypt(encrypted, password))
	}

	@Test
	fun `wrong passphrase and tampering are rejected`() {
		val encrypted = PasswordEnvelope.encrypt(
			"private".toByteArray(),
			"one recovery passphrase".toCharArray(),
			PasswordEnvelope.MIN_ITERATIONS,
		)

		assertThrows(AEADBadTagException::class.java) {
			PasswordEnvelope.decrypt(encrypted, "another recovery passphrase".toCharArray())
		}
		encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
		assertThrows(AEADBadTagException::class.java) {
			PasswordEnvelope.decrypt(encrypted, "one recovery passphrase".toCharArray())
		}
	}

	@Test
	fun `truncated envelope is rejected before decryption`() {
		val encrypted = PasswordEnvelope.encrypt(
			"private".toByteArray(),
			"one recovery passphrase".toCharArray(),
			PasswordEnvelope.MIN_ITERATIONS,
		)

		assertThrows(IllegalArgumentException::class.java) {
			PasswordEnvelope.decrypt(encrypted.copyOf(encrypted.size - 1), "one recovery passphrase".toCharArray())
		}
	}
}
