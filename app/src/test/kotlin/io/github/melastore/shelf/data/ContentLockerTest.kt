package io.github.melastore.shelf.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ContentLockerTest {

	@get:Rule val temp = TemporaryFolder()

	private val locker = ContentLocker()
	private val passphrase get() = "the folder passphrase".toCharArray()

	private fun folder(): File = temp.newFolder("album").apply {
		File(this, "one.jpg").writeBytes(ByteArray(80_000) { it.toByte() })
		File(this, "two.mp4").writeBytes(ByteArray(300_000) { (it % 97).toByte() })
		File(this, "nested").mkdirs()
		File(this, "nested/three.png").writeBytes(ByteArray(4_000) { (it % 13).toByte() })
		File(this, "empty.txt").writeBytes(ByteArray(0))
	}

	private fun snapshot(root: File): Map<String, ByteArray> = root.walkTopDown()
		.filter { it.isFile }
		.associate { it.relativeTo(root).path to it.readBytes() }

	@Test
	fun `a folder round trips through lock and unlock`() = runBlocking {
		val root = folder()
		val before = snapshot(root)

		val locked = locker.lock(ContentLocker.targetsUnder(root), passphrase)
		assertEquals(3, locked.changed)
		assertEquals(1, locked.skipped) // the empty file
		assertEquals(0, locked.failed)

		assertTrue(
			"every non-empty file should be unreadable",
			root.walkTopDown().filter { it.isFile && it.length() > 0 }
				.all { FileLocker.isLocked(FileLockTarget(it)) },
		)

		val unlocked = locker.unlock(ContentLocker.targetsUnder(root), passphrase)
		assertEquals(3, unlocked.changed)

		val after = snapshot(root)
		assertEquals(before.keys, after.keys)
		before.forEach { (name, bytes) -> assertArrayEquals(name, bytes, after.getValue(name)) }
	}

	@Test
	fun `the wrong passphrase changes nothing and says so`() = runBlocking {
		val root = folder()
		locker.lock(ContentLocker.targetsUnder(root), passphrase)
		val locked = snapshot(root)

		val result = locker.unlock(ContentLocker.targetsUnder(root), "not it at all".toCharArray())

		assertTrue(result.wrongPassphrase)
		assertEquals(0, result.changed)
		snapshot(root).forEach { (name, bytes) -> assertArrayEquals(name, locked.getValue(name), bytes) }
	}

	@Test
	fun `locking an already locked folder is a no-op rather than a second layer`() = runBlocking {
		val root = folder()
		locker.lock(ContentLocker.targetsUnder(root), passphrase)
		val once = snapshot(root)

		val again = locker.lock(ContentLocker.targetsUnder(root), passphrase)
		assertEquals(0, again.changed)
		assertEquals(3, again.alreadyDone)
		snapshot(root).forEach { (name, bytes) -> assertArrayEquals(name, once.getValue(name), bytes) }
	}

	@Test
	fun `a folder locked in two passes with different salts still unlocks in one`() = runBlocking {
		val root = folder()
		locker.lock(ContentLocker.targetsUnder(root), passphrase)

		// A file added later, locked separately, therefore carrying a different salt.
		val late = File(root, "four.jpg").apply { writeBytes(ByteArray(50_000) { (it % 7).toByte() }) }
		val lateBytes = late.readBytes()
		locker.lock(listOf(FileLockTarget(late)), passphrase)

		val unlocked = locker.unlock(ContentLocker.targetsUnder(root), passphrase)

		assertEquals(4, unlocked.changed)
		assertArrayEquals(lateBytes, late.readBytes())
		assertFalse(FileLocker.isLocked(FileLockTarget(late)))
	}

	@Test
	fun `unlocking a folder that was never locked reports nothing changed`() = runBlocking {
		val root = folder()
		val before = snapshot(root)

		val result = locker.unlock(ContentLocker.targetsUnder(root), passphrase)

		assertEquals(0, result.changed)
		assertFalse(result.wrongPassphrase)
		snapshot(root).forEach { (name, bytes) -> assertArrayEquals(name, before.getValue(name), bytes) }
	}
}
