package io.github.melastore.shelf.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderRegistryTest {

	@get:Rule val temp = TemporaryFolder()

	private fun registry() = FolderRegistry(File(temp.root, "folders.json"))

	@Test
	fun `a folder stays listed after its record is gone`() = runBlocking {
		val registry = registry()
		registry.put("/storage/emulated/0/DCIM/Camera", "Camera")

		assertEquals(listOf("Camera"), registry.read().map { it.displayName })
		assertEquals(listOf("Camera"), registry().read().map { it.displayName })
	}

	@Test
	fun `adding the same folder twice keeps one row and refreshes its name`() = runBlocking {
		val registry = registry()
		registry.put("/storage/emulated/0/Docs", "Docs")
		registry.put("/storage/emulated/0/Docs", "Documents")

		assertEquals(1, registry.read().size)
		assertEquals("Documents", registry.read().single().displayName)
	}

	@Test
	fun `order of addition is preserved`() = runBlocking {
		val registry = registry()
		registry.put("/storage/emulated/0/One", "One")
		registry.put("/storage/emulated/0/Two", "Two")
		registry.putAll(listOf(TrackedFolder("/storage/emulated/0/Three", "Three", 0)))

		assertEquals(listOf("One", "Two", "Three"), registry.read().map { it.displayName })
	}

	@Test
	fun `adopting records already tracked changes nothing`() = runBlocking {
		val registry = registry()
		registry.put("/storage/emulated/0/One", "One")
		registry.putAll(listOf(TrackedFolder("/storage/emulated/0/One", "Stale", 0)))

		assertEquals(listOf("One"), registry.read().map { it.displayName })
	}

	@Test
	fun `forgetting a folder drops only that row`() = runBlocking {
		val registry = registry()
		registry.put("/storage/emulated/0/One", "One")
		registry.put("/storage/emulated/0/Two", "Two")
		registry.remove("/storage/emulated/0/One")

		assertEquals(listOf("Two"), registry.read().map { it.displayName })
	}
}
