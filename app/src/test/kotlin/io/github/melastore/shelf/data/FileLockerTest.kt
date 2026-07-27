package io.github.melastore.shelf.data

import io.github.melastore.shelf.crypto.HeaderCipher
import java.io.File
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileLockerTest {

	@get:Rule val temp = TemporaryFolder()

	private val salt = ByteArray(HeaderCipher.SALT_LENGTH) { it.toByte() }
	private val key: SecretKey by lazy { HeaderCipher.deriveKey("a passphrase".toCharArray(), salt) }
	private val wrongKey: SecretKey by lazy { HeaderCipher.deriveKey("another one".toCharArray(), salt) }

	private fun file(bytes: ByteArray, name: String = "photo.jpg"): File =
		File(temp.root, name).apply { writeBytes(bytes) }

	private fun content(size: Int): ByteArray = ByteArray(size) { (it * 31 % 251).toByte() }

	@Test
	fun `round trip restores the file byte for byte`() {
		val original = content(200_000)
		val f = file(original)

		assertEquals(LockOutcome.LOCKED, FileLocker.lock(FileLockTarget(f), key, salt))
		assertEquals(LockOutcome.UNLOCKED, FileLocker.unlock(FileLockTarget(f)) { key })

		assertArrayEquals(original, f.readBytes())
	}

	@Test
	fun `a locked file has an unreadable head and its original length is not exposed by its size`() {
		val original = content(200_000)
		val f = file(original)

		FileLocker.lock(FileLockTarget(f), key, salt)

		val locked = f.readBytes()
		assertNotEquals(
			original.take(FileLocker.SLICE_LENGTH),
			locked.take(FileLocker.SLICE_LENGTH),
		)
		assertTrue("trailer should extend the file", f.length() > original.size)
		assertTrue(FileLocker.isLocked(FileLockTarget(f)))
	}

	@Test
	fun `a file smaller than one slice is locked whole`() {
		val original = content(100)
		val f = file(original)

		assertEquals(LockOutcome.LOCKED, FileLocker.lock(FileLockTarget(f), key, salt))
		assertNotEquals(original.toList(), f.readBytes().take(100))

		FileLocker.unlock(FileLockTarget(f)) { key }
		assertArrayEquals(original, f.readBytes())
	}

	@Test
	fun `an empty file is left alone`() {
		val f = file(ByteArray(0))

		assertEquals(LockOutcome.EMPTY, FileLocker.lock(FileLockTarget(f), key, salt))
		assertEquals(0, f.length())
	}

	@Test
	fun `locking twice does not double lock and lose the original head`() {
		val original = content(90_000)
		val f = file(original)

		assertEquals(LockOutcome.LOCKED, FileLocker.lock(FileLockTarget(f), key, salt))
		val afterFirst = f.readBytes()
		assertEquals(LockOutcome.ALREADY, FileLocker.lock(FileLockTarget(f), key, salt))

		assertArrayEquals(afterFirst, f.readBytes())
		FileLocker.unlock(FileLockTarget(f)) { key }
		assertArrayEquals(original, f.readBytes())
	}

	@Test
	fun `the wrong passphrase is refused without touching the file`() {
		val original = content(90_000)
		val f = file(original)
		FileLocker.lock(FileLockTarget(f), key, salt)
		val locked = f.readBytes()

		assertEquals(LockOutcome.WRONG_PASSPHRASE, FileLocker.unlock(FileLockTarget(f)) { wrongKey })

		assertArrayEquals("a refused unlock must not write", locked, f.readBytes())
		// And the right one still works afterwards.
		assertEquals(LockOutcome.UNLOCKED, FileLocker.unlock(FileLockTarget(f)) { key })
		assertArrayEquals(original, f.readBytes())
	}

	@Test
	fun `an ordinary file is not mistaken for a locked one`() {
		assertFalse(FileLocker.isLocked(FileLockTarget(file(content(5_000)))))
		assertFalse(FileLocker.isLocked(FileLockTarget(file(ByteArray(0)))))
		assertFalse(FileLocker.isLocked(FileLockTarget(file(content(10)))))
		assertEquals(LockOutcome.ALREADY, FileLocker.unlock(FileLockTarget(file(content(5_000)))) { key })
	}

	// The crash cases. These are the reason the trailer is written first, so they are the tests that
	// actually justify the design.

	@Test
	fun `killed after the trailer is written but before the head is overwritten`() {
		val original = content(200_000)
		val f = file(original)

		// Step 1 only: the trailer is durable, the head is still the original.
		FileLocker.lock(CrashingTarget(f, failWritesAfter = 1), key, salt)

		assertTrue("the trailer landed", FileLocker.isLocked(FileLockTarget(f)))
		assertEquals(LockOutcome.UNLOCKED, FileLocker.unlock(FileLockTarget(f)) { key })
		assertArrayEquals(original, f.readBytes())
	}

	@Test
	fun `killed part way through overwriting the head`() {
		val original = content(200_000)
		val f = file(original)
		FileLocker.lock(FileLockTarget(f), key, salt)

		// Simulate a torn head write: half ciphertext, half whatever was there before.
		val torn = f.readBytes()
		for (i in 0 until 20_000) torn[i] = 0
		f.writeBytes(torn)

		assertEquals(LockOutcome.UNLOCKED, FileLocker.unlock(FileLockTarget(f)) { key })
		assertArrayEquals("the trailer is the source of truth", original, f.readBytes())
	}

	@Test
	fun `killed part way through unlocking is recovered by running it again`() {
		val original = content(200_000)
		val f = file(original)
		FileLocker.lock(FileLockTarget(f), key, salt)

		// Head written back, but the process died before the trailer was truncated away.
		FileLocker.unlock(CrashingTarget(f, failTruncate = true)) { key }
		assertTrue("still marked as locked", FileLocker.isLocked(FileLockTarget(f)))

		assertEquals(LockOutcome.UNLOCKED, FileLocker.unlock(FileLockTarget(f)) { key })
		assertArrayEquals(original, f.readBytes())
	}

	@Test
	fun `a truncated trailer leaves the file recognisable as unlocked rather than corrupt`() {
		val original = content(200_000)
		val f = file(original)
		FileLocker.lock(FileLockTarget(f), key, salt)
		FileLocker.unlock(FileLockTarget(f)) { key }

		// The footer is gone, so nothing will try to decrypt this file again.
		assertFalse(FileLocker.isLocked(FileLockTarget(f)))
		assertEquals(original.size.toLong(), f.length())
	}

	/** Stops at a chosen point in the protocol, the way a killed process would. */
	private class CrashingTarget(
		file: File,
		private val failWritesAfter: Int = Int.MAX_VALUE,
		private val failTruncate: Boolean = false,
	) : LockTarget {
		private val delegate = FileLockTarget(file)
		private var writes = 0

		override fun size(): Long = delegate.size()
		override fun read(offset: Long, length: Int): ByteArray = delegate.read(offset, length)
		override fun sync() = delegate.sync()

		override fun write(offset: Long, bytes: ByteArray) {
			if (writes >= failWritesAfter) throw java.io.IOException("process died")
			writes++
			delegate.write(offset, bytes)
		}

		override fun truncate(size: Long) {
			if (failTruncate) throw java.io.IOException("process died")
			delegate.truncate(size)
		}
	}
}
