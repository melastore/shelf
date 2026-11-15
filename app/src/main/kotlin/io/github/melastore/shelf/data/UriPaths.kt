package io.github.melastore.shelf.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import io.github.melastore.shelf.root.StoragePaths
import java.io.File

/**
 * Turns a uri from the picker or from another app's share into a path on primary storage. Shelf
 * moves the file itself, so a uri that only hands over a copy is no use and comes back null.
 */
class UriPaths(context: Context, private val paths: StoragePaths) {

	private val appContext = context.applicationContext
	private val resolver get() = appContext.contentResolver

	fun treePath(uri: Uri): String? = documentPath(uri, isTree = true)

	/**
	 * Only what the sender could hand over is taken. With all-files access or root Shelf can read any
	 * path or MediaStore row itself, so an id read off the uri, or a lookup made on Shelf's own
	 * access, says nothing about whether the sender had the file.
	 */
	fun filePath(uri: Uri): String? {
		if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
		// A tree uri is read on Shelf's own persisted grant, not the sender's.
		if (runCatching { DocumentsContract.isTreeUri(uri) }.getOrDefault(true)) return null
		val path = when (uri.authority) {
			// Shelf does not hold MANAGE_DOCUMENTS, so the provider only answers to a grant. It also
			// says whether this is a folder.
			EXTERNAL_STORAGE, MEDIA_DOCUMENTS, DOWNLOAD_DOCUMENTS -> {
				if (DocumentFile.fromSingleUri(appContext, uri)?.isFile != true) return null
				if (uri.authority == EXTERNAL_STORAGE) documentPath(uri, isTree = false) else mediaDocumentPath(uri)
			}

			MediaStore.AUTHORITY -> if (granted(uri)) dataColumn(uri) else null

			else -> sharedPath(uri)
		}
		return path?.let(::insideStorage)?.takeIf { it != paths.emulatedRoot }
	}

	// Only grants count here, not access Shelf has to the provider anyway.
	private fun granted(uri: Uri): Boolean = runCatching {
		appContext.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
			PackageManager.PERMISSION_GRANTED
	}.getOrDefault(false)

	private fun documentPath(uri: Uri, isTree: Boolean): String? {
		if (uri.authority != EXTERNAL_STORAGE) return null
		val document = if (isTree) {
			DocumentFile.fromTreeUri(appContext, uri)
		} else {
			DocumentFile.fromSingleUri(appContext, uri)
		}
		val documentId = document?.uri?.lastPathSegment ?: uri.lastPathSegment ?: return null
		val parts = documentId.split(':', limit = 2)
		if (parts.firstOrNull() != "primary") return null
		val relative = parts.getOrElse(1) { "" }.trimEnd('/')
		// A made-up id can climb out with "..". The real provider never hands out one like that.
		if (relative.isNotEmpty() && !relative.split('/').all(SafPaths::isSafeName)) return null
		return paths.emulatedRoot + if (relative.isEmpty()) "" else "/$relative"
	}

	// Images, Videos and Downloads in the picker hand out MediaStore ids, not paths.
	private fun mediaDocumentPath(uri: Uri): String? {
		if (uri.authority != MEDIA_DOCUMENTS && uri.authority != DOWNLOAD_DOCUMENTS) return null
		val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
		if (uri.authority == DOWNLOAD_DOCUMENTS && id.startsWith("raw:")) return id.removePrefix("raw:")
		val media = runCatching { MediaStore.getMediaUri(appContext, uri) }.getOrNull()
			?: id.takeIf { uri.authority == DOWNLOAD_DOCUMENTS && it.startsWith("msf:") }
				?.removePrefix("msf:")?.toLongOrNull()
				?.let { ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), it) }
			?: return null
		return dataColumn(media) ?: dataColumn(uri)
	}

	/**
	 * Some file managers answer the DATA column for their own uris. That path is the sending app's
	 * word, as the share itself is. Google Photos wraps a MediaStore uri in its own, and that inner
	 * one can only be asked with all-files access, so it is taken only from Photos itself.
	 */
	private fun sharedPath(uri: Uri): String? {
		dataColumn(uri)?.let { return it }
		if (uri.authority != PHOTOS || !isPhotos(uri)) return null
		val inner = uri.pathSegments.firstNotNullOfOrNull { segment ->
			segment.takeIf { it.startsWith("content://media/") }?.let(Uri::parse)
		} ?: return null
		return dataColumn(inner)
	}

	// The authority alone is whoever claimed it first, which on a phone without Photos can be anyone.
	private fun isPhotos(uri: Uri): Boolean = runCatching {
		appContext.packageManager.resolveContentProvider(uri.authority ?: return false, 0)?.packageName == PHOTOS_PACKAGE
	}.getOrDefault(false)

	private fun dataColumn(uri: Uri): String? = runCatching {
		resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
			?.use { if (it.moveToFirst()) it.getString(0) else null }
	}.getOrNull()

	/**
	 * Resolved through any link, and refused when it lands outside shared storage. Without all-files
	 * access another app's file does not show to File at all, so only a folder Shelf can see is
	 * turned away here; a move through a grant checks the rest.
	 */
	private fun insideStorage(path: String): String? {
		val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
		// A photo picker uri answers with a synthetic copy under .transforms, not the file itself.
		val inside =
			file.path.startsWith(paths.emulatedRoot + "/") && !file.path.startsWith(paths.emulatedRoot + "/.transforms/")
		return file.path.takeIf { inside && !file.isDirectory }
	}

	companion object {
		const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
		const val MEDIA_DOCUMENTS = "com.android.providers.media.documents"
		const val DOWNLOAD_DOCUMENTS = "com.android.providers.downloads.documents"
		private const val PHOTOS = "com.google.android.apps.photos.contentprovider"
		private const val PHOTOS_PACKAGE = "com.google.android.apps.photos"
	}
}
