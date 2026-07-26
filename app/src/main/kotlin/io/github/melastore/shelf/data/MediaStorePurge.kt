package io.github.melastore.shelf.data

import android.content.Context
import android.media.MediaScannerConnection
import android.provider.MediaStore
import io.github.melastore.shelf.root.RootShell
import java.io.File

/**
 * Keeps MediaStore in step with a hide or restore.
 *
 * Clearing permission bits stops apps opening the files but leaves their MediaStore rows in place,
 * so galleries keep listing the entries with broken thumbnails. That is louder than not hiding at
 * all. Dropping the rows on hide, and rescanning on restore, closes that gap.
 *
 * Which half is needed depends on how the folder was hidden. A root hide leaves the files where they
 * are, so the rows have to be deleted outright — and since Shelf holds no storage permission, a
 * plain ContentResolver delete only reaches rows this app inserted, which is why that path falls back
 * to `content` under root. A hide that moves or renames the folder needs none of that: pointing the
 * scanner at a path that no longer holds the file is enough for MediaStore to drop the row itself.
 */
object MediaStorePurge {

	private val collections = listOf(
		MediaStore.Files.getContentUri("external"),
		MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
		MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
		MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
	)

	/** Deletes the rows under [emulatedFolder]. Root only; used when the files do not move. */
	suspend fun forFolder(context: Context, emulatedFolder: String): String? {
		val pattern = likePrefix(emulatedFolder)
		return purge(
			context,
			selection = "${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\'",
			args = arrayOf(pattern),
			where = "${MediaStore.MediaColumns.DATA} LIKE ${sqlLiteral(pattern)} ESCAPE '\\'",
		)
	}

	/** Deletes the row for [emulatedFile]. Root only; used when the file does not move. */
	suspend fun forFile(context: Context, emulatedFile: String): String? = purge(
		context,
		selection = "${MediaStore.MediaColumns.DATA} = ?",
		args = arrayOf(emulatedFile),
		where = "${MediaStore.MediaColumns.DATA} = ${sqlLiteral(emulatedFile)}",
	)

	/**
	 * Points the scanner at [paths]. Rows for paths that no longer hold a file are dropped, and rows
	 * for paths that do are created, which is both halves of a move in one call.
	 */
	fun scan(context: Context, paths: List<String>) {
		if (paths.isEmpty()) return
		MediaScannerConnection.scanFile(context, paths.take(MAX_SCAN).toTypedArray(), null, null)
	}

	/**
	 * Puts the media under [path] back on the map after a restore. Handing the scanner a directory
	 * indexes the directory and nothing inside it, so the files are listed first — by whatever means
	 * this build can read them, which without root or all-files access is nothing, leaving the
	 * directory itself and the next full scan.
	 *
	 * @return null on success, or a note about what could not be reached.
	 */
	suspend fun rescan(context: Context, path: String): String? {
		val listed = listFiles(path)
		scan(context, listed.ifEmpty { listOf(path) })
		return "only the first $MAX_SCAN items under $path were rescanned"
			.takeIf { listed.size > MAX_SCAN }
	}

	/** Files under [path], read directly when this build can and through root when it cannot. */
	suspend fun listFiles(path: String): List<String> {
		val target = File(path)
		if (target.canRead()) {
			return target.walkTopDown().filter { it.isFile }.take(MAX_SCAN + 1).map { it.path }.toList()
		}
		val found = RootShell.run("find ${RootShell.quote(path)} -type f 2>/dev/null | head -n ${MAX_SCAN + 1}")
		return if (found.ok) found.stdout else emptyList()
	}

	private suspend fun purge(
		context: Context,
		selection: String,
		args: Array<String>,
		where: String,
	): String? {
		val failures = mutableListOf<String>()
		for (uri in collections) {
			val direct = runCatching { context.contentResolver.delete(uri, selection, args) }
			if (direct.isSuccess) continue

			val viaRoot = RootShell.run(
				"content delete --uri ${RootShell.quote(uri.toString())} --where ${RootShell.quote(where)}",
			)
			if (!viaRoot.ok) {
				val detail = viaRoot.stderr.joinToString().ifEmpty {
					direct.exceptionOrNull()?.message.orEmpty()
				}
				failures += "${uri.lastPathSegment ?: uri}: $detail"
			}
		}
		return failures.takeIf { it.isNotEmpty() }
			?.joinToString("; ", prefix = "gallery entries may still be visible (")
			?.plus(")")
	}

	/**
	 * Escapes the LIKE wildcards so a folder called `100_ANDRO` or `50% off` matches itself and not
	 * every sibling that happens to fit the pattern.
	 */
	private fun likePrefix(path: String): String =
		path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%"

	private fun sqlLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

	private const val MAX_SCAN = 5_000
}
