package io.github.melastore.shelf.crypto

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * PBKDF2 results for the open session. A derivation costs seconds on a phone and every hide and
 * restore needs two, so without this each folder paid them again. Holds nothing the credential it
 * came from does not already give away, and is wiped with it on lock.
 *
 * Entries are looked up by a peppered digest of the password, salt and work factor, so a different
 * credential, a test's own password or an old folder's salt can never be handed another's key. Two
 * callers asking for one key at once share a single derivation.
 */
object KeyCache {
	/**
	 * Whether a session is open to keep keys for. Set by the credential's owner; until then nothing
	 * is kept. Asked after every derivation, because a pass that copied the credential before a lock
	 * can still be deriving after it.
	 */
	@Volatile var keepWhile: () -> Boolean = { false }

	private val pepper = ByteArray(32).also(SecureRandom()::nextBytes)
	private val slots = ConcurrentHashMap<String, Slot>()

	/** A copy of the key, so a caller wiping it after use cannot wipe the cache's. */
	fun get(password: CharArray, salt: ByteArray, iterations: Int, derive: () -> ByteArray): ByteArray {
		val id = fingerprint(password, salt, iterations)
		val slot = slots.computeIfAbsent(id) { Slot() }
		// Wiped by a lock while this waited: still answer, just keep nothing.
		val key = slot.copy(derive) ?: derive()
		if (!keepWhile() && slots.remove(id, slot)) slot.wipe()
		return key
	}

	fun clear() {
		val dropped = slots.values.toList()
		slots.clear()
		dropped.forEach(Slot::wipe)
	}

	/**
	 * One key. A wipe never waits on a derivation, which can take seconds and is asked for from the
	 * lock path, and never zeroes bytes a caller is still copying: a pass handed a zeroed key would
	 * seal files nobody can open.
	 */
	private class Slot {
		private val lock = ReentrantLock()
		private var key: ByteArray? = null

		@Volatile private var wiped = false

		fun copy(derive: () -> ByteArray): ByteArray? = lock.withLock {
			if (wiped) return null
			val current = key ?: derive().also { key = it }
			val copy = current.copyOf()
			// A wipe that came in during the derivation could not take the lock, so it is finished here.
			if (wiped) dropKey()
			copy
		}

		fun wipe() {
			wiped = true
			if (lock.tryLock()) {
				try {
					dropKey()
				} finally {
					lock.unlock()
				}
			}
		}

		private fun dropKey() {
			key?.fill(0)
			key = null
		}
	}

	private fun fingerprint(password: CharArray, salt: ByteArray, iterations: Int): String {
		val digest = MessageDigest.getInstance("SHA-256")
		digest.update(pepper)
		digest.update(ByteBuffer.allocate(8).putInt(iterations).putInt(salt.size).array())
		digest.update(salt)
		val char = ByteArray(2)
		for (c in password) {
			char[0] = (c.code shr 8).toByte()
			char[1] = c.code.toByte()
			digest.update(char)
		}
		char.fill(0)
		return digest.digest().joinToString("") { "%02x".format(it) }
	}
}
