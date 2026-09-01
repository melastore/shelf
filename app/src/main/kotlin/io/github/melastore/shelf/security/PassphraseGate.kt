package io.github.melastore.shelf.security

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Guards the private space behind a credential without storing the credential.
 *
 * Only a salted PBKDF2 hash goes to app-private storage, so the file says a gate exists and nothing
 * more. There is no UI that lists or exports the phrase; it is typed, never shown.
 */
class PassphraseGate(private val file: File) {

	fun isSet(): Boolean = file.exists()

	fun set(passphrase: CharArray) {
		require(passphrase.size >= MIN_LENGTH) { "passphrase must be at least $MIN_LENGTH characters" }
		val salt = ByteArray(SALT_LEN).also { java.security.SecureRandom().nextBytes(it) }
		val hash = hash(passphrase, salt)
		val temporary = File(file.parentFile, ".${file.name}.tmp")
		try {
			FileOutputStream(temporary).use { output ->
				output.write("${salt.encode()}:${hash.encode()}".toByteArray(Charsets.US_ASCII))
				output.fd.sync()
			}
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
		} finally {
			temporary.delete()
		}
	}

	fun clear() {
		file.delete()
	}

	/** True when [input] matches the stored passphrase. Uses a constant-time comparison. */
	fun matches(input: CharArray): Boolean {
		if (!file.exists()) return false
		return runCatching {
			val (saltPart, hashPart) = file.readText().split(':', limit = 2).takeIf { it.size == 2 }
				?: return@runCatching false
			val salt = saltPart.decode()
			val expected = hashPart.decode()
			MessageDigest.isEqual(hash(input, salt), expected)
		}.getOrDefault(false)
	}

	private fun hash(passphrase: CharArray, salt: ByteArray): ByteArray {
		val spec = PBEKeySpec(passphrase, salt, PBKDF2_ROUNDS, KEY_BITS)
		return try {
			SecretKeyFactory.getInstance(PBKDF2_ALG).generateSecret(spec).encoded
		} finally {
			// Also on the failure path: the spec holds a copy of the credential either way.
			spec.clearPassword()
		}
	}

	private fun ByteArray.encode(): String = Base64.getEncoder().encodeToString(this)
	private fun String.decode(): ByteArray = Base64.getDecoder().decode(this)

	private companion object {
		const val PBKDF2_ALG = "PBKDF2withHmacSHA256"
		const val PBKDF2_ROUNDS = 210_000
		const val KEY_BITS = 256
		const val SALT_LEN = 16
		const val MIN_LENGTH = 4
	}
}
