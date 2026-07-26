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

	fun selectedMethod(preference: HidingPreference, available: Set<HideMethod>): HideMethod? =
		when (preference) {
			HidingPreference.AUTO -> strategies.firstOrNull { it.method in available }?.method
			HidingPreference.ROOT -> HideMethod.ROOT_CHMOD.takeIf { it in available }
			HidingPreference.ALL_FILES -> HideMethod.PRIVATE_MOVE.takeIf { it in available }
			HidingPreference.SAF -> HideMethod.DOT_RENAME.takeIf { it in available }
		}

	suspend fun hide(target: FolderTarget, preference: HidingPreference): HideResult {
		val method = activeMethod(preference)
			?: return HideResult.Failed("The selected hiding method is not available on this device")
		return strategies.first { it.method == method }.hide(target)
	}

	suspend fun restore(entry: HiddenEntry): HideResult =
		strategies.firstOrNull { it.method == entry.method }?.restore(entry)
			?: HideResult.Failed("${entry.displayName} was hidden in a way this build cannot reverse")

	suspend fun recoverOrphans(method: HideMethod): Int =
		strategies.firstOrNull { it.method == method }?.recoverOrphans() ?: 0
}
