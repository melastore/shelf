package io.github.melastore.shelf.data

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * When to remind the owner to export a recovery file: after the first hide, and again once enough
 * hidden folders are missing from the last file that it would no longer find most of them.
 *
 * Folders are kept as salted digests, so the preferences file does not list what was hidden.
 */
object RecoveryNudge {

	fun digest(salt: ByteArray, path: String): String {
		val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
		return mac.doFinal(path.toByteArray(Charsets.UTF_8)).take(DIGEST_BYTES)
			.joinToString("") { "%02x".format(it) }
	}

	/** [exported] is what the last file covered, [snoozed] what the owner last put off; null for never. */
	fun due(current: Set<String>, exported: Set<String>?, snoozed: Set<String>?): Boolean {
		if (current.isEmpty()) return false
		if (!movedOn(current, exported)) return false
		return snoozed == null || movedOn(current, snoozed)
	}

	// Removals never count: the old file still covers everything that is left.
	private fun movedOn(current: Set<String>, base: Set<String>?): Boolean {
		if (base == null) return true
		val added = current.count { it !in base }
		return added >= MANY || (added > 0 && added * 2 >= current.size)
	}

	private const val DIGEST_BYTES = 8
	private const val MANY = 3
}
