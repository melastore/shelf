package io.github.melastore.shelf.data

import android.content.Context
import io.github.melastore.shelf.root.StoragePaths

/**
 * Hides and restores folders, using the best method this device currently allows.
 *
 * The first available strategy is used: root, an all-files move, then a SAF rename. A restore always
 * uses the method recorded in the entry.
 */
class FolderHider(private val strategies: List<HideStrategy>) {

	constructor(
		context: Context,
		journal: Journal,
		paths: StoragePaths = StoragePaths.forCurrentUser(),
	) : this(
		listOf(
			RootChmodHider(context, journal, paths),
			PrivateMoveHider(context, journal, paths),
			DotRenameHider(context, journal, paths),
		),
	)

	suspend fun availableMethods(checkRoot: Boolean = true): Set<HideMethod> = strategies
		.filter { (checkRoot || it.method != HideMethod.ROOT_CHMOD) && it.isAvailable() }
		.mapTo(linkedSetOf()) { it.method }

	/** The configured method a hide would use right now, or null when it is unavailable. */
	suspend fun activeMethod(preference: HidingPreference): HideMethod? {
		val available = availableMethods(
			checkRoot = preference == HidingPreference.AUTO || preference == HidingPreference.ROOT,
		)
		return selectedMethod(preference, available)
	}

	fun selectedMethod(preference: HidingPreference, available: Set<HideMethod>): HideMethod? = when (preference) {
		HidingPreference.AUTO -> strategies.firstOrNull { it.method in available }?.method
		HidingPreference.ROOT -> HideMethod.ROOT_CHMOD.takeIf { it in available }
		HidingPreference.ALL_FILES -> HideMethod.PRIVATE_MOVE.takeIf { it in available }
		HidingPreference.SAF -> HideMethod.DOT_RENAME.takeIf { it in available }
	}

	suspend fun hide(target: FolderTarget, preference: HidingPreference): HideResult {
		val method = activeMethod(preference)
			?: return HideResult.Failed(HideFailure.MethodUnavailable)
		return strategies.first { it.method == method }.hide(target)
	}

	/**
	 * Completes a hide whose journal write survived but whose physical change did not. Rolling the
	 * interrupted entry back first also restores any headers or names changed before Android stopped
	 * the earlier operation, then a fresh journalled hide can safely start.
	 */
	suspend fun rehide(target: FolderTarget, preference: HidingPreference, interrupted: HiddenEntry?,): HideResult {
		if (interrupted != null) {
			val health = health(interrupted)
			if (health.status != HiddenHealthStatus.ALREADY_RESTORED) {
				return HideResult.Failed(HideFailure.AlreadyHidden(target.displayName))
			}
			val rolledBack = restore(interrupted)
			if (rolledBack !is HideResult.Ok) return rolledBack
		}
		return hide(target, preference)
	}

	/**
	 * A journal record is not proof of hiding until its strategy confirms the physical state.
	 *
	 * A conflict is not exposure. The hidden copy is still where it was put; what is back at the
	 * original path is something else that has taken the name, and [rehide] refuses to hide over a
	 * record it cannot roll back first. Calling that exposed would leave the folder counted as visible
	 * for good — an emergency hide that can never finish, a notification that never clears, and a
	 * device-bound re-hide credential that is never dropped. The health check reports it instead.
	 */
	suspend fun isExposed(entry: HiddenEntry): Boolean = health(entry).status == HiddenHealthStatus.ALREADY_RESTORED

	suspend fun restore(entry: HiddenEntry): HideResult =
		strategies.firstOrNull { it.method == entry.method }?.restore(entry)
			?: HideResult.Failed(HideFailure.MethodCannotRestore)

	suspend fun health(entry: HiddenEntry): HiddenHealth =
		strategies.firstOrNull { it.method == entry.method }?.health(entry)
			?: HiddenHealth(HiddenHealthStatus.UNKNOWN, HiddenHealthDetail.METHOD_UNAVAILABLE)

	suspend fun recoveryCandidates(parentTree: android.net.Uri): List<SafRecoveryCandidate> =
		strategies.firstOrNull { it.method == HideMethod.DOT_RENAME }
			?.recoveryCandidates(parentTree).orEmpty()

	suspend fun recover(candidate: SafRecoveryCandidate, restoredName: String): HideResult =
		strategies.firstOrNull { it.method == HideMethod.DOT_RENAME }
			?.recover(candidate, restoredName)
			?: HideResult.Failed(HideFailure.MethodCannotRecover)

	suspend fun recoverOrphans(method: HideMethod): Int =
		strategies.firstOrNull { it.method == method }?.recoverOrphans() ?: 0

	/**
	 * Sweeps every method, not just the configured one. Each strategy performs its own capability
	 * check; doing one here as well would ask for root twice. A folder hidden with root before the user
	 * reinstalled is still on disk at mode 000 even when another method is selected today.
	 */
	suspend fun recoverEverything(): Int = strategies.sumOf { it.recoverOrphans() }
}
