package io.github.melastore.shelf.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.melastore.shelf.root.StoragePaths

/** Resolves a path to a document the user has already granted. Shared by both protectors. */
object SafGrants {

	private const val PRIMARY = "primary:"

	/**
	 * A persisted read-write grant covering [path], including one on the folder itself. Grants
	 * accumulate, so the closest wins.
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
	 * Has to be [DocumentFile.fromTreeUri]. `fromSingleUri` answers `isDirectory` from the provider
	 * and looks right, but `listFiles`, `findFile` and `createFile` all throw on it.
	 */
	fun folder(context: Context, paths: StoragePaths, path: String): DocumentFile? {
		val tree = covering(context.contentResolver, paths.emulatedRoot, path) ?: return null
		val id = SafPaths.documentId(paths.emulatedRoot, path) ?: return null
		val uri = runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, id) }.getOrNull()
			?: return null
		return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.isDirectory }
	}
}
