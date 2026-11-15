package io.github.melastore.shelf.data

/**
 * The SAF bookkeeping that is pure string work.
 *
 * ExternalStorageProvider names documents `primary:<path relative to the volume root>`, so an id can
 * be derived from a path instead of stored and kept in step with renames.
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

	/** The name a hidden folder takes. The media scanner skips the leading dot. */
	fun hiddenName(name: String): String = if (name.startsWith(".")) name else ".$name"

	fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

	fun isSafeName(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
		'/' !in name && '\u0000' !in name

	fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/')

	/** [path] with its last segment replaced, which is all an in-place rename amounts to. */
	fun sibling(path: String, newName: String): String = "${parentOf(path)}/$newName"

	/**
	 * Whether the picker could ever return a grant covering children of [parent].
	 *
	 * ExternalStorageProvider sets FLAG_DIR_BLOCKS_OPEN_DOCUMENT_TREE on the volume root and on
	 * Download, so OPEN_DOCUMENT_TREE cannot land on either. A grant inside one of them is no help:
	 * the rename is authorised by the folder above, and that is the blocked one. Asking anyway is
	 * what used to make the picker reappear however often it was answered.
	 */
	fun grantable(emulatedRoot: String, parent: String): Boolean {
		val clean = parent.trimEnd('/')
		return clean != emulatedRoot && !clean.equals("$emulatedRoot/$DOWNLOAD", ignoreCase = true)
	}

	private const val DOWNLOAD = "Download"
}
