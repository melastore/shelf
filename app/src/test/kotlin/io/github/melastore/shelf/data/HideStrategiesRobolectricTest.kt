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
	 * A build can answer isExternalStorageManager true, resolve the appop to allowed, and still hand
	 * the process a view in which the folder is empty and cannot be renamed. Going ahead protects
	 * every file and then rolls it back when the rename fails, leaving the vault behind for a move
	 * that never happened.
	 */
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
