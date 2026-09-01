package io.github.melastore.shelf.ui

import android.net.Uri
import io.github.melastore.shelf.data.FolderHider
import io.github.melastore.shelf.data.FolderRegistry
import io.github.melastore.shelf.data.FolderTarget
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HiddenHealth
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HideResult
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
	suspend fun load(previousHealth: Map<String, HiddenHealth?>): List<VaultFolder> {
		val entries = journal.read()
		val byPath = entries.associateBy { paths.toEmulated(it.path) }
		val tracked = registry.read()
		val known = tracked.mapTo(mutableSetOf()) { it.path }
		val adopted = byPath.filterKeys { it !in known }.map { (path, entry) ->
			TrackedFolder(path, entry.displayName, entry.hiddenAt)
		}
		if (adopted.isNotEmpty()) runCatching { registry.putAll(adopted) }
		return (tracked + adopted).map { folder ->
			val recorded = byPath[folder.path]
			val physicallyExposed = recorded?.let {
				runCatching { hider.isExposed(it) }.getOrDefault(false)
			} ?: false
			VaultFolder(
				path = folder.path,
				displayName = folder.displayName,
				entry = recorded.takeUnless { physicallyExposed },
				health = previousHealth[folder.path],
			)
		}
	}

	suspend fun availableMethods(checkRoot: Boolean): Set<HideMethod> = hider.availableMethods(checkRoot)
	suspend fun activeMethod(preference: HidingPreference): HideMethod? = hider.activeMethod(preference)
	fun selectedMethod(preference: HidingPreference, available: Set<HideMethod>): HideMethod? =
		hider.selectedMethod(preference, available)
	suspend fun hide(target: FolderTarget, preference: HidingPreference): HideResult {
		val interrupted = journal.read().firstOrNull { paths.toEmulated(it.path) == target.emulatedPath }
		return hider.rehide(target, preference, interrupted)
	}
	suspend fun restore(entry: HiddenEntry): HideResult = hider.restore(entry)
	suspend fun health(entry: HiddenEntry): HiddenHealth = hider.health(entry)
	suspend fun recoveryCandidates(parentTree: Uri): List<SafRecoveryCandidate> = hider.recoveryCandidates(parentTree)
	suspend fun recover(candidate: SafRecoveryCandidate, restoredName: String): HideResult =
		hider.recover(candidate, restoredName)
	suspend fun recoverEverything(): Int = hider.recoverEverything()
	suspend fun recoverOrphans(method: HideMethod): Int = hider.recoverOrphans(method)
}
