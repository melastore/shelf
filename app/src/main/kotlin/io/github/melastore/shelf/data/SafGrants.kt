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

	/**
	 * The files under [path], read through [tree]. These are the paths whose MediaStore rows go stale
	 * once a hide renames them, so they are collected before anything moves.
	 */
	fun childPaths(
		resolver: ContentResolver,
		emulatedRoot: String,
		tree: Uri,
		path: String,
		maxDepth: Int,
		maxChildren: Int,
	): List<String> = buildList {
		collect(resolver, emulatedRoot, tree, path, 0, maxDepth, maxChildren, this)
	}

	private fun collect(
		resolver: ContentResolver,
		emulatedRoot: String,
		tree: Uri,
		path: String,
		depth: Int,
		maxDepth: Int,
		maxChildren: Int,
		found: MutableList<String>,
	) {
		if (depth > maxDepth || found.size >= maxChildren) return
		val id = SafPaths.documentId(emulatedRoot, path) ?: return
		val children = runCatching {
			DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
		}.getOrNull() ?: return

		runCatching {
			resolver.query(
				children,
				arrayOf(
					DocumentsContract.Document.COLUMN_DISPLAY_NAME,
					DocumentsContract.Document.COLUMN_MIME_TYPE,
				),
				null,
				null,
				null,
			)?.use { cursor ->
				while (cursor.moveToNext() && found.size < maxChildren) {
					val name = cursor.getString(0) ?: continue
					val childPath = "$path/$name"
					if (cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) {
						collect(resolver, emulatedRoot, tree, childPath, depth + 1, maxDepth, maxChildren, found)
					} else {
						found += childPath
					}
				}
			}
		}
	}
}
