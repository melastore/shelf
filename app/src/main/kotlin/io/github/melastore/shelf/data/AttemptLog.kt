package io.github.melastore.shelf.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class FailedUnlock(val at: Long)

/**
 * Every credential that was refused, and when.
 *
 * The lockout already slows guessing down; this is the half the owner sees. Someone who picked the
 * phone up and tried leaves nothing behind otherwise — the prompt they failed at looks exactly the
 * same the next time it is opened. A short list of times on the next real unlock is the difference
 * between suspecting that and knowing it.
 *
 * It is kept in app-private storage and says nothing about what was typed, only that something was.
 */
class FailedUnlockLog(file: File) {

	private val store = AtomicJsonList(file, ListSerializer(FailedUnlock.serializer()))

	suspend fun record(at: Long) = store.update { current ->
		(current + FailedUnlock(at)).takeLast(MAX_EVENTS)
	}

	suspend fun read(): List<FailedUnlock> = store.read()

	/** Cleared only when the owner dismisses the warning, so an automatic lock cannot lose it. */
	suspend fun clear() = store.update { emptyList() }

	private companion object {
		const val MAX_EVENTS = 50
	}
}
