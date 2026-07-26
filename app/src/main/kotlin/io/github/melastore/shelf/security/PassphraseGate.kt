package io.github.melastore.shelf.security

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory

/**
 * Guards the hidden features behind a passphrase, without ever storing the passphrase itself.
 *
 * Only a salted PBKDF2 hash is written to app-private storage, so the file reveals at most that a
 * gate exists — never the phrase. There is deliberately no UI that lists or exports it: the phrase
 * lives only in the owner's head and is typed, never displayed.
 */
class PassphraseGate(private val file: File) {

	fun isSet(): Boolean = file.exists()

	fun set(passphrase: CharArray) {
		require(passphrase.size >= MIN_LENGTH) { "passphrase must be at least $MIN_LENGTH characters" }
		val salt = ByteArray(SALT_LEN).also { java.security.SecureRandom().nextBytes(it) }
		val hash = hash(passphrase, salt)
		file.writeText("${salt.encode()}:${hash.encode()}")
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
		return SecretKeyFactory.getInstance(PBKDF2_ALG).generateSecret(spec).encoded
			.also { spec.clearPassword() }
	}

	private fun ByteArray.encode(): String = Base64.getEncoder().encodeToString(this)
	private fun String.decode(): ByteArray = Base64.getDecoder().decode(this)

	private companion object {
		const val PBKDF2_ALG = "PBKDF2withHmacSHA256"
		const val PBKDF2_ROUNDS = 210_000
		const val KEY_BITS = 256
		const val SALT_LEN = 16
		const val MIN_LENGTH = 8
	}
}
