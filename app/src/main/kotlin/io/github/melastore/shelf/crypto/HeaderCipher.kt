package io.github.melastore.shelf.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase transform behind content locking. No file or Android dependency, so it can be tested
 * on its own.
 *
 * Key derivation is separate from [seal] and [open] on purpose: PBKDF2 at this work factor takes
 * about a second, which is fine once per folder and hopeless once per file. Callers derive once per
 * pass and pass the key in; every file still gets its own nonce, which GCM requires.
 */
object HeaderCipher {

	const val SALT_LENGTH = 16
	const val NONCE_LENGTH = 12
	const val TAG_LENGTH = 16

	/** An encrypted slice plus the per-file parameters [open] needs to reverse it. */
	class Sealed(val cipherText: ByteArray, val nonce: ByteArray, val tag: ByteArray)

	fun newSalt(): ByteArray = randomBytes(SALT_LENGTH)

	fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKey {
		require(salt.size == SALT_LENGTH) { "unexpected salt length" }
		val spec = PBEKeySpec(passphrase, salt, PBKDF2_ROUNDS, KEY_BITS)
		return try {
			SecretKeySpec(SecretKeyFactory.getInstance(PBKDF2_ALG).generateSecret(spec).encoded, "AES")
		} finally {
			spec.clearPassword()
		}
	}

	fun seal(slice: ByteArray, key: SecretKey): Sealed {
		require(slice.isNotEmpty()) { "nothing to seal" }
		val nonce = randomBytes(NONCE_LENGTH)
		val cipher = Cipher.getInstance(TRANSFORM).apply {
			init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
		}
		// doFinal returns ciphertext then tag. Stored apart so the ciphertext overwrites exactly the
		// bytes it replaced.
		val combined = cipher.doFinal(slice)
		return Sealed(
			cipherText = combined.copyOfRange(0, slice.size),
			nonce = nonce,
			tag = combined.copyOfRange(slice.size, combined.size),
		)
	}

	/**
	 * Recovers the original slice.
	 *
	 * @throws javax.crypto.AEADBadTagException on a wrong passphrase or altered bytes. Callers rely
	 * on this: a wrong passphrase must fail loudly rather than return rubbish that then gets written
	 * over the only copy of the real bytes.
	 */
	fun open(cipherText: ByteArray, nonce: ByteArray, tag: ByteArray, key: SecretKey): ByteArray {
		val cipher = Cipher.getInstance(TRANSFORM).apply {
			init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
		}
		return cipher.doFinal(cipherText + tag)
	}

	private fun randomBytes(n: Int): ByteArray = ByteArray(n).also(secureRandom::nextBytes)

	private const val TRANSFORM = "AES/GCM/NoPadding"
	private const val PBKDF2_ALG = "PBKDF2withHmacSHA256"
	private const val PBKDF2_ROUNDS = 210_000
	private const val KEY_BITS = 256
	private const val TAG_BITS = 128
	private val secureRandom = SecureRandom()
}
