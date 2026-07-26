package io.github.melastore.shelf.data

import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class CalendarEvent(
	val id: String = UUID.randomUUID().toString(),
	val date: String,
	val title: String,
)

class CalendarEventStore(file: File) {

	private val store = AtomicJsonList(file, ListSerializer(CalendarEvent.serializer()))

	suspend fun read(): List<CalendarEvent> = store.read()

	suspend fun add(date: String, title: String) = store.update { current ->
		current + CalendarEvent(date = date, title = title)
	}

	suspend fun remove(id: String) = store.update { current ->
		current.filterNot { it.id == id }
	}
}
