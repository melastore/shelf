package io.github.melastore.shelf.security

import android.system.Os
import android.system.OsConstants
import io.github.melastore.shelf.crypto.Pbkdf2
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/**
 * Guards the private space behind a credential without storing the credential.
 *
 * Only a salted PBKDF2 hash goes to app-private storage, so the file says a gate exists and nothing
 * more. There is no UI that lists or exports the phrase; it is typed, never shown.
 */
class PassphraseGate(private val file: File) {

	// The same hash as the gate, not the one it replaced: falling back to an older generation would
	// let the previous credential in.
	private val backup = File(file.parentFile, "${file.name}.bak")

	fun isSet(): Boolean = file.exists()

	fun set(passphrase: CharArray) {
		require(passphrase.size >= MIN_LENGTH) { "passphrase must be at least $MIN_LENGTH characters" }
		val salt = ByteArray(SALT_LEN).also { java.security.SecureRandom().nextBytes(it) }
		val hash = hash(passphrase, salt)
		val temporary = File(file.parentFile, ".${file.name}.tmp")
		try {
			val record = "${salt.encode()}:${hash.encode()}".toByteArray(Charsets.US_ASCII)
			temporary.writeSynced(record)
			backup.writeSynced(record)
			runCatching {
				Files.move(
					temporary.toPath(),
					file.toPath(),
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING,
				)
			}.getOrElse {
				Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
			}
			syncParentDirectory()
		} finally {
			hash.fill(0)
			temporary.delete()
		}
	}

	fun clear() {
		file.delete()
		backup.delete()
	}

	/**
	 * True when [input] matches the stored passphrase. Uses a constant-time comparison.
	 *
	 * @throws GateUnreadable when neither the gate nor its backup can be read. A damaged file is not a
	 * wrong credential, and counting it as one put the owner into a lockout.
	 */
	fun matches(input: CharArray): Boolean {
		if (!file.exists()) return false
		val (salt, expected) = read(file) ?: read(backup) ?: throw GateUnreadable(file)
		val actual = hash(input, salt)
		return try {
			MessageDigest.isEqual(actual, expected)
		} finally {
			actual.fill(0)
			expected.fill(0)
		}
	}

	private fun read(source: File): Pair<ByteArray, ByteArray>? = runCatching {
		val (saltPart, hashPart) = source.readText().split(':', limit = 2).takeIf { it.size == 2 }
			?: return@runCatching null
		saltPart.decode() to hashPart.decode()
	}.getOrNull()?.takeIf { (salt, hash) -> salt.size == SALT_LEN && hash.size == KEY_BITS / 8 }

	private fun File.writeSynced(bytes: ByteArray) {
		FileOutputStream(this).use { output ->
			output.write(bytes)
			output.fd.sync()
		}
	}

	// The rename is only durable once the directory entry is flushed too.
	private fun syncParentDirectory() {
		val dir = file.parentFile ?: return
		runCatching {
			val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
			try {
				Os.fsync(fd)
			} finally {
				Os.close(fd)
			}
		}
	}

	private fun hash(passphrase: CharArray, salt: ByteArray): ByteArray =
		Pbkdf2.sha256(passphrase, salt, PBKDF2_ROUNDS, KEY_BITS)

	private fun ByteArray.encode(): String = Base64.getEncoder().encodeToString(this)
	private fun String.decode(): ByteArray = Base64.getDecoder().decode(this)

	class GateUnreadable(file: File) : IOException("credential file unreadable: ${file.name}")

	private companion object {
		const val PBKDF2_ROUNDS = 210_000
		const val KEY_BITS = 256
		const val SALT_LEN = 16
		const val MIN_LENGTH = 4
	}
}
