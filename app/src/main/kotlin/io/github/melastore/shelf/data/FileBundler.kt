package io.github.melastore.shelf.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import io.github.melastore.shelf.root.RootCommandRunner
import io.github.melastore.shelf.root.RootShell
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface BundleResult {
	data class Ok(val bundlePath: String, val moved: Int, val failed: List<String>) : BundleResult
	data class NeedsAccess(val path: String, val name: String) : BundleResult
	data class Failed(val failure: HideFailure) : BundleResult
}

/**
 * Puts picked files in a folder of their own, so the folder hiders can take them unchanged.
 *
 * The folder is made in the files' own directory, so the way back needs no record: everything in it
 * goes up one level. Every move is a rename within one volume, never a copy, and nothing is ever
 * moved over a name already taken.
 */
class FileBundler(
	context: Context,
	private val paths: StoragePaths,
	private val root: RootCommandRunner = RootShell,
	// Only where root is the chosen route or AUTO would pick it. Asking su otherwise can put a grant
	// prompt in front of someone who never chose root.
	private val useRoot: suspend () -> Boolean = {
		AppPreferences(context).hidingPreference().let { it == HidingPreference.AUTO || it == HidingPreference.ROOT } &&
			root.isAvailable()
	},
	// A bundle with a journal row is hidden, or a hide of it was cut short. Moving files out of it
	// would carry protected names and headers into the open.
	private val isHidden: suspend (String) -> Boolean = { false },
	private val mediaIndex: FolderMediaIndex = MediaStorePurge,
	// Without all-files access the mount still lets a folder be made in Pictures, but not the marker,
	// and not a rename of files another app owns.
	private val direct: (File) -> Boolean = { Environment.isExternalStorageManager() && canWalkAsFile(it) },
	private val now: () -> Long = System::currentTimeMillis,
) {

	private val appContext = context.applicationContext
	private val resolver get() = appContext.contentResolver

	/** Where [create] would put a bundle in [parent] right now, before any suffix. */
	fun plannedPath(parent: String, name: String? = null): String = "$parent/${name ?: defaultName()}"

	/** The name a bundle gets when the owner gives none. */
	fun defaultName(): String = PREFIX + STAMP.format(Instant.ofEpochMilli(now()))

	/**
	 * Creates an empty bundle folder with its marker in [parent], called [name] or the default.
	 * [label] names the files in messages.
	 */
	suspend fun create(parent: String, label: String = SafPaths.nameOf(parent), name: String? = null): BundleResult =
		withContext(Dispatchers.IO) {
			if (SafPaths.documentId(paths.emulatedRoot, parent) == null) {
				return@withContext BundleResult.Failed(HideFailure.NotPrimaryStorage(parent))
			}
			val route = route(parent) ?: return@withContext blocked(parent, label)
			val base = name ?: defaultName()
			val made = when (route) {
				Route.Direct -> createDirect(parent, base)
				is Route.Root -> createRoot(route.backingParent, parent, base)
				is Route.Saf -> createSaf(route.tree, parent, base)
			}
			made?.let { BundleResult.Ok(it, 0, emptyList()) }
				?: BundleResult.Failed(HideFailure.FolderCreateFailed(label))
		}

	/**
	 * Moves [files] (all direct children of the bundle's parent) into [bundlePath]. A name already in
	 * the bundle is never replaced: the file goes in as `name (1).ext`.
	 */
	suspend fun fill(bundlePath: String, files: List<String>): BundleResult = withContext(Dispatchers.IO) {
		// Same lock as every hide and restore, so a re-hide on lock cannot take the folder halfway.
		OpenFolders.transition { fillNow(bundlePath, files) }
	}

	/** Moves every non-bookkeeping child up one level, then deletes marker and folder. */
	suspend fun unwrap(bundlePath: String): BundleResult = withContext(Dispatchers.IO) {
		OpenFolders.transition { unwrapNow(bundlePath) }
	}

	/**
	 * Moves the named files up one level, out of an open bundle, and leaves the rest. A name taken in
	 * the parent gets `name (1).ext`, as on unwrap.
	 */
	suspend fun takeOut(bundlePath: String, names: List<String>): BundleResult = withContext(Dispatchers.IO) {
		OpenFolders.transition { takeOutNow(bundlePath, names) }
	}

	/** Whether anything but Shelf's own files is left in the bundle, or null when it cannot be read. */
	suspend fun hasFiles(bundlePath: String): Boolean? = withContext(Dispatchers.IO) {
		val route = route(SafPaths.parentOf(bundlePath)) ?: return@withContext null
		list(route, bundlePath, bundlePath)?.any { !leftBehind(it) }
	}

	suspend fun isBundle(bundlePath: String): Boolean = withContext(Dispatchers.IO) {
		if (runCatching { File(bundlePath, MARKER).isFile }.getOrDefault(false)) return@withContext true
		val tree = SafGrants.covering(resolver, paths.emulatedRoot, bundlePath)
		if (tree != null) {
			val folder = documentUri(tree, bundlePath)
			if (folder != null && runCatching { SafTree.child(appContext, folder, MARKER) }.getOrNull() != null) {
				return@withContext true
			}
		}
		if (!runCatching { useRoot() }.getOrDefault(false)) return@withContext false
		val backing = paths.resolveTarget(bundlePath) ?: return@withContext false
		root.run("test -f ${RootShell.quote("$backing/$MARKER")}").ok
	}

	/** Like [isBundle], but null when nothing could look inside: no access yet is not an answer. */
	suspend fun bundleState(bundlePath: String): Boolean? {
		if (isBundle(bundlePath)) return true
		return withContext(Dispatchers.IO) {
			val looked = runCatching { direct(File(bundlePath)) }.getOrDefault(false) ||
				SafGrants.covering(resolver, paths.emulatedRoot, bundlePath) != null ||
				runCatching { useRoot() }.getOrDefault(false)
			false.takeIf { looked }
		}
	}

	private suspend fun fillNow(bundlePath: String, files: List<String>): BundleResult {
		val name = SafPaths.nameOf(bundlePath)
		if (isHidden(bundlePath)) return BundleResult.Failed(HideFailure.AlreadyHidden(name))
		val parent = SafPaths.parentOf(bundlePath)
		val route = route(parent) ?: return blocked(parent, name)
		val (valid, refused) = files.distinct().partition { path ->
			val child = SafPaths.nameOf(path)
			SafPaths.parentOf(path) == parent && path != bundlePath && SafPaths.isSafeName(child) && !leftBehind(child)
		}
		val inside = list(route, bundlePath, bundlePath)?.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
			?: return BundleResult.Failed(HideFailure.FolderUnreadable(name))
		// The siblings count too: over SAF a clash is renamed where the file sits, before it moves.
		val above = list(route, parent, bundlePath)?.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }.orEmpty()
		val moves = valid.map { path ->
			val child = SafPaths.nameOf(path)
			val target = if (child.lowercase(Locale.ROOT) in inside) freeName(child, inside + above) else child
			inside += target.lowercase(Locale.ROOT)
			Move(child, target)
		}
		val done = move(route, parent, bundlePath, bundlePath, moves, filesOnly = true)
		val doneFrom = done.mapTo(hashSetOf()) { it.from }
		// Only the old paths. The new ones are about to be hidden, and indexing them now could land
		// after the hide's own purge and leave thumbnails pointing into the hidden folder.
		mediaIndex.scan(appContext, done.map { "$parent/${it.from}" })
		val failed = refused.map(SafPaths::nameOf) + moves.filterNot { it.from in doneFrom }.map { it.from }
		return BundleResult.Ok(bundlePath, done.size, failed)
	}

	private suspend fun takeOutNow(bundlePath: String, names: List<String>): BundleResult {
		val name = SafPaths.nameOf(bundlePath)
		if (isHidden(bundlePath)) return BundleResult.Failed(HideFailure.AlreadyHidden(name))
		val parent = SafPaths.parentOf(bundlePath)
		val route = route(parent) ?: return blocked(parent, name)
		val siblings = list(route, parent, bundlePath) ?: return BundleResult.Failed(HideFailure.FolderUnreadable(name))
		val children = list(route, bundlePath, bundlePath) ?: return BundleResult.Failed(HideFailure.FolderUnreadable(name))
		if (FolderNameProtector.MANIFEST in children) return BundleResult.Failed(HideFailure.AlreadyHidden(name))

		val wanted = names.toSet()
		val (present, missing) = wanted.partition { it in children && !leftBehind(it) && SafPaths.isSafeName(it) }
		val above = siblings.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
		val inside = children.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
		val moves = present.map { child ->
			val target = freeName(child, above + (inside - child.lowercase(Locale.ROOT)))
			above += target.lowercase(Locale.ROOT)
			Move(child, target)
		}
		val done = move(route, bundlePath, parent, bundlePath, moves, filesOnly = true)
		val doneFrom = done.mapTo(hashSetOf()) { it.from }
		mediaIndex.scan(appContext, done.flatMap { listOf("$bundlePath/${it.from}", "$parent/${it.to}") })
		val failed = missing + moves.filterNot { it.from in doneFrom }.map { it.from }
		return BundleResult.Ok(bundlePath, done.size, failed)
	}

	private suspend fun unwrapNow(bundlePath: String): BundleResult {
		val name = SafPaths.nameOf(bundlePath)
		if (isHidden(bundlePath)) return BundleResult.Failed(HideFailure.AlreadyHidden(name))
		val parent = SafPaths.parentOf(bundlePath)
		val route = route(parent) ?: return blocked(parent, name)
		val siblings = list(route, parent, bundlePath) ?: return BundleResult.Failed(HideFailure.FolderUnreadable(name))
		val children = list(route, bundlePath, bundlePath)
		if (children == null) {
			// Already emptied and removed, by a put back cut off before the row went.
			val gone = siblings.isNotEmpty() && siblings.none { it.equals(name, ignoreCase = true) }
			return if (gone) {
				BundleResult.Ok(bundlePath, 0, emptyList())
			} else {
				BundleResult.Failed(HideFailure.FolderUnreadable(name))
			}
		}
		// Names still replaced means the folder is not really back. Moving now would strand them.
		if (FolderNameProtector.MANIFEST in children) return BundleResult.Failed(HideFailure.AlreadyHidden(name))

		val above = siblings.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
		val inside = children.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) }
		val moves = children.filterNot(::leftBehind).map { child ->
			// Its own name in the bundle is no clash. The other children count: a SAF clash is renamed in there.
			val target = freeName(child, above + (inside - child.lowercase(Locale.ROOT)))
			above += target.lowercase(Locale.ROOT)
			Move(child, target)
		}
		val done = move(route, bundlePath, parent, bundlePath, moves, filesOnly = false)
		val doneFrom = done.mapTo(hashSetOf()) { it.from }
		mediaIndex.scan(appContext, done.flatMap { listOf("$bundlePath/${it.from}", "$parent/${it.to}") })
		val failed = moves.filterNot { it.from in doneFrom }.map { it.from }
		if (failed.isNotEmpty()) return BundleResult.Ok(bundlePath, done.size, failed)
		// The folder going is the proof. A view that hides files from this process lists the bundle as
		// empty, and reporting that as done would drop the row with the files still inside.
		val removed = removeFolder(route, parent, bundlePath)
		return BundleResult.Ok(bundlePath, done.size, if (removed) emptyList() else listOf(name))
	}

	private sealed interface Route {
		data object Direct : Route
		data class Root(val backingParent: String) : Route
		data class Saf(val tree: Uri) : Route
	}

	private data class Move(val from: String, val to: String)

	/** The first way in that this process has for [parent]: the mount, then root, then a grant. */
	private suspend fun route(parent: String): Route? {
		if (runCatching { direct(File(parent)) }.getOrDefault(false)) return Route.Direct
		if (runCatching { useRoot() }.getOrDefault(false)) {
			paths.resolveTarget(parent)?.let { return Route.Root(it) }
		}
		if (!SAF_MOVES) return null
		return SafGrants.covering(resolver, paths.emulatedRoot, parent)?.let { Route.Saf(it) }
	}

	private fun blocked(parent: String, label: String): BundleResult =
		if (SAF_MOVES && SafPaths.grantable(paths.emulatedRoot, parent)) {
			BundleResult.NeedsAccess(parent, label)
		} else {
			BundleResult.Failed(HideFailure.ParentNotGrantable(label))
		}

	private fun candidates(base: String): Sequence<String> =
		sequenceOf(base) + (2..MAX_SUFFIX).asSequence().map { "$base-$it" }

	/** [name], or the first `name (n).ext` free in [taken]. Compared ignoring case, as the volume does. */
	private fun freeName(name: String, taken: Set<String>): String {
		if (name.lowercase(Locale.ROOT) !in taken) return name
		val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
		val stem = name.substring(0, dot)
		val extension = name.substring(dot)
		return (1..Int.MAX_VALUE).asSequence().map { "$stem ($it)$extension" }
			.first { it.lowercase(Locale.ROOT) !in taken }
	}

	/** Names under [dir], or null when it cannot be read. [bundlePath] only places [dir] for root. */
	private suspend fun list(route: Route, dir: String, bundlePath: String): List<String>? = when (route) {
		Route.Direct -> File(dir).list()?.toList()
		is Route.Root -> rootList(backing(route, dir, bundlePath))
		is Route.Saf -> documentUri(route.tree, dir)?.let { SafTree.children(resolver, it) }?.map { it.name }
	}

	/** [dir] on the backing store. It is the bundle or the parent the route was resolved for. */
	private fun backing(route: Route.Root, dir: String, bundlePath: String): String =
		if (dir == bundlePath) "${route.backingParent}/${SafPaths.nameOf(bundlePath)}" else route.backingParent

	/** The moves that happened, with the name each one really ended up under. */
	private suspend fun move(
		route: Route,
		fromDir: String,
		toDir: String,
		bundlePath: String,
		moves: List<Move>,
		filesOnly: Boolean,
	): List<Move> {
		if (moves.isEmpty()) return emptyList()
		return when (route) {
			Route.Direct -> moves.filter { moveDirect(File(fromDir, it.from), File(toDir, it.to), filesOnly) }

			is Route.Root -> moveRoot(
				backing(route, fromDir, bundlePath),
				backing(route, toDir, bundlePath),
				moves,
				filesOnly,
			)

			is Route.Saf -> moves.mapNotNull { moveSaf(route.tree, fromDir, toDir, it, filesOnly) }
		}
	}

	private fun moveDirect(source: File, target: File, filesOnly: Boolean): Boolean = runCatching {
		if (filesOnly && !source.isFile) return@runCatching false
		// renameTo replaces an existing target without a word, so the check comes first.
		if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching false
		source.renameTo(target)
	}.getOrDefault(false)

	private suspend fun moveRoot(from: String, to: String, moves: List<Move>, filesOnly: Boolean): List<Move> {
		val failed = hashSetOf<Int>()
		var trusted = true
		for (chunk in moves.indices.chunked(ROOT_BATCH)) {
			val commands = chunk.map { index ->
				val source = RootShell.quote("$from/${moves[index].from}")
				val target = RootShell.quote("$to/${moves[index].to}")
				val notFile = if (filesOnly) " || [ ! -f $source ]" else ""
				// Inside an if, so set -e does not stop the batch at the first file that cannot go.
				"if [ -e $target ] || [ -L $target ]$notFile; then echo F$index; " +
					"else mv $source $target || echo F$index; fi"
			}
			val result = root.run(*commands.toTypedArray())
			if (!result.ok) trusted = false
			result.stdout.mapNotNullTo(failed) { it.trim().removePrefix("F").toIntOrNull() }
		}
		if (trusted) return moves.filterIndexed { index, _ -> index !in failed }
		// The shell did not finish. Go by what is on disk rather than what it managed to print.
		val arrived = rootList(to)?.toSet() ?: return emptyList()
		val left = rootList(from)?.toSet() ?: return emptyList()
		return moves.filter { it.to in arrived && it.from !in left }
	}

	private fun moveSaf(tree: Uri, fromDir: String, toDir: String, move: Move, filesOnly: Boolean): Move? {
		var source = documentUri(tree, "$fromDir/${move.from}") ?: return null
		// A provider move takes a folder whole, with everything under it.
		if (filesOnly) {
			val type = runCatching { resolver.getType(source) }.getOrNull()
			if (type == null || type == DocumentsContract.Document.MIME_TYPE_DIR) return null
		}
		var name = move.from
		if (move.to != move.from) {
			// A move keeps the name, so a clash is renamed inside the bundle first.
			source = runCatching { DocumentsContract.renameDocument(resolver, source, move.to) }.getOrNull()
				?: return null
			name = documentPath(source)?.let(SafPaths::nameOf) ?: move.to
		}
		val sourceParent = documentUri(tree, fromDir) ?: return null
		val targetParent = documentUri(tree, toDir) ?: return null
		val moved = runCatching {
			DocumentsContract.moveDocument(resolver, source, sourceParent, targetParent)
		}.getOrNull() ?: return null
		return Move(move.from, documentPath(moved)?.let(SafPaths::nameOf) ?: name)
	}

	/**
	 * Deletes what Shelf left in the emptied bundle, then the bundle. Nothing goes while anything else
	 * is still in it, and only a folder that is really gone counts.
	 */
	private suspend fun removeFolder(route: Route, parent: String, bundlePath: String): Boolean {
		val children = list(route, bundlePath, bundlePath) ?: return false
		if (!children.all(::leftBehind)) return false
		return when (route) {
			Route.Direct -> {
				val folder = File(bundlePath)
				children.forEach { runCatching { File(folder, it).delete() } }
				runCatching { folder.delete() }
				!folder.exists()
			}

			is Route.Root -> {
				val folder = backing(route, bundlePath, bundlePath)
				val commands = children.map { "rm -f ${RootShell.quote("$folder/$it")}" } +
					"rmdir ${RootShell.quote(folder)}"
				root.run(*commands.toTypedArray()).ok
			}

			is Route.Saf -> {
				val folder = documentUri(route.tree, bundlePath) ?: return false
				children.forEach { child ->
					documentUri(route.tree, "$bundlePath/$child")?.let { uri ->
						runCatching { DocumentsContract.deleteDocument(resolver, uri) }
					}
				}
				// A provider delete takes a folder with everything in it, so it has to be empty first.
				if (SafTree.children(resolver, folder)?.isEmpty() != true) return false
				runCatching { DocumentsContract.deleteDocument(resolver, folder) }.getOrDefault(false) &&
					list(route, parent, bundlePath)?.none { it.equals(SafPaths.nameOf(bundlePath), ignoreCase = true) } == true
			}
		}
	}

	private fun createDirect(parent: String, base: String): String? {
		val name = candidates(base).firstOrNull { !File(parent, it).exists() } ?: return null
		val folder = File(parent, name)
		if (!runCatching { folder.mkdir() }.getOrDefault(false)) return null
		val marked = runCatching {
			FileOutputStream(File(folder, MARKER)).use { output ->
				output.write(BODY.toByteArray())
				output.fd.sync()
			}
		}.isSuccess
		if (marked) return "$parent/$name"
		runCatching { File(folder, MARKER).delete() }
		runCatching { folder.delete() }
		return null
	}

	private suspend fun createRoot(backingParent: String, parent: String, base: String): String? {
		// Made as root, so it takes the parent's owner and mode or the mount would refuse its own files.
		val stat = root.run("stat -c '%u:%g %a' ${RootShell.quote(backingParent)}")
		val parts = stat.stdout.firstOrNull()?.trim()?.split(' ').orEmpty()
		if (!stat.ok || parts.size != 2) return null
		val (owner, mode) = parts
		if (!owner.matches(OWNER) || !mode.matches(MODE)) return null
		val taken = rootList(backingParent)?.mapTo(hashSetOf()) { it.lowercase(Locale.ROOT) } ?: return null
		val name = candidates(base).firstOrNull { it.lowercase(Locale.ROOT) !in taken } ?: return null
		val folder = RootShell.quote("$backingParent/$name")
		val marker = RootShell.quote("$backingParent/$name/$MARKER")
		if (!root.run("mkdir $folder").ok) return null
		val marked = root.run(
			"printf '%s' ${RootShell.quote(BODY)} > $marker",
			"chown $owner $folder $marker",
			"chmod $mode $folder",
			"chmod 660 $marker",
		).ok
		if (marked) return "$parent/$name"
		root.run("rm -f $marker", "rmdir $folder")
		return null
	}

	private fun createSaf(tree: Uri, parent: String, base: String): String? {
		val parentUri = documentUri(tree, parent) ?: return null
		val taken = SafTree.children(resolver, parentUri)?.mapTo(hashSetOf()) { it.name.lowercase(Locale.ROOT) }
			?: return null
		val name = candidates(base).firstOrNull { it.lowercase(Locale.ROOT) !in taken } ?: return null
		val made = runCatching {
			DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
		}.getOrNull() ?: return null
		val path = documentPath(made)
		if (path != null && SafPaths.parentOf(path) == parent && writeMarkerSaf(made)) return path
		// Fresh and holding at most the marker, so the recursive provider delete takes nothing else.
		runCatching { DocumentsContract.deleteDocument(resolver, made) }
		return null
	}

	/**
	 * The provider appends an extension to a name it does not recognise, so the marker is written
	 * under a name that has one and renamed after, as the in-place marker is.
	 */
	private fun writeMarkerSaf(folder: Uri): Boolean {
		val temporary = runCatching {
			DocumentsContract.createDocument(resolver, folder, MIME, MARKER_TEMP)
		}.getOrNull() ?: return false
		val written = runCatching {
			resolver.openFileDescriptor(temporary, "rwt")?.use { descriptor ->
				FileOutputStream(descriptor.fileDescriptor).use { output ->
					output.write(BODY.toByteArray())
					output.fd.sync()
				}
			} != null
		}.getOrDefault(false)
		val renamed = if (written) {
			runCatching { DocumentsContract.renameDocument(resolver, temporary, MARKER) }.getOrNull()
		} else {
			null
		}
		if (renamed != null && documentPath(renamed)?.let(SafPaths::nameOf) == MARKER) return true
		runCatching { DocumentsContract.deleteDocument(resolver, renamed ?: temporary) }
		return false
	}

	/** Names directly under [dir] on the backing store, or null when it is not a folder root can read. */
	private suspend fun rootList(dir: String): List<String>? {
		val found = root.run(
			"[ -d ${RootShell.quote(dir)} ]",
			"find ${RootShell.quote(dir)} -mindepth 1 -maxdepth 1 -print0 | base64",
		)
		if (!found.ok) return null
		val encoded = found.stdout.joinToString("").filterNot { it.isWhitespace() }
		val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
		return bytes.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.map { it.substringAfterLast('/') }
	}

	private fun documentUri(tree: Uri, path: String): Uri? {
		val id = SafPaths.documentId(paths.emulatedRoot, path) ?: return null
		return runCatching { DocumentsContract.buildDocumentUriUsingTree(tree, id) }.getOrNull()
	}

	/** External storage ids are paths, so the name a provider settled on can be read off the uri. */
	private fun documentPath(uri: Uri): String? {
		val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
		if (!id.startsWith(PRIMARY)) return null
		val relative = id.removePrefix(PRIMARY)
		return paths.emulatedRoot + if (relative.isEmpty()) "" else "/$relative"
	}

	companion object {
		const val MARKER = SHELF_FILE_PREFIX + "bundle-v1"

		/**
		 * Moving through a grant needs ExternalStorageProvider to take moveDocument. If a build refuses
		 * it, false here turns a bundle with no other way in into ParentNotGrantable.
		 */
		internal const val SAF_MOVES = true

		private const val MARKER_TEMP = SHELF_FILE_PREFIX + "bundle.tmp"
		private const val BODY = """{"v":1}"""
		private const val MIME = "application/octet-stream"
		private const val PRIMARY = "primary:"

		// Visible under the root and in-place methods, so it says nothing about what is inside.
		private const val PREFIX = "Files-"
		private const val MAX_SUFFIX = 99
		private const val ROOT_BATCH = 200
		private val STAMP = DateTimeFormatter.ofPattern("yyMMdd-HHmm", Locale.ROOT).withZone(ZoneId.systemDefault())
		private val OWNER = Regex("""\d+:\d+""")
		private val MODE = Regex("""[0-7]{3,4}""")

		const val MAX_NAME = 100

		/**
		 * [raw] trimmed, or null when it cannot name a bundle. A dot name would hide the folder from
		 * galleries before Shelf does, and the rest are what some volumes and providers refuse.
		 */
		fun folderName(raw: String): String? = raw.trim().takeIf { name ->
			name.length in 1..MAX_NAME && !name.startsWith('.') && SafPaths.isSafeName(name) &&
				name.none { it < ' ' || it in "\\\":*?<>|" }
		}

		/** Shelf's own files and a stray .nomedia stay behind and go with the folder. */
		private fun leftBehind(name: String): Boolean = isShelfBookkeeping(name) || name == ".nomedia"
	}
}
