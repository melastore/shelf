package io.github.melastore.shelf.data

import android.content.Context
import io.github.melastore.shelf.root.RootShell
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import java.util.Base64

/**
 * Hides folders by clearing their permission bits on the backing store.
 *
 * The strongest of the three: nothing moves, nothing is renamed, and the folder is unreadable to
 * every app on the device rather than merely unlisted. It needs root because chmod against the FUSE
 * mount is discarded — the bits only persist on /data/media/<user>, which nothing unprivileged can
 * reach.
 */
class RootChmodHider(
	context: Context,
	private val journal: Journal,
	private val paths: StoragePaths,
) : HideStrategy {

	private val appContext = context.applicationContext

	override val method = HideMethod.ROOT_CHMOD

	override suspend fun isAvailable(): Boolean = RootShell.isAvailable()

	override suspend fun hide(target: FolderTarget): HideResult {
		val backing = paths.resolveTarget(target.emulatedPath)
			?: return HideResult.Failed("${target.emulatedPath} is not somewhere Shelf can safely operate")

		val stat = RootShell.run("stat -c '%a %u:%g' ${RootShell.quote(backing)}")
		if (!stat.ok) return HideResult.Failed("cannot read $backing: ${stat.stderr.joinToString()}")
		val parts = stat.stdout.first().trim().split(' ', limit = 2)
		if (parts.size != 2) return HideResult.Failed("cannot read permissions of $backing")
		val (mode, owner) = parts
		if (!mode.matches(modePattern) || !owner.matches(ownerPattern)) {
			return HideResult.Failed("cannot safely record permissions of $backing")
		}

		val entry = HiddenEntry(
			path = backing,
			displayName = target.displayName,
			hiddenAt = System.currentTimeMillis(),
			method = method,
			originalMode = mode,
			originalOwner = owner,
		)
		// Journal before touching the folder: a crash after this line is recoverable, one before is a
		// no-op, but a crash after chmod with no record would strand the folder at mode 000. Hiding a
		// folder that is already hidden would record that 000 as its original mode and destroy the
		// only copy of the real permissions, so it is refused before anything is written.
		if (!journal.addNew(entry)) {
			return HideResult.Failed("${target.displayName} is already hidden")
		}
		val marker = markerPath(entry)
		val marked = RootShell.run(
			"[ ! -e ${RootShell.quote(marker)} ]",
			": > ${RootShell.quote(marker)}",
			"chmod 600 ${RootShell.quote(marker)}",
		)
		if (!marked.ok) {
			journal.remove(backing)
			return HideResult.Failed("could not create recovery data for ${target.displayName}")
		}

		val hidden = RootShell.run("chmod 000 ${RootShell.quote(backing)}")
		if (!hidden.ok) {
			RootShell.run("rm -f ${RootShell.quote(marker)}")
			journal.remove(backing)
			return HideResult.Failed("chmod failed: ${hidden.stderr.joinToString()}")
		}

		// The files never moved, so their rows have to be deleted outright.
		val warning = MediaStorePurge.forFolder(appContext, paths.toEmulated(backing))
		return HideResult.Ok(entry, warning)
	}

	override suspend fun restore(entry: HiddenEntry): HideResult {
		// chown after chmod would clear any setgid bit the mode restored, so the ownership goes back
		// first and the recorded mode is the last word.
		val restored = RootShell.run(
			"chown ${RootShell.quote(entry.originalOwner)} ${RootShell.quote(entry.path)}",
			"chmod ${RootShell.quote(entry.originalMode)} ${RootShell.quote(entry.path)}",
		)
		if (!restored.ok) return HideResult.Failed(restored.stderr.joinToString())

		val marker = markerPathOrNull(entry)
		val markerRemoved = marker == null || RootShell.run("rm -f ${RootShell.quote(marker)}").ok
		journal.remove(entry.path)
		val warning = listOfNotNull(
			"the recovery marker could not be removed".takeUnless { markerRemoved },
			MediaStorePurge.rescan(appContext, paths.toEmulated(entry.path)),
		).joinToString("; ").ifEmpty { null }
		return HideResult.Ok(entry, warning)
	}

	override suspend fun recoverOrphans(): Int {
		if (!isAvailable()) return 0
		val found = RootShell.run(
			"find ${RootShell.quote(paths.backingRoot)} -type f " +
				"-name ${RootShell.quote("$MARKER_PREFIX*")} -print0 2>/dev/null | base64",
		)
		if (!found.ok) return 0
		val encoded = found.stdout.joinToString("").filterNot { it.isWhitespace() }
		val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull() ?: return 0
		val markerPaths = bytes.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }

		var recovered = 0
		for (marker in markerPaths.take(MAX_RECOVERY_MARKERS)) {
			val match = markerPattern.matchEntire(marker.substringAfterLast('/')) ?: continue
			val parent = File(marker).parent ?: continue
			if (!paths.isSafeTarget(parent)) continue
			val currentMode = RootShell.run("stat -c %a ${RootShell.quote(parent)}")
				.takeIf { it.ok }?.stdout?.firstOrNull()?.trim()
			if (currentMode?.toIntOrNull() != 0) continue

			val entry = HiddenEntry(
				path = parent,
				displayName = File(parent).name,
				hiddenAt = System.currentTimeMillis(),
				method = method,
				originalMode = match.groupValues[1],
				originalOwner = "${match.groupValues[2]}:${match.groupValues[3]}",
			)
			if (journal.addNew(entry)) recovered++
		}
		return recovered
	}

	private fun markerPath(entry: HiddenEntry): String {
		return "${entry.path}/$MARKER_PREFIX${entry.originalMode}-${entry.originalOwner.replace(':', '-')}"
	}

	private fun markerPathOrNull(entry: HiddenEntry): String? = entry.takeIf {
		it.originalMode.matches(modePattern) && it.originalOwner.matches(ownerPattern)
	}?.let(::markerPath)

	private companion object {
		const val MARKER_PREFIX = ".shelf-recovery-v1-"
		const val MAX_RECOVERY_MARKERS = 1_000
		val modePattern = Regex("[0-7]{1,4}")
		val ownerPattern = Regex("[0-9]+:[0-9]+")
		val markerPattern = Regex("\\.shelf-recovery-v1-([0-7]{1,4})-([0-9]+)-([0-9]+)")
	}
}
