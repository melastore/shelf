package io.github.melastore.shelf.data

import io.github.melastore.shelf.crypto.HeaderCipher
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.SecretKey
import kotlin.math.min

/**
 * Random access to one file, so the locking protocol runs against a temp directory in a unit test
 * and a document provider on a device without knowing the difference.
 */
interface LockTarget {
	fun size(): Long
	fun read(offset: Long, length: Int): ByteArray
	fun write(offset: Long, bytes: ByteArray)
	fun truncate(size: Long)

	/** Must not return until the bytes are on the medium. The protocol rests on this. */
	fun sync()
}

class FileLockTarget(private val file: File) : LockTarget {
	override fun size(): Long = file.length()

	override fun read(offset: Long, length: Int): ByteArray = RandomAccessFile(file, "r").use { raf ->
		raf.seek(offset)
		ByteArray(length).also(raf::readFully)
	}

	override fun write(offset: Long, bytes: ByteArray) = RandomAccessFile(file, "rwd").use { raf ->
		raf.seek(offset)
		raf.write(bytes)
	}

	override fun truncate(size: Long) = RandomAccessFile(file, "rwd").use { it.setLength(size) }

	override fun sync() = RandomAccessFile(file, "rw").use { it.fd.sync() }
}

/** What happened to one file. The folder-level pass records these and carries on. */
enum class LockOutcome { LOCKED, UNLOCKED, ALREADY, EMPTY, WRONG_PASSPHRASE, FAILED }

/**
 * Scrambles the first [SLICE_LENGTH] bytes of a file in place so nothing can open or preview it.
 *
 * Concealment, not confidentiality. Everything past the slice is still the original bytes on the
 * original sectors and a carving tool recovers them. What it does stop is every ordinary reader:
 * a gallery, a preview, a video player. The cost is fixed per file rather than per byte, which is
 * the only reason it can be offered on a folder of video.
 *
 * The order of writes is the whole thing. Overwriting the head destroys the only copy of those
 * bytes, so the encrypted copy is appended and flushed first:
 *
 *  1. append the trailer, sync
 *  2. overwrite the head, sync
 *
 * Killed anywhere in step 2, the trailer still holds the original head and [unlock] puts it back.
 * Unlocking reverses the same order (write head, sync, drop trailer) and is idempotent, so
 * re-running after an interruption is safe.
 */
object FileLocker {

	const val SLICE_LENGTH = 64 * 1024

	fun isLocked(target: LockTarget): Boolean = readTrailer(target) != null

	/** @param key derived once by the caller from [salt]. Every file still gets its own nonce. */
	fun lock(target: LockTarget, key: SecretKey, salt: ByteArray): LockOutcome {
		val size = target.size()
		if (size == 0L) return LockOutcome.EMPTY
		if (isLocked(target)) return LockOutcome.ALREADY

		return runCatching {
			val sliceLength = min(SLICE_LENGTH.toLong(), size).toInt()
			val slice = target.read(0, sliceLength)
			val sealed = HeaderCipher.seal(slice, key)
			val trailer = LockTrailerCodec.encode(
				LockTrailer(sealed.cipherText, salt, sealed.nonce, sealed.tag, size),
			)

			// The recoverable copy is durable before the original is touched.
			target.write(size, trailer)
			target.sync()
			target.write(0, sealed.cipherText)
			target.sync()
			LockOutcome.LOCKED
		}.getOrDefault(LockOutcome.FAILED)
	}

	/**
	 * @param keyFor derives the key for a trailer's salt. Files locked in one pass share a salt, so a
	 * caller that caches on it derives once instead of once per file.
	 */
	fun unlock(target: LockTarget, keyFor: (ByteArray) -> SecretKey): LockOutcome {
		val trailer = readTrailer(target) ?: return LockOutcome.ALREADY

		val slice = runCatching {
			HeaderCipher.open(trailer.cipherText, trailer.nonce, trailer.tag, keyFor(trailer.salt))
		}.getOrElse {
			// A wrong passphrase fails the GCM tag rather than returning rubbish, so nothing gets
			// written over the head of a file we cannot actually recover.
			return LockOutcome.WRONG_PASSPHRASE
		}

		return runCatching {
			target.write(0, slice)
			target.sync()
			target.truncate(trailer.originalSize)
			target.sync()
			LockOutcome.UNLOCKED
		}.getOrDefault(LockOutcome.FAILED)
	}

	private fun readTrailer(target: LockTarget): LockTrailer? = runCatching {
		val size = target.size()
		if (size < LockTrailerCodec.FOOTER_SIZE) return null
		val footer = target.read(size - LockTrailerCodec.FOOTER_SIZE, LockTrailerCodec.FOOTER_SIZE)
		val total = LockTrailerCodec.totalLength(footer) ?: return null
		if (total > size) return null
		LockTrailerCodec.decode(target.read(size - total, total))
	}.getOrNull()
}
