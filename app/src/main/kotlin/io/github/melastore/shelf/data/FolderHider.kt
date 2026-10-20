package io.github.melastore.shelf.data

import android.content.Context
import android.net.Uri
import io.github.melastore.shelf.root.StoragePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hides and restores folders with the best method the device allows: root, an all-files move, then
 * a SAF rename. A restore always uses the method recorded in the entry.
 *
 * Every call is moved to the IO dispatcher here rather than at each caller. A strategy walks
 * folders, opens files through a document provider and shells out to root, all of it blocking, and
 * a caller that forgot froze the UI until the folder was done.
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
			// Last on purpose. AUTO takes the first available and the dot rename always is, so this
			// is never selected outright, only reached as the fallback for a folder it cannot reach.
			InPlaceHider(context, journal, paths),
		),
	)

	suspend fun availableMethods(checkRoot: Boolean = true): Set<HideMethod> = withContext(Dispatchers.IO) {
		strategies
			.filter { (checkRoot || it.method != HideMethod.ROOT_CHMOD) && it.isAvailable() }
			.mapTo(linkedSetOf()) { it.method }
	}

	/** The method a hide would use right now, or null when the configured one is unavailable. */
	suspend fun activeMethod(preference: HidingPreference): HideMethod? = withContext(Dispatchers.IO) {
		val available = availableMethods(
			checkRoot = preference == HidingPreference.AUTO || preference == HidingPreference.ROOT,
		)
		selectedMethod(preference, available)
	}

	suspend fun activeStrategy(preference: HidingPreference): HideStrategy? = withContext(Dispatchers.IO) {
		val method = activeMethod(preference) ?: return@withContext null
		strategies.firstOrNull { it.method == method }
	}

	/** The strategy that takes folders the chosen method cannot reach. */
	fun inPlaceStrategy(): HideStrategy? = strategies.firstOrNull { it.method == HideMethod.IN_PLACE }

	fun selectedMethod(preference: HidingPreference, available: Set<HideMethod>): HideMethod? = when (preference) {
		HidingPreference.AUTO -> strategies.firstOrNull { it.method in available }?.method
		HidingPreference.ROOT -> HideMethod.ROOT_CHMOD.takeIf { it in available }
		HidingPreference.ALL_FILES -> HideMethod.PRIVATE_MOVE.takeIf { it in available }
		HidingPreference.SAF -> HideMethod.DOT_RENAME.takeIf { it in available }
	}

	/**
	 * A folder the chosen method cannot reach is hidden where it stands rather than refused. That is
	 * the only way to hide one sitting in the volume root without all-files access: the provider will
	 * not grant the parent, so the folder keeps its name and its contents are protected in place.
	 */
	suspend fun hide(target: FolderTarget, preference: HidingPreference): HideResult = withContext(Dispatchers.IO) {
		val method = activeMethod(preference)
			?: return@withContext HideResult.Failed(HideFailure.MethodUnavailable)
		val strategy = strategies.first { it.method == method }
		// A folder still hidden in place from before a reinstall goes back to that method, which takes
		// it over. Any other method would hide it a second time over its scrambled contents.
		val fallback = inPlaceStrategy()?.takeIf {
			it.hiddenHere(target.emulatedPath) || strategy.blocker(target.emulatedPath) != null
		}
		(fallback ?: strategy).hide(target)
	}

	/**
	 * Completes a hide whose journal write survived but whose physical change did not. Rolling the
	 * interrupted entry back first also puts any headers or names changed before the interruption
	 * back, so a fresh journalled hide can start cleanly.
	 */
	suspend fun rehide(target: FolderTarget, preference: HidingPreference, interrupted: HiddenEntry?,): HideResult =
		withContext(Dispatchers.IO) {
			OpenFolders.transition {
				if (interrupted != null) {
					val health = health(interrupted)
					if (health.status != HiddenHealthStatus.ALREADY_RESTORED) {
						return@transition HideResult.Failed(HideFailure.AlreadyHidden(target.displayName))
					}
					val rolledBack = restore(interrupted)
					if (rolledBack !is HideResult.Ok) return@transition rolledBack
				}
				hide(target, preference)
			}
		}

	/**
	 * A journal record is not proof of hiding until the strategy confirms the physical state.
	 *
	 * A conflict is not exposure. The hidden copy is still where it was put and something else has
	 * taken the original name, and [rehide] refuses to hide over a record it cannot roll back.
	 * Calling that exposed would count the folder as visible for good: an emergency hide that never
	 * finishes, a notification that never clears, a re-hide credential never dropped. The health
	 * check reports it instead.
	 */
	suspend fun isExposed(entry: HiddenEntry): Boolean = health(entry).status == HiddenHealthStatus.ALREADY_RESTORED

	suspend fun restore(entry: HiddenEntry): HideResult = withContext(Dispatchers.IO) {
		strategies.firstOrNull { it.method == entry.method }?.restore(entry)
			?: HideResult.Failed(HideFailure.MethodCannotRestore)
	}

	/** Puts [entry] under a name in the current style. A no-op for methods with no visible name. */
	suspend fun remask(entry: HiddenEntry): HideResult = withContext(Dispatchers.IO) {
		strategies.firstOrNull { it.method == entry.method }?.remask(entry)
			?: HideResult.Failed(HideFailure.MethodUnavailable)
	}

	suspend fun health(entry: HiddenEntry): HiddenHealth = withContext(Dispatchers.IO) {
		strategies.firstOrNull { it.method == entry.method }?.health(entry)
			?: HiddenHealth(HiddenHealthStatus.UNKNOWN, HiddenHealthDetail.METHOD_UNAVAILABLE)
	}

	suspend fun recoveryCandidates(parentTree: Uri): List<SafRecoveryCandidate> = withContext(Dispatchers.IO) {
		strategies.firstOrNull { it.method == HideMethod.DOT_RENAME }
			?.recoveryCandidates(parentTree).orEmpty()
	}

	suspend fun recordedName(hiddenPath: String): String? = withContext(Dispatchers.IO) {
		strategies.firstOrNull { it.method == HideMethod.DOT_RENAME }?.recordedName(hiddenPath)
	}

	suspend fun recover(candidate: SafRecoveryCandidate, restoredName: String): HideResult = withContext(Dispatchers.IO) {
		strategies.firstOrNull { it.method == HideMethod.DOT_RENAME }
			?.recover(candidate, restoredName)
			?: HideResult.Failed(HideFailure.MethodCannotRecover)
	}

	suspend fun recoverOrphans(method: HideMethod): Int = withContext(Dispatchers.IO) {
		strategies.firstOrNull { it.method == method }?.recoverOrphans() ?: 0
	}

	/**
	 * Sweeps every method, not just the configured one: a folder hidden with root before a reinstall
	 * is still at mode 000 whatever is selected today. Each strategy runs its own capability check,
	 * so doing one here too would ask for root twice.
	 */
	suspend fun recoverEverything(): Int = withContext(Dispatchers.IO) {
		strategies.sumOf { it.recoverOrphans() }
	}

	suspend fun dropRecoveryMarkers() = withContext(Dispatchers.IO) {
		strategies.forEach { runCatching { it.dropRecoveryMarkers() } }
	}
}

/** Collapses the warnings one operation collected into the single one a caller reports. */
internal fun mergeWarnings(vararg warnings: HideWarning?): HideWarning? {
	val present = warnings.filterNotNull()
	return when (present.size) {
		0 -> null
		1 -> present.single()
		else -> HideWarning.Multiple(present)
	}
}
