package io.github.melastore.shelf.data

import io.github.melastore.shelf.crypto.HeaderCipher
import java.io.File
import javax.crypto.SecretKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
	fun `locking and unlocking preserves original lastModified timestamp`() {
		val original = content(50_000)
		val f = file(original)
		val pastTimestamp = 1600000000000L
		f.setLastModified(pastTimestamp)
		assertEquals(pastTimestamp, f.lastModified())

		assertEquals(LockOutcome.LOCKED, FileLocker.lock(FileLockTarget(f), key, salt))
		assertEquals(pastTimestamp, f.lastModified())

		assertEquals(LockOutcome.UNLOCKED, FileLocker.unlock(FileLockTarget(f)) { key })
		assertEquals(pastTimestamp, f.lastModified())
	}

	@Test
	fun `legacy SHLCK001 trailer without padding decodes and unlocks properly`() {
		val original = content(10_000)
		val f = file(original)
		FileLocker.lock(FileLockTarget(f), key, salt, paddingBytes = 0)
		assertTrue(FileLocker.isLocked(FileLockTarget(f)))

		val trailer = FileLocker.readTrailer(FileLockTarget(f))
		requireNotNull(trailer)
		assertEquals(0, trailer.padding.size)

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

	@Test
	fun `openStream reads full original content in-memory without altering disk state`() {
		val original = content(200_000)
		val f = file(original)
		val target = FileLockTarget(f)
		FileLocker.lock(target, key, salt)
		assertTrue("file is locked on disk", FileLocker.isLocked(target))

		val stream = FileLocker.openStream(target) { key }
		requireNotNull(stream)
		val readBytes = stream.use { it.readBytes() }

		assertTrue("decrypted stream matches original bytes", original.contentEquals(readBytes))
		assertTrue("file remains locked on disk", FileLocker.isLocked(target))
	}

	@Test
	fun `a target whose size cannot be read is reported failed rather than thrown out of`() {
		val target = UnreadableTarget()

		assertEquals(0L, target.sizeOrZero())
		assertEquals(LockOutcome.FAILED, FileLocker.lock(target, key, salt))
		assertNull(FileLocker.readTrailer(target))
		assertNull(FileLocker.openStream(target) { key })
	}

	/** A document whose grant went stale: every call throws the way a content provider does. */
	private class UnreadableTarget : LockTarget {
		override val name: String get() = "gone.jpg"

		private fun gone(): Nothing = throw java.io.FileNotFoundException("open failed: EACCES")

		override fun size(): Long = gone()
		override fun read(offset: Long, length: Int): ByteArray = gone()
		override fun write(offset: Long, bytes: ByteArray) = gone()
		override fun truncate(size: Long) = gone()
		override fun sync() = gone()
	}

	@Test
	fun `locking a file opens it once, not once per operation`() {
		val pooled = CountingTarget(FileLockTarget(file(content(90_000), "pooled.jpg")))
		// Counted from inside, so every call the protocol makes lands on the counter rather than on
		// a session that would have absorbed it.
		val plain = CountingTarget(FileLockTarget(file(content(90_000), "plain.jpg")))
		val unpooled = NoSessionTarget(plain)

		assertEquals(LockOutcome.LOCKED, FileLocker.lock(pooled, key, salt))
		assertEquals(LockOutcome.LOCKED, FileLocker.lock(unpooled, key, salt))

		// Every one of these is a Binder round trip to a document provider on a device, which is
		// the whole cost of hiding a folder.
		assertEquals("a pooled lock should open the file once", 1, pooled.opens)
		assertTrue(
			"the unpooled path should be the expensive one this replaces, was ${plain.opens}",
			plain.opens >= 8,
		)
	}

	/** Counts what each call would cost as an open. A session counts once, however much it serves. */
	private class CountingTarget(private val delegate: LockTarget) : LockTarget {
		var opens = 0
			private set

		override val name: String get() = delegate.name

		override fun <T> withSession(mode: String, block: (LockTarget) -> T): T {
			opens++
			return delegate.withSession(mode, block)
		}

		override fun size(): Long = delegate.size().also { opens++ }
		override fun read(offset: Long, length: Int): ByteArray = delegate.read(offset, length).also { opens++ }

		override fun write(offset: Long, bytes: ByteArray) {
			opens++
			delegate.write(offset, bytes)
		}

		override fun truncate(size: Long) {
			opens++
			delegate.truncate(size)
		}

		override fun sync() {
			opens++
			delegate.sync()
		}
	}

	/** A target with no pooling of its own, the way every target behaved before sessions existed. */
	private class NoSessionTarget(private val delegate: LockTarget) : LockTarget by delegate {
		override fun <T> withSession(mode: String, block: (LockTarget) -> T): T = block(this)
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
