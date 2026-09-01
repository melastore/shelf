package io.github.melastore.shelf.data

import android.content.Context
import io.github.melastore.shelf.root.RootCommandRunner
import io.github.melastore.shelf.root.RootShell
import io.github.melastore.shelf.root.StoragePaths
import java.io.File
import java.util.Base64

/**
 * Hides folders by clearing their permission bits on the backing store.
 *
 * The strongest of the three. Nothing moves, nothing is renamed, and the folder is unreadable to
 * every app on the device rather than just unlisted. Headers and names are protected first as a
 * second layer. Root is required because chmod against the FUSE mount is discarded; the bits only
 * stick on /data/media/<user>, which nothing unprivileged can reach.
 */
class RootChmodHider(
	context: Context,
	private val journal: Journal,
	private val paths: StoragePaths,
	private val root: RootCommandRunner = RootShell,
) : HideStrategy {

	private val appContext = context.applicationContext
	private val content = FolderContentProtector(appContext, paths)
	private val names = FolderNameProtector(appContext, paths)

	override val method = HideMethod.ROOT_CHMOD

	override suspend fun isAvailable(): Boolean = root.isAvailable()

	override suspend fun hide(target: FolderTarget): HideResult {
		val backing = paths.resolveTarget(target.emulatedPath)
			?: return HideResult.Failed(HideFailure.UnsafePath(target.emulatedPath))

		val stat = root.run("stat -c '%a %u:%g' ${RootShell.quote(backing)}")
		if (!stat.ok) {
			return HideResult.Failed(
				HideFailure.CannotReadPermissions(backing, stat.stderr.joinToString().ifBlank { null }),
			)
		}
		val parts = stat.stdout.firstOrNull()?.trim()?.split(' ', limit = 2).orEmpty()
		if (parts.size != 2) return HideResult.Failed(HideFailure.CannotReadPermissions(backing))
		val (mode, owner) = parts
		if (!mode.matches(modePattern) || !owner.matches(ownerPattern)) {
			return HideResult.Failed(HideFailure.InvalidPermissions(backing))
		}

		val entry = HiddenEntry(
			path = backing,
			displayName = target.displayName,
			hiddenAt = System.currentTimeMillis(),
			method = method,
			originalMode = mode,
			originalOwner = owner,
		)
		// Journal before touching the folder. A crash after this line is recoverable and one before is
		// a no-op, but a crash after chmod with no record strands the folder at mode 000. Hiding an
		// already-hidden folder would record that 000 as the original mode and lose the real
		// permissions for good, so it is refused before anything is written.
		if (!journal.addNew(entry)) {
			return HideResult.Failed(HideFailure.AlreadyHidden(target.displayName))
		}
		val marker = markerPath(entry)
		val marked = root.run(
			"[ ! -e ${RootShell.quote(marker)} ]",
			": > ${RootShell.quote(marker)}",
			"chmod 600 ${RootShell.quote(marker)}",
		)
		if (!marked.ok) {
			journal.remove(backing)
			return HideResult.Failed(HideFailure.RecoveryDataCreateFailed(target.displayName))
		}
		val protectionWarning = when (val protected = content.protect(target.emulatedPath)) {
			is ContentProtectionResult.Done, ContentProtectionResult.NoFiles -> null

			ContentProtectionResult.AccessUnavailable,
			ContentProtectionResult.CredentialRequired -> HideWarning.ContentProtectionUnavailable

			ContentProtectionResult.WrongCredential -> {
				root.run("rm -f ${RootShell.quote(marker)}")
				journal.remove(backing)
				return HideResult.Failed(HideFailure.ContentCredentialIncorrect)
			}

			is ContentProtectionResult.Failed -> {
				root.run("rm -f ${RootShell.quote(marker)}")
				journal.remove(backing)
				return HideResult.Failed(HideFailure.ContentProtectionFailed(protected.count))
			}
		}
		val nameWarning = when (val protected = names.protect(target.emulatedPath)) {
			is NameProtectionResult.Done, NameProtectionResult.NoFiles -> null

			NameProtectionResult.AccessUnavailable,
			NameProtectionResult.CredentialRequired -> HideWarning.NameProtectionUnavailable

			NameProtectionResult.WrongCredential -> {
				names.restore(target.emulatedPath)
				content.restore(target.emulatedPath)
				root.run("rm -f ${RootShell.quote(marker)}")
				journal.remove(backing)
				return HideResult.Failed(HideFailure.ContentCredentialIncorrect)
			}

			is NameProtectionResult.Failed -> {
				names.restore(target.emulatedPath)
				content.restore(target.emulatedPath)
				root.run("rm -f ${RootShell.quote(marker)}")
				journal.remove(backing)
				return HideResult.Failed(HideFailure.NameProtectionFailed(protected.count))
			}
		}

		val hidden = root.run("chmod 000 ${RootShell.quote(backing)}")
		if (!hidden.ok) {
			names.restore(target.emulatedPath)
			content.restore(target.emulatedPath)
			root.run("rm -f ${RootShell.quote(marker)}")
			journal.remove(backing)
			return HideResult.Failed(HideFailure.ChmodFailed(hidden.stderr.joinToString().ifBlank { null }))
		}

		// The files never moved, so their MediaStore rows have to be deleted outright.
		val warning = mergeWarnings(
			protectionWarning,
			nameWarning,
			MediaStorePurge.forFolder(appContext, paths.toEmulated(backing), root),
		)
		return HideResult.Ok(entry, warning)
	}

	override suspend fun restore(entry: HiddenEntry): HideResult {
		if (!isAvailable()) {
			return HideResult.Failed(HideFailure.RootRequired(entry.displayName))
		}
		// chown after chmod clears any setgid bit the mode restored, so ownership goes back first and
		// the recorded mode has the last word.
		val restored = root.run(
			"chown ${RootShell.quote(entry.originalOwner)} ${RootShell.quote(entry.path)}",
			"chmod ${RootShell.quote(entry.originalMode)} ${RootShell.quote(entry.path)}",
		)
		// A root shell can fail without writing to stderr, which would leave a blank message.
		if (!restored.ok) {
			return HideResult.Failed(
				HideFailure.RestoreFailed(entry.displayName, restored.stderr.joinToString().ifBlank { null }),
			)
		}
		when (val nameRestore = names.restore(paths.toEmulated(entry.path))) {
			is NameProtectionResult.Done, NameProtectionResult.NoFiles -> Unit

			NameProtectionResult.CredentialRequired -> {
				root.run("chmod 000 ${RootShell.quote(entry.path)}")
				return HideResult.Failed(HideFailure.ContentCredentialRequired)
			}

			NameProtectionResult.WrongCredential -> {
				root.run("chmod 000 ${RootShell.quote(entry.path)}")
				return HideResult.Failed(HideFailure.ContentCredentialIncorrect)
			}

			is NameProtectionResult.Failed -> {
				root.run("chmod 000 ${RootShell.quote(entry.path)}")
				return HideResult.Failed(HideFailure.NameRestoreFailed(nameRestore.count))
			}

			NameProtectionResult.AccessUnavailable -> {
				root.run("chmod 000 ${RootShell.quote(entry.path)}")
				return HideResult.Failed(HideFailure.NameRestoreFailed(0))
			}
		}
		when (val contentRestore = content.restore(paths.toEmulated(entry.path))) {
			is ContentProtectionResult.Done, ContentProtectionResult.NoFiles -> Unit

			ContentProtectionResult.CredentialRequired -> {
				root.run("chmod 000 ${RootShell.quote(entry.path)}")
				return HideResult.Failed(HideFailure.ContentCredentialRequired)
			}

			ContentProtectionResult.WrongCredential -> {
				root.run("chmod 000 ${RootShell.quote(entry.path)}")
				return HideResult.Failed(HideFailure.ContentCredentialIncorrect)
			}

			is ContentProtectionResult.Failed -> {
				root.run("chmod 000 ${RootShell.quote(entry.path)}")
				return HideResult.Failed(HideFailure.ContentRestoreFailed(contentRestore.count))
			}

			ContentProtectionResult.AccessUnavailable -> {
				root.run("chmod 000 ${RootShell.quote(entry.path)}")
				return HideResult.Failed(HideFailure.ContentRestoreFailed(0))
			}
		}

		val marker = markerPathOrNull(entry)
		val markerRemoved = marker == null || root.run("rm -f ${RootShell.quote(marker)}").ok
		journal.remove(entry.path)
		val warning = mergeWarnings(
			HideWarning.RecoveryMarkerRemovalFailed.takeUnless { markerRemoved },
			MediaStorePurge.rescan(appContext, paths.toEmulated(entry.path), root),
		)
		return HideResult.Ok(entry, warning)
	}

	override suspend fun health(entry: HiddenEntry): HiddenHealth {
		if (!isAvailable()) {
			return HiddenHealth(HiddenHealthStatus.ACCESS_REQUIRED, HiddenHealthDetail.ROOT_ACCESS_REQUIRED)
		}
		val mode = root.run("stat -c %a ${RootShell.quote(entry.path)}")
		if (!mode.ok) return HiddenHealth(HiddenHealthStatus.MISSING, HiddenHealthDetail.BACKING_FOLDER_MISSING)
		val current = mode.stdout.firstOrNull()?.trim()
		if (current != "0" && current != "000") {
			return HiddenHealth(HiddenHealthStatus.ALREADY_RESTORED, HiddenHealthDetail.PERMISSIONS_RESTORED)
		}
		val marker = markerPathOrNull(entry)
		if (marker == null || !root.run("test -f ${RootShell.quote(marker)}").ok) {
			return HiddenHealth(HiddenHealthStatus.RECOVERY_DAMAGED, HiddenHealthDetail.ROOT_MARKER_MISSING)
		}
		return HiddenHealth(HiddenHealthStatus.HEALTHY, HiddenHealthDetail.ROOT_INTACT)
	}

	override suspend fun recoverOrphans(): Int {
		if (!isAvailable()) return 0
		val found = root.run(
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
			val currentMode = root.run("stat -c %a ${RootShell.quote(parent)}")
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

	private fun markerPath(entry: HiddenEntry): String =
		"${entry.path}/$MARKER_PREFIX${entry.originalMode}-${entry.originalOwner.replace(':', '-')}"

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
