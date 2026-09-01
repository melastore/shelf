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
	data class ParentNotGrantable(val name: String) : HideFailure
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
	 * Nothing changed: no grant Shelf holds reaches the folder. [path] is what the user has to allow.
	 * A rename changes the parent, so a grant on the folder itself cannot perform one and would be
	 * left pointing at a name that no longer exists.
	 */
	data class NeedsAccess(val path: String, val name: String) : HideResult
}

/** A folder the user picked, in both the forms the strategies need it. */
data class FolderTarget(val emulatedPath: String, val displayName: String, val treeUri: Uri?,)

/**
 * One way of making a folder disappear, and the way back.
 *
 * Every implementation is O(1) in folder size: a permission change or a rename within one volume,
 * never a copy. A copy would stall for minutes on a folder of video, and a half-finished one is a
 * worse failure than a refusal.
 */
interface HideStrategy {

	val method: HideMethod

	/** Whether this device and the grants held right now can use it. */
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

	/** Rebuilds records from markers that survived outside app data, after a wipe or reinstall. */
	suspend fun recoverOrphans(): Int = 0
}
