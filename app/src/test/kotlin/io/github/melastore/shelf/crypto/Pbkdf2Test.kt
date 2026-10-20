package io.github.melastore.shelf.crypto

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** Everything already on disk was derived by the platform's PBKDF2, so the fast one has to match it byte for byte. */
class Pbkdf2Test {
	private val salt = ByteArray(16) { (it * 7).toByte() }

	private fun platform(password: String, iterations: Int, bits: Int): ByteArray =
		SecretKeyFactory.getInstance("PBKDF2withHmacSHA256")
			.generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, bits)).encoded

	@Test
	fun `matches the platform for the passwords people type`() {
		for (password in listOf("1357", "48261357", "correct horse battery", "pässwörd", "密码1357", "x".repeat(100))) {
			assertArrayEquals(password, platform(password, 1_000, 256), Pbkdf2.native(password.toCharArray(), salt, 1_000, 32))
		}
	}

	@Test
	fun `matches the platform past one output block`() {
		assertArrayEquals(platform("1357", 1_000, 512), Pbkdf2.native("1357".toCharArray(), salt, 1_000, 64))
		assertArrayEquals(platform("1357", 1_000, 384), Pbkdf2.native("1357".toCharArray(), salt, 1_000, 48))
	}

	@Test
	fun `the entry point gives the same bytes whichever path it takes`() {
		assertArrayEquals(platform("1357", 1_000, 256), Pbkdf2.sha256("1357".toCharArray(), salt, 1_000, 256))
		assertArrayEquals(platform("", 1_000, 256), Pbkdf2.sha256(CharArray(0), salt, 1_000, 256))
	}

	@Test
	fun `a single round matches too`() {
		assertArrayEquals(platform("1357", 1, 256), Pbkdf2.native("1357".toCharArray(), salt, 1, 32))
	}
}
