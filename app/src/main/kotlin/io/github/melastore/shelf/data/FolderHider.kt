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

	/** The method a hide would use right now, or null if the device allows none of them. */
	suspend fun activeMethod(): HideMethod? = strategies.firstOrNull { it.isAvailable() }?.method

	suspend fun hide(target: FolderTarget): HideResult =
		strategies.firstOrNull { it.isAvailable() }?.hide(target)
			?: HideResult.Failed("Shelf has no way to hide folders on this device")

	suspend fun restore(entry: HiddenEntry): HideResult =
		strategies.firstOrNull { it.method == entry.method }?.restore(entry)
			?: HideResult.Failed("${entry.displayName} was hidden in a way this build cannot reverse")

	suspend fun recoverOrphans(): Int {
		var recovered = 0
		for (strategy in strategies) recovered += strategy.recoverOrphans()
		return recovered
	}
}
