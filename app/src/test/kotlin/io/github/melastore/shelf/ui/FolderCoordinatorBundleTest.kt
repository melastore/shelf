package io.github.melastore.shelf.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.data.BundleResult
import io.github.melastore.shelf.data.FileBundler
import io.github.melastore.shelf.data.FolderHider
import io.github.melastore.shelf.data.FolderMediaIndex
import io.github.melastore.shelf.data.FolderRegistry
import io.github.melastore.shelf.data.FolderTarget
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HideFailure
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HideResult
import io.github.melastore.shelf.data.HideStrategy
import io.github.melastore.shelf.data.HideWarning
import io.github.melastore.shelf.data.Journal
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderCoordinatorBundleTest {

	@get:Rule
	val temp = TemporaryFolder()

	private val context: Application
		get() = ApplicationProvider.getApplicationContext()

	private object NoIndex : FolderMediaIndex {
		override fun scan(context: Context, paths: List<String>) = Unit
		override suspend fun rescan(context: Context, path: String): HideWarning? = null
		override suspend fun listFiles(path: String): List<String> = emptyList()
	}

	private lateinit var storage: File
	private lateinit var registry: FolderRegistry
	private lateinit var bundler: FileBundler
	private lateinit var journal: Journal
	private var canLook = true

	// Restoring is the strategy's job and is faked: the folder is already back on disk.
	private fun coordinator(): FolderCoordinator {
		storage = temp.newFolder("storage")
		val paths = StoragePaths.forTest(storage.path, storage.path)
		journal = Journal(File(temp.root, "journal.json"))
		registry = FolderRegistry(File(temp.root, "folders.json"))
		bundler =
			FileBundler(context, paths, useRoot = { false }, mediaIndex = NoIndex, direct = { canLook && it.isDirectory })
		val hider = FolderHider(
			listOf(
				object : HideStrategy {
					override val method = HideMethod.PRIVATE_MOVE
					override suspend fun isAvailable() = true
					override suspend fun hide(target: FolderTarget) = HideResult.Failed(HideFailure.MethodUnavailable)
					override suspend fun restore(entry: HiddenEntry) = HideResult.Ok(entry)
				},
			),
		)
		return FolderCoordinator(paths, journal, registry, hider, bundler)
	}

	private suspend fun bundleOf(vararg names: String): Pair<File, String> {
		val dir = File(storage, "Pictures").apply { mkdirs() }
		names.forEach { File(dir, it).writeText(it) }
		val bundle = (bundler.create(dir.path) as BundleResult.Ok).bundlePath
		bundler.fill(bundle, names.map { "${dir.path}/$it" })
		return dir to bundle
	}

	private fun entry(path: String, name: String) =
		HiddenEntry(path = path, displayName = name, hiddenAt = 1L, method = HideMethod.PRIVATE_MOVE)

	@Test
	fun `unhiding a bundle puts the files back and drops the row`() = runBlocking {
		val coordinator = coordinator()
		val (dir, bundle) = bundleOf("a.jpg", "b.jpg")
		registry.put(bundle, "a.jpg +1", bundle = true)

		val restored = coordinator.restoreFolder(entry(bundle, "a.jpg +1"))

		assertTrue(restored.result is HideResult.Ok)
		assertTrue(restored.bundle)
		assertTrue(File(dir, "a.jpg").isFile && File(dir, "b.jpg").isFile)
		assertFalse(File(bundle).exists())
		assertTrue(registry.read().isEmpty())
	}

	@Test
	fun `files that stay behind keep the row and say so`() = runBlocking {
		val coordinator = coordinator()
		val (_, bundle) = bundleOf("a.jpg")
		registry.put(bundle, "a.jpg", bundle = true)
		File(bundle).setWritable(false)

		val result = try {
			coordinator.restore(entry(bundle, "a.jpg"))
		} finally {
			File(bundle).setWritable(true)
		}

		val warning = (result as HideResult.Ok).warning
		assertTrue("$warning", warning is HideWarning.FilesLeftInFolder)
		assertEquals(1, (warning as HideWarning.FilesLeftInFolder).count)
		assertEquals(listOf(true), registry.read().map { it.bundle })
		assertTrue(File(bundle, "a.jpg").isFile)
	}

	@Test
	fun `a plain folder is restored as before`() = runBlocking {
		val coordinator = coordinator()
		val folder = File(storage, "Album").apply { mkdirs() }
		File(folder, "a.jpg").writeText("x")
		registry.put(folder.path, "Album")

		val result = coordinator.restore(entry(folder.path, "Album"))

		assertTrue(result is HideResult.Ok)
		assertTrue(File(folder, "a.jpg").isFile)
		assertEquals(listOf(folder.path), registry.read().map { it.path })
	}

	@Test
	fun `the marker alone is enough when the flag was lost`() = runBlocking {
		val coordinator = coordinator()
		val (dir, bundle) = bundleOf("a.jpg")
		// A list rebuilt from the journal knows the path and nothing else.
		registry.put(bundle, File(bundle).name)

		coordinator.restore(entry(bundle, File(bundle).name))

		assertTrue(File(dir, "a.jpg").isFile)
		assertFalse(File(bundle).exists())
		assertTrue(registry.read().isEmpty())
	}

	@Test
	fun `put back finishes an exposed bundle`() = runBlocking {
		val coordinator = coordinator()
		val (dir, bundle) = bundleOf("a.jpg")
		registry.put(bundle, "a.jpg", bundle = true)

		val result = coordinator.putBack(bundle, "a.jpg")

		assertTrue(result is BundleResult.Ok)
		assertTrue(File(dir, "a.jpg").isFile)
		assertTrue(registry.read().isEmpty())
	}

	@Test
	fun `reopening leaves the files inside and keeps the flag`() = runBlocking {
		val coordinator = coordinator()
		val (dir, bundle) = bundleOf("a.jpg")
		// A list rebuilt from the journal: the marker has to bring the flag back.
		registry.put(bundle, "a.jpg")

		val result = coordinator.reopen(entry(bundle, "a.jpg"))

		assertTrue(result is HideResult.Ok)
		assertTrue(File(bundle, "a.jpg").isFile)
		assertFalse(File(dir, "a.jpg").exists())
		assertEquals(listOf(true), registry.read().map { it.bundle })
	}

	@Test
	fun `a bundle adopted from the journal keeps its flag`() = runBlocking {
		val coordinator = coordinator()
		val (_, bundle) = bundleOf("a.jpg")
		// App data lost: the journal row came back from a recovery file, the list did not.
		journal.addNew(entry(bundle, "a.jpg"))

		val folders = coordinator.load(emptyMap())

		assertEquals(listOf(true), folders.map { it.bundle })
		assertEquals(listOf(true), registry.read().map { it.bundle })
	}

	@Test
	fun `a row nothing could look into is shown but not saved until something can`() = runBlocking {
		val coordinator = coordinator()
		val plain = File(storage, "Pictures/plain").apply { mkdirs() }
		journal.addNew(entry(plain.path, "plain"))
		canLook = false

		assertEquals(listOf(false), coordinator.load(emptyMap()).map { it.bundle })
		assertTrue(registry.read().isEmpty())

		canLook = true
		coordinator.load(emptyMap())
		assertEquals(listOf(false), registry.read().map { it.bundle })
	}
}
