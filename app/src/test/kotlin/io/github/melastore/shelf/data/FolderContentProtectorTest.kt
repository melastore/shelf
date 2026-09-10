package io.github.melastore.shelf.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderContentProtectorTest {

	@get:Rule val temp = TemporaryFolder()

	private val context: Application get() = ApplicationProvider.getApplicationContext()

	@Test
	fun `protect and restore cover every file under a folder`() = runBlocking {
		val root = temp.newFolder("album")
		val first = File(root, "one.jpg").apply { writeBytes(ByteArray(90_000) { it.toByte() }) }
		val second = File(root, "nested/two.mp4").apply {
			parentFile?.mkdirs()
			writeBytes(ByteArray(8_000) { (it % 41).toByte() })
		}
		val beforeFirst = first.readBytes()
		val beforeSecond = second.readBytes()
		val password = "48261357".toCharArray()
		val protector = FolderContentProtector(
			context,
			paths(root),
			credential = { password.copyOf() },
		)

		val protected = protector.protect(root.path)

		assertEquals(ContentProtectionResult.Done(2), protected)
		assertTrue(FileLocker.isLocked(FileLockTarget(first)))
		assertTrue(FileLocker.isLocked(FileLockTarget(second)))

		assertEquals(ContentProtectionResult.Done(2), protector.restore(root.path))
		assertArrayEquals(beforeFirst, first.readBytes())
		assertArrayEquals(beforeSecond, second.readBytes())
		password.fill(' ')
	}

	@Test
	fun `a second pass leaves the files the first one locked alone`() = runBlocking {
		val root = temp.newFolder("resumed")
		val first = File(root, "one.jpg").apply { writeBytes(ByteArray(9_000) { it.toByte() }) }
		val password = "48261357".toCharArray()
		val protector = FolderContentProtector(context, paths(root), credential = { password.copyOf() })

		assertEquals(ContentProtectionResult.Done(1), protector.protect(root.path))
		val lockedBytes = first.readBytes()

		// What an interrupted hide leaves behind: one file locked, one added since.
		val second = File(root, "two.jpg").apply { writeBytes(ByteArray(4_000) { (it % 7).toByte() }) }

		assertEquals(ContentProtectionResult.Done(1), protector.protect(root.path))
		assertArrayEquals(lockedBytes, first.readBytes())
		assertTrue(FileLocker.isLocked(FileLockTarget(second)))
		password.fill(' ')
	}

	@Test
	fun `an already protected folder needs no credential`() = runBlocking {
		val root = temp.newFolder("done")
		File(root, "one.jpg").apply { writeBytes(ByteArray(9_000) { it.toByte() }) }
		val password = "48261357".toCharArray()
		val withCredential = FolderContentProtector(context, paths(root), credential = { password.copyOf() })
		assertEquals(ContentProtectionResult.Done(1), withCredential.protect(root.path))

		val without = FolderContentProtector(context, paths(root), credential = { null })

		assertEquals(ContentProtectionResult.Done(0), without.protect(root.path))
		password.fill(' ')
	}

	@Test
	fun `shelf's own bookkeeping files are never part of the lock set`() = runBlocking {
		val root = temp.newFolder("marked")
		val photo = File(root, "one.jpg").apply { writeBytes(ByteArray(9_000) { it.toByte() }) }
		// What RootChmodHider drops in before protecting, at mode 600 and owned by root on a device.
		val marker = File(root, ".shelf-recovery-v1-2770-10181-1023").apply { writeBytes(ByteArray(0)) }
		val manifest = File(root, ".shelf-names-v1").apply { writeBytes(ByteArray(64) { it.toByte() }) }
		val manifestBefore = manifest.readBytes()
		val password = "48261357".toCharArray()
		val protector = FolderContentProtector(context, paths(root), credential = { password.copyOf() })

		assertEquals(ContentProtectionResult.Done(1), protector.protect(root.path))

		assertTrue(FileLocker.isLocked(FileLockTarget(photo)))
		assertEquals(0L, marker.length())
		assertArrayEquals(manifestBefore, manifest.readBytes())
		password.fill(' ')
	}

	@Test
	fun `a folder with nothing locked restores without a credential`() = runBlocking {
		val root = temp.newFolder("clear")
		val file = File(root, "one.jpg").apply { writeBytes(ByteArray(9_000) { it.toByte() }) }
		val before = file.readBytes()
		val protector = FolderContentProtector(context, paths(root), credential = { null })

		assertEquals(ContentProtectionResult.NoFiles, protector.restore(root.path))
		assertArrayEquals(before, file.readBytes())
	}

	@Test
	fun `locked content is not restored without the primary credential`() = runBlocking {
		val root = temp.newFolder("locked")
		val file = File(root, "private.jpg").apply { writeBytes(ByteArray(4_000) { it.toByte() }) }
		val password = "48261357".toCharArray()
		val withCredential = FolderContentProtector(
			context,
			paths(root),
			credential = { password.copyOf() },
		)
		withCredential.protect(root.path)

		val withoutCredential = FolderContentProtector(
			context,
			paths(root),
			credential = { null },
		)

		assertEquals(ContentProtectionResult.CredentialRequired, withoutCredential.restore(root.path))
		assertTrue(FileLocker.isLocked(FileLockTarget(file)))
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
