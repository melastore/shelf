package io.github.melastore.shelf.data

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Raised when the records in [original] can no longer be read and no intact copy was left to fall
 * back to. The damaged bytes are kept at [preserved]: they are the only trace of what was hidden, so
 * they are never overwritten in place.
 */
class RecordsCorrupted(val original: File, val preserved: File) :
	IOException(
		"${original.name} could not be read; the damaged file was kept as ${preserved.name}",
	)

/**
 * A small list of records persisted as JSON, updated atomically.
 *
 * The folder journal is the sole record of a reversible change, so a torn write would be as
 * damaging as losing the data itself. Every update goes through a temp file
 * and a rename, the previous generation is kept alongside as a backup, and updates are serialised so
 * a read-modify-write can't race another.
 *
 * A file that fails to parse is never treated as an empty list: doing so would let the next write
 * quietly replace the records a restore depends on with a single fresh entry.
 */
class AtomicJsonList<T>(private val file: File, private val serializer: KSerializer<List<T>>) {

	private val json = Json {
		prettyPrint = true
		ignoreUnknownKeys = true
	}
	private val mutex = Mutex()
	private val backup = File(file.parentFile, "${file.name}.bak")

	/** @throws RecordsCorrupted if the records are unreadable and unrecoverable. */
	suspend fun read(): List<T> = withContext(Dispatchers.IO) { load() }

	/** @throws RecordsCorrupted if the records are unreadable and unrecoverable. */
	suspend fun update(transform: (List<T>) -> List<T>) {
		mutate { current -> transform(current) to Unit }
	}

	/**
	 * Applies [block] to the current records under the same lock that guards the write, and returns
	 * whatever it decided. This is how a caller rejects a duplicate: the check and the write that
	 * depends on it happen as one step, so two operations on the same path can't interleave.
	 *
	 * @throws RecordsCorrupted if the records are unreadable and unrecoverable.
	 */
	suspend fun <R> mutate(block: (List<T>) -> Pair<List<T>, R>): R = mutex.withLock {
		withContext(Dispatchers.IO) {
			val current = load()
			val (next, outcome) = block(current)
			if (next == current) return@withContext outcome

			val temp = File(file.parentFile, "${file.name}.tmp")
			temp.writeSynced(json.encodeToString(serializer, next))
			// Keep the generation we are about to replace. If the rename or a later write tears, the
			// previous set of records is still somewhere intact.
			// Never replace a good backup with a primary file we only managed to read by falling
			// back to that backup.
			if (parse(file) != null) backup.writeSynced(file.readText())
			check(temp.renameTo(file)) { "could not commit ${file.path}" }
			syncParentDirectory()
			outcome
		}
	}

	private fun load(): List<T> {
		parse(file)?.let { return it }

		// Nothing committed yet, or a create that never reached the disk: the backup is the only
		// place records could still be.
		if (!file.isFile || file.length() == 0L) {
			parse(backup)?.let { return it }
			if (!backup.isFile || backup.length() == 0L) return emptyList()

			throw RecordsCorrupted(backup, corruptCopyOf(backup))
		}

		// Only when the backup cannot stand in for it: a damaged primary that a good backup covers is
		// read again on every open, and copying it each time would bury app storage in copies of the
		// one file whose contents are worth the least to leave lying around.
		return parse(backup) ?: throw RecordsCorrupted(file, corruptCopyOf(file))
	}

	/** One copy per damaged generation, named for its contents rather than the time it was noticed. */
	private fun corruptCopyOf(source: File): File {
		val stamp = source.lastModified().takeIf { it > 0 } ?: 0L
		val preserved = File(source.parentFile, "${source.name}.corrupt-$stamp")
		if (!preserved.isFile) source.copyTo(preserved, overwrite = true)
		return preserved
	}

	private fun parse(candidate: File): List<T>? {
		if (!candidate.isFile || candidate.length() == 0L) return null
		return runCatching { json.decodeFromString(serializer, candidate.readText()) }.getOrNull()
	}

	private fun File.writeSynced(text: String) {
		FileOutputStream(this).use { out ->
			out.write(text.toByteArray())
			out.fd.sync()
		}
	}

	/**
	 * A rename is only durable once the directory entry itself is flushed. Without this a power loss
	 * can leave a zero-length journal, which is the exact loss the temp-and-rename dance is here to
	 * prevent.
	 */
	private fun syncParentDirectory() {
		val dir = file.parentFile ?: return
		runCatching {
			val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
			try {
				Os.fsync(fd)
			} finally {
				Os.close(fd)
			}
		}
	}
}
