package io.github.melastore.shelf.ui

import android.net.Uri
import io.github.melastore.shelf.data.FolderHider
import io.github.melastore.shelf.data.FolderRegistry
import io.github.melastore.shelf.data.FolderTarget
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HiddenHealth
import io.github.melastore.shelf.data.HideFailure
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HideResult
import io.github.melastore.shelf.data.HideStrategy
import io.github.melastore.shelf.data.HidingPreference
import io.github.melastore.shelf.data.Journal
import io.github.melastore.shelf.data.SafRecoveryCandidate
import io.github.melastore.shelf.data.TrackedFolder
import io.github.melastore.shelf.root.StoragePaths

/** The storage-facing folder operations, shared by the vault and recovery screens. */
internal class FolderCoordinator(
	private val paths: StoragePaths,
	private val journal: Journal,
	private val registry: FolderRegistry,
	private val hider: FolderHider,
) {
	suspend fun load(
		previousHealth: Map<String, HiddenHealth?>,
		preference: HidingPreference = HidingPreference.AUTO,
	): List<VaultFolder> {
		val strategy = hider.activeStrategy(preference)
		val fallback = hider.inPlaceStrategy()
		val entries = journal.read()
		val byPath = entries.associateBy { paths.toEmulated(it.path) }
		val tracked = registry.read()
		val known = tracked.mapTo(mutableSetOf()) { it.path }
		val adopted = byPath.filterKeys { it !in known }.map { (path, entry) ->
			TrackedFolder(path, entry.displayName, entry.hiddenAt)
		}
		if (adopted.isNotEmpty()) runCatching { registry.putAll(adopted) }
		val all = tracked + adopted
		val result = ArrayList<VaultFolder>(all.size)
		for (folder in all) {
			val recorded = byPath[folder.path]
			val physicallyExposed = recorded?.let {
				runCatching { hider.isExposed(it) }.getOrDefault(false)
			} ?: false
			val exposed = recorded == null || physicallyExposed
			val blocker = if (exposed) blockerFor(strategy, fallback, folder.path) else null
			result += VaultFolder(
				path = folder.path,
				displayName = folder.displayName,
				entry = recorded.takeUnless { physicallyExposed },
				health = previousHealth[folder.path],
				blocker = blocker,
			)
		}
		return result
	}

	/**
	 * What stands in the way of hiding [path], or null when something can still take it. The chosen
	 * method being unable to reach a folder is not a block on its own: the in-place fallback picks
	 * those up, so this only reports when that cannot run either.
	 */
	private suspend fun blockerFor(primary: HideStrategy?, fallback: HideStrategy?, path: String,): HideFailure? {
		val blocked = primary?.blocker(path) ?: return null
		return if (fallback != null && fallback.blocker(path) == null) null else blocked
	}

	/**
	 * Drops every record of [emulatedPath], journal row included, hidden or not. Both or neither:
	 * [load] adopts a journal row the registry has no entry for, so clearing one side alone puts the
	 * folder back in the list on the next reload and it can never be removed.
	 */
	suspend fun forget(emulatedPath: String) {
		journal.read()
			.filter { paths.toEmulated(it.path) == emulatedPath }
			.forEach { journal.remove(it.path) }
		registry.remove(emulatedPath)
	}

	suspend fun availableMethods(checkRoot: Boolean): Set<HideMethod> = hider.availableMethods(checkRoot)
	suspend fun activeMethod(preference: HidingPreference): HideMethod? = hider.activeMethod(preference)
	fun selectedMethod(preference: HidingPreference, available: Set<HideMethod>): HideMethod? =
		hider.selectedMethod(preference, available)
	suspend fun hide(target: FolderTarget, preference: HidingPreference): HideResult {
		val interrupted = journal.read().firstOrNull { paths.toEmulated(it.path) == target.emulatedPath }
		return hider.rehide(target, preference, interrupted)
	}
	suspend fun restore(entry: HiddenEntry): HideResult {
		val result = hider.restore(entry)
		if (result is HideResult.Ok) {
			val oldPath = paths.toEmulated(entry.path)
			val newPath = paths.toEmulated(result.entry.path)
			if (newPath != oldPath || result.entry.displayName != entry.displayName) {
				registry.remove(oldPath)
				registry.put(newPath, result.entry.displayName)
			}
		}
		return result
	}
	suspend fun health(entry: HiddenEntry): HiddenHealth = hider.health(entry)

	/** Every folder recorded as hidden, whatever method put it there. */
	suspend fun hiddenEntries(): List<HiddenEntry> = journal.read()

	/** Renames one hidden folder into the current style. Unchanged is a normal outcome. */
	suspend fun remask(entry: HiddenEntry): HideResult = hider.remask(entry)

	suspend fun recordedName(hiddenPath: String): String? = hider.recordedName(hiddenPath)
	suspend fun recoveryCandidates(parentTree: Uri): List<SafRecoveryCandidate> = hider.recoveryCandidates(parentTree)
	suspend fun recover(candidate: SafRecoveryCandidate, restoredName: String): HideResult {
		val result = hider.recover(candidate, restoredName)
		if (result is HideResult.Ok) {
			registry.put(paths.toEmulated(result.entry.path), result.entry.displayName)
		}
		return result
	}
	suspend fun recoverEverything(): Int = hider.recoverEverything()
	suspend fun recoverOrphans(method: HideMethod): Int = hider.recoverOrphans(method)
}
