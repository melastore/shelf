package io.github.melastore.shelf.data

import java.io.File
import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer

/** Persists the habit list that is the app's visible face. */
class HabitStore(file: File) {

	private val store = AtomicJsonList(file, ListSerializer(Habit.serializer()))

	suspend fun read(): List<Habit> = store.read()

	suspend fun add(name: String) = store.update { current ->
		current + Habit(UUID.randomUUID().toString(), name, System.currentTimeMillis())
	}

	suspend fun remove(id: String) = store.update { current ->
		current.filterNot { it.id == id }
	}

	suspend fun toggle(id: String, date: String) = store.update { current ->
		current.map { habit ->
			if (habit.id != id) return@map habit
			val dates = if (date in habit.checkedDates) {
				habit.checkedDates - date
			} else {
				habit.checkedDates + date
			}
			habit.copy(checkedDates = dates)
		}
	}
}
