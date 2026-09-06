package io.github.melastore.shelf.ui

import io.github.melastore.shelf.data.FolderHider
import io.github.melastore.shelf.data.FolderRegistry
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.Journal
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderCoordinatorTest {

	@get:Rule val temp = TemporaryFolder()

	private fun coordinator(backing: String, emulated: String): Triple<FolderCoordinator, Journal, FolderRegistry> {
		val journal = Journal(File(temp.root, "journal.json"))
		val registry = FolderRegistry(File(temp.root, "folders.json"))
		val paths = StoragePaths.forTest(backing, emulated)
		return Triple(FolderCoordinator(paths, journal, registry, FolderHider(emptyList())), journal, registry)
	}

	@Test
	fun `forgetting clears the journal row so the folder is not adopted back`() = runBlocking {
		val backing = temp.newFolder("backing").path
		val emulated = temp.newFolder("emulated").path
		val (coordinator, journal, registry) = coordinator(backing, emulated)

		journal.addNew(HiddenEntry(path = "$backing/album", displayName = "album", hiddenAt = 1L))
		registry.put("$emulated/album", "album")
		assertEquals(1, coordinator.load(emptyMap()).size)

		coordinator.forget("$emulated/album")

		assertTrue("journal row survived the forget", journal.read().isEmpty())
		assertTrue("registry row survived the forget", registry.read().isEmpty())
		// The reload is the part that used to undo it: a journal row with no registry entry is
		// adopted, and the record the owner just dismissed reappears.
		assertTrue("folder was adopted back", coordinator.load(emptyMap()).isEmpty())
	}

	@Test
	fun `forgetting a folder with no journal row still drops it from the list`() = runBlocking {
		val backing = temp.newFolder("backing").path
		val emulated = temp.newFolder("emulated").path
		val (coordinator, _, registry) = coordinator(backing, emulated)

		registry.put("$emulated/exposed", "exposed")
		assertEquals(1, coordinator.load(emptyMap()).size)

		coordinator.forget("$emulated/exposed")

		assertTrue(registry.read().isEmpty())
		assertTrue(coordinator.load(emptyMap()).isEmpty())
	}
}
