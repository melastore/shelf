package io.github.melastore.shelf.data

import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps a spare copy of each locked file's sealed header in app-private storage.
 *
 * Writing the header back in place is the one step Shelf cannot make atomic: `dd` overwrites the
 * front of somebody else's file, and a crash or a power loss part-way through leaves a header that
 * is half ciphertext and half plaintext. GCM then rejects the whole slice and the file is gone.
 *
 * The copy stored here is the complete sealed slice — already encrypted under the user's passphrase,
 * so it discloses nothing the locked file does not — and unlocking falls back to it whenever the
 * bytes on disk no longer decrypt. It is deleted as soon as a file is unlocked.
 */
class HeaderRecovery(private val dir: File) {

	suspend fun save(backingPath: String, sealedHeader: ByteArray) = withContext(Dispatchers.IO) {
		dir.mkdirs()
		val target = fileFor(backingPath)
		val temp = File(dir, "${target.name}.tmp")
		FileOutputStream(temp).use { out ->
			out.write(sealedHeader)
			out.fd.sync()
		}
		check(temp.renameTo(target)) { "could not save header recovery data" }
		Unit
	}

	suspend fun load(backingPath: String): ByteArray? = withContext(Dispatchers.IO) {
		fileFor(backingPath).takeIf { it.isFile }?.readBytes()
	}

	suspend fun clear(backingPath: String) = withContext(Dispatchers.IO) {
		fileFor(backingPath).delete()
		Unit
	}

	/** Named by digest rather than by path: the directory listing gives away no filenames. */
	private fun fileFor(backingPath: String): File {
		val digest = MessageDigest.getInstance("SHA-256").digest(backingPath.toByteArray())
		return File(dir, digest.joinToString("") { "%02x".format(it) })
	}
}
