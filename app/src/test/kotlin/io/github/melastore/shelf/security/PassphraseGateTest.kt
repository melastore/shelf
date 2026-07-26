package io.github.melastore.shelf.security

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseGateTest {

	@Test
	fun `accepts four digit pin and replaces it safely`() {
		val directory = Files.createTempDirectory("shelf-gate-test").toFile()
		try {
			val gate = PassphraseGate(directory.resolve("gate"))
			gate.set("1234".toCharArray())
			assertTrue(gate.matches("1234".toCharArray()))
			assertFalse(gate.matches("1235".toCharArray()))

			gate.set("987654".toCharArray())
			assertFalse(gate.matches("1234".toCharArray()))
			assertTrue(gate.matches("987654".toCharArray()))
		} finally {
			directory.deleteRecursively()
		}
	}
}
