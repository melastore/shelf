package io.github.melastore.shelf.ui

import android.net.Uri
import io.github.melastore.shelf.data.BundleResult
import io.github.melastore.shelf.data.FileBundler
import io.github.melastore.shelf.data.FolderHider
import io.github.melastore.shelf.data.FolderRegistry
import io.github.melastore.shelf.data.FolderTarget
import io.github.melastore.shelf.data.HiddenEntry
import io.github.melastore.shelf.data.HiddenHealth
import io.github.melastore.shelf.data.HideFailure
import io.github.melastore.shelf.data.HideMethod
import io.github.melastore.shelf.data.HideResult
import io.github.melastore.shelf.data.HideStrategy
import io.github.melastore.shelf.data.HideWarning
import io.github.melastore.shelf.data.HidingPreference
import io.github.melastore.shelf.data.Journal
import io.github.melastore.shelf.data.SafRecoveryCandidate
import io.github.melastore.shelf.data.TrackedFolder
import io.github.melastore.shelf.data.mergeWarnings
import io.github.melastore.shelf.root.StoragePaths

/** The storage-facing folder operations, shared by the vault and recovery screens. */
internal class FolderCoordinator(
	private val paths: StoragePaths,
	private val journal: Journal,
	private val registry: FolderRegistry,
	private val hider: FolderHider,
	private val bundler: FileBundler? = null,
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
		// Rebuilt from the journal after app data was lost. The journal does not say which rows are
		// bundles, but the marker is still in the folder wherever hiding put it. A row nothing could
		// look into yet is shown but not saved, so it is asked again once there is access.
		val checked = byPath.filterKeys { it !in known }.map { (path, entry) ->
			val bundle = hiddenBundleState(entry)
			TrackedFolder(path, entry.displayName, entry.hiddenAt, bundle = bundle == true) to (bundle != null)
		}
		val adopted = checked.map { it.first }
		val settled = checked.filter { it.second }.map { it.first }
		if (settled.isNotEmpty()) runCatching { registry.putAll(settled) }
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
				bundle = folder.bundle,
			)
		}
		return result
	}

	private suspend fun hiddenBundleState(entry: HiddenEntry): Boolean? {
		val bundler = bundler ?: return false
		val where = runCatching { paths.toEmulated(entry.hiddenPath.ifEmpty { entry.path }) }.getOrNull() ?: return null
		return runCatching { bundler.bundleState(where) }.getOrNull()
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
	 *
	 * A row whose folder reads as exposed can be a hide cut off after the per-file passes, so it is
	 * restored first. Dropping it outright leaves scrambled files and nothing that can put them back.
	 * Returns the restore that stopped it, or null once everything is gone.
	 */
	suspend fun forget(emulatedPath: String): HideResult? {
		val rows = journal.read().filter { paths.toEmulated(it.path) == emulatedPath }
		for (row in rows) {
			if (!runCatching { hider.isExposed(row) }.getOrDefault(false)) continue
			val undone = hider.restore(row)
			if (undone !is HideResult.Ok) return undone
		}
		rows.forEach { journal.remove(it.path) }
		registry.remove(emulatedPath)
		return null
	}

	suspend fun availableMethods(checkRoot: Boolean): Set<HideMethod> = hider.availableMethods(checkRoot)
	suspend fun activeMethod(preference: HidingPreference): HideMethod? = hider.activeMethod(preference)
	fun selectedMethod(preference: HidingPreference, available: Set<HideMethod>): HideMethod? =
		hider.selectedMethod(preference, available)
	suspend fun hide(target: FolderTarget, preference: HidingPreference): HideResult {
		val interrupted = journal.read().firstOrNull { paths.toEmulated(it.path) == target.emulatedPath }
		return hider.rehide(target, preference, interrupted)
	}

	/**
	 * Every restore comes through here, so a bundle of picked files is put back whichever screen it is
	 * unhidden from. The flag or the marker is enough: the flag is lost when the list is rebuilt from
	 * the journal.
	 */
	suspend fun restore(entry: HiddenEntry): HideResult = restoreFolder(entry).result

	/** The restore, and whether what came back was a bundle, which only shows once it is back. */
	class Restored(val result: HideResult, val bundle: Boolean)

	suspend fun restoreFolder(entry: HiddenEntry): Restored {
		val result = hider.restore(entry)
		if (result !is HideResult.Ok) return Restored(result, false)
		val (newPath, name, bundle) = track(entry, result)
		if (!bundle) return Restored(result, false)
		return Restored(unwrapRestored(newPath, name, result), true)
	}

	private suspend fun unwrapRestored(newPath: String, name: String, result: HideResult.Ok): HideResult {
		val bundler = bundler ?: return result
		val unwrapped = bundler.unwrap(newPath)
		if (unwrapped is BundleResult.Ok && unwrapped.failed.isEmpty()) {
			registry.remove(newPath)
			// The folder is gone, so a note about the name it came back under says nothing useful.
			return result.copy(warning = result.warning.takeUnless { it is HideWarning.RestoredWithDifferentName })
		}
		// Unhidden, but some files are still in the folder: the row stays, flagged, with Put back on it.
		registry.put(newPath, name, bundle = true)
		val left = (unwrapped as? BundleResult.Ok)?.failed?.size ?: 0
		return HideResult.Ok(result.entry, mergeWarnings(result.warning, HideWarning.FilesLeftInFolder(left)))
	}

	/** Unhides a bundle and leaves its files inside, for adding more before it goes back. */
	suspend fun reopen(entry: HiddenEntry): HideResult {
		val result = hider.restore(entry)
		if (result is HideResult.Ok) track(entry, result)
		return result
	}

	private data class Tracked(val path: String, val name: String, val bundle: Boolean)

	/** Moves the row to where [entry] came back, flagged when it is a bundle. */
	private suspend fun track(entry: HiddenEntry, result: HideResult.Ok): Tracked {
		val oldPath = paths.toEmulated(entry.path)
		val newPath = paths.toEmulated(result.entry.path)
		val row = runCatching { registry.read() }.getOrNull()?.firstOrNull { it.path == oldPath }
		val bundle = bundler != null && (row?.bundle == true || bundler.isBundle(newPath))
		// A bundle keeps the name its files gave it, not whatever the folder came back as.
		val name = if (bundle) row?.displayName ?: entry.displayName else result.entry.displayName
		if (newPath != oldPath || name != entry.displayName || bundle != (row?.bundle == true)) {
			registry.remove(oldPath)
			registry.put(newPath, name, bundle = bundle)
		}
		return Tracked(newPath, name, bundle)
	}

	/**
	 * Moves the files in an exposed bundle back where they came from and drops the row. A journal row
	 * still there is a hide cut off partway, so it is rolled back first, as [forget] does: moving the
	 * files out before that would carry replaced names and headers with them.
	 */
	suspend fun putBack(emulatedPath: String, displayName: String): BundleResult {
		val bundler = bundler ?: return BundleResult.Failed(HideFailure.MethodUnavailable)
		for (row in journal.read().filter { paths.toEmulated(it.path) == emulatedPath }) {
			if (!runCatching { hider.isExposed(row) }.getOrDefault(false)) {
				return BundleResult.Failed(HideFailure.AlreadyHidden(displayName))
			}
			when (val undone = hider.restore(row)) {
				is HideResult.Ok -> Unit
				is HideResult.Failed -> return BundleResult.Failed(undone.failure)
				is HideResult.NeedsAccess -> return BundleResult.NeedsAccess(undone.path, undone.name)
			}
		}
		val unwrapped = bundler.unwrap(emulatedPath)
		if (unwrapped is BundleResult.Ok && unwrapped.failed.isEmpty()) registry.remove(emulatedPath)
		return unwrapped
	}

	suspend fun health(entry: HiddenEntry): HiddenHealth = hider.health(entry)

	/** Every folder recorded as hidden, whatever method put it there. */
	suspend fun hiddenEntries(): List<HiddenEntry> = journal.read()

	/** Renames one hidden folder into the current style. Unchanged is a normal outcome. */
	suspend fun remask(entry: HiddenEntry): HideResult = hider.remask(entry)

	suspend fun recordedName(hiddenPath: String): String? = hider.recordedName(hiddenPath)
	suspend fun recoveryCandidates(parentTree: Uri): List<SafRecoveryCandidate> = hider.recoveryCandidates(parentTree)
	suspend fun recover(candidate: SafRecoveryCandidate, restoredName: String): Restored {
		val result = hider.recover(candidate, restoredName)
		if (result !is HideResult.Ok) return Restored(result, false)
		val path = paths.toEmulated(result.entry.path)
		val bundle = runCatching { bundler?.isBundle(path) == true }.getOrDefault(false)
		registry.put(path, result.entry.displayName, bundle = bundle)
		// Found again a bundle is still a bundle: its files go back where they were picked from.
		return Restored(if (bundle) unwrapRestored(path, result.entry.displayName, result) else result, bundle)
	}
	suspend fun recoverEverything(): Int = hider.recoverEverything()
	suspend fun recoverOrphans(method: HideMethod): Int = hider.recoverOrphans(method)
	suspend fun lockedOrphans(): Int = hider.lockedOrphans()
}
