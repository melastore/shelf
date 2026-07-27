package io.github.melastore.shelf.data

import io.github.melastore.shelf.crypto.HeaderCipher
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.SecretKey
import kotlin.math.min

/**
 * Random access to one file, so the locking protocol can be exercised against a temp directory in a
 * unit test and against a document provider on a device without knowing the difference.
 */
interface LockTarget {
	fun size(): Long
	fun read(offset: Long, length: Int): ByteArray
	fun write(offset: Long, bytes: ByteArray)
	fun truncate(size: Long)

	/** Must not return until the bytes are on the medium. The whole protocol rests on this. */
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

/** Why a single file could not be locked or unlocked. The folder-level pass records these and goes on. */
enum class LockOutcome { LOCKED, UNLOCKED, ALREADY, EMPTY, WRONG_PASSPHRASE, FAILED }

/**
 * Scrambles the first [SLICE_LENGTH] bytes of a file in place so nothing can open or preview it.
 *
 * This is concealment with teeth, not confidentiality: everything past the slice is still the
 * original bytes on the original sectors, and a carving tool recovers them. What it does defeat is
 * every ordinary reader — a gallery, a file manager's preview, a video player — because none of them
 * can make sense of a file whose header is noise. The price is fixed per file rather than per byte,
 * which is the only reason it can be offered on a folder of video at all.
 *
 * ## Why the order of writes is the whole design
 *
 * Overwriting the head of a file destroys the only copy of those bytes. A process killed midway
 * through would leave the file permanently broken, which for an app whose promise is reversibility is
 * the worst failure available. So the encrypted copy is appended to the end and flushed *first*:
 *
 *  1. append the trailer, sync
 *  2. overwrite the head, sync
 *
 * Killed anywhere in step 2, the trailer still holds a complete authenticated copy of the original
 * head, and [unlock] puts it back. Unlocking reverses it in the same order — write the head, sync,
 * then drop the trailer — and is idempotent, so re-running after any interruption is safe.
 */
object FileLocker {

	const val SLICE_LENGTH = 64 * 1024

	fun isLocked(target: LockTarget): Boolean = readTrailer(target) != null

	/**
	 * @param key derived once by the caller from [salt]; every file gets its own nonce regardless.
	 */
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

			// The recoverable copy lands, and is durable, before the original is touched.
			target.write(size, trailer)
			target.sync()
			target.write(0, sealed.cipherText)
			target.sync()
			LockOutcome.LOCKED
		}.getOrDefault(LockOutcome.FAILED)
	}

	/**
	 * @param keyFor derives the key for a trailer's salt. Files locked in one pass share a salt, so a
	 * caller that caches on it pays for the derivation once rather than once per file.
	 */
	fun unlock(target: LockTarget, keyFor: (ByteArray) -> SecretKey): LockOutcome {
		val trailer = readTrailer(target) ?: return LockOutcome.ALREADY

		val slice = runCatching {
			HeaderCipher.open(trailer.cipherText, trailer.nonce, trailer.tag, keyFor(trailer.salt))
		}.getOrElse {
			// A wrong passphrase fails the GCM tag rather than returning rubbish, which is what stops
			// this from writing garbage over the head of a file it cannot actually recover.
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
