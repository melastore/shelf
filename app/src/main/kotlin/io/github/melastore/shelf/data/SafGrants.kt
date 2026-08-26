package io.github.melastore.shelf.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.melastore.shelf.root.StoragePaths

/** Resolving a path to a document the user has already granted, shared by both protectors. */
object SafGrants {

	private const val PRIMARY = "primary:"

	/**
	 * A persisted read-write grant covering [path], including one taken on the folder itself. Grants
	 * accumulate, so the closest one wins.
	 */
	fun covering(resolver: ContentResolver, emulatedRoot: String, path: String): Uri? =
		resolver.persistedUriPermissions.asSequence()
			.filter { it.isReadPermission && it.isWritePermission }
			.mapNotNull { permission ->
				val id = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
					?: return@mapNotNull null
				val relative = id.removePrefix(PRIMARY)
				if (relative == id) return@mapNotNull null
				val root = emulatedRoot + if (relative.isEmpty()) "" else "/$relative"
				root to permission.uri
			}
			.filter { (root, _) -> path == root || path.startsWith("$root/") }
			.maxByOrNull { (root, _) -> root.length }
			?.second

	/**
	 * [path] as a directory that can be walked and written to.
	 *
	 * It has to be built with [DocumentFile.fromTreeUri]. `fromSingleUri` answers `isDirectory` from
	 * the provider and so looks correct, but every operation that needs the folder's children —
	 * `listFiles`, `findFile`, `createFile` — throws `UnsupportedOperationException` on it. Callers
	 * that caught that turned a folder they never touched into a folder with nothing in it, and
	 * reported success.
	 */
	fun folder(context: Context, paths: StoragePaths, path: String): DocumentFile? {
		val tree = covering(context.contentResolver, paths.emulatedRoot, path) ?: return null
		val id = SafPaths.documentId(paths.emulatedRoot, path) ?: return null
		val uri = runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, id) }.getOrNull()
			?: return null
		return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.isDirectory }
	}
}
