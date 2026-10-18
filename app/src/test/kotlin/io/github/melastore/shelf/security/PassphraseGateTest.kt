package io.github.melastore.shelf.security

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

	@Test
	fun `primary and decoy pins remain independent`() {
		val directory = Files.createTempDirectory("shelf-two-gates-test").toFile()
		try {
			val primary = PassphraseGate(directory.resolve("primary"))
			val decoy = PassphraseGate(directory.resolve("decoy"))
			primary.set("4826".toCharArray())
			decoy.set("1937".toCharArray())

			assertTrue(primary.matches("4826".toCharArray()))
			assertFalse(primary.matches("1937".toCharArray()))
			assertTrue(decoy.matches("1937".toCharArray()))
			assertFalse(decoy.matches("4826".toCharArray()))
		} finally {
			directory.deleteRecursively()
		}
	}

	@Test
	fun `a damaged gate falls back to its backup and never reads as a wrong pin`() {
		val directory = Files.createTempDirectory("shelf-damaged-gate-test").toFile()
		try {
			val file = directory.resolve("gate")
			val gate = PassphraseGate(file)
			gate.set("4826".toCharArray())

			file.writeText("not a gate")
			assertTrue(gate.matches("4826".toCharArray()))
			assertFalse(gate.matches("1111".toCharArray()))

			directory.resolve("gate.bak").delete()
			assertThrows(PassphraseGate.GateUnreadable::class.java) { gate.matches("4826".toCharArray()) }

			gate.clear()
			assertFalse(directory.resolve("gate.bak").exists())
			assertFalse(gate.matches("4826".toCharArray()))
		} finally {
			directory.deleteRecursively()
		}
	}
}
