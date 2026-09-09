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
	 */
	private fun walkable(path: String): File? =
		File(path).takeIf { canWalkAsFile(it) && it.listFiles().orEmpty().isNotEmpty() }

	suspend fun protect(path: String): NameProtectionResult = withContext(Dispatchers.IO) {
		walkable(path)?.takeIf(::ownsManifest)?.let { return@withContext protectFile(it) }
		safRoot(path)?.let { protectSaf(it) } ?: NameProtectionResult.AccessUnavailable
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
		val direct = File(path).takeIf { canWalkAsFile(it) }
		if (direct != null && ownsManifest(direct)) return@withContext restoreFile(direct)
		safRoot(path)?.let { return@withContext restoreSaf(it) }
		direct?.let { restoreFile(it) } ?: NameProtectionResult.AccessUnavailable
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

	private fun protectFile(root: File): NameProtectionResult {
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
				if (!writeManifestFile(root, it)) return NameProtectionResult.Failed()
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

	private suspend fun protectSaf(root: DocumentFile): NameProtectionResult {
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
			if (!collectSaf(root, "", files, 0)) return NameProtectionResult.Failed()
			if (files.isEmpty()) return NameProtectionResult.NoFiles
			if (files.size > MAX_FILES) return NameProtectionResult.Failed()
			walked = files
			files.map { NameMapping(it.path, opaqueSibling(it.path)) }.also {
				if (!writeManifestSaf(root, it)) return NameProtectionResult.Failed()
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
			if (!collectSaf(root, "", it, 0)) return NameProtectionResult.Failed()
		}
		val byPath = current.associateByTo(mutableMapOf(), SafEntry::path)
		if (protect) return protectSafMappings(mappings, byPath)
		var changed = 0
		for (mapping in mappings) {
			val fromPath = if (protect) mapping.original else mapping.opaque
			val toPath = if (protect) mapping.opaque else mapping.original
			val from = byPath[fromPath] ?: if (protect) {
				null
			} else {
				compatibleOpaqueSaf(byPath, mapping.opaque)
			}
			val to = byPath[toPath]
			run {
				when {
					from != null && to == null -> {
						val renamed = renameSaf(from, toPath.substringAfterLast('/'))
							?: return NameProtectionResult.Failed()
						byPath.remove(from.path)
						byPath[siblingPath(toPath, renamed.name)] = renamed.entry
						changed++
					}

					from != null && to != null -> {
						val wanted = recoveryName(toPath.substringAfterLast('/'), mapping.opaque) { candidate ->
							byPath.containsKey(siblingPath(toPath, candidate))
						}
						val renamed = renameSaf(from, wanted) ?: return NameProtectionResult.Failed()
						byPath.remove(from.path)
						byPath[siblingPath(toPath, renamed.name)] = renamed.entry
						changed++
					}

					from == null -> Unit // Already restored, deleted, or provider-renamed on an old hide.
				}
			}
		}
		return NameProtectionResult.Done(changed)
	}

	/**
	 * Renames several at a time.
	 *
	 * Only in the protect direction, where the target names are fresh identifiers and no two
	 * mappings can want the same one, so the renames do not depend on each other's order. Restore is
	 * left serial: it resolves collisions against names already in the folder, which only makes
	 * sense one at a time. Each rename is a provider round trip and the provider rescans the paths
	 * it touched, which measured at about 70ms a file, so this is where the waiting was.
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
			DocumentsContract.renameDocument(resolver, entry.document.uri, wanted)
		}.getOrNull()?.takeUnless { it == Uri.EMPTY } ?: return null
		val document = DocumentFile.fromSingleUri(appContext, uri) ?: return null
		val actual = document.name ?: return null
		return SafRename(SafEntry(siblingPath(entry.path, actual), document), actual)
	}

	private fun compatibleOpaqueSaf(entries: Map<String, SafEntry>, expected: String): SafEntry? {
		val token = opaqueToken(expected) ?: return null
		val parent = expected.substringBeforeLast('/', "")
		return entries.values.singleOrNull { candidate ->
			candidate.path.substringBeforeLast('/', "") == parent &&
				candidate.path.substringAfterLast('/').contains(token)
		}
	}

	private fun writeManifestFile(root: File, mappings: List<NameMapping>): Boolean {
		val encrypted = encryptedManifest(mappings) ?: return false
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

	private fun writeManifestSaf(root: DocumentFile, mappings: List<NameMapping>): Boolean {
		val encrypted = encryptedManifest(mappings) ?: return false
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

	private fun encryptedManifest(mappings: List<NameMapping>): ByteArray? {
		val password = credential() ?: return null
		var plain = byteArrayOf()
		return try {
			plain = json.encodeToString(
				NameManifest.serializer(),
				NameManifest(files = mappings),
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
			parseManifest(encrypted, password)
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

	private fun collectSaf(folder: DocumentFile, prefix: String, found: MutableList<SafEntry>, depth: Int,): Boolean {
		if (depth > MAX_DEPTH || found.size > MAX_FILES) return false
		val children = runCatching { folder.listFiles() }.getOrNull() ?: return false
		for (child in children) {
			val name = child.name ?: return false
			val relative = if (prefix.isEmpty()) name else "$prefix/$name"
			when {
				child.isDirectory -> if (!collectSaf(child, relative, found, depth + 1)) return false
				child.isFile && !reserved(name) -> found += SafEntry(relative, child)
			}
			if (found.size > MAX_FILES) return false
		}
		return true
	}

	private fun readSaf(uri: Uri): ByteArray? = runCatching {
		resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
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
	private data class NameManifest(val version: Int = VERSION, val files: List<NameMapping>)

	@Serializable
	private data class NameMapping(val original: String, val opaque: String)

	private data class SafEntry(val path: String, val document: DocumentFile)
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
		 * Opaque name to original name, for a reader that has the manifest bytes and the credential but
		 * no reason to build a protector. Held to the same checks as [readManifest]: a manifest that
		 * fails them is not one this app wrote, and the names in it are not shown.
		 */
		fun decryptManifest(encrypted: ByteArray, password: CharArray): Map<String, String>? {
			val files = parseManifest(encrypted, password) ?: return null
			return files.associate { mapping ->
				mapping.opaque.substringAfterLast('/') to mapping.original.substringAfterLast('/')
			}
		}

		private fun parseManifest(encrypted: ByteArray, password: CharArray): List<NameMapping>? {
			if (encrypted.size !in 1..PasswordEnvelope.MAX_ENVELOPE_BYTES) return null
			var plain = byteArrayOf()
			return try {
				plain = PasswordEnvelope.decrypt(encrypted, password)
				val manifest = json.decodeFromString(NameManifest.serializer(), plain.toString(Charsets.UTF_8))
				manifest.files.takeIf { valid(manifest) }
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
