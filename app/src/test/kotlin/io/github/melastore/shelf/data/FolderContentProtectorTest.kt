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
