package io.github.melastore.shelf.crypto

import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HeaderCipherTest {

	private val cipher = HeaderCipher()

	private fun slice(size: Int) = ByteArray(size) { (it * 7 % 256).toByte() }

	@Test fun sealThenOpenRecoversTheSlice() {
		val original = slice(4096)
		val sealed = cipher.seal(original, "correct horse".toCharArray())

		val recovered = cipher.open(
			sealed.cipherText, sealed.salt, sealed.nonce, sealed.tag, "correct horse".toCharArray(),
		)
		assertArrayEquals(original, recovered)
	}

	@Test fun ciphertextDiffersFromPlaintext() {
		val original = slice(4096)
		val sealed = cipher.seal(original, "pw".toCharArray())
		assertThrows(AssertionError::class.java) {
			assertArrayEquals(original, sealed.cipherText)
		}
	}

	@Test fun wrongPassphraseFailsTheTagCheck() {
		val sealed = cipher.seal(slice(4096), "right".toCharArray())
		assertThrows(AEADBadTagException::class.java) {
			cipher.open(sealed.cipherText, sealed.salt, sealed.nonce, sealed.tag, "wrong".toCharArray())
		}
	}

	@Test fun tamperedCiphertextIsRejected() {
		val sealed = cipher.seal(slice(4096), "pw".toCharArray())
		sealed.cipherText[0] = (sealed.cipherText[0] + 1).toByte()
		assertThrows(AEADBadTagException::class.java) {
			cipher.open(sealed.cipherText, sealed.salt, sealed.nonce, sealed.tag, "pw".toCharArray())
		}
	}

	@Test fun eachSealUsesFreshSaltAndNonce() {
		val a = cipher.seal(slice(1024), "pw".toCharArray())
		val b = cipher.seal(slice(1024), "pw".toCharArray())
		assertThrows(AssertionError::class.java) { assertArrayEquals(a.salt, b.salt) }
		assertThrows(AssertionError::class.java) { assertArrayEquals(a.nonce, b.nonce) }
	}
}
