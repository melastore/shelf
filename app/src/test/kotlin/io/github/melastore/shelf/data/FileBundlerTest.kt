package io.github.melastore.shelf.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
class FileBundlerTest {

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

	private fun bundler(hidden: Boolean = false): FileBundler {
		storage = temp.newFolder("storage")
		return FileBundler(
			context,
			StoragePaths.forTest(storage.path, storage.path),
			useRoot = { false },
			direct = ::canWalkAsFile,
			isHidden = { hidden },
			mediaIndex = NoIndex,
			now = { 1_700_000_000_000L },
		)
	}

	private fun pictures(vararg names: String): File = File(storage, "Pictures").apply {
		mkdirs()
		names.forEach { File(this, it).writeBytes("bytes of $it".toByteArray()) }
	}

	private suspend fun FileBundler.made(parent: File): String = (create(parent.path) as BundleResult.Ok).bundlePath

	@Test
	fun `files move into a marked folder next to them`() = runBlocking {
		val bundler = bundler()
		val dir = pictures("a.jpg", "b.mp4", "c.txt")
		val bundle = bundler.made(dir)

		val filled = bundler.fill(bundle, listOf("${dir.path}/a.jpg", "${dir.path}/b.mp4")) as BundleResult.Ok

		assertEquals(2, filled.moved)
		assertTrue(filled.failed.isEmpty())
		assertEquals(dir.path, File(bundle).parent)
		assertTrue(File(bundle, "a.jpg").isFile)
		assertFalse(File(dir, "a.jpg").exists())
		assertTrue("unpicked file moved", File(dir, "c.txt").isFile)
		assertTrue(bundler.isBundle(bundle))
		assertFalse(bundler.isBundle(dir.path))
	}

	@Test
	fun `unwrap puts every byte back and removes the folder`() = runBlocking {
		val bundler = bundler()
		val dir = pictures("a.jpg", "b.mp4")
		val before = dir.listFiles()!!.associate { it.name to it.readBytes() }
		val bundle = bundler.made(dir)
		bundler.fill(bundle, before.keys.map { "${dir.path}/$it" })

		val back = bundler.unwrap(bundle) as BundleResult.Ok

		assertEquals(2, back.moved)
		assertTrue(back.failed.isEmpty())
		assertFalse(File(bundle).exists())
		before.forEach { (name, bytes) -> assertArrayEquals(bytes, File(dir, name).readBytes()) }
	}

	@Test
	fun `a name taken meanwhile is never overwritten`() = runBlocking {
		val bundler = bundler()
		val dir = pictures("a.jpg")
		val bundle = bundler.made(dir)
		bundler.fill(bundle, listOf("${dir.path}/a.jpg"))
		File(dir, "a.jpg").writeText("new one")

		bundler.unwrap(bundle)

		assertEquals("new one", File(dir, "a.jpg").readText())
		assertEquals("bytes of a.jpg", File(dir, "a (1).jpg").readText())
		assertFalse(File(bundle).exists())
	}

	@Test
	fun `files that cannot go are listed and the rest still move`() = runBlocking {
		val bundler = bundler()
		val dir = pictures("a.jpg")
		val other = File(storage, "Music").apply { mkdirs() }
		File(other, "song.mp3").writeText("x")
		val bundle = bundler.made(dir)

		val filled = bundler.fill(
			bundle,
			listOf("${dir.path}/a.jpg", "${dir.path}/gone.jpg", "${other.path}/song.mp3"),
		) as BundleResult.Ok

		assertEquals(1, filled.moved)
		assertEquals(setOf("gone.jpg", "song.mp3"), filled.failed.toSet())
		assertTrue(File(other, "song.mp3").isFile)
	}

	@Test
	fun `leftover bookkeeping goes with the folder`() = runBlocking {
		val bundler = bundler()
		val dir = pictures("a.jpg")
		val bundle = bundler.made(dir)
		bundler.fill(bundle, listOf("${dir.path}/a.jpg"))
		File(bundle, ".nomedia").createNewFile()
		File(bundle, ".shelf-origin").writeText("x")

		val back = bundler.unwrap(bundle) as BundleResult.Ok

		assertTrue(back.failed.isEmpty())
		assertFalse(File(bundle).exists())
		assertFalse(File(dir, ".nomedia").exists())
		assertTrue(File(dir, "a.jpg").isFile)
	}

	@Test
	fun `a half emptied folder is finished on the next unwrap`() = runBlocking {
		val bundler = bundler()
		val dir = pictures("a.jpg", "b.jpg")
		val bundle = bundler.made(dir)
		bundler.fill(bundle, listOf("${dir.path}/a.jpg", "${dir.path}/b.jpg"))
		// What a kill partway through the first unwrap leaves.
		File(bundle, "a.jpg").renameTo(File(dir, "a.jpg"))

		val back = bundler.unwrap(bundle) as BundleResult.Ok

		assertEquals(1, back.moved)
		assertTrue(back.failed.isEmpty())
		assertTrue(File(dir, "a.jpg").isFile && File(dir, "b.jpg").isFile)
		assertFalse(File(dir, "a (1).jpg").exists())
		assertFalse(File(bundle).exists())
		// And once more, with the folder already gone.
		assertTrue(bundler.unwrap(bundle) is BundleResult.Ok)
	}

	@Test
	fun `a second bundle in the same minute gets a suffix`() = runBlocking {
		val bundler = bundler()
		val dir = pictures()

		val first = bundler.made(dir)
		val second = bundler.made(dir)

		assertTrue(first != second)
		assertTrue(second.endsWith("-2"))
		assertTrue(File(first, FileBundler.MARKER).isFile && File(second, FileBundler.MARKER).isFile)
	}

	@Test
	fun `a hidden bundle is not touched`() = runBlocking {
		val bundler = bundler(hidden = true)
		val dir = pictures("a.jpg")
		val bundle = "${dir.path}/Files-x"
		File(bundle).mkdirs()
		File(bundle, "b.jpg").writeText("x")

		assertTrue(bundler.unwrap(bundle) is BundleResult.Failed)
		assertTrue(bundler.fill(bundle, listOf("${dir.path}/a.jpg")) is BundleResult.Failed)
		assertTrue(File(bundle, "b.jpg").isFile && File(dir, "a.jpg").isFile)
	}

	@Test
	fun `a named bundle takes the name and a suffix when it is taken`() = runBlocking {
		val bundler = bundler()
		val dir = pictures()
		File(dir, "Trip").mkdirs()

		val made = (bundler.create(dir.path, name = "Trip") as BundleResult.Ok).bundlePath

		assertEquals("${dir.path}/Trip-2", made)
		assertTrue(bundler.isBundle(made))
		assertFalse(bundler.isBundle("${dir.path}/Trip"))
	}

	@Test
	fun `adding to a bundle never replaces what is already in it`() = runBlocking {
		val bundler = bundler()
		val dir = pictures("a.jpg")
		val bundle = bundler.made(dir)
		bundler.fill(bundle, listOf("${dir.path}/a.jpg"))
		File(dir, "a.jpg").writeText("second")

		val added = bundler.fill(bundle, listOf("${dir.path}/a.jpg")) as BundleResult.Ok

		assertEquals(1, added.moved)
		assertEquals("bytes of a.jpg", File(bundle, "a.jpg").readText())
		assertEquals("second", File(bundle, "a (1).jpg").readText())
		bundler.unwrap(bundle)
		assertEquals(setOf("a.jpg", "a (1).jpg"), dir.list()!!.toSet())
	}

	@Test
	fun `folder names that would hide or break the folder are refused`() {
		assertEquals("Trip", FileBundler.folderName("  Trip "))
		listOf("", "   ", ".Trip", "a/b", "a:b", "a*b", "a?b", "a\\b", "x".repeat(FileBundler.MAX_NAME + 1))
			.forEach { assertEquals(it, null, FileBundler.folderName(it)) }
	}

	@Test
	fun `take out moves only the named files and keeps the folder`() = runBlocking {
		val bundler = bundler()
		val dir = pictures("a.jpg", "b.mp4", "c.txt")
		val bundle = bundler.made(dir)
		bundler.fill(bundle, listOf("${dir.path}/a.jpg", "${dir.path}/b.mp4", "${dir.path}/c.txt"))
		File(dir, "a.jpg").writeText("new one")

		val taken = bundler.takeOut(bundle, listOf("a.jpg", "c.txt", "missing.png")) as BundleResult.Ok

		assertEquals(2, taken.moved)
		assertEquals(listOf("missing.png"), taken.failed)
		assertEquals("new one", File(dir, "a.jpg").readText())
		assertEquals("bytes of a.jpg", File(dir, "a (1).jpg").readText())
		assertEquals("bytes of c.txt", File(dir, "c.txt").readText())
		assertTrue(File(bundle, "b.mp4").isFile)
		assertEquals(true, bundler.hasFiles(bundle))
	}

	@Test
	fun `take out never moves the marker, and an emptied bundle reports no files`() = runBlocking {
		val bundler = bundler()
		val dir = pictures("a.jpg")
		val bundle = bundler.made(dir)
		bundler.fill(bundle, listOf("${dir.path}/a.jpg"))

		val taken = bundler.takeOut(bundle, listOf("a.jpg", FileBundler.MARKER)) as BundleResult.Ok

		assertEquals(1, taken.moved)
		assertEquals(listOf(FileBundler.MARKER), taken.failed)
		assertTrue(File(bundle, FileBundler.MARKER).isFile)
		assertFalse(File(dir, FileBundler.MARKER).exists())
		assertEquals(false, bundler.hasFiles(bundle))
	}

	@Test
	fun `take out refuses a hidden bundle`() = runBlocking {
		val bundler = bundler(hidden = true)
		val dir = pictures()
		File(dir, "Files-x").mkdirs()

		val taken = bundler.takeOut("${dir.path}/Files-x", listOf("a.jpg"))

		assertTrue(taken is BundleResult.Failed)
	}
}
