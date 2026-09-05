package io.github.melastore.shelf.data

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EphemeralMediaDataSourceTest {

	@get:Rule val temp = TemporaryFolder()

	private fun file(bytes: ByteArray, name: String = "video.mp4"): File =
		File(temp.root, name).apply { writeBytes(bytes) }

	@Test
	fun `reads entirely from head slice when requested offset is in head`() {
		val head = ByteArray(100) { it.toByte() }
		val remainder = ByteArray(200) { (100 + it).toByte() }
		val f = file(remainder)
		val target = FileLockTarget(f)
		val source = EphemeralMediaDataSource(target, head, 300L)

		val buffer = ByteArray(50)
		val read = source.readAt(10L, buffer, 0, 50)

		assertEquals(50, read)
		val expected = head.sliceArray(10 until 60)
		assertArrayEquals(expected, buffer)
	}

	@Test
	fun `reads across boundary from head slice and file remainder`() {
		val head = ByteArray(100) { it.toByte() }
		val remainder = ByteArray(200) { (100 + it).toByte() }
		val f = file(remainder)
		val target = FileLockTarget(f)
		val source = EphemeralMediaDataSource(target, head, 300L)

		val buffer = ByteArray(80)
		val read = source.readAt(80L, buffer, 0, 80)

		assertEquals(80, read)
		val expected = head.sliceArray(80 until 100) + remainder.sliceArray(100 until 160)
		assertArrayEquals(expected, buffer)
	}

	@Test
	fun `reads entirely from target when offset is past head`() {
		val head = ByteArray(100) { it.toByte() }
		val remainder = ByteArray(200) { (100 + it).toByte() }
		val f = file(remainder)
		val target = FileLockTarget(f)
		val source = EphemeralMediaDataSource(target, head, 300L)

		val buffer = ByteArray(50)
		val read = source.readAt(150L, buffer, 0, 50)

		assertEquals(50, read)
		val expected = remainder.sliceArray(150 until 200)
		assertArrayEquals(expected, buffer)
	}

	@Test
	fun `returns minus one at EOF`() {
		val head = ByteArray(100) { it.toByte() }
		val f = file(ByteArray(0))
		val target = FileLockTarget(f)
		val source = EphemeralMediaDataSource(target, head, 100L)

		val buffer = ByteArray(10)
		assertEquals(-1, source.readAt(100L, buffer, 0, 10))
		assertEquals(-1, source.readAt(150L, buffer, 0, 10))
	}

	@Test
	fun `reports total size correctly`() {
		val head = ByteArray(50)
		val f = file(ByteArray(0))
		val target = FileLockTarget(f)
		val source = EphemeralMediaDataSource(target, head, 12345L)

		assertEquals(12345L, source.size)
	}

	@Test(expected = IOException::class)
	fun `readAt after close throws IOException`() {
		val head = ByteArray(50)
		val f = file(ByteArray(50))
		val target = FileLockTarget(f)
		val source = EphemeralMediaDataSource(target, head, 100L)
		source.close()
		source.readAt(0L, ByteArray(10), 0, 10)
	}
}
