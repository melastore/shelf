package io.github.melastore.shelf.data

import android.app.Application
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
class FolderNameProtectorTest {

	@get:Rule val temp = TemporaryFolder()

	private val context: Application get() = ApplicationProvider.getApplicationContext()

	@Test
	fun `protect replaces names and encrypts their recovery manifest`() = runBlocking {
		val root = temp.newFolder("album")
		val firstBytes = "first secret".toByteArray()
		val secondBytes = "second secret".toByteArray()
		File(root, "holiday photo.jpg").writeBytes(firstBytes)
		File(root, "nested/秘密.txt").apply {
			parentFile?.mkdirs()
			writeBytes(secondBytes)
		}
		val password = "48261357".toCharArray()
		val protector = protector(root, password)

		assertEquals(NameProtectionResult.Done(2), protector.protect(root.path))
		assertFalse(File(root, "holiday photo.jpg").exists())
		assertFalse(File(root, "nested/秘密.txt").exists())

		val opaque = root.walkTopDown().filter { it.isFile && it.name.startsWith("sfn_") }.toList()
		assertEquals(2, opaque.size)
		assertTrue(opaque.all { it.name.matches(Regex("sfn_[a-f0-9]{32}")) })
		assertTrue(opaque.any { it.readBytes().contentEquals(firstBytes) })
		assertTrue(opaque.any { it.readBytes().contentEquals(secondBytes) })

		val manifest = File(root, ".shelf-names-v1")
		assertTrue(manifest.isFile)
		val encrypted = manifest.readBytes().toString(Charsets.ISO_8859_1)
		assertFalse(encrypted.contains("holiday photo.jpg"))
		assertFalse(encrypted.contains("秘密.txt"))
		assertEquals(NameProtectionResult.Done(0), protector.protect(root.path))

		assertEquals(NameProtectionResult.Done(2), protector.restore(root.path))
		assertArrayEquals(firstBytes, File(root, "holiday photo.jpg").readBytes())
		assertArrayEquals(secondBytes, File(root, "nested/秘密.txt").readBytes())
		assertFalse(manifest.exists())
		password.fill(' ')
	}

	@Test
	fun `restore resumes safely when one name was already restored`() = runBlocking {
		val root = temp.newFolder("interrupted")
		File(root, "one.txt").writeText("one")
		File(root, "two.txt").writeText("two")
		val password = "48261357".toCharArray()
		val protector = protector(root, password)

		assertEquals(NameProtectionResult.Done(2), protector.protect(root.path))
		val firstOpaque = requireNotNull(
			root.listFiles()?.firstOrNull { it.name.startsWith("sfn_") },
		)
		val originalName = if (firstOpaque.readText() == "one") "one.txt" else "two.txt"
		assertTrue(firstOpaque.renameTo(File(root, originalName)))

		assertEquals(NameProtectionResult.Done(1), protector.restore(root.path))
		assertEquals("one", File(root, "one.txt").readText())
		assertEquals("two", File(root, "two.txt").readText())
		assertFalse(File(root, ".shelf-names-v1").exists())
		password.fill(' ')
	}

	@Test
	fun `encrypted names are not restored without the primary credential`() = runBlocking {
		val root = temp.newFolder("locked")
		File(root, "private.jpg").writeText("secret")
		val password = "48261357".toCharArray()
		assertEquals(NameProtectionResult.Done(1), protector(root, password).protect(root.path))

		val withoutCredential = FolderNameProtector(
			context,
			paths(root),
			credential = { null },
		)

		assertEquals(NameProtectionResult.CredentialRequired, withoutCredential.restore(root.path))
		assertFalse(File(root, "private.jpg").exists())
		assertTrue(File(root, ".shelf-names-v1").isFile)
		password.fill(' ')
	}

	@Test
	fun `restore recognizes an opaque filename adjusted by a storage provider`() = runBlocking {
		val root = temp.newFolder("provider-adjusted")
		File(root, "photo.jpg").writeText("original")
		val password = "48261357".toCharArray()
		val protector = protector(root, password)
		assertEquals(NameProtectionResult.Done(1), protector.protect(root.path))
		val opaque = requireNotNull(root.listFiles()?.singleOrNull { it.name.startsWith("sfn_") })
		assertTrue(opaque.renameTo(File(root, "${opaque.name}.bin")))

		assertEquals(NameProtectionResult.Done(1), protector.restore(root.path))

		assertEquals("original", File(root, "photo.jpg").readText())
		assertFalse(File(root, ".shelf-names-v1").exists())
		password.fill(' ')
	}

	@Test
	fun `restore preserves both files when an original name reappears`() = runBlocking {
		val root = temp.newFolder("collision")
		File(root, "note.txt").writeText("hidden version")
		val password = "48261357".toCharArray()
		val protector = protector(root, password)
		assertEquals(NameProtectionResult.Done(1), protector.protect(root.path))
		File(root, "note.txt").writeText("new version")

		assertEquals(NameProtectionResult.Done(1), protector.restore(root.path))

		assertEquals("new version", File(root, "note.txt").readText())
		val recovered = root.listFiles()?.singleOrNull { it.name.startsWith("note (Shelf recovered ") }
		assertEquals("hidden version", requireNotNull(recovered).readText())
		assertFalse(File(root, ".shelf-names-v1").exists())
		password.fill(' ')
	}

	@Test
	fun `a manifest this view cannot read leaves the folder to the provider`() = runBlocking {
		val root = temp.newFolder("provider-written")
		File(root, "clip.mp4").writeBytes("payload".toByteArray())
		// Stands in for a manifest written through a document provider: it stats fine and will not
		// open. There is no grant in this test, so the fall-through has nowhere left to go.
		File(root, FolderNameProtector.MANIFEST).writeBytes(ByteArray(0))
		val password = "48261357".toCharArray()

		assertEquals(NameProtectionResult.AccessUnavailable, protector(root, password).protect(root.path))
		password.fill(' ')
	}

	private fun protector(root: File, password: CharArray) = FolderNameProtector(
		context,
		paths(root),
		credential = { password.copyOf() },
	)

	/**
	 * Scoped storage lists the directories inside a folder this app holds no permission for and
	 * hides the files in it. A subdirectory was enough to make the folder look walkable, and the walk
	 * that found no files reported the folder protected without touching a name.
	 */
	@Test
	fun `a folder whose files the file view cannot see is not reported as protected`() = runBlocking {
		val root = temp.newFolder("opaque")
		File(root, "nested").mkdirs()
		val password = "48261357".toCharArray()
		val protector = FolderNameProtector(context, paths(root), credential = { password.copyOf() })

		// No provider grant in a unit test, so the only honest answer left is that neither view
		// reached the folder.
		assertEquals(NameProtectionResult.AccessUnavailable, protector.protect(root.path))
		assertEquals(listOf("nested"), root.list()?.sorted())
		password.fill(' ')
	}

	private fun paths(root: File): StoragePaths {
		val parent = requireNotNull(root.parentFile)
		return StoragePaths.forTest(
			backingRoot = File(parent, "backing").apply { mkdirs() }.path,
			emulatedRoot = parent.path,
		)
	}
}
