package io.github.melastore.shelf.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The passphrase transform behind content locking, kept free of any file or Android dependency so it
 * can be reasoned about and tested on its own.
 *
 * Key derivation is deliberately separate from [seal] and [open]. PBKDF2 at this work factor takes
 * roughly a second, which is the right price to pay once for a folder and quite the wrong one to pay
 * once per file — a folder of two thousand photos would take half an hour before a single byte moved.
 * One salt per run, one derivation, and a fresh nonce for every file, which is what GCM requires.
 */
object HeaderCipher {

	const val SALT_LENGTH = 16
	const val NONCE_LENGTH = 12
	const val TAG_LENGTH = 16

	/** An encrypted slice and the per-file parameters [open] needs to reverse it. */
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
		// doFinal returns ciphertext followed by the tag; they are stored apart so the ciphertext can
		// be written back over exactly the bytes it replaced.
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
	 * @throws javax.crypto.AEADBadTagException on the wrong passphrase or altered bytes. That
	 * distinction matters: a wrong passphrase must never produce plausible-looking rubbish that then
	 * gets written back over the only copy of the real bytes.
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
