package io.github.melastore.shelf.data

import android.net.Uri

sealed interface HideFailure {
	data object MethodUnavailable : HideFailure
	data object MethodCannotRestore : HideFailure
	data object MethodCannotRecover : HideFailure
	data class UnsafePath(val path: String) : HideFailure
	data class CannotReadPermissions(val path: String, val detail: String? = null) : HideFailure
	data class InvalidPermissions(val path: String) : HideFailure
	data class AlreadyHidden(val name: String) : HideFailure
	data class RecoveryDataCreateFailed(val name: String) : HideFailure
	data class ChmodFailed(val detail: String? = null) : HideFailure
	data class RootRequired(val name: String) : HideFailure
	data class RestoreFailed(val name: String, val detail: String? = null) : HideFailure
	data class FolderUnreadable(val name: String) : HideFailure
	data class MoveFailed(val name: String) : HideFailure
	data class AllFilesRequired(val name: String) : HideFailure
	data class HiddenFolderMissing(val name: String, val path: String) : HideFailure
	data class DestinationExists(val name: String, val path: String) : HideFailure
	data class MoveBackFailed(val name: String, val path: String) : HideFailure
	data class NotPrimaryStorage(val path: String) : HideFailure
	data class RenameFailed(val name: String) : HideFailure
	data class RenameBackFailed(val name: String) : HideFailure
	data object HiddenNameUnverified : HideFailure
	data object JournalUpdateFailed : HideFailure
	data class RollbackFailed(val cause: HideFailure) : HideFailure
	data object InvalidFolderName : HideFailure
	data object InvalidRecoveryGrant : HideFailure
	data class RecoveryNameConflict(val name: String) : HideFailure
	data class RecoveryRecordExists(val name: String) : HideFailure
	data object AccessNotPersisted : HideFailure
	data class ContentProtectionFailed(val count: Int) : HideFailure
	data object PrimaryPinSessionRequired : HideFailure
	data object ContentCredentialRequired : HideFailure
	data object ContentCredentialIncorrect : HideFailure
	data class ContentRestoreFailed(val count: Int) : HideFailure
	data class NameProtectionFailed(val count: Int) : HideFailure
	data class NameRestoreFailed(val count: Int) : HideFailure
}

sealed interface HideWarning {
	data object RecoveryMarkerRemovalFailed : HideWarning
	data class MediaRescanLimited(val path: String, val limit: Int) : HideWarning
	data class MediaEntriesMayRemain(val details: List<String>) : HideWarning
	data class ProviderRenamed(val finalName: String) : HideWarning
	data class RestoredWithDifferentName(val finalName: String) : HideWarning
	data object ContentProtectionUnavailable : HideWarning
	data object NameProtectionUnavailable : HideWarning
	data class Multiple(val warnings: List<HideWarning>) : HideWarning
}

sealed interface HideResult {
	data class Ok(val entry: HiddenEntry, val warning: HideWarning? = null) : HideResult
	data class Failed(val failure: HideFailure) : HideResult

	/**
	 * Nothing was changed because the folder cannot be reached from any grant Shelf holds; [path] is
	 * the folder the user would have to allow access to. Renaming a folder is a change to its parent,
	 * so a grant on the folder itself is the one grant that cannot perform it — and would be left
	 * pointing at a name that no longer exists.
	 */
	data class NeedsAccess(val path: String, val name: String) : HideResult
}

/** A folder the user picked, in both the forms the strategies need it. */
data class FolderTarget(val emulatedPath: String, val displayName: String, val treeUri: Uri?,)

/**
 * One way of making a folder disappear, and the way back.
 *
 * Every implementation is O(1) in the size of the folder. Hiding is a permission change or a rename
 * within one volume, never a copy: a vault that stalls for two minutes on a folder of video is one
 * the user stops reaching for, and a half-finished copy is a far worse failure than a refusal.
 */
interface HideStrategy {

	val method: HideMethod

	/** Whether this device, and the grants this app currently holds, can use it right now. */
	suspend fun isAvailable(): Boolean

	suspend fun hide(target: FolderTarget): HideResult

	suspend fun restore(entry: HiddenEntry): HideResult

	suspend fun health(entry: HiddenEntry): HiddenHealth = HiddenHealth(
		HiddenHealthStatus.UNKNOWN,
		HiddenHealthDetail.NOT_SUPPORTED,
	)

	suspend fun recoveryCandidates(parentTree: Uri): List<SafRecoveryCandidate> = emptyList()

	suspend fun recover(candidate: SafRecoveryCandidate, restoredName: String): HideResult =
		HideResult.Failed(HideFailure.MethodCannotRecover)

	/** Rebuilds records that survived outside app data after a reinstall. */
	suspend fun recoverOrphans(): Int = 0
}
