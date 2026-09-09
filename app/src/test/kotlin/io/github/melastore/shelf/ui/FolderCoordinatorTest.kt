package io.github.melastore.shelf.ui

import io.github.melastore.shelf.data.FolderHider
import io.github.melastore.shelf.data.FolderRegistry
import io.github.melastore.shelf.data.FolderTarget
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HideResult
import io.github.melastore.shelf.data.HideStrategy
import io.github.melastore.shelf.data.Journal
import io.github.melastore.shelf.data.SafRecoveryCandidate
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

	@Test
	fun `restore with renamed folder updates registry path and display name`() = runBlocking {
		val backing = temp.newFolder("backing").path
		val emulated = temp.newFolder("emulated").path
		val journal = Journal(File(temp.root, "journal.json"))
		val registry = FolderRegistry(File(temp.root, "folders.json"))
		val paths = StoragePaths.forTest(backing, emulated)
		val renamedEntry = HiddenEntry(
			path = "$emulated/album (1)",
			displayName = "album (1)",
			hiddenAt = 1L,
			method = HideMethod.DOT_RENAME,
		)
		val hider = FolderHider(
			listOf(
				object : HideStrategy {
					override val method = HideMethod.DOT_RENAME
					override suspend fun isAvailable() = true
					override suspend fun hide(target: FolderTarget) = HideResult.Ok(renamedEntry)
					override suspend fun restore(entry: HiddenEntry) = HideResult.Ok(renamedEntry)
				},
			),
		)
		val coordinator = FolderCoordinator(paths, journal, registry, hider)

		registry.put("$emulated/album", "album")
		val originalEntry = HiddenEntry(
			path = "$emulated/album",
			displayName = "album",
			hiddenAt = 1L,
			method = HideMethod.DOT_RENAME,
		)
		coordinator.restore(originalEntry)

		val tracked = registry.read()
		assertEquals(1, tracked.size)
		assertEquals("$emulated/album (1)", tracked.first().path)
		assertEquals("album (1)", tracked.first().displayName)
	}

	@Test
	fun `recovering orphaned candidate registers folder in registry`() = runBlocking {
		val backing = temp.newFolder("backing").path
		val emulated = temp.newFolder("emulated").path
		val journal = Journal(File(temp.root, "journal.json"))
		val registry = FolderRegistry(File(temp.root, "folders.json"))
		val paths = StoragePaths.forTest(backing, emulated)
		val candidate = SafRecoveryCandidate(
			treeUri = "content://tree",
			hiddenPath = "$emulated/.secret",
			hiddenName = ".secret",
			suggestedName = "secret",
		)
		val recoveredEntry = HiddenEntry(path = "$emulated/secret", displayName = "secret", hiddenAt = 1L)
		val hider = FolderHider(
			listOf(
				object : HideStrategy {
					override val method = HideMethod.DOT_RENAME
					override suspend fun isAvailable() = true
					override suspend fun hide(target: FolderTarget) = HideResult.Ok(recoveredEntry)
					override suspend fun restore(entry: HiddenEntry) = HideResult.Ok(recoveredEntry)
					override suspend fun recover(candidate: SafRecoveryCandidate, restoredName: String) = HideResult.Ok(recoveredEntry)
				},
			),
		)
		val coordinator = FolderCoordinator(paths, journal, registry, hider)

		assertTrue(registry.read().isEmpty())
		coordinator.recover(candidate, "secret")

		val tracked = registry.read()
		assertEquals(1, tracked.size)
		assertEquals("$emulated/secret", tracked.first().path)
		assertEquals("secret", tracked.first().displayName)
	}
}
