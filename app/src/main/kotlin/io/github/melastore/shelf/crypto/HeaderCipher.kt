package io.github.melastore.shelf.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.SecretKeyFactory

/**
 * The passphrase-based transform behind file locking, kept free of any file or Android dependency so
 * it can be reasoned about and tested on its own.
 *
 * A caller passes in the leading slice of a file; [seal] returns the encrypted slice plus the salt,
 * nonce and GCM tag needed to reverse it. Where those bytes live on disk, and how the file itself is
 * read and written, is the caller's concern.
 */
class HeaderCipher {

	/** An encrypted slice and the parameters [open] needs to recover it. */
	data class Sealed(
		val cipherText: ByteArray,
		val salt: ByteArray,
		val nonce: ByteArray,
		val tag: ByteArray,
	)

	fun seal(slice: ByteArray, passphrase: CharArray): Sealed {
		require(slice.isNotEmpty()) { "nothing to seal" }
		val salt = randomBytes(SALT_LEN)
		val nonce = randomBytes(NONCE_LEN)
		val cipher = Cipher.getInstance(TRANSFORM).apply {
			init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, nonce))
		}
		val combined = cipher.doFinal(slice) // ciphertext (slice.size) followed by the tag
		return Sealed(
			cipherText = combined.copyOfRange(0, slice.size),
			salt = salt,
			nonce = nonce,
			tag = combined.copyOfRange(slice.size, combined.size),
		)
	}

	/** Recovers the original slice, or throws [javax.crypto.AEADBadTagException] on a wrong passphrase. */
	fun open(
		cipherText: ByteArray,
		salt: ByteArray,
		nonce: ByteArray,
		tag: ByteArray,
		passphrase: CharArray,
	): ByteArray {
		val cipher = Cipher.getInstance(TRANSFORM).apply {
			init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, nonce))
		}
		return cipher.doFinal(cipherText + tag)
	}

	private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
		val spec = PBEKeySpec(passphrase, salt, PBKDF2_ROUNDS, KEY_BITS)
		val bytes = SecretKeyFactory.getInstance(PBKDF2_ALG).generateSecret(spec).encoded
		spec.clearPassword()
		return SecretKeySpec(bytes, "AES")
	}

	private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

	private companion object {
		const val TRANSFORM = "AES/GCM/NoPadding"
		const val PBKDF2_ALG = "PBKDF2withHmacSHA256"
		const val PBKDF2_ROUNDS = 210_000
		const val KEY_BITS = 256
		const val TAG_BITS = 128
		const val SALT_LEN = 16
		const val NONCE_LEN = 12
		val secureRandom = SecureRandom()
	}
}
