package io.github.melastore.shelf.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.root.RootCommandRunner
import io.github.melastore.shelf.root.ShellResult
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HideStrategiesRobolectricTest {

	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private val context: Application
		get() = ApplicationProvider.getApplicationContext()

	@Test
	fun `root strategy journals permissions before chmod`() = runBlocking {
		val root = FakeRoot()
		val paths = testPaths(root)
		val journal = Journal(File(temporaryFolder.root, "root-journal.json"))
		val targetPath = "${paths.emulatedRoot}/Secret"
		val backingPath = "${paths.backingRoot}/Secret"
		root.responses += { command ->
			when {
				command.startsWith("realpath ") -> success(backingPath)
				command.startsWith("stat -c '%a %u:%g'") -> success("750 1023:1023")
				else -> success()
			}
		}
		val hider = RootChmodHider(context, journal, paths, root)

		val result = hider.hide(FolderTarget(targetPath, "Secret", null))

		assertTrue(result is HideResult.Ok)
		assertEquals("750", journal.read().single().originalMode)
		assertTrue(root.commands.any { it == "chmod 000 '$backingPath'" })
	}

	@Test
	fun `a shallow hide records what it skipped and restores without it`() = runBlocking {
		ContentCredential.set("48261357".toCharArray())
		val root = FakeRoot()
		val paths = testPaths(root)
		val journal = Journal(File(temporaryFolder.root, "shallow-journal.json"))
		val targetPath = "${paths.emulatedRoot}/Secret"
		val backingPath = "${paths.backingRoot}/Secret"
		File(backingPath).mkdirs()
		val file = File(backingPath, "note.txt").apply { writeText("plain") }
		root.responses += { command ->
			when {
				command.startsWith("realpath ") -> success(backingPath)
				command.startsWith("stat -c '%a %u:%g'") -> success("750 1023:1023")
				else -> success()
			}
		}
		val hider = RootChmodHider(context, journal, paths, root, deepProtection = { false })

		val hidden = hider.hide(FolderTarget(targetPath, "Secret", null))

		assertTrue(hidden is HideResult.Ok)
		assertEquals(false, journal.read().single().deep)
		assertEquals("plain", file.readText())
		assertTrue(root.commands.any { it == "chmod 000 '$backingPath'" })

		val restored = hider.restore(journal.read().single())

		assertTrue(restored is HideResult.Ok)
		assertTrue(journal.read().isEmpty())
		ContentCredential.clear()
	}

	@Test
	fun `private move strategy moves to a random vault and restores`() = runBlocking {
		ContentCredential.set("48261357".toCharArray())
		val paths = testPaths(FakeRoot())
		val source = File(paths.emulatedRoot, "Secret").apply {
			mkdirs()
			resolve("note.txt").writeText("private")
		}
		val journal = Journal(File(temporaryFolder.root, "move-journal.json"))
		val hider = PrivateMoveHider(
			context,
			journal,
			paths,
			allFilesAvailable = { true },
			mediaIndex = FakeMediaIndex,
		)

		try {
			val hidden = hider.hide(FolderTarget(source.path, source.name, null))

			assertTrue(hidden is HideResult.Ok)
			val entry = (hidden as HideResult.Ok).entry
			val hiddenFolder = File(entry.hiddenPath)
			assertTrue(!source.exists() && hiddenFolder.isDirectory)
			assertTrue(!File(hiddenFolder, "note.txt").exists())
			val opaqueFile = hiddenFolder.listFiles()?.singleOrNull {
				it.isFile && it.name.startsWith("sfn_")
			}
			assertTrue(opaqueFile != null)
			assertTrue(FileLocker.isLocked(FileLockTarget(requireNotNull(opaqueFile))))

			val restored = hider.restore(entry)

			assertTrue(restored is HideResult.Ok)
			assertEquals("private", source.resolve("note.txt").readText())
			assertTrue(journal.read().isEmpty())
		} finally {
			ContentCredential.clear()
		}
	}

	@Test
	fun `dot rename strategy requests the parent grant before changing anything`() = runBlocking {
		val paths = testPaths(FakeRoot())
		val journal = Journal(File(temporaryFolder.root, "rename-journal.json"))
		val hider = DotRenameHider(context, journal, paths)
		val target = FolderTarget("${paths.emulatedRoot}/Pictures/Secret", "Secret", null)

		val result = hider.hide(target)

		assertEquals(HideResult.NeedsAccess("${paths.emulatedRoot}/Pictures", "Secret"), result)
		assertTrue(journal.read().isEmpty())
	}

	/**
	 * The provider marks the volume root and Download with FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE, so a
	 * grant above a folder sitting in either of them cannot be obtained however often the picker is
	 * shown. Asking for one produced a picker that reappeared after every answer, and the folder was
	 * never hidden. The mount is the only way in, so it is the one taken.
	 */
	@Test
	fun `dot rename does not ask for a grant the picker cannot give`() = runBlocking {
		val paths = testPaths(FakeRoot())
		val journal = Journal(File(temporaryFolder.root, "toplevel-journal.json"))
		val source = File(paths.emulatedRoot, "Newpipe").apply { mkdirs() }
		File(source, "clip.mp4").writeText("x")
		val hider = DotRenameHider(context, journal, paths)

		val result = hider.hide(FolderTarget(source.path, "Newpipe", null))

		assertTrue("must not ask for the root grant", result !is HideResult.NeedsAccess)
		assertTrue(result is HideResult.Ok)
		assertTrue(!source.exists())

		val entry = journal.read().single()
		assertEquals(paths.emulatedRoot, File(entry.hiddenPath).parent)
		assertTrue(File(entry.hiddenPath).isDirectory)
		hider.restore(entry)
		assertTrue(source.isDirectory)
		assertTrue(journal.read().isEmpty())
	}

	/** Download is blocked for the same reason, so its children take the same path. */
	@Test
	fun `dot rename does not ask for a grant above Download`() = runBlocking {
		val paths = testPaths(FakeRoot())
		val journal = Journal(File(temporaryFolder.root, "download-journal.json"))
		val source = File(paths.emulatedRoot, "Download/Clips").apply { mkdirs() }
		File(source, "clip.mp4").writeText("x")
		val hider = DotRenameHider(context, journal, paths)

		val result = hider.hide(FolderTarget(source.path, "Clips", null))

		assertTrue("must not ask for the Download grant", result !is HideResult.NeedsAccess)
		assertTrue(!source.exists())
		val hidden = File(journal.read().single().hiddenPath)
		assertEquals(File(paths.emulatedRoot, "Download").path, hidden.parent)
		assertTrue(hidden.isDirectory)
	}

	@Test
	fun `dot rename rolls back folder on mount when journal update fails`() = runBlocking {
		val paths = testPaths(FakeRoot())
		val journalFile = File(temporaryFolder.root, "failing-journal.json")
		val journal = object : Journal(journalFile) {
			override suspend fun replace(entry: HiddenEntry): Boolean = false
		}
		val source = File(paths.emulatedRoot, "Download/RollbackTest").apply { mkdirs() }
		File(source, "clip.mp4").writeText("x")
		val hider = DotRenameHider(context, journal, paths)

		val result = hider.hide(FolderTarget(source.path, "RollbackTest", null))

		assertTrue(result is HideResult.Failed)
		assertEquals(HideFailure.JournalUpdateFailed, (result as HideResult.Failed).failure)
		assertTrue("Folder must be restored to original name", source.isDirectory)
		assertTrue("Hidden folder must not exist", !File(paths.emulatedRoot, "Download/.RollbackTest").exists())
		assertTrue(journal.read().isEmpty())
	}

	/**
	 * A build can answer isExternalStorageManager true, resolve the appop to allowed, and still hand
	 * the process a view in which the folder is empty and cannot be renamed. Going ahead protects
	 * every file and then rolls it back when the rename fails, leaving the vault behind for a move
	 * that never happened.
	 */
	@Test
	fun `dot rename hides under a name that gives the folder away and restores from it`() = runBlocking {
		ContentCredential.set("48261357".toCharArray())
		val paths = testPaths(FakeRoot())
		val journal = Journal(File(temporaryFolder.root, "masked-journal.json"))
		val source = File(paths.emulatedRoot, "Download/Holiday").apply { mkdirs() }
		File(source, "clip.mp4").writeText("x")
		val hider = DotRenameHider(context, journal, paths, nameStyle = { HiddenNameStyle.CAMOUFLAGE })

		val result = hider.hide(FolderTarget(source.path, "Holiday", null))

		assertTrue(result is HideResult.Ok)
		val entry = journal.read().single()
		val hiddenName = File(entry.hiddenPath).name
		assertTrue("Hidden name must start with a dot", hiddenName.startsWith("."))
		assertTrue("Hidden name must not carry the folder name", !hiddenName.contains("Holiday"))
		assertTrue(HiddenNamer.matches(HiddenNameStyle.CAMOUFLAGE, "Holiday", hiddenName))
		assertTrue(File(entry.hiddenPath).isDirectory)
		assertTrue(!source.exists())

		assertTrue(hider.restore(entry) is HideResult.Ok)
		assertTrue("The folder comes back under its own name", source.isDirectory)
		assertEquals("x", File(source, "clip.mp4").readText())
		assertTrue(journal.read().isEmpty())
	}

	@Test
	fun `a rename with the second layer off leaves the files as they are and records it`() = runBlocking {
		ContentCredential.set("48261357".toCharArray())
		val paths = testPaths(FakeRoot())
		val journal = Journal(File(temporaryFolder.root, "shallow-rename-journal.json"))
		val source = File(paths.emulatedRoot, "Download/Holiday").apply { mkdirs() }
		File(source, "clip.mp4").writeText("keep me")
		val hider = DotRenameHider(context, journal, paths, deepProtection = { false })

		val result = hider.hide(FolderTarget(source.path, "Holiday", null))

		assertTrue(result is HideResult.Ok)
		val entry = journal.read().single()
		assertTrue("The entry must not claim protection", !entry.deep)
		val hidden = File(entry.hiddenPath)
		assertEquals(listOf("clip.mp4"), hidden.listFiles().orEmpty().map { it.name })
		assertEquals("keep me", File(hidden, "clip.mp4").readText())

		assertTrue(hider.restore(entry) is HideResult.Ok)
		assertEquals("keep me", File(source, "clip.mp4").readText())
	}

	@Test
	fun `a hide that cannot protect does not record protection`() = runBlocking {
		ContentCredential.clear()
		val paths = testPaths(FakeRoot())
		val journal = Journal(File(temporaryFolder.root, "unprotected-journal.json"))
		val source = File(paths.emulatedRoot, "Download/Holiday").apply { mkdirs() }
		File(source, "clip.mp4").writeText("keep me")
		val hider = DotRenameHider(context, journal, paths)

		val result = hider.hide(FolderTarget(source.path, "Holiday", null))

		assertTrue(result is HideResult.Ok)
		assertTrue("Warned about what it could not do", (result as HideResult.Ok).warning != null)
		assertTrue("The entry must not claim protection", !journal.read().single().deep)
	}

	@Test
	fun `changing the style renames what is already hidden, and only once`() = runBlocking {
		ContentCredential.set("48261357".toCharArray())
		val paths = testPaths(FakeRoot())
		val journal = Journal(File(temporaryFolder.root, "remask-journal.json"))
		val source = File(paths.emulatedRoot, "Download/Holiday").apply { mkdirs() }
		File(source, "clip.mp4").writeText("x")
		var style = HiddenNameStyle.KEEP
		val hider = DotRenameHider(context, journal, paths, nameStyle = { style })

		assertTrue(hider.hide(FolderTarget(source.path, "Holiday", null)) is HideResult.Ok)
		val dotted = journal.read().single()
		assertEquals(File(paths.emulatedRoot, "Download/.Holiday").path, dotted.hiddenPath)

		style = HiddenNameStyle.RANDOM
		val masked = hider.remask(dotted)
		assertTrue(masked is HideResult.Ok)
		val maskedEntry = (masked as HideResult.Ok).entry
		assertEquals(maskedEntry, journal.read().single())
		assertTrue(File(maskedEntry.hiddenPath).isDirectory)
		assertTrue(!File(dotted.hiddenPath).exists())
		assertTrue(HiddenNamer.matches(HiddenNameStyle.RANDOM, "Holiday", File(maskedEntry.hiddenPath).name))

		// Already in the chosen style, so nothing moves and the record stays as it is.
		assertEquals(HideResult.Ok(maskedEntry), hider.remask(maskedEntry))

		style = HiddenNameStyle.KEEP
		val back = hider.remask(maskedEntry)
		assertTrue(back is HideResult.Ok)
		assertEquals(dotted.hiddenPath, (back as HideResult.Ok).entry.hiddenPath)
		assertTrue(hider.restore(back.entry) is HideResult.Ok)
		assertTrue(source.isDirectory)
	}

	@Test
	fun `private move refuses a folder the mount will not show it`() = runBlocking {
		val paths = testPaths(FakeRoot())
		// Present and empty to File, while the index reports what is really in there.
		val source = File(paths.emulatedRoot, "Secret").apply { mkdirs() }
		val journal = Journal(File(temporaryFolder.root, "restricted-journal.json"))
		val index = object : FolderMediaIndex {
			override fun scan(context: android.content.Context, paths: List<String>) = Unit
			override suspend fun rescan(context: android.content.Context, path: String): HideWarning? = null
			override suspend fun listFiles(path: String) = listOf("$path/one.jpg", "$path/two.jpg")
		}

		val hider = PrivateMoveHider(context, journal, paths, allFilesAvailable = { true }, mediaIndex = index)

		val result = hider.hide(FolderTarget(source.path, "Secret", null))

		assertTrue(result is HideResult.Failed)
		assertTrue(journal.read().isEmpty())
		assertTrue("nothing may be left behind", File(paths.emulatedRoot, ".shelf").listFiles().isNullOrEmpty())
	}

	@Test
	fun `dot rename blocker identifies top-level folders and ungrantable parents`() = runBlocking {
		val paths = testPaths(FakeRoot())
		val journal = Journal(File(temporaryFolder.root, "dot-journal.json"))
		val hider = DotRenameHider(context, journal, paths, writableThroughMount = { false })

		assertEquals(
			HideFailure.ParentNotGrantable("11"),
			hider.blocker("${paths.emulatedRoot}/11"),
		)
		assertEquals(
			HideFailure.ParentNotGrantable("receipts"),
			hider.blocker("${paths.emulatedRoot}/Download/receipts"),
		)
		assertEquals(
			null,
			hider.blocker("${paths.emulatedRoot}/Custom/nested"),
		)
	}

	// The grant is not the only way in. A parent the app can write is renamed through the mount, and
	// a folder under one used to be reported unhideable even though hiding it worked.
	@Test
	fun `dot rename blocker clears a folder the mount can rename`() = runBlocking {
		val paths = testPaths(FakeRoot())
		val journal = Journal(File(temporaryFolder.root, "dot-writable-journal.json"))
		val hider = DotRenameHider(context, journal, paths, writableThroughMount = { true })

		assertEquals(null, hider.blocker("${paths.emulatedRoot}/11"))
		assertEquals(null, hider.blocker("${paths.emulatedRoot}/Download/receipts"))
	}

	@Test
	fun `in place hider asks for a grant on the folder itself`() = runBlocking {
		ContentCredential.set("48261357".toCharArray())
		val paths = testPaths(FakeRoot())
		val journal = Journal(File(temporaryFolder.root, "inplace-journal.json"))
		val target = "${paths.emulatedRoot}/11"
		File(target).mkdirs()
		val hider = InPlaceHider(context, journal, paths)

		val result = hider.hide(FolderTarget(target, "11", null))

		// The folder, not its parent: a rename is what needs the parent, and this renames nothing.
		assertEquals(HideResult.NeedsAccess(target, "11"), result)
		assertTrue("nothing may be journalled before the grant", journal.read().isEmpty())
	}

	@Test
	fun `in place hider never reports a block, since the picker can still grant the folder`() = runBlocking {
		val paths = testPaths(FakeRoot())
		val journal = Journal(File(temporaryFolder.root, "inplace-blocker-journal.json"))
		val hider = InPlaceHider(context, journal, paths)

		assertEquals(null, hider.blocker("${paths.emulatedRoot}/11"))
		assertEquals(null, hider.blocker("${paths.emulatedRoot}/Download/receipts"))
	}

	@Test
	fun `a folder the chosen method cannot reach falls to the in-place strategy`() = runBlocking {
		val paths = testPaths(FakeRoot())
		val reached = mutableListOf<HideMethod>()
		val entry = HiddenEntry(path = "${paths.emulatedRoot}/11", displayName = "11", hiddenAt = 1L)
		fun strategy(hideMethod: HideMethod, blocked: Boolean) = object : HideStrategy {
			override val method = hideMethod
			override suspend fun isAvailable() = true
			override suspend fun hide(target: FolderTarget): HideResult {
				reached += hideMethod
				return HideResult.Ok(entry)
			}

			override suspend fun restore(entry: HiddenEntry) = HideResult.Ok(entry)
			override suspend fun blocker(path: String) = if (blocked) HideFailure.ParentNotGrantable("11") else null
		}
		val hider = FolderHider(
			listOf(
				strategy(HideMethod.DOT_RENAME, blocked = true),
				strategy(HideMethod.IN_PLACE, blocked = false),
			),
		)

		hider.hide(FolderTarget("${paths.emulatedRoot}/11", "11", null), HidingPreference.AUTO)

		assertEquals(listOf(HideMethod.IN_PLACE), reached)
	}

	@Test
	fun `a folder the chosen method can reach never touches the fallback`() = runBlocking {
		val paths = testPaths(FakeRoot())
		val reached = mutableListOf<HideMethod>()
		val entry = HiddenEntry(path = "${paths.emulatedRoot}/Pictures/album", displayName = "album", hiddenAt = 1L)
		fun strategy(hideMethod: HideMethod) = object : HideStrategy {
			override val method = hideMethod
			override suspend fun isAvailable() = true
			override suspend fun hide(target: FolderTarget): HideResult {
				reached += hideMethod
				return HideResult.Ok(entry)
			}

			override suspend fun restore(entry: HiddenEntry) = HideResult.Ok(entry)
			override suspend fun blocker(path: String): HideFailure? = null
		}
		val hider = FolderHider(listOf(strategy(HideMethod.DOT_RENAME), strategy(HideMethod.IN_PLACE)))

		hider.hide(FolderTarget(entry.path, "album", null), HidingPreference.AUTO)

		assertEquals(listOf(HideMethod.DOT_RENAME), reached)
	}

	private fun testPaths(root: RootCommandRunner): StoragePaths {
		val base = temporaryFolder.newFolder()
		return StoragePaths.forTest(
			backingRoot = File(base, "backing").apply { mkdirs() }.path,
			emulatedRoot = File(base, "emulated").apply { mkdirs() }.path,
			root = root,
		)
	}

	private class FakeRoot : RootCommandRunner {
		val commands = mutableListOf<String>()
		val responses = mutableListOf<(String) -> ShellResult>()

		override suspend fun isAvailable(): Boolean = true

		override suspend fun run(vararg commands: String): ShellResult {
			this.commands += commands
			val command = commands.firstOrNull().orEmpty()
			return responses.firstOrNull()?.invoke(command) ?: success()
		}
	}

	private object FakeMediaIndex : FolderMediaIndex {
		override fun scan(context: android.content.Context, paths: List<String>) = Unit
		override suspend fun rescan(context: android.content.Context, path: String): HideWarning? = null
		override suspend fun listFiles(path: String): List<String> = emptyList()
	}

	private companion object {
		fun success(vararg stdout: String) = ShellResult(0, stdout.toList(), emptyList())
	}
}
