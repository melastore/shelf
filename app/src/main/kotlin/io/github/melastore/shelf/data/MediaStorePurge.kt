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
 * Clearing permission bits stops apps opening the files but leaves their rows behind, so galleries
 * go on listing broken thumbnails. That is louder than not hiding at all.
 *
 * Which half is needed depends on the method. A root hide leaves the files in place, so the rows
 * have to be deleted outright, and since Shelf holds no storage permission a plain ContentResolver
 * delete only reaches rows this app inserted; hence the fallback to `content` under root. A hide
 * that moves or renames needs none of that: point the scanner at a path that no longer holds the
 * file and MediaStore drops the row itself.
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
	 * Points the scanner at [paths]. Rows for paths that no longer hold a file are dropped and rows
	 * for paths that do are created, which is both halves of a move in one call.
	 */
	override fun scan(context: Context, paths: List<String>) {
		if (paths.isEmpty()) return
		MediaScannerConnection.scanFile(context, paths.take(MAX_SCAN).toTypedArray(), null, null)
	}

	/**
	 * Puts the media under [path] back on the map after a restore. Handing the scanner a directory
	 * indexes the directory and nothing inside it, so the files are listed first by whatever means
	 * this build has. With neither root nor all-files access that is nothing, and the directory plus
	 * the next full scan is all we get.
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
	 * Files under [path], read directly where possible and through root where not.
	 *
	 * canRead does not tell you the walk worked. On a restricted mount it answers yes and the walk
	 * still comes back empty, and an empty list here leaves the stale rows in place with the gallery
	 * still listing what was just hidden. So a walk that finds nothing falls through to the
	 * privileged path, the same way [purge] treats a delete that reaches no rows.
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
			// A delete that reaches no rows does not fail. With no storage permission the provider
			// narrows the selection to rows this app inserted, of which there are none, and returns
			// zero. Anything short of a deleted row falls through to the privileged path.
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

	/** Escapes LIKE wildcards, so `100_ANDRO` or `50% off` matches itself and not every sibling. */
	private fun likePrefix(path: String): String =
		path.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%"

	private fun sqlLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

	private const val MAX_SCAN = 5_000
}
