package io.github.melastore.shelf.data

/**
 * The bits of Storage Access Framework bookkeeping that are pure string work.
 *
 * ExternalStorageProvider names its documents `primary:<path relative to the volume root>`, so a
 * document id is derivable from a path rather than something to store and keep in step with renames.
 */
object SafPaths {

	private const val PRIMARY = "primary:"

	/** The document id for [path], or null if it is not on this user's primary volume. */
	fun documentId(emulatedRoot: String, path: String): String? {
		val clean = path.trimEnd('/')
		if (clean == emulatedRoot) return PRIMARY
		if (!clean.startsWith("$emulatedRoot/")) return null
		return PRIMARY + clean.removePrefix("$emulatedRoot/")
	}

	/** The name a hidden folder takes. The leading dot is what the media scanner skips. */
	fun hiddenName(name: String): String = if (name.startsWith(".")) name else ".$name"

	fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

	fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/')

	/** [path] with its last segment replaced, which is all a rename in place amounts to. */
	fun sibling(path: String, newName: String): String = "${parentOf(path)}/$newName"
}
