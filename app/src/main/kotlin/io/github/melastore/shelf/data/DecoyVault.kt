package io.github.melastore.shelf.data

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class DecoyItem(val id: String, val name: String, val hiddenAt: Long, val hidden: Boolean = true)

/**
 * What someone sees when the second credential is entered.
 *
 * A private space that opens on nothing is not believable: being made to open it and showing an
 * empty room reads as "there is another PIN". So it opens on a few dull-looking rows that restore
 * one by one exactly as the real ones do. Nothing here touches storage; restoring strikes the row
 * off and no more.
 */
class DecoyVault(file: File) {

	private val store = AtomicJsonList(file, ListSerializer(DecoyItem.serializer()))
	private val seededMarker = File(file.parentFile, "${file.name}.seeded")

	/** Seeded once, so deliberately restoring every row does not bring them back on the next read. */
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

	/**
	 * Populates the decoy space with realistic dummy items, spacing them across time so they look
	 * naturally accumulated rather than freshly generated.
	 */
	suspend fun seedPresets(clearExisting: Boolean = false) {
		store.update { current ->
			val base = if (clearExisting) emptyList() else current
			val now = System.currentTimeMillis()
			val pool = (SEED_NAMES + EXTRA_PRESETS).shuffled()
			val additions = pool.take(4).mapIndexed { index, name ->
				DecoyItem(
					id = UUID.randomUUID().toString(),
					name = name,
					hiddenAt = now - (index + 1) * SEED_SPACING,
					hidden = true,
				)
			}
			base + additions.filter { item -> base.none { it.name.equals(item.name, ignoreCase = true) } }
		}
		markSeeded()
	}

	/** Keeps the decoy believable when its backing file cannot be read. */
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

	companion object {
		const val SEED_SPACING = 9L * 24 * 60 * 60 * 1_000
		val SEED_NAMES = listOf("Payslips", "Old phone backup", "Insurance scans", "Tax 2023")
		val EXTRA_PRESETS = listOf(
			"Passport & IDs",
			"Medical records",
			"Tax 2024",
			"Rental agreement",
			"Vehicle papers",
			"Travel tickets",
			"Work expenses",
		)
	}
}

@Serializable
data class DuressEvent(val at: Long)

/**
 * A tripwire. Records every time the second credential opens the decoy space; the real space reports
 * it on the next visit. Being made to hand over a credential is worth knowing about afterwards, and
 * it is the one thing the person doing the coercing cannot see.
 */
class DuressLog(file: File) {

	private val store = AtomicJsonList(file, ListSerializer(DuressEvent.serializer()))

	suspend fun record(at: Long) = store.update { current ->
		(current + DuressEvent(at)).takeLast(MAX_EVENTS)
	}

	suspend fun read(): List<DuressEvent> = store.read()

	/** Cleared only when the owner dismisses the warning, so a background lock cannot lose it. */
	suspend fun clear() = store.update { emptyList() }

	private companion object {
		const val MAX_EVENTS = 50
	}
}
