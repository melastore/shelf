package io.github.melastore.shelf.data

import io.github.melastore.shelf.root.StoragePaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class RecoveryBundleV1(val version: Int = 1, val exportedAt: Long, val records: List<PortableHiddenEntry>,)

@Serializable
private data class PortableHiddenEntry(
	val relativePath: String,
	val displayName: String,
	val hiddenAt: Long,
	val method: HideMethod,
	val hiddenRelativePath: String = "",
	val originalMode: String = "",
	val originalOwner: String = "",
)

data class RecoveryMergeResult(val added: Int, val duplicates: Int, val conflicts: Int = 0)

class RecoveryBundleCodec(private val paths: StoragePaths) {

	private val json = Json { ignoreUnknownKeys = false }

	fun encode(entries: List<HiddenEntry>, now: Long = System.currentTimeMillis()): ByteArray {
		require(entries.size <= MAX_RECORDS) { "too many recovery records" }
		val records = entries.map(::toPortable)
		return json.encodeToString(RecoveryBundleV1.serializer(), RecoveryBundleV1(exportedAt = now, records = records))
			.toByteArray(Charsets.UTF_8)
	}

	fun decode(bytes: ByteArray): List<HiddenEntry> {
		require(bytes.size <= MAX_JSON_BYTES) { "recovery records are too large" }
		val bundle = json.decodeFromString(RecoveryBundleV1.serializer(), bytes.toString(Charsets.UTF_8))
		require(bundle.version == 1) { "unsupported recovery record version" }
		require(bundle.records.size <= MAX_RECORDS) { "too many recovery records" }
		return bundle.records.map(::fromPortable)
	}

	private fun toPortable(entry: HiddenEntry): PortableHiddenEntry {
		val original = if (entry.method == HideMethod.ROOT_CHMOD) paths.toEmulated(entry.path) else entry.path
		return PortableHiddenEntry(
			relativePath = relative(original),
			displayName = entry.displayName.take(MAX_NAME_LENGTH),
			hiddenAt = entry.hiddenAt,
			method = entry.method,
			hiddenRelativePath = entry.hiddenPath.takeIf { it.isNotEmpty() }?.let(::relative).orEmpty(),
			originalMode = entry.originalMode,
			originalOwner = entry.originalOwner,
		)
	}

	private fun fromPortable(entry: PortableHiddenEntry): HiddenEntry {
		require(entry.displayName.isNotBlank() && entry.displayName.length <= MAX_NAME_LENGTH) { "invalid name" }
		val originalEmulated = absolute(entry.relativePath)
		val original = if (entry.method == HideMethod.ROOT_CHMOD) paths.toBacking(originalEmulated) else originalEmulated
		val hidden = entry.hiddenRelativePath.takeIf { it.isNotEmpty() }?.let(::absolute).orEmpty()
		when (entry.method) {
			HideMethod.ROOT_CHMOD -> {
				require(entry.originalMode.matches(MODE)) { "invalid root mode" }
				require(entry.originalOwner.matches(OWNER)) { "invalid root owner" }
			}

			HideMethod.PRIVATE_MOVE -> require(hidden.startsWith("${paths.emulatedRoot}/.shelf/")) {
				"invalid private-vault path"
			}

			HideMethod.DOT_RENAME -> require(SafPaths.nameOf(hidden).startsWith(".")) {
				"invalid renamed-folder path"
			}
		}
		return HiddenEntry(
			path = original,
			displayName = entry.displayName,
			hiddenAt = entry.hiddenAt,
			method = entry.method,
			originalMode = entry.originalMode,
			originalOwner = entry.originalOwner,
			hiddenPath = hidden,
			treeUri = "",
		)
	}

	private fun relative(path: String): String {
		require(path.startsWith("${paths.emulatedRoot}/")) { "path is outside primary storage" }
		return validateRelative(path.removePrefix("${paths.emulatedRoot}/"))
	}

	private fun absolute(relative: String): String = "${paths.emulatedRoot}/${validateRelative(relative)}"

	private fun validateRelative(value: String): String {
		require(value.isNotBlank() && value.length <= MAX_PATH_LENGTH && '\u0000' !in value) { "invalid path" }
		val parts = value.split('/')
		require(parts.none { it.isBlank() || it == "." || it == ".." }) { "invalid path traversal" }
		return value
	}

	private companion object {
		const val MAX_RECORDS = 2_000
		const val MAX_NAME_LENGTH = 255
		const val MAX_PATH_LENGTH = 4_096
		const val MAX_JSON_BYTES = 8 * 1024 * 1024
		val MODE = Regex("[0-7]{1,4}")
		val OWNER = Regex("[0-9]+:[0-9]+")
	}
}
