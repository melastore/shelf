package io.github.melastore.shelf.ui

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import io.github.melastore.shelf.R
import io.github.melastore.shelf.data.HiddenHealthDetail
import io.github.melastore.shelf.data.HideFailure
import io.github.melastore.shelf.data.HideWarning

/** A user-facing message whose words live in Android resources, not state or data classes. */
sealed interface UiMessage {
	data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiMessage
	data class Plural(@PluralsRes val id: Int, val quantity: Int, val args: List<Any>) : UiMessage
}

fun uiMessage(@StringRes id: Int, vararg args: Any): UiMessage = UiMessage.Resource(id, args.toList())

fun uiPlural(@PluralsRes id: Int, quantity: Int, vararg args: Any): UiMessage =
	UiMessage.Plural(id, quantity, args.toList())

fun UiMessage.resolve(resources: Resources): String = when (this) {
	is UiMessage.Resource -> resources.getString(id, *args.map { it.resolve(resources) }.toTypedArray())

	is UiMessage.Plural -> resources.getQuantityString(
		id,
		quantity,
		*args.map { it.resolve(resources) }.toTypedArray(),
	)
}

private fun Any.resolve(resources: Resources): Any = if (this is UiMessage) resolve(resources) else this

fun HideFailure.toUiMessage(): UiMessage = when (this) {
	HideFailure.MethodUnavailable -> uiMessage(R.string.error_method_unavailable)

	HideFailure.MethodCannotRestore -> uiMessage(R.string.error_method_cannot_restore)

	HideFailure.MethodCannotRecover -> uiMessage(R.string.error_method_cannot_recover)

	is HideFailure.UnsafePath -> uiMessage(R.string.error_unsafe_path, path)

	is HideFailure.CannotReadPermissions -> detail?.let {
		uiMessage(R.string.error_cannot_read_permissions_detail, path, it)
	} ?: uiMessage(R.string.error_cannot_read_permissions, path)

	is HideFailure.InvalidPermissions -> uiMessage(R.string.error_invalid_permissions, path)

	is HideFailure.AlreadyHidden -> uiMessage(R.string.error_already_hidden, name)

	is HideFailure.RecoveryDataCreateFailed -> uiMessage(R.string.error_recovery_data_create, name)

	is HideFailure.ChmodFailed -> detail?.let {
		uiMessage(R.string.error_chmod_detail, it)
	} ?: uiMessage(R.string.error_chmod)

	is HideFailure.RootRequired -> uiMessage(R.string.error_root_required, name)

	is HideFailure.RestoreFailed -> detail?.let {
		uiMessage(R.string.error_restore_detail, name, it)
	} ?: uiMessage(R.string.error_restore, name)

	is HideFailure.FolderUnreadable -> uiMessage(R.string.error_folder_unreadable, name)

	is HideFailure.MoveFailed -> uiMessage(R.string.error_move_failed, name)

	is HideFailure.AllFilesRequired -> uiMessage(R.string.error_all_files_required, name)

	is HideFailure.ParentNotGrantable -> uiMessage(R.string.error_parent_not_grantable, name)

	is HideFailure.HiddenFolderMissing -> uiMessage(R.string.error_hidden_folder_missing, name, path)

	is HideFailure.DestinationExists -> uiMessage(R.string.error_destination_exists, path, name)

	is HideFailure.MoveBackFailed -> uiMessage(R.string.error_move_back_failed, name, path)

	is HideFailure.NotPrimaryStorage -> uiMessage(R.string.error_not_primary_storage, path)

	is HideFailure.RenameFailed -> uiMessage(R.string.error_rename_failed, name)

	is HideFailure.RenameBackFailed -> uiMessage(R.string.error_rename_back_failed, name)

	HideFailure.HiddenNameUnverified -> uiMessage(R.string.error_hidden_name_unverified)

	HideFailure.JournalUpdateFailed -> uiMessage(R.string.error_journal_update_failed)

	is HideFailure.RollbackFailed -> uiMessage(R.string.error_rollback_failed, cause.toUiMessage())

	HideFailure.InvalidFolderName -> uiMessage(R.string.error_invalid_folder_name)

	HideFailure.InvalidRecoveryGrant -> uiMessage(R.string.error_invalid_recovery_grant)

	is HideFailure.RecoveryNameConflict -> uiMessage(R.string.error_recovery_name_conflict, name)

	is HideFailure.RecoveryRecordExists -> uiMessage(R.string.error_recovery_record_exists, name)

	HideFailure.AccessNotPersisted -> uiMessage(R.string.error_access_not_persisted)

	is HideFailure.ContentProtectionFailed -> uiMessage(R.string.error_content_protection_failed, count)

	HideFailure.PrimaryPinSessionRequired -> uiMessage(R.string.error_primary_pin_session_required)

	HideFailure.ContentCredentialRequired -> uiMessage(R.string.error_content_credential_required)

	HideFailure.ContentCredentialIncorrect -> uiMessage(R.string.error_content_credential_incorrect)

	is HideFailure.ContentRestoreFailed -> uiMessage(R.string.error_content_restore_failed, count)

	is HideFailure.NameProtectionFailed -> uiMessage(R.string.error_name_protection_failed, count)

	is HideFailure.NameRestoreFailed -> uiMessage(R.string.error_name_restore_failed, count)
}

fun HideWarning.toUiMessage(): UiMessage = when (this) {
	HideWarning.RecoveryMarkerRemovalFailed -> uiMessage(R.string.warning_recovery_marker_removal)

	is HideWarning.MediaRescanLimited -> uiMessage(R.string.warning_media_rescan_limited, limit, path)

	is HideWarning.MediaEntriesMayRemain -> uiMessage(
		R.string.warning_media_entries_remain,
		details.joinToString("; "),
	)

	is HideWarning.ProviderRenamed -> uiMessage(R.string.warning_provider_renamed, finalName)

	is HideWarning.RestoredWithDifferentName -> uiMessage(R.string.warning_restored_name, finalName)

	HideWarning.ContentProtectionUnavailable -> uiMessage(R.string.warning_content_protection_unavailable)

	HideWarning.NameProtectionUnavailable -> uiMessage(R.string.warning_name_protection_unavailable)

	is HideWarning.Multiple -> warnings.map(HideWarning::toUiMessage)
		.reduceOrNull { first, next -> uiMessage(R.string.message_joined, first, next) }
		?: uiMessage(R.string.operation_warning)
}

val HiddenHealthDetail.stringResource: Int
	@StringRes get() = when (this) {
		HiddenHealthDetail.METHOD_UNAVAILABLE -> R.string.health_method_unavailable
		HiddenHealthDetail.ROOT_ACCESS_REQUIRED -> R.string.health_root_access_required
		HiddenHealthDetail.BACKING_FOLDER_MISSING -> R.string.health_backing_folder_missing
		HiddenHealthDetail.PERMISSIONS_RESTORED -> R.string.health_permissions_restored
		HiddenHealthDetail.ROOT_MARKER_MISSING -> R.string.health_root_marker_missing
		HiddenHealthDetail.ROOT_INTACT -> R.string.health_root_intact
		HiddenHealthDetail.ALL_FILES_ACCESS_REQUIRED -> R.string.health_all_files_required
		HiddenHealthDetail.MOVE_CONFLICT -> R.string.health_move_conflict
		HiddenHealthDetail.MOVE_MARKER_DAMAGED -> R.string.health_move_marker_damaged
		HiddenHealthDetail.MOVE_INTACT -> R.string.health_move_intact
		HiddenHealthDetail.MOVE_ALREADY_RESTORED -> R.string.health_move_restored
		HiddenHealthDetail.MOVE_MISSING -> R.string.health_move_missing
		HiddenHealthDetail.SAF_ACCESS_REQUIRED -> R.string.health_saf_access_required
		HiddenHealthDetail.RENAME_CONFLICT -> R.string.health_rename_conflict
		HiddenHealthDetail.RENAME_INTACT -> R.string.health_rename_intact
		HiddenHealthDetail.RENAME_ALREADY_RESTORED -> R.string.health_rename_restored
		HiddenHealthDetail.RENAME_MISSING -> R.string.health_rename_missing
		HiddenHealthDetail.SAF_UNVERIFIED -> R.string.health_saf_unverified
		HiddenHealthDetail.NOT_SUPPORTED -> R.string.health_not_supported
	}
