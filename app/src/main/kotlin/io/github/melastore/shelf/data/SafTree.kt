package io.github.melastore.shelf.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/** One child of a document tree, with everything a walk needs already read out of the cursor. */
internal data class SafChild(val uri: Uri, val name: String, val mimeType: String?) {
	val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
	val isFile: Boolean get() = !mimeType.isNullOrEmpty() && !isDirectory
}

/**
 * Lists a folder in one query.
 *
 * DocumentFile.listFiles asks the provider for the ids and then charges another round trip per
 * child for its name and one more for its type, so walking a hundred files cost three hundred of
 * them. The child cursor carries all three columns already, and a hide walks the folder more than
 * once.
 */
internal object SafTree {

	fun children(resolver: ContentResolver, folder: Uri): List<SafChild>? = runCatching {
		val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
			folder,
			DocumentsContract.getDocumentId(folder),
		)
		resolver.query(childrenUri, COLUMNS, null, null, null)?.use { cursor ->
			val id = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
			val name = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
			val mime = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
			if (id < 0 || name < 0) return@use null
			val found = mutableListOf<SafChild>()
			while (cursor.moveToNext()) {
				// A child the provider will not name cannot be mapped back later, and a walk that
				// quietly dropped it would leave that file in the clear.
				val documentId = cursor.getString(id) ?: return@use null
				val displayName = cursor.getString(name) ?: return@use null
				found += SafChild(
					DocumentsContract.buildDocumentUriUsingTree(folder, documentId),
					displayName,
					mime.takeIf { it >= 0 }?.let(cursor::getString),
				)
			}
			found
		}
	}.getOrNull()

	/**
	 * The child of [folder] called exactly [name], or null. DocumentFile.findFile lists the folder and
	 * then asks for each child's name one round trip at a time, so looking for one marker in a folder
	 * of a thousand photos cost a thousand Binder calls. External storage ids are paths, so the
	 * child's id is known up front and one query answers; any other provider gets one listing.
	 */
	fun child(context: Context, folder: Uri, name: String): DocumentFile? {
		val resolver = context.contentResolver
		val uri = if (folder.authority == EXTERNAL_STORAGE) {
			val parent = runCatching { DocumentsContract.getDocumentId(folder) }.getOrNull() ?: return null
			val id = if (parent.endsWith(':')) "$parent$name" else "$parent/$name"
			// The volume ignores case and findFile did not, so the name is checked as stored.
			DocumentsContract.buildDocumentUriUsingTree(folder, id).takeIf { displayName(resolver, it) == name }
		} else {
			children(resolver, folder)?.firstOrNull { it.name == name }?.uri
		}
		return uri?.let { DocumentFile.fromTreeUri(context, it) }
	}

	private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
		resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
			if (it.moveToFirst()) it.getString(0) else null
		}
	}.getOrNull()

	private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

	private val COLUMNS = arrayOf(
		DocumentsContract.Document.COLUMN_DOCUMENT_ID,
		DocumentsContract.Document.COLUMN_DISPLAY_NAME,
		DocumentsContract.Document.COLUMN_MIME_TYPE,
	)
}

/**
 * Opens [uri] for reading, and settles for read-write when that is refused.
 *
 * A read-only descriptor is the one a provider runs its redaction path for: it rewrites the bytes
 * on the way out, and on a LineageOS build it throws out of AppOpsService instead of returning
 * anything. Shelf wants the file as stored, and every grant it holds is read-write already, so the
 * second attempt asks for nothing the work did not have.
 */
internal fun ContentResolver.openUnredacted(uri: Uri): ParcelFileDescriptor? =
	runCatching { openFileDescriptor(uri, "r") }.getOrNull()
		?: runCatching { openFileDescriptor(uri, "rw") }.getOrNull()
