package io.github.melastore.shelf.data

import android.content.Context
import android.media.MediaScannerConnection
import android.provider.MediaStore
import io.github.melastore.shelf.root.RootCommandRunner
import io.github.melastore.shelf.root.RootShell
import java.io.File

interface FolderMediaIndex {
	fun scan(context: Context, paths: List<String>)
	suspend fun rescan(context: Context, path: String): HideWarning?
	suspend fun listFiles(path: String): List<String>
}

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
object MediaStorePurge : FolderMediaIndex {

	private val collections = listOf(
		MediaStore.Files.getContentUri("external"),
		MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
		MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
		MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
	)

	/** Deletes the rows under [emulatedFolder]. Root only; used when the files do not move. */
	suspend fun forFolder(context: Context, emulatedFolder: String, runner: RootCommandRunner = RootShell,): HideWarning? {
		val pattern = likePrefix(emulatedFolder)
		return purge(
			context,
			selection = "${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\'",
			args = arrayOf(pattern),
			where = "${MediaStore.MediaColumns.DATA} LIKE ${sqlLiteral(pattern)} ESCAPE '\\'",
			runner = runner,
		)
	}

	/** Deletes the row for [emulatedFile]. Root only; used when the file does not move. */
	suspend fun forFile(context: Context, emulatedFile: String, runner: RootCommandRunner = RootShell,): HideWarning? =
		purge(
			context,
			selection = "${MediaStore.MediaColumns.DATA} = ?",
			args = arrayOf(emulatedFile),
			where = "${MediaStore.MediaColumns.DATA} = ${sqlLiteral(emulatedFile)}",
			runner = runner,
		)

	/**
	 * Points the scanner at [paths]. Rows for paths that no longer hold a file are dropped, and rows
	 * for paths that do are created, which is both halves of a move in one call.
	 */
	override fun scan(context: Context, paths: List<String>) {
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
	suspend fun rescan(context: Context, path: String, runner: RootCommandRunner,): HideWarning? {
		val listed = listFiles(path, runner)
		scan(context, listed.ifEmpty { listOf(path) })
		return HideWarning.MediaRescanLimited(path, MAX_SCAN).takeIf { listed.size > MAX_SCAN }
	}

	override suspend fun rescan(context: Context, path: String): HideWarning? = rescan(context, path, RootShell)

	/**
	 * Files under [path], read directly when this build can and through root when it cannot.
	 *
	 * canRead is not the test for whether the walk worked. On a restricted storage mount it answers
	 * yes and the walk still comes back empty, and an empty list here means the stale rows for these
	 * files are never invalidated — the gallery goes on listing what was just hidden. So a walk that
	 * finds nothing falls through to the privileged path rather than being taken for an empty folder,
	 * the same way [purge] treats a delete that reaches no rows.
	 */
	suspend fun listFiles(path: String, runner: RootCommandRunner): List<String> {
		val target = File(path)
		if (target.canRead()) {
			val walked = target.walkTopDown().filter { it.isFile }.take(MAX_SCAN + 1).map { it.path }.toList()
			if (walked.isNotEmpty()) return walked
		}
		val found = runner.run("find ${RootShell.quote(path)} -type f 2>/dev/null | head -n ${MAX_SCAN + 1}")
		return if (found.ok) found.stdout else emptyList()
	}

	override suspend fun listFiles(path: String): List<String> = listFiles(path, RootShell)

	private suspend fun purge(
		context: Context,
		selection: String,
		args: Array<String>,
		where: String,
		runner: RootCommandRunner,
	): HideWarning? {
		val failures = mutableListOf<String>()
		for (uri in collections) {
			// A delete that reaches no rows does not fail: without a storage permission the provider
			// quietly narrows the selection to rows this app inserted, of which there are none, and
			// returns zero. Treating that as success is what left the gallery listing the folder, so
			// anything short of a deleted row falls through to the privileged path.
			val direct = runCatching { context.contentResolver.delete(uri, selection, args) }
			if (direct.getOrDefault(0) > 0) continue

			val viaRoot = runner.run(
				"content delete --uri ${RootShell.quote(uri.toString())} --where ${RootShell.quote(where)}",
			)
			if (!viaRoot.ok) {
				val detail = viaRoot.stderr.joinToString().ifEmpty {
					direct.exceptionOrNull()?.message.orEmpty()
				}
				failures += "${uri.lastPathSegment ?: uri}: $detail"
			}
		}
		return failures.takeIf { it.isNotEmpty() }?.let(HideWarning::MediaEntriesMayRemain)
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
