package io.github.melastore.shelf.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.melastore.shelf.crypto.PasswordEnvelope
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface NameProtectionResult {
	data class Done(val changed: Int) : NameProtectionResult
	data object NoFiles : NameProtectionResult
	data object AccessUnavailable : NameProtectionResult
	data object CredentialRequired : NameProtectionResult
	data object WrongCredential : NameProtectionResult
	data class Failed(val count: Int = 1) : NameProtectionResult
}

/**
 * Replaces filenames with opaque identifiers, keeping the originals in one credential-encrypted
 * manifest at the folder root.
 *
 * The whole manifest is flushed before the first rename, so a killed hide can be resumed or rolled
 * back, and restore is idempotent: each mapping may be at either its original or its opaque name.
 * Directory names are left alone on purpose. Renaming those invalidates the path of every
 * descendant mid-operation and makes provider recovery much less reliable.
 */
class FolderNameProtector(
	context: Context,
	private val paths: StoragePaths,
	private val credential: () -> CharArray? = ContentCredential::copy,
) {
	private val appContext = context.applicationContext
	private val resolver get() = appContext.contentResolver

	/**
	 * The File walk is only believed when it actually sees files.
	 *
	 * A folder this process may not enumerate still answers yes to isDirectory, canRead and canWrite
	 * and lists as empty, and some builds have isExternalStorageManager agreeing. Renaming nothing
	 * and calling it done is the failure this guards against, so an empty File view defers to the
	 * provider, which answers from the grant rather than the mount.
	 *
	 * Files, not entries. Without storage permission the mount still lists the directories inside a
	 * folder and hides the files, so a folder with one subdirectory in it looked walkable, walked up
	 * nothing, and reported a folder whose names were never touched as protected.
	 */
	private fun walkable(path: String): File? = File(path).takeIf { root ->
		canWalkAsFile(root) && root.walkTopDown()
			.onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }
			.take(MAX_FILES + 1)
			.any(File::isFile)
	}

	/**
	 * [folderName] is the folder's own name, kept beside the file names. Once a hidden folder is
	 * renamed to something meaningless, this is the only record of it that outlives the journal.
	 */
	suspend fun protect(path: String, folderName: String? = null): NameProtectionResult = withContext(Dispatchers.IO) {
		walkable(path)?.takeIf(::ownsManifest)?.let { return@withContext protectFile(it, folderName) }
		safRoot(path)?.let { protectSaf(it, folderName) } ?: NameProtectionResult.AccessUnavailable
	}

	/** The folder name recorded when [path] was hidden, or null if there is none to read. */
	suspend fun folderName(path: String): String? = withContext(Dispatchers.IO) {
		val encrypted = File(path, MANIFEST).takeIf { it.isFile }?.let(::readFile)
			?: safRoot(path)?.findFile(MANIFEST)?.takeIf { it.isFile }?.let { readSaf(it.uri) }
			?: return@withContext null
		val password = credential() ?: return@withContext null
		try {
			parseManifest(encrypted, password)?.folder?.takeIf(SafPaths::isSafeName)
		} finally {
			password.fill(' ')
		}
	}

	/**
	 * Restore deliberately fails the [protect] test.
	 *
	 * Protecting a folder that lists as empty risks reporting success over files never touched, so
	 * an empty File view is refused there. Putting names back is the opposite: with no manifest there
	 * is nothing to put back, and refusing would strand a folder the owner is trying to open. A root
	 * hide on a restricted mount lands in exactly that state.
	 */
	suspend fun restore(path: String): NameProtectionResult = withContext(Dispatchers.IO) {
		walkable(path)?.takeIf(::ownsManifest)?.let { return@withContext restoreFile(it) }
		safRoot(path)?.let { return@withContext restoreSaf(it) }
		File(path).takeIf { canWalkAsFile(it) }?.let { restoreFile(it) } ?: NameProtectionResult.AccessUnavailable
	}

	/**
	 * Whether the File view wrote the manifest, and so has to put the names back. Reading it is the
	 * proof, not stat-ing it: a manifest written through a provider stats fine over the mount and
	 * still refuses to open, because FUSE answers `isFile` from the directory entry and the open from
	 * the media database. No manifest at all is the File view's to take.
	 */
	private fun ownsManifest(root: File): Boolean {
		val manifest = File(root, MANIFEST)
		return !manifest.isFile || readFile(manifest) != null
	}

	private fun protectFile(root: File, folderName: String?): NameProtectionResult {
		val manifestFile = File(root, MANIFEST)
		val mappings = if (manifestFile.isFile) {
			readManifest(readFile(manifestFile) ?: return NameProtectionResult.Failed())
				?: return credentialFailure()
		} else {
			if (!hasCredential()) return NameProtectionResult.CredentialRequired
			val files = root.walkTopDown()
				// Not through directory symlinks: renaming files outside the folder is not something
				// this folder's manifest could ever put back.
				.onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }
				.filter { it.isFile && !reserved(it.name) && !Files.isSymbolicLink(it.toPath()) }
				.take(MAX_FILES + 1)
				.toList()
			if (files.isEmpty()) return NameProtectionResult.NoFiles
			if (files.size > MAX_FILES) return NameProtectionResult.Failed()
			files.map { file ->
				val original = file.relativeTo(root).invariantSeparatorsPath
				NameMapping(original, opaqueSibling(original))
			}.also {
				if (!writeManifestFile(root, it, folderName)) return NameProtectionResult.Failed()
			}
		}
		return applyFileMappings(root, mappings, protect = true)
	}

	private fun restoreFile(root: File): NameProtectionResult {
		val manifestFile = File(root, MANIFEST)
		if (!manifestFile.isFile) return NameProtectionResult.NoFiles
		val mappings = readManifest(readFile(manifestFile) ?: return NameProtectionResult.Failed())
			?: return credentialFailure()
		val result = applyFileMappings(root, mappings, protect = false)
		if (result is NameProtectionResult.Done || result == NameProtectionResult.NoFiles) {
			if (!manifestFile.delete()) return NameProtectionResult.Failed()
		}
		return result
	}

	private fun applyFileMappings(root: File, mappings: List<NameMapping>, protect: Boolean,): NameProtectionResult {
		var changed = 0
		for (mapping in mappings) {
			val expectedFrom = File(root, if (protect) mapping.original else mapping.opaque)
			val from = expectedFrom.takeIf { it.exists() }
				?: expectedFrom.takeUnless { protect }?.let { compatibleOpaqueFile(it, mapping.opaque) }
			val to = File(root, if (protect) mapping.opaque else mapping.original)
			if (protect) {
				when {
					from?.isFile == true && !to.exists() -> {
						if (from.renameTo(to)) changed++ else return NameProtectionResult.Failed()
					}

					from == null && to.isFile -> Unit

					else -> return NameProtectionResult.Failed()
				}
			} else {
				when {
					from?.isFile == true && !to.exists() -> {
						if (from.renameTo(to)) changed++ else return NameProtectionResult.Failed()
					}

					from?.isFile == true && to.exists() -> {
						val recovered = recoverySibling(to, mapping.opaque)
						if (from.renameTo(recovered)) changed++ else return NameProtectionResult.Failed()
					}

					from == null -> Unit

					// Already restored, or deleted while the folder was hidden.
					else -> return NameProtectionResult.Failed()
				}
			}
		}
		return NameProtectionResult.Done(changed)
	}

	private suspend fun protectSaf(root: DocumentFile, folderName: String?): NameProtectionResult {
		val existing = root.findFile(MANIFEST)
		// Held on to when the walk happens here, so applying the mappings does not list the whole
		// tree a second time. A provider charges a query per directory and two per child for that.
		var walked: List<SafEntry>? = null
		val mappings = if (existing?.isFile == true) {
			readManifest(readSaf(existing.uri) ?: return NameProtectionResult.Failed())
				?: return credentialFailure()
		} else {
			if (!hasCredential()) return NameProtectionResult.CredentialRequired
			val files = mutableListOf<SafEntry>()
			if (!collectSaf(root.uri, "", files, 0)) return NameProtectionResult.Failed()
			if (files.isEmpty()) return NameProtectionResult.NoFiles
			if (files.size > MAX_FILES) return NameProtectionResult.Failed()
			walked = files
			files.map { NameMapping(it.path, opaqueSibling(it.path)) }.also {
				if (!writeManifestSaf(root, it, folderName)) return NameProtectionResult.Failed()
			}
		}
		return applySafMappings(root, mappings, protect = true, walked = walked)
	}

	private suspend fun restoreSaf(root: DocumentFile): NameProtectionResult {
		val manifest = root.findFile(MANIFEST)?.takeIf { it.isFile } ?: return NameProtectionResult.NoFiles
		val mappings = readManifest(readSaf(manifest.uri) ?: return NameProtectionResult.Failed())
			?: return credentialFailure()
		val result = applySafMappings(root, mappings, protect = false)
		if (result is NameProtectionResult.Done || result == NameProtectionResult.NoFiles) {
			if (!DocumentsContract.deleteDocument(resolver, manifest.uri)) return NameProtectionResult.Failed()
		}
		return result
	}

	private suspend fun applySafMappings(
		root: DocumentFile,
		mappings: List<NameMapping>,
		protect: Boolean,
		walked: List<SafEntry>? = null,
	): NameProtectionResult {
		val current = walked ?: mutableListOf<SafEntry>().also {
			if (!collectSaf(root.uri, "", it, 0)) return NameProtectionResult.Failed()
		}
		val byPath = current.associateByTo(mutableMapOf(), SafEntry::path)
		if (protect) return protectSafMappings(mappings, byPath)

		var changed = 0
		val direct = independentRestores(mappings, byPath)
		val renames = restoreInParallel(direct, byPath) ?: return NameProtectionResult.Failed()
		for ((mapping, renamed) in renames) {
			byPath.remove(mapping.opaque)
			byPath[siblingPath(mapping.original, renamed.name)] = renamed.entry
			changed++
		}

		val done = direct.mapTo(mutableSetOf(), NameMapping::opaque)
		for (mapping in mappings.filterNot { it.opaque in done }) {
			val toPath = mapping.original
			// Already restored, deleted, or provider-renamed on an old hide.
			val from = byPath[mapping.opaque] ?: compatibleOpaqueSaf(byPath, mapping.opaque) ?: continue
			val wanted = if (byPath.containsKey(toPath)) {
				recoveryName(toPath.substringAfterLast('/'), mapping.opaque) { candidate ->
					byPath.containsKey(siblingPath(toPath, candidate))
				}
			} else {
				toPath.substringAfterLast('/')
			}
			val renamed = renameSaf(from, wanted) ?: return NameProtectionResult.Failed()
			byPath.remove(from.path)
			byPath[siblingPath(toPath, renamed.name)] = renamed.entry
			changed++
		}
		return NameProtectionResult.Done(changed)
	}

	/**
	 * The mappings a restore can apply without looking at what the others did: the opaque name is
	 * there, the original name is free, and no other mapping reads or writes either of them. What is
	 * left over needs a name picked against the folder as it stands, which only works one at a time.
	 */
	private fun independentRestores(mappings: List<NameMapping>, byPath: Map<String, SafEntry>,): List<NameMapping> {
		// A manifest is decrypted user data, so two rows claiming one name is possible even though a
		// walk cannot produce it. Those are left to the serial pass, which knows how to place both.
		val originals = mappings.groupingBy(NameMapping::original).eachCount()
		val opaques = mappings.groupingBy(NameMapping::opaque).eachCount()
		return mappings.filter {
			byPath.containsKey(it.opaque) &&
				!byPath.containsKey(it.original) &&
				originals.getValue(it.original) == 1 &&
				opaques.getValue(it.opaque) == 1 &&
				it.original !in opaques &&
				it.opaque !in originals
		}
	}

	private suspend fun restoreInParallel(
		mappings: List<NameMapping>,
		byPath: Map<String, SafEntry>,
	): List<Pair<NameMapping, SafRename>>? = coroutineScope {
		val gate = Semaphore(PARALLEL_RENAMES)
		val renamed = mappings.map { mapping ->
			async(Dispatchers.IO) {
				gate.withPermit {
					val from = byPath.getValue(mapping.opaque)
					renameSaf(from, mapping.original.substringAfterLast('/'))?.let { mapping to it }
				}
			}
		}.awaitAll()
		if (renamed.any { it == null }) null else renamed.filterNotNull()
	}

	/**
	 * Renames several at a time. The target names are fresh identifiers and no two mappings can want
	 * the same one, so the renames do not depend on each other's order. Each one is a provider round
	 * trip and the provider rescans the paths it touched, about 70ms a file measured, so this is
	 * where the waiting was.
	 */
	private suspend fun protectSafMappings(
		mappings: List<NameMapping>,
		byPath: Map<String, SafEntry>,
	): NameProtectionResult = coroutineScope {
		val gate = Semaphore(PARALLEL_RENAMES)
		// null is a failure, 0 a mapping already applied, 1 a rename this pass made.
		val outcomes = mappings.map { mapping ->
			async(Dispatchers.IO) {
				gate.withPermit { protectOne(mapping, byPath) }
			}
		}.awaitAll()
		if (outcomes.any { it == null }) {
			NameProtectionResult.Failed()
		} else {
			NameProtectionResult.Done(outcomes.sumOf { it ?: 0 })
		}
	}

	private fun protectOne(mapping: NameMapping, byPath: Map<String, SafEntry>): Int? {
		val from = byPath[mapping.original]
		val taken = byPath.containsKey(mapping.opaque)
		// Neither name present is a file that went missing; both present is two files claiming one
		// mapping. Only the provider could tell those apart and it cannot, so both are refused.
		if (from == null) return if (taken) 0 else null
		if (taken) return null
		val wanted = mapping.opaque.substringAfterLast('/')
		val renamed = renameSaf(from, wanted) ?: return null
		if (renamed.name != wanted) {
			// The provider picked its own name, so the mapping would not find it again. Put the
			// original back and fail rather than leave a file the manifest cannot describe.
			renameSaf(renamed.entry, mapping.original.substringAfterLast('/'))
			return null
		}
		return 1
	}

	private fun renameSaf(entry: SafEntry, wanted: String): SafRename? {
		val uri = runCatching {
			DocumentsContract.renameDocument(resolver, entry.uri, wanted)
		}.getOrNull()?.takeUnless { it == Uri.EMPTY } ?: return null
		val document = DocumentFile.fromSingleUri(appContext, uri) ?: return null
		val actual = document.name ?: return null
		return SafRename(SafEntry(siblingPath(entry.path, actual), uri), actual)
	}

	private fun compatibleOpaqueSaf(entries: Map<String, SafEntry>, expected: String): SafEntry? {
		val token = opaqueToken(expected) ?: return null
		val parent = expected.substringBeforeLast('/', "")
		return entries.values.singleOrNull { candidate ->
			candidate.path.substringBeforeLast('/', "") == parent &&
				candidate.path.substringAfterLast('/').contains(token)
		}
	}

	private fun writeManifestFile(root: File, mappings: List<NameMapping>, folderName: String?): Boolean {
		val encrypted = encryptedManifest(mappings, folderName) ?: return false
		val temporary = File(root, MANIFEST_TEMP)
		val target = File(root, MANIFEST)
		return try {
			if (temporary.exists() && !temporary.delete()) return false
			FileOutputStream(temporary).use { output ->
				output.write(encrypted)
				output.fd.sync()
			}
			!target.exists() && temporary.renameTo(target)
		} finally {
			encrypted.fill(0)
			temporary.delete()
		}
	}

	private fun writeManifestSaf(root: DocumentFile, mappings: List<NameMapping>, folderName: String?): Boolean {
		val encrypted = encryptedManifest(mappings, folderName) ?: return false
		return try {
			root.findFile(MANIFEST_TEMP)?.delete()
			val temporary = root.createFile(MIME, MANIFEST_TEMP) ?: return false
			val actualName = temporary.name ?: return false
			if (actualName != MANIFEST_TEMP) return false
			val written = runCatching {
				resolver.openFileDescriptor(temporary.uri, "rwt")?.use { descriptor ->
					FileOutputStream(descriptor.fileDescriptor).use { output ->
						output.write(encrypted)
						output.fd.sync()
					}
				} != null
			}.getOrDefault(false)
			if (!written) return false
			val renamed = DocumentsContract.renameDocument(resolver, temporary.uri, MANIFEST)
				?: return false
			DocumentFile.fromSingleUri(appContext, renamed)?.name == MANIFEST
		} finally {
			encrypted.fill(0)
			root.findFile(MANIFEST_TEMP)?.delete()
		}
	}

	private fun encryptedManifest(mappings: List<NameMapping>, folderName: String?): ByteArray? {
		val password = credential() ?: return null
		var plain = byteArrayOf()
		return try {
			plain = json.encodeToString(
				NameManifest.serializer(),
				NameManifest(files = mappings, folder = folderName?.takeIf(SafPaths::isSafeName)),
			).toByteArray(Charsets.UTF_8)
			PasswordEnvelope.encrypt(plain, password)
		} catch (_: Exception) {
			null
		} finally {
			password.fill(' ')
			plain.fill(0)
		}
	}

	private fun readManifest(encrypted: ByteArray): List<NameMapping>? {
		val password = credential() ?: return null
		return try {
			parseManifest(encrypted, password)?.files
		} finally {
			password.fill(' ')
		}
	}

	private fun opaqueSibling(original: String): String {
		val parent = original.substringBeforeLast('/', "")
		val name = "$OPAQUE_PREFIX${UUID.randomUUID().toString().replace("-", "")}"
		return if (parent.isEmpty()) name else "$parent/$name"
	}

	/** Finds an opaque name an OEM provider adjusted by adding or dropping punctuation or a suffix. */
	private fun compatibleOpaqueFile(expected: File, mapping: String): File? {
		val token = opaqueToken(mapping) ?: return null
		return expected.parentFile?.listFiles()?.singleOrNull { it.isFile && token in it.name }
	}

	private fun recoverySibling(original: File, opaque: String): File {
		val parent = requireNotNull(original.parentFile)
		val name = recoveryName(original.name, opaque) { File(parent, it).exists() }
		return File(parent, name)
	}

	private fun recoveryName(original: String, opaque: String, exists: (String) -> Boolean): String {
		val extension = original.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != original }
		val stem = if (extension == null) original else original.removeSuffix(".$extension")
		val tag = opaqueToken(opaque)?.take(6) ?: "file"
		for (attempt in 1..MAX_RECOVERY_NAMES) {
			val suffix = if (attempt == 1) "" else " $attempt"
			val candidate = "$stem (Shelf recovered $tag$suffix)${extension?.let { ".$it" }.orEmpty()}"
			if (!exists(candidate)) return candidate
		}
		return "shelf_recovered_${UUID.randomUUID()}"
	}

	private fun opaqueToken(path: String): String? = UUID_TOKEN.find(path.substringAfterLast('/'))?.value

	private fun siblingPath(path: String, name: String): String {
		val parent = path.substringBeforeLast('/', "")
		return if (parent.isEmpty()) name else "$parent/$name"
	}

	private fun collectSaf(folder: Uri, prefix: String, found: MutableList<SafEntry>, depth: Int,): Boolean {
		if (depth > MAX_DEPTH || found.size > MAX_FILES) return false
		val children = SafTree.children(resolver, folder) ?: return false
		for (child in children) {
			val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
			when {
				child.isDirectory -> if (!collectSaf(child.uri, relative, found, depth + 1)) return false
				child.isFile && !reserved(child.name) -> found += SafEntry(relative, child.uri)
			}
			if (found.size > MAX_FILES) return false
		}
		return true
	}

	private fun readSaf(uri: Uri): ByteArray? = runCatching {
		resolver.openUnredacted(uri)?.use { descriptor ->
			val size = descriptor.statSize
			if (size !in 1..PasswordEnvelope.MAX_ENVELOPE_BYTES.toLong()) return@use null
			FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
		}
	}.getOrNull()

	private fun readFile(file: File): ByteArray? {
		if (file.length() !in 1..PasswordEnvelope.MAX_ENVELOPE_BYTES.toLong()) return null
		return runCatching { FileInputStream(file).use { it.readBytes() } }.getOrNull()
	}

	private fun safRoot(path: String): DocumentFile? = SafGrants.folder(appContext, paths, path)

	private fun credentialFailure(): NameProtectionResult = if (hasCredential()) {
		NameProtectionResult.WrongCredential
	} else {
		NameProtectionResult.CredentialRequired
	}

	private fun hasCredential(): Boolean {
		val password = credential() ?: return false
		password.fill(' ')
		return true
	}

	private fun reserved(name: String): Boolean = isShelfBookkeeping(name)

	// Matched to ContentLocker: the wait is a provider round trip, not the CPU.
	private val PARALLEL_RENAMES get() = 6

	@Serializable
	private data class NameManifest(
		val version: Int = VERSION,
		val files: List<NameMapping>,
		/** The folder's own name. Absent in manifests written before hidden names could be masked. */
		val folder: String? = null,
	)

	@Serializable
	private data class NameMapping(val original: String, val opaque: String)

	private data class SafEntry(val path: String, val uri: Uri)
	private data class SafRename(val entry: SafEntry, val name: String)

	companion object {
		const val VERSION = 1
		const val MANIFEST = ".shelf-names-v1"
		const val MANIFEST_TEMP = ".shelf-names-v1.tmp"
		const val MIME = "application/octet-stream"
		const val OPAQUE_PREFIX = "sfn_"
		const val MAX_FILES = ContentLocker.MAX_FILES
		const val MAX_DEPTH = 8
		const val MAX_PATH = 4_096
		const val MAX_RECOVERY_NAMES = 100
		val OPAQUE = Regex("(?:\\.sfn-|sfn_)[a-f0-9]{32}")
		val UUID_TOKEN = Regex("[a-f0-9]{32}")

		private val json = Json { ignoreUnknownKeys = false }

		/**
		 * Opaque name to original path within the folder, for a reader that has the manifest bytes and
		 * the credential but no reason to build a protector. The path and not only the name, or
		 * 2025/IMG_1.jpg and 2026/IMG_1.jpg are two rows nobody can tell apart. Held to the same checks
		 * as [readManifest]: a manifest that fails them is not one this app wrote, and the names in it
		 * are not shown.
		 */
		fun decryptManifest(encrypted: ByteArray, password: CharArray): Map<String, String>? {
			val files = parseManifest(encrypted, password)?.files ?: return null
			return files.associate { mapping -> mapping.opaque.substringAfterLast('/') to mapping.original }
		}

		private fun parseManifest(encrypted: ByteArray, password: CharArray): NameManifest? {
			if (encrypted.size !in 1..PasswordEnvelope.MAX_ENVELOPE_BYTES) return null
			var plain = byteArrayOf()
			return try {
				plain = PasswordEnvelope.decrypt(encrypted, password)
				val manifest = json.decodeFromString(NameManifest.serializer(), plain.toString(Charsets.UTF_8))
				manifest.takeIf { valid(it) }
			} catch (_: Exception) {
				null
			} finally {
				plain.fill(0)
			}
		}

		private fun valid(manifest: NameManifest): Boolean {
			if (manifest.version != VERSION || manifest.files.size !in 1..MAX_FILES) return false
			if (manifest.files.map { it.original }.toSet().size != manifest.files.size) return false
			if (manifest.files.map { it.opaque }.toSet().size != manifest.files.size) return false
			return manifest.files.all { mapping ->
				safeRelative(mapping.original) && safeRelative(mapping.opaque) &&
					mapping.original.substringBeforeLast('/', "") == mapping.opaque.substringBeforeLast('/', "") &&
					OPAQUE.matches(mapping.opaque.substringAfterLast('/'))
			}
		}

		private fun safeRelative(path: String): Boolean = path.isNotBlank() && path.length <= MAX_PATH &&
			'\u0000' !in path && path.split('/').none { it.isBlank() || it == "." || it == ".." }
	}
}
