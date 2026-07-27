package io.github.melastore.shelf.data

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class DecoyItem(val id: String, val name: String, val hiddenAt: Long, val hidden: Boolean = true)

/**
 * What someone sees when the decoy PIN is entered.
 *
 * A private space that opens to nothing is not a private space anyone believes in; being made to
 * open it and showing an empty room reads as "there is another PIN". So the decoy PIN opens a real
 * looking space with a few dull things in it, which can be restored one by one exactly as the real
 * ones can. Nothing here touches storage: restoring simply strikes the row off.
 */
class DecoyVault(file: File) {

	private val store = AtomicJsonList(file, ListSerializer(DecoyItem.serializer()))
	private val seededMarker = File(file.parentFile, "${file.name}.seeded")

	/** Seeded once so intentionally restoring every decoy item does not bring them back later. */
	suspend fun read(): List<DecoyItem> {
		val existing = store.read()
		if (seededMarker.isFile) return existing
		if (existing.isEmpty()) store.update { current -> current.ifEmpty(::seed) }
		markSeeded()
		return store.read()
	}

	suspend fun remove(id: String) = store.update { current -> current.filterNot { it.id == id } }

	/** Mirrors what the real space does to a row, so the two behave alike under inspection. */
	suspend fun setHidden(id: String, hidden: Boolean) = store.update { current ->
		current.map { if (it.id == id) it.copy(hidden = hidden) else it }
	}

	suspend fun setAllHidden(hidden: Boolean) = store.update { current ->
		current.map { it.copy(hidden = hidden) }
	}

	suspend fun add(name: String) = store.update { current ->
		current + DecoyItem(UUID.randomUUID().toString(), name, System.currentTimeMillis())
	}

	/** Keeps the decoy believable even if its non-sensitive backing file cannot be read. */
	fun fallback(): List<DecoyItem> = seed()

	private fun seed(): List<DecoyItem> {
		val now = System.currentTimeMillis()
		return SEED_NAMES.mapIndexed { index, name ->
			DecoyItem(
				id = UUID.randomUUID().toString(),
				name = name,
				hiddenAt = now - (index + 1) * SEED_SPACING,
			)
		}
	}

	private fun markSeeded() {
		seededMarker.parentFile?.mkdirs()
		FileOutputStream(seededMarker).use { output ->
			output.write(1)
			output.fd.sync()
		}
	}

	private companion object {
		const val SEED_SPACING = 9L * 24 * 60 * 60 * 1_000
		val SEED_NAMES = listOf("Payslips", "Old phone backup", "Insurance scans", "Tax 2023")
	}
}

@Serializable
data class DuressEvent(val at: Long)

/**
 * A tripwire. Every time the decoy PIN opens the decoy space this records when, and the real space
 * reports it on the next visit. Being made to hand over a PIN is worth knowing about afterwards, and
 * it is the one thing the person doing the coercing has no way to see.
 */
class DuressLog(file: File) {

	private val store = AtomicJsonList(file, ListSerializer(DuressEvent.serializer()))

	suspend fun record(at: Long) = store.update { current ->
		(current + DuressEvent(at)).takeLast(MAX_EVENTS)
	}

	suspend fun read(): List<DuressEvent> = store.read()

	/** Cleared only after the owner dismisses the warning, so background locking cannot lose it. */
	suspend fun clear() = store.update { emptyList() }

	private companion object {
		const val MAX_EVENTS = 50
	}
}
