package io.github.melastore.shelf.data

import kotlinx.serialization.Serializable

/**
 * One tracked habit and the days it was marked done. [checkedDates] holds ISO `yyyy-MM-dd` strings,
 * which sort and compare as plain text and need no date library.
 */
@Serializable
data class Habit(
	val id: String,
	val name: String,
	val createdAt: Long,
	val checkedDates: List<String> = emptyList(),
)
