package io.github.melastore.shelf.data

enum class HiddenHealthStatus {
	HEALTHY,
	ACCESS_REQUIRED,
	ALREADY_RESTORED,
	CONFLICT,
	MISSING,
	RECOVERY_DAMAGED,
	UNKNOWN,
}

enum class HiddenHealthDetail {
	METHOD_UNAVAILABLE,
	ROOT_ACCESS_REQUIRED,
	BACKING_FOLDER_MISSING,
	PERMISSIONS_RESTORED,
	ROOT_MARKER_MISSING,
	ROOT_INTACT,
	ALL_FILES_ACCESS_REQUIRED,
	MOVE_CONFLICT,
	MOVE_MARKER_DAMAGED,
	MOVE_INTACT,
	MOVE_ALREADY_RESTORED,
	MOVE_MISSING,
	SAF_ACCESS_REQUIRED,
	RENAME_CONFLICT,
	RENAME_INTACT,
	RENAME_ALREADY_RESTORED,
	RENAME_MISSING,
	SAF_UNVERIFIED,
	NOT_SUPPORTED,
}

data class HiddenHealth(val status: HiddenHealthStatus, val detail: HiddenHealthDetail)

data class SafRecoveryCandidate(
	val treeUri: String,
	val hiddenPath: String,
	val hiddenName: String,
	val suggestedName: String,
)
