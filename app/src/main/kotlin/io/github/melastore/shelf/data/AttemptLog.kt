package io.github.melastore.shelf.data

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class FailedUnlock(val at: Long)

/**
 * Every credential that was refused, and when.
 *
 * The lockout slows guessing down; this is the half the owner sees. Someone who picked the phone up
 * and tried otherwise leaves nothing behind, since the prompt they failed at looks the same next
 * time. A list of times on the next real unlock turns a suspicion into a fact.
 *
 * App-private, and says nothing about what was typed, only that something was.
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
