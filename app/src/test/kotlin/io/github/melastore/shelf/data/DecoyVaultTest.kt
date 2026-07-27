package io.github.melastore.shelf.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DecoyVaultTest {

	@get:Rule val temp = TemporaryFolder()

	@Test
	fun `restoring every seeded item leaves the decoy space empty`() = runBlocking {
		val file = File(temp.root, "decoy.json")
		val vault = DecoyVault(file)
		val seeded = vault.read()

		seeded.forEach { vault.remove(it.id) }

		assertTrue(vault.read().isEmpty())
		assertTrue(DecoyVault(file).read().isEmpty())
	}

	@Test
	fun `existing decoy data is kept when adding the seed marker`() = runBlocking {
		val file = File(temp.root, "decoy.json")
		val vault = DecoyVault(file)
		vault.add("Receipts")

		assertEquals(listOf("Receipts"), vault.read().map { it.name })
	}

	@Test
	fun `fallback remains plausible when the backing file is damaged`() {
		val items = DecoyVault(File(temp.root, "decoy.json")).fallback()

		assertEquals(4, items.size)
		assertTrue(items.all { it.name.isNotBlank() && it.hiddenAt > 0 })
	}

	@Test
	fun `duress events remain until explicitly cleared`() = runBlocking {
		val file = File(temp.root, "duress.json")
		val log = DuressLog(file)
		log.record(10)
		log.record(20)

		assertEquals(listOf(10L, 20L), log.read().map { it.at })
		assertEquals(listOf(10L, 20L), DuressLog(file).read().map { it.at })

		log.clear()
		assertTrue(log.read().isEmpty())
	}
}
